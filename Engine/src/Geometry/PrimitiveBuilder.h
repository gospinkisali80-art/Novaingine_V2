#pragma once

#include <vector>
#include <cmath>
#include <cstdint>
#include <algorithm>

#include "Mesh.h"

namespace nova {

class PrimitiveBuilder {

private:

    static constexpr float PI = 3.14159265358979323846f;

    static void addFace(
        Mesh& mesh,
        const Vec3& a,
        const Vec3& b,
        const Vec3& c,
        const Vec3& d,
        const Vec3& normal) {

        uint32_t start =
            static_cast<uint32_t>(mesh.vertexCount());

        mesh.addVertex(Vertex(a, normal, 0.0f, 0.0f));
        mesh.addVertex(Vertex(b, normal, 1.0f, 0.0f));
        mesh.addVertex(Vertex(c, normal, 1.0f, 1.0f));
        mesh.addVertex(Vertex(d, normal, 0.0f, 1.0f));

        mesh.addTriangle(start, start + 1, start + 2);
        mesh.addTriangle(start, start + 2, start + 3);
    }

public:

    static Mesh createCube() {

        Mesh mesh;

        const float h = 0.5f;

        addFace(mesh,
            Vec3(-h,-h,h), Vec3(h,-h,h),
            Vec3(h,h,h), Vec3(-h,h,h),
            Vec3(0,0,1));

        addFace(mesh,
            Vec3(h,-h,-h), Vec3(-h,-h,-h),
            Vec3(-h,h,-h), Vec3(h,h,-h),
            Vec3(0,0,-1));

        addFace(mesh,
            Vec3(-h,-h,-h), Vec3(-h,-h,h),
            Vec3(-h,h,h), Vec3(-h,h,-h),
            Vec3(-1,0,0));

        addFace(mesh,
            Vec3(h,-h,h), Vec3(h,-h,-h),
            Vec3(h,h,-h), Vec3(h,h,h),
            Vec3(1,0,0));

        addFace(mesh,
            Vec3(-h,h,h), Vec3(h,h,h),
            Vec3(h,h,-h), Vec3(-h,h,-h),
            Vec3(0,1,0));

        addFace(mesh,
            Vec3(-h,-h,-h), Vec3(h,-h,-h),
            Vec3(h,-h,h), Vec3(-h,-h,h),
            Vec3(0,-1,0));

        return mesh;
    }

    static Mesh createPlane() {

        Mesh mesh;

        addFace(
            mesh,
            Vec3(-0.5f, 0.0f, -0.5f),
            Vec3(-0.5f, 0.0f, 0.5f),
            Vec3(0.5f, 0.0f, 0.5f),
            Vec3(0.5f, 0.0f, -0.5f),
            Vec3(0.0f, 1.0f, 0.0f)
        );

        return mesh;
    }

