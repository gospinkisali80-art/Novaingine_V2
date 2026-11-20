#pragma once

#include <cmath>
#include <algorithm>

namespace nova {

struct Vec3 {

    float x;
    float y;
    float z;

    Vec3() : x(0), y(0), z(0) {}

    Vec3(float xValue, float yValue, float zValue)
        : x(xValue), y(yValue), z(zValue) {}

    Vec3 operator+(const Vec3& other) const {
        return Vec3(
            x + other.x,
            y + other.y,
            z + other.z
        );
    }

    Vec3 operator-(const Vec3& other) const {
        return Vec3(
            x - other.x,
            y - other.y,
            z - other.z
        );
    }

    Vec3 operator*(float value) const {
        return Vec3(
            x * value,
            y * value,
            z * value
        );
    }

    Vec3 operator/(float value) const {
        if (std::fabs(value) < 0.000001f) {
            return Vec3();
        }

        return Vec3(
            x / value,
            y / value,
            z / value
        );
    }
};

inline float dot(
    const Vec3& a,
    const Vec3& b) {

    return
        a.x * b.x +
        a.y * b.y +
        a.z * b.z;
}

inline Vec3 cross(
    const Vec3& a,
    const Vec3& b) {

    return Vec3(
        a.y * b.z - a.z * b.y,
        a.z * b.x - a.x * b.z,
        a.x * b.y - a.y * b.x
    );
}

inline float length(const Vec3& value) {

    return std::sqrt(dot(value, value));
}

inline Vec3 normalize(const Vec3& value) {

    float magnitude = length(value);

    if (magnitude < 0.000001f) {
        return Vec3();
    }

    return value / magnitude;
}

struct Mat4 {

    float m[16];

    Mat4() {
        identity();
    }

    void identity() {

        for (int i = 0; i < 16; ++i) {
            m[i] = 0.0f;
        }

        m[0] = 1.0f;
        m[5] = 1.0f;
        m[10] = 1.0f;
        m[15] = 1.0f;
    }

    static Mat4 perspective(
        float fovDegrees,
        float aspect,
        float nearPlane,
        float farPlane) {

        Mat4 result;

        for (int i = 0; i < 16; ++i) {
            result.m[i] = 0.0f;
        }

        aspect = std::max(aspect, 0.001f);

        float radians =
            fovDegrees * 0.017453292519943295f;

        float tangent = std::tan(radians * 0.5f);

        if (tangent < 0.000001f) {
            tangent = 0.000001f;
        }

        float scale = 1.0f / tangent;

        result.m[0] = scale / aspect;
        result.m[5] = scale;

        result.m[10] =
            -(farPlane + nearPlane) /
            (farPlane - nearPlane);

        result.m[11] = -1.0f;

        result.m[14] =
            -(2.0f * farPlane * nearPlane) /
            (farPlane - nearPlane);

        result.m[15] = 0.0f;

        return result;
    }

    static Mat4 lookAt(
        const Vec3& eye,
        const Vec3& target,
        const Vec3& up) {

        Vec3 forward = normalize(target - eye);
        Vec3 right = normalize(cross(forward, up));
        Vec3 correctedUp = cross(right, forward);

        Mat4 result;

        result.m[0] = right.x;
        result.m[1] = correctedUp.x;
        result.m[2] = -forward.x;
        result.m[3] = 0.0f;

        result.m[4] = right.y;
        result.m[5] = correctedUp.y;
        result.m[6] = -forward.y;
        result.m[7] = 0.0f;

        result.m[8] = right.z;
        result.m[9] = correctedUp.z;
        result.m[10] = -forward.z;
        result.m[11] = 0.0f;

        result.m[12] = -dot(right, eye);
        result.m[13] = -dot(correctedUp, eye);
        result.m[14] = dot(forward, eye);
        result.m[15] = 1.0f;

        return result;
    }
};

class Camera {

public:

    Vec3 position;
    Vec3 target;

    float yaw;
    float pitch;
    float fieldOfView;

    float nearPlane;
    float farPlane;

    float movementSpeed;

    Camera() {

        position = Vec3(0.0f, 18.0f, 28.0f);
        target = Vec3(0.0f, 0.0f, 0.0f);

        yaw = -90.0f;
        pitch = -35.0f;

        fieldOfView = 60.0f;

        nearPlane = 0.1f;
        farPlane = 500.0f;

        movementSpeed = 3.0f;
    }

    Vec3 forward() const {

        float yawRadians =
            yaw * 0.017453292519943295f;

        float pitchRadians =
            pitch * 0.017453292519943295f;

        return normalize(Vec3(
            std::cos(yawRadians) *
                std::cos(pitchRadians),

            std::sin(pitchRadians),

            std::sin(yawRadians) *
                std::cos(pitchRadians)
        ));
    }

    Vec3 right() const {

        return normalize(
            cross(
                forward(),
                Vec3(0.0f, 1.0f, 0.0f)
            )
        );
    }

    Vec3 up() const {

        return normalize(
            cross(right(), forward())
        );
    }

    void rotate(
        float deltaX,
        float deltaY) {

        yaw += deltaX * 0.25f;
        pitch -= deltaY * 0.25f;

        pitch = std::clamp(
            pitch,
            -89.0f,
            89.0f
        );
    }

    void move(
        float forwardAmount,
        float rightAmount,
        float verticalAmount,
        float deltaTime) {

        float speed =
            movementSpeed * deltaTime;

        position = position +
            forward() * (forwardAmount * speed);

        position = position +
            right() * (rightAmount * speed);

        position.y += verticalAmount * speed;

        target = position + forward() * 10.0f;
    }

    void zoom(float scale) {

        if (!std::isfinite(scale) || scale <= 0.0f) {
            return;
        }

        fieldOfView /= scale;

        fieldOfView = std::clamp(
            fieldOfView,
            20.0f,
            90.0f
        );
    }

    void pan(
        float horizontal,
        float vertical) {

        Vec3 movement =
            right() * horizontal +
            up() * vertical;

        position = position + movement;
        target = target + movement;
    }

    Mat4 viewMatrix() const {

        return Mat4::lookAt(
            position,
            position + forward(),
            Vec3(0.0f, 1.0f, 0.0f)
        );
    }

    Mat4 projectionMatrix(float aspect) const {

        return Mat4::perspective(
            fieldOfView,
            aspect,
            nearPlane,
            farPlane
        );
    }

    void reset() {

        position = Vec3(0.0f, 18.0f, 28.0f);

        yaw = -90.0f;
        pitch = -35.0f;

        fieldOfView = 60.0f;

        target = Vec3(0.0f, 0.0f, 0.0f);
    }
};

}
