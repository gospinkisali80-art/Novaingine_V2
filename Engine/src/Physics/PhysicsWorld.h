#pragma once

#include <algorithm>
#include <cmath>

#include "../Scene/Scene.h"

namespace nova {

class PhysicsWorld {
public:
    static constexpr float PLAYER_RADIUS = 0.32f;
    static constexpr float PLAYER_HEIGHT = 1.72f;
    static constexpr float GRAVITY = 20.0f;
    static constexpr float JUMP_SPEED = 7.2f;

    struct Aabb {
        Vec3 min;
        Vec3 max;
        bool valid = false;
    };

    static Vec3 rotatePoint(const Vec3& point, const Vec3& degrees) {
        constexpr float DEG_TO_RAD = 0.017453292519943295f;
        const float x = degrees.x * DEG_TO_RAD;
        const float y = degrees.y * DEG_TO_RAD;
        const float z = degrees.z * DEG_TO_RAD;

        const float cx = std::cos(x), sx = std::sin(x);
        const float cy = std::cos(y), sy = std::sin(y);
        const float cz = std::cos(z), sz = std::sin(z);

        Vec3 p(
            point.x,
            point.y * cx - point.z * sx,
            point.y * sx + point.z * cx
        );
        p = Vec3(
            p.x * cy + p.z * sy,
            p.y,
            -p.x * sy + p.z * cy
        );
        return Vec3(
            p.x * cz - p.y * sz,
            p.x * sz + p.y * cz,
            p.z
        );
    }

    static Aabb boundsFor(const SceneObject& object) {
        Aabb result;
        const Vec3 half(
            std::fabs(object.transform.scale.x) * 0.5f,
            std::fabs(object.transform.scale.y) * 0.5f,
            std::fabs(object.transform.scale.z) * 0.5f
        );

        if (object.primitive == PrimitiveType::Plane) {
            result.min = Vec3(
                object.transform.position.x - half.x,
                object.transform.position.y - 0.025f,
                object.transform.position.z - half.z
            );
            result.max = Vec3(
                object.transform.position.x + half.x,
                object.transform.position.y + 0.025f,
                object.transform.position.z + half.z
            );
            result.valid = true;
            return result;
        }

        const float maxFloat = 3.402823466e+38F;
        result.min = Vec3(maxFloat, maxFloat, maxFloat);
        result.max = Vec3(-maxFloat, -maxFloat, -maxFloat);

        for (int ix = -1; ix <= 1; ix += 2) {
            for (int iy = -1; iy <= 1; iy += 2) {
                for (int iz = -1; iz <= 1; iz += 2) {
                    Vec3 corner(
                        half.x * static_cast<float>(ix),
                        half.y * static_cast<float>(iy),
                        half.z * static_cast<float>(iz)
                    );
                    corner = rotatePoint(corner, object.transform.rotation);
                    corner = corner + object.transform.position;

                    result.min.x = std::min(result.min.x, corner.x);
                    result.min.y = std::min(result.min.y, corner.y);
                    result.min.z = std::min(result.min.z, corner.z);
                    result.max.x = std::max(result.max.x, corner.x);
                    result.max.y = std::max(result.max.y, corner.y);
                    result.max.z = std::max(result.max.z, corner.z);
                }
            }
        }

        result.valid = true;
        return result;
    }

    static bool overlapsHorizontal(
        float x, float z, const Aabb& box
    ) {
        return
            x + PLAYER_RADIUS > box.min.x &&
            x - PLAYER_RADIUS < box.max.x &&
            z + PLAYER_RADIUS > box.min.z &&
            z - PLAYER_RADIUS < box.max.z;
    }

    static bool overlapsBody(
        float x, float foot, float z, const Aabb& box
    ) {
        return overlapsHorizontal(x, z, box) &&
            foot + PLAYER_HEIGHT > box.min.y + 0.001f &&
            foot < box.max.y - 0.001f;
    }

    static bool hasWallCollision(
        const Scene& scene,
        float x, float foot, float z
    ) {
        for (const SceneObject& object : scene.getObjects()) {
            if (!object.visible || !object.isStatic) continue;
            if (object.primitive == PrimitiveType::Plane) continue;

            const Aabb box = boundsFor(object);
            if (box.valid && overlapsBody(x, foot, z, box)) return true;
        }
        return false;
    }

    static void step(
        const Scene& scene,
        Camera& camera,
        float forwardInput,
        float rightInput,
        float jumpInput,
        float deltaTime,
        float movementSpeed,
        float& verticalVelocity,
        bool& grounded,
        bool& jumpWasPressed
    ) {
        const float dt = std::clamp(deltaTime, 0.0f, 0.05f);
        if (dt <= 0.0f) return;

        const bool jumpPressed = jumpInput > 0.35f;
        if (jumpPressed && !jumpWasPressed && grounded) {
            verticalVelocity = JUMP_SPEED;
            grounded = false;
        }
        jumpWasPressed = jumpPressed;

        Vec3 forward = camera.forward();
        forward.y = 0.0f;
        forward = normalize(forward);

        Vec3 right = camera.right();
        right.y = 0.0f;
        right = normalize(right);

        Vec3 wish = forward * forwardInput + right * rightInput;
        const float wishLength = length(wish);
        if (wishLength > 1.0f) wish = wish / wishLength;

        const float speed = std::clamp(movementSpeed, 0.5f, 12.0f);
        const float dx = wish.x * speed * dt;
        const float dz = wish.z * speed * dt;

        float foot = camera.position.y - PLAYER_HEIGHT;

        float nextX = camera.position.x + dx;
        if (!hasWallCollision(scene, nextX, foot, camera.position.z)) {
            camera.position.x = nextX;
        }

        float nextZ = camera.position.z + dz;
        if (!hasWallCollision(scene, camera.position.x, foot, nextZ)) {
            camera.position.z = nextZ;
        }

        const float oldFoot = foot;
        verticalVelocity -= GRAVITY * dt;
        foot += verticalVelocity * dt;
        grounded = false;

        for (const SceneObject& object : scene.getObjects()) {
            if (!object.visible || !object.isStatic) continue;
            const Aabb box = boundsFor(object);
            if (!box.valid || !overlapsHorizontal(
                    camera.position.x, camera.position.z, box)) {
                continue;
            }

            // Land on the top face of floors and solid platforms.
            if (verticalVelocity <= 0.0f &&
                oldFoot >= box.max.y - 0.12f &&
                foot <= box.max.y) {
                foot = box.max.y;
                verticalVelocity = 0.0f;
                grounded = true;
            }

            // Stop the player from rising through a ceiling.
            if (verticalVelocity > 0.0f &&
                oldFoot + PLAYER_HEIGHT <= box.min.y + 0.08f &&
                foot + PLAYER_HEIGHT >= box.min.y) {
                foot = box.min.y - PLAYER_HEIGHT;
                verticalVelocity = 0.0f;
            }
        }

        camera.position.y = foot + PLAYER_HEIGHT;
        camera.target = camera.position + camera.forward() * 10.0f;
    }
};

} // namespace nova
