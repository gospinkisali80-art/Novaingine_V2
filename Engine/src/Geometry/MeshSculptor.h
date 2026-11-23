#pragma once

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <vector>

#include "Mesh.h"

namespace nova {

enum class SculptBrush : int {
    Draw = 0, Smooth = 1, Grab = 2, Inflate = 3,
    Pinch = 4, Flatten = 5, Crease = 6, Mask = 7
};

class MeshSculptor {
public:
    static Mesh makeSphere(int rings = 48, int segments = 64) {
        rings = std::clamp(rings, 8, 128);
        segments = std::clamp(segments, 12, 192);
        std::vector<Vertex> vertices;
        std::vector<uint32_t> indices;
        vertices.reserve(static_cast<size_t>(rings + 1) * (segments + 1));
        indices.reserve(static_cast<size_t>(rings) * segments * 6);
        for (int y = 0; y <= rings; ++y) {
            const float v = static_cast<float>(y) / rings;
            const float phi = v * 3.14159265358979323846f;
            for (int x = 0; x <= segments; ++x) {
                const float u = static_cast<float>(x) / segments;
                const float theta = u * 6.28318530717958647692f;
                Vec3 p(std::sin(phi) * std::cos(theta),
                       std::cos(phi),
                       std::sin(phi) * std::sin(theta));
                vertices.emplace_back(p, normalize(p), u, v);
            }
        }
        for (int y = 0; y < rings; ++y) {
            for (int x = 0; x < segments; ++x) {
                const uint32_t a = static_cast<uint32_t>(y * (segments + 1) + x);
                const uint32_t b = a + static_cast<uint32_t>(segments + 1);
                indices.insert(indices.end(), {a, a + 1, b, a + 1, b + 1, b});
            }
        }
        Mesh mesh(std::move(vertices), std::move(indices));
        mesh.recalculateNormals();
        return mesh;
    }

    static Mesh makePlane(int divisions = 96) {
        divisions = std::clamp(divisions, 8, 192);
        const int side = divisions + 1;
        std::vector<Vertex> vertices;
        std::vector<uint32_t> indices;
        vertices.reserve(static_cast<size_t>(side) * side);
        indices.reserve(static_cast<size_t>(divisions) * divisions * 6);
        for (int z = 0; z <= divisions; ++z) {
            for (int x = 0; x <= divisions; ++x) {
                const float u = static_cast<float>(x) / divisions;
                const float v = static_cast<float>(z) / divisions;
                vertices.emplace_back(Vec3((u - 0.5f) * 2.0f, 0.0f, (v - 0.5f) * 2.0f),
                                      Vec3(0, 1, 0), u, v);
            }
        }
        for (int z = 0; z < divisions; ++z) {
            for (int x = 0; x < divisions; ++x) {
                const uint32_t a = static_cast<uint32_t>(z * side + x);
                const uint32_t b = a + 1;
                const uint32_t c = a + static_cast<uint32_t>(side);
                const uint32_t d = c + 1;
                indices.insert(indices.end(), {a, c, b, b, c, d});
            }
        }
        Mesh mesh(std::move(vertices), std::move(indices));
        mesh.recalculateNormals();
        return mesh;
    }

    static bool apply(Mesh& mesh, std::vector<float>& masks,
                      const Vec3& center, const Vec3& dragDelta,
                      float radius, float strength, SculptBrush brush,
                      float falloff, bool symmetryX) {
        auto vertices = mesh.getVertices();
        const auto& indices = mesh.getIndices();
        if (vertices.empty() || indices.empty()) return false;
        if (masks.size() != vertices.size()) masks.assign(vertices.size(), 0.0f);
        radius = std::clamp(radius, 0.005f, 100.0f);
        strength = std::clamp(strength, 0.0001f, 2.0f);
        falloff = std::clamp(falloff, 0.0f, 1.0f);

        std::vector<Vec3> sums;
        std::vector<uint32_t> counts;
        if (brush == SculptBrush::Smooth) {
            sums.resize(vertices.size());
            counts.assign(vertices.size(), 0);
            for (size_t i = 0; i + 2 < indices.size(); i += 3) {
                const uint32_t a = indices[i], b = indices[i + 1], c = indices[i + 2];
                if (a >= vertices.size() || b >= vertices.size() || c >= vertices.size()) continue;
                sums[a] = sums[a] + vertices[b].position + vertices[c].position;
                sums[b] = sums[b] + vertices[a].position + vertices[c].position;
                sums[c] = sums[c] + vertices[a].position + vertices[b].position;
                counts[a] += 2; counts[b] += 2; counts[c] += 2;
            }
        }

        bool changed = false;
        const float crease = strength * 0.45f;
        for (size_t i = 0; i < vertices.size(); ++i) {
            Vertex& vertex = vertices[i];
            const Vec3 original = vertex.position;
            const Vec3 mirrored(-original.x, original.y, original.z);
            const float d0 = length(original - center);
            const float d1 = symmetryX ? length(mirrored - center) : 1.0e9f;
            const bool mirroredSide = d1 < d0;
            const float distance = std::min(d0, d1);
            if (distance > radius) continue;

            const float t = 1.0f - distance / radius;
            const float smooth = t * t * (3.0f - 2.0f * t);
            const float weight = std::pow(std::max(0.0f, smooth), 1.0f + falloff * 4.0f);
            const float protection = 1.0f - std::clamp(masks[i], 0.0f, 1.0f);
            const float w = weight * protection;
            Vec3 p = original;
            Vec3 normal = vertex.normal;
            if (mirroredSide) normal.x = -normal.x;

            switch (brush) {
                case SculptBrush::Draw:
                case SculptBrush::Inflate:
                    p = original + normal * (strength * w);
                    break;
                case SculptBrush::Smooth:
                    if (counts[i]) {
                        const Vec3 average = sums[i] * (1.0f / static_cast<float>(counts[i]));
                        p = original + (average - original) * std::min(1.0f, strength * w);
                    }
                    break;
                case SculptBrush::Grab:
                    p = original + dragDelta * w;
                    break;
                case SculptBrush::Pinch: {
                    Vec3 target = center - original;
                    if (mirroredSide) target.x = -target.x;
                    p = original + normalize(target) * (strength * w);
                    break;
                }
                case SculptBrush::Flatten:
                    p = original - normal * (dot(original - center, normal) * strength * w);
                    break;
                case SculptBrush::Crease:
                    p = original - normal * (crease * w);
                    break;
                case SculptBrush::Mask:
                    masks[i] = std::clamp(masks[i] + strength * w, 0.0f, 1.0f);
                    break;
            }
            if (length(p - original) > 0.000001f) {
                vertex.position = p;
                changed = true;
            }
        }
        if (brush == SculptBrush::Mask) return true;
        if (!changed) return false;
        mesh.setData(std::move(vertices), indices);
        mesh.recalculateNormals();
        return true;
    }
};

} // namespace nova