    static Mesh createCylinder(
        int segments = 32,
        bool capped = true) {

        segments = std::clamp(segments, 3, 128);

        Mesh mesh;

        const float radius = 0.5f;
        const float halfHeight = 0.5f;

        for (int i = 0; i < segments; ++i) {

            float a0 = 2.0f * PI * i / segments;
            float a1 = 2.0f * PI * (i + 1) / segments;

            float x0 = std::cos(a0) * radius;
            float z0 = std::sin(a0) * radius;

            float x1 = std::cos(a1) * radius;
            float z1 = std::sin(a1) * radius;

            Vec3 normal0 = normalize(Vec3(x0, 0, z0));
            Vec3 normal1 = normalize(Vec3(x1, 0, z1));

            uint32_t start =
                static_cast<uint32_t>(mesh.vertexCount());

            mesh.addVertex(Vertex(
                Vec3(x0, -halfHeight, z0),
                normal0, 0, 0
            ));

            mesh.addVertex(Vertex(
                Vec3(x1, -halfHeight, z1),
                normal1, 1, 0
            ));

            mesh.addVertex(Vertex(
                Vec3(x1, halfHeight, z1),
                normal1, 1, 1
            ));

            mesh.addVertex(Vertex(
                Vec3(x0, halfHeight, z0),
                normal0, 0, 1
            ));

            mesh.addTriangle(start, start + 1, start + 2);
            mesh.addTriangle(start, start + 2, start + 3);

            if (capped) {

                uint32_t top =
                    static_cast<uint32_t>(mesh.vertexCount());

                mesh.addVertex(Vertex(
                    Vec3(0, halfHeight, 0),
                    Vec3(0, 1, 0), 0.5f, 0.5f
                ));

                mesh.addVertex(Vertex(
                    Vec3(x0, halfHeight, z0),
                    Vec3(0, 1, 0), 0, 0
                ));

                mesh.addVertex(Vertex(
                    Vec3(x1, halfHeight, z1),
                    Vec3(0, 1, 0), 1, 0
                ));

                mesh.addTriangle(
                    top,
                    top + 1,
                    top + 2
                );

                uint32_t bottom =
                    static_cast<uint32_t>(mesh.vertexCount());

                mesh.addVertex(Vertex(
                    Vec3(0, -halfHeight, 0),
                    Vec3(0, -1, 0), 0.5f, 0.5f
                ));

                mesh.addVertex(Vertex(
                    Vec3(x1, -halfHeight, z1),
                    Vec3(0, -1, 0), 1, 0
                ));

                mesh.addVertex(Vertex(
                    Vec3(x0, -halfHeight, z0),
                    Vec3(0, -1, 0), 0, 0
                ));

                mesh.addTriangle(
                    bottom,
                    bottom + 1,
                    bottom + 2
                );
            }
        }

        return mesh;
    }

    static Mesh createHalfCylinder(
        int segments = 32) {

        segments = std::clamp(segments, 3, 128);

        Mesh mesh;

        const float radius = 0.5f;
        const float halfHeight = 0.5f;

        for (int i = 0; i < segments; ++i) {

            float a0 = PI * i / segments;
            float a1 = PI * (i + 1) / segments;

            float x0 = std::cos(a0) * radius;
            float z0 = std::sin(a0) * radius;

            float x1 = std::cos(a1) * radius;
            float z1 = std::sin(a1) * radius;

            Vec3 n0 = normalize(Vec3(x0, 0, z0));
            Vec3 n1 = normalize(Vec3(x1, 0, z1));

            uint32_t start =
                static_cast<uint32_t>(mesh.vertexCount());

            mesh.addVertex(Vertex(
                Vec3(x0, -halfHeight, z0), n0, 0, 0
            ));

            mesh.addVertex(Vertex(
                Vec3(x1, -halfHeight, z1), n1, 1, 0
            ));

            mesh.addVertex(Vertex(
                Vec3(x1, halfHeight, z1), n1, 1, 1
            ));

            mesh.addVertex(Vertex(
                Vec3(x0, halfHeight, z0), n0, 0, 1
            ));

            mesh.addTriangle(start, start + 1, start + 2);
            mesh.addTriangle(start, start + 2, start + 3);
        }

        return mesh;
    }

    static Mesh createSphere(
        int rings = 16,
        int segments = 24) {

        rings = std::clamp(rings, 3, 64);
        segments = std::clamp(segments, 3, 128);

        Mesh mesh;

        for (int y = 0; y <= rings; ++y) {

            float v = static_cast<float>(y) / rings;
            float latitude = PI * (v - 0.5f);

            float ringRadius = std::cos(latitude);
            float py = std::sin(latitude);

            for (int x = 0; x <= segments; ++x) {

                float u = static_cast<float>(x) / segments;
                float longitude = 2.0f * PI * u;

                Vec3 position(
                    ringRadius * std::cos(longitude) * 0.5f,
                    py * 0.5f,
                    ringRadius * std::sin(longitude) * 0.5f
                );

                mesh.addVertex(Vertex(
                    position,
                    normalize(position),
                    u,
                    v
                ));
            }
        }

        for (int y = 0; y < rings; ++y) {

            for (int x = 0; x < segments; ++x) {

                uint32_t a =
                    y * (segments + 1) + x;

                uint32_t b = a + segments + 1;

                mesh.addTriangle(a, b, a + 1);
                mesh.addTriangle(a + 1, b, b + 1);
            }
        }

        return mesh;
    }

