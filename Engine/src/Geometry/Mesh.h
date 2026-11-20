#pragma once

#include <vector>
#include <cstdint>
#include <cstddef>
#include <algorithm>
#include <cmath>
#include <limits>

#include "../Camera/Camera.h"

namespace nova {

struct Vertex {

    Vec3 position;
    Vec3 normal;

    float u;
    float v;

    Vertex()
        : position(),
          normal(0.0f, 1.0f, 0.0f),
          u(0.0f),
          v(0.0f) {}

    Vertex(
        const Vec3& positionValue,
        const Vec3& normalValue,
        float textureU,
        float textureV)
        : position(positionValue),
          normal(normalValue),
          u(textureU),
          v(textureV) {}
};

struct Bounds {

    Vec3 minimum;
    Vec3 maximum;

    bool valid;

    Bounds()
        : minimum(),
          maximum(),
          valid(false) {}

    void reset() {

        float maximumFloat =
            std::numeric_limits<float>::max();

        minimum = Vec3(
            maximumFloat,
            maximumFloat,
            maximumFloat
        );

        maximum = Vec3(
            -maximumFloat,
            -maximumFloat,
            -maximumFloat
        );

        valid = false;
    }

    void expand(const Vec3& point) {

        minimum.x = std::min(minimum.x, point.x);
        minimum.y = std::min(minimum.y, point.y);
        minimum.z = std::min(minimum.z, point.z);

        maximum.x = std::max(maximum.x, point.x);
        maximum.y = std::max(maximum.y, point.y);
        maximum.z = std::max(maximum.z, point.z);

        valid = true;
    }

    Vec3 center() const {

        if (!valid) {
            return Vec3();
        }

        return (minimum + maximum) * 0.5f;
    }

    Vec3 extent() const {

        if (!valid) {
            return Vec3();
        }

        return (maximum - minimum) * 0.5f;
    }

    bool contains(const Vec3& point) const {

        if (!valid) {
            return false;
        }

        return
            point.x >= minimum.x &&
            point.x <= maximum.x &&
            point.y >= minimum.y &&
            point.y <= maximum.y &&
            point.z >= minimum.z &&
            point.z <= maximum.z;
    }

    void merge(const Bounds& other) {

        if (!other.valid) {
            return;
        }

        expand(other.minimum);
        expand(other.maximum);
    }
};

class Mesh {

private:

    std::vector<Vertex> vertices;

    std::vector<uint32_t> indices;

    Bounds bounds;

public:

    Mesh() = default;

    Mesh(
        std::vector<Vertex> vertexData,
        std::vector<uint32_t> indexData)
        : vertices(std::move(vertexData)),
          indices(std::move(indexData)) {

        calculateBounds();
    }

    void setData(
        std::vector<Vertex> vertexData,
        std::vector<uint32_t> indexData) {

        vertices = std::move(vertexData);
        indices = std::move(indexData);

        calculateBounds();
    }

    void addVertex(const Vertex& vertex) {

        vertices.push_back(vertex);

        bounds.expand(vertex.position);
    }

    void addTriangle(
        uint32_t a,
        uint32_t b,
        uint32_t c) {

        if (a >= vertices.size() ||
                b >= vertices.size() ||
                c >= vertices.size()) {
            return;
        }

        indices.push_back(a);
        indices.push_back(b);
        indices.push_back(c);
    }

    void calculateBounds() {

        bounds.reset();

        for (const Vertex& vertex : vertices) {
            bounds.expand(vertex.position);
        }
    }

    bool isValid() const {

        if (vertices.empty() || indices.empty()) {
            return false;
        }

        if (indices.size() % 3 != 0) {
            return false;
        }

        for (uint32_t index : indices) {

            if (index >= vertices.size()) {
                return false;
            }
        }

        return true;
    }

    bool isEmpty() const {

        return vertices.empty() || indices.empty();
    }

    const std::vector<Vertex>& getVertices() const {
        return vertices;
    }

    const std::vector<uint32_t>& getIndices() const {
        return indices;
    }

    const Bounds& getBounds() const {
        return bounds;
    }

    size_t vertexCount() const {
        return vertices.size();
    }

    size_t indexCount() const {
        return indices.size();
    }

    size_t triangleCount() const {
        return indices.size() / 3;
    }

    size_t vertexBufferSize() const {

        return vertices.size() * sizeof(Vertex);
    }

    size_t indexBufferSize() const {

        return indices.size() * sizeof(uint32_t);
    }

    void clear() {

        vertices.clear();
        indices.clear();

        bounds.reset();
    }

    void reserve(
        size_t vertexCapacity,
        size_t indexCapacity) {

        vertices.reserve(vertexCapacity);
        indices.reserve(indexCapacity);
    }

    void recalculateNormals() {

        for (Vertex& vertex : vertices) {
            vertex.normal = Vec3();
        }

        for (size_t i = 0; i + 2 < indices.size(); i += 3) {

            uint32_t ia = indices[i];
            uint32_t ib = indices[i + 1];
            uint32_t ic = indices[i + 2];

            if (ia >= vertices.size() ||
                    ib >= vertices.size() ||
                    ic >= vertices.size()) {
                continue;
            }

            Vec3 a = vertices[ia].position;
            Vec3 b = vertices[ib].position;
            Vec3 c = vertices[ic].position;

            Vec3 normal = cross(
                b - a,
                c - a
            );

            vertices[ia].normal =
                vertices[ia].normal + normal;

            vertices[ib].normal =
                vertices[ib].normal + normal;

            vertices[ic].normal =
                vertices[ic].normal + normal;
        }

        for (Vertex& vertex : vertices) {

            vertex.normal = normalize(
                vertex.normal
            );

            if (length(vertex.normal) < 0.0001f) {
                vertex.normal = Vec3(
                    0.0f,
                    1.0f,
                    0.0f
                );
            }
        }
    }

};

}