    static Mesh createRamp() {

        Mesh mesh;

        Vec3 a(-0.5f, -0.5f, -0.5f);
        Vec3 b(0.5f, -0.5f, -0.5f);
        Vec3 c(0.5f, 0.5f, 0.5f);
        Vec3 d(-0.5f, 0.5f, 0.5f);

        Vec3 e(-0.5f, -0.5f, 0.5f);
        Vec3 f(0.5f, -0.5f, 0.5f);

        addFace(mesh, a, b, f, e, Vec3(0, -1, 0));
        addFace(mesh, a, e, d, a, Vec3(-1, 0, 0));
        addFace(mesh, b, c, f, b, Vec3(1, 0, 0));

        Vec3 slopeNormal = normalize(Vec3(0, 1, -1));

        addFace(mesh, d, c, b, a, slopeNormal);

        addFace(mesh, e, f, c, d, Vec3(0, 0, 1));

        return mesh;
    }

    static Mesh createWedge() {

        Mesh mesh;

        Vec3 a(-0.5f, -0.5f, -0.5f);
        Vec3 b(0.5f, -0.5f, -0.5f);
        Vec3 c(0.5f, -0.5f, 0.5f);
        Vec3 d(-0.5f, -0.5f, 0.5f);

        Vec3 e(-0.5f, 0.5f, -0.5f);
        Vec3 f(0.5f, 0.5f, -0.5f);

        addFace(
            mesh,
            a, b, c, d,
            Vec3(0, -1, 0)
        );

        addFace(
            mesh,
            a, e, f, b,
            Vec3(0, 0, -1)
        );

        addFace(
            mesh,
            a, d, e, e,
            Vec3(-1, 0, 0)
        );

        addFace(
            mesh,
            b, f, c, c,
            Vec3(1, 0, 0)
        );

        Vec3 slope = normalize(Vec3(0, 1, -1));

        addFace(
            mesh,
            d, c, f, e,
            slope
        );

        return mesh;
    }

    static Mesh createCapsule(
        int segments = 20) {

        segments = std::clamp(segments, 6, 64);

        Mesh mesh;

        const float radius = 0.25f;
        const float halfBody = 0.25f;

        const int rings = 12;

        for (int y = 0; y <= rings; ++y) {

            float v = static_cast<float>(y) / rings;
            float latitude = -PI * 0.5f + PI * v;

            float ringRadius =
                std::cos(latitude) * radius;

            float py =
                std::sin(latitude) * radius;

            if (py > 0) {
                py += halfBody;
            } else {
                py -= halfBody;
            }

            for (int x = 0; x <= segments; ++x) {

                float u = static_cast<float>(x) / segments;
                float angle = 2.0f * PI * u;

                Vec3 position(
                    ringRadius * std::cos(angle),
                    py,
                    ringRadius * std::sin(angle)
                );

                Vec3 normal = normalize(Vec3(
                    position.x,
                    position.y -
                        (py > 0 ? halfBody : -halfBody),
                    position.z
                ));

                mesh.addVertex(Vertex(
                    position,
                    normal,
                    u,
                    v
                ));
            }
        }

        for (int y = 0; y < rings; ++y) {

            for (int x = 0; x < segments; ++x) {

                uint32_t a =
                    y * (segments + 1) + x;

                uint32_t b = a + segments + 1;

                mesh.addTriangle(a, a + 1, b);
                mesh.addTriangle(a + 1, b + 1, b);
            }
        }

        return mesh;
    }

};

}
