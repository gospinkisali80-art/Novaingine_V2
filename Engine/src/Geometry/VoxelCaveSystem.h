#pragma once

#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <limits>
#include <vector>

#include "Mesh.h"

namespace nova {

class VoxelCaveSystem {
public:
    bool build(const std::vector<Vec3>& controlPath, float radius, float voxelSize = 0.5f) {
        if (controlPath.size() < 2 || controlPath.size() > 1024) return false;
        radius_ = std::clamp(radius, 0.5f, 8.0f);
        spacing_ = std::clamp(voxelSize, 0.2f, 1.0f);
        path_ = controlPath;

        for (const Vec3& point : path_) {
            if (!std::isfinite(point.x) || !std::isfinite(point.y) || !std::isfinite(point.z))
                return false;
        }

        const Vec3 startDirection = normalize(path_[1] - path_[0]);
        const Vec3 endDirection = normalize(path_.back() - path_[path_.size() - 2]);
        path_.front() = path_.front() - startDirection * (radius_ * 2.5f);
        path_.back() = path_.back() + endDirection * (radius_ * 2.5f);

        Vec3 minimum(std::numeric_limits<float>::max(),
                     std::numeric_limits<float>::max(),
                     std::numeric_limits<float>::max());
        Vec3 maximum(-std::numeric_limits<float>::max(),
                     -std::numeric_limits<float>::max(),
                     -std::numeric_limits<float>::max());
        for (const Vec3& point : path_) {
            minimum.x = std::min(minimum.x, point.x);
            minimum.y = std::min(minimum.y, point.y);
            minimum.z = std::min(minimum.z, point.z);
            maximum.x = std::max(maximum.x, point.x);
            maximum.y = std::max(maximum.y, point.y);
            maximum.z = std::max(maximum.z, point.z);
        }
        const float margin = radius_ + spacing_ * 2.0f;
        origin_ = Vec3(minimum.x - margin, minimum.y - margin, minimum.z - margin);
        const Vec3 extent(maximum.x - minimum.x + margin * 2.0f,
                          maximum.y - minimum.y + margin * 2.0f,
                          maximum.z - minimum.z + margin * 2.0f);
        nx_ = std::max(3, static_cast<int>(std::ceil(extent.x / spacing_)) + 1);
        ny_ = std::max(3, static_cast<int>(std::ceil(extent.y / spacing_)) + 1);
        nz_ = std::max(3, static_cast<int>(std::ceil(extent.z / spacing_)) + 1);
        if (nx_ > 256 || ny_ > 256 || nz_ > 256 ||
            static_cast<uint64_t>(nx_) * ny_ * nz_ > 1200000u) return false;

        field_.resize(static_cast<size_t>(nx_) * ny_ * nz_);
        for (int z = 0; z < nz_; ++z) {
            for (int y = 0; y < ny_; ++y) {
                for (int x = 0; x < nx_; ++x) {
                    const Vec3 p = gridPoint(x, y, z);
                    float distance = std::numeric_limits<float>::max();
                    for (size_t i = 1; i < path_.size(); ++i)
                        distance = std::min(distance, distanceToSegment(p, path_[i - 1], path_[i]));
                    field_[index(x, y, z)] = distance - radius_;
                }
            }
        }
        return true;
    }

    bool valid() const {
        return nx_ >= 3 && ny_ >= 3 && nz_ >= 3 &&
               field_.size() == static_cast<size_t>(nx_) * ny_ * nz_;
    }

    const std::vector<Vec3>& path() const { return path_; }
    float radius() const { return radius_; }
    float spacing() const { return spacing_; }

    float sampleDistance(const Vec3& p) const {
        if (!valid()) return std::numeric_limits<float>::max();
        const float gx = (p.x - origin_.x) / spacing_;
        const float gy = (p.y - origin_.y) / spacing_;
        const float gz = (p.z - origin_.z) / spacing_;
        if (gx < 0.0f || gy < 0.0f || gz < 0.0f ||
            gx > nx_ - 1.0f || gy > ny_ - 1.0f || gz > nz_ - 1.0f)
            return std::numeric_limits<float>::max();
        const int x0 = std::min(nx_ - 2, static_cast<int>(std::floor(gx)));
        const int y0 = std::min(ny_ - 2, static_cast<int>(std::floor(gy)));
        const int z0 = std::min(nz_ - 2, static_cast<int>(std::floor(gz)));
        const float tx = gx - x0, ty = gy - y0, tz = gz - z0;
        const float c000 = field_[index(x0,y0,z0)];
        const float c100 = field_[index(x0+1,y0,z0)];
        const float c010 = field_[index(x0,y0+1,z0)];
        const float c110 = field_[index(x0+1,y0+1,z0)];
        const float c001 = field_[index(x0,y0,z0+1)];
        const float c101 = field_[index(x0+1,y0,z0+1)];
        const float c011 = field_[index(x0,y0+1,z0+1)];
        const float c111 = field_[index(x0+1,y0+1,z0+1)];
        const float x00 = c000 + (c100-c000)*tx;
        const float x10 = c010 + (c110-c010)*tx;
        const float x01 = c001 + (c101-c001)*tx;
        const float x11 = c011 + (c111-c011)*tx;
        const float y0v = x00 + (x10-x00)*ty;
        const float y1v = x01 + (x11-x01)*ty;
        return y0v + (y1v-y0v)*tz;
    }

    bool containsAir(const Vec3& p) const {
        return sampleDistance(p) < 0.0f;
    }

    bool resolveSphereInsideCave(Vec3& position, float sphereRadius) const {
        const float distance = sampleDistance(position);
        if (!std::isfinite(distance) || distance >= 0.0f || distance <= -sphereRadius)
            return false;
        const float h = spacing_ * 0.5f;
        const Vec3 gradient(
            sampleDistance(position + Vec3(h,0,0)) - sampleDistance(position - Vec3(h,0,0)),
            sampleDistance(position + Vec3(0,h,0)) - sampleDistance(position - Vec3(0,h,0)),
            sampleDistance(position + Vec3(0,0,h)) - sampleDistance(position - Vec3(0,0,h))
        );
        const Vec3 normal = normalize(gradient);
        if (length(normal) < 0.001f) return false;
        position = position - normal * (sphereRadius + distance);
        return true;
    }

    Mesh buildSurfaceMesh() const {
        if (!valid()) return Mesh();
        std::vector<Vertex> vertices;
        std::vector<uint32_t> indices;
        const int cubeCorners[8][3] = {
            {0,0,0},{1,0,0},{1,1,0},{0,1,0},
            {0,0,1},{1,0,1},{1,1,1},{0,1,1}
        };
        const int tetrahedra[6][4] = {
            {0,5,1,6},{0,1,2,6},{0,2,3,6},
            {0,3,7,6},{0,7,4,6},{0,4,5,6}
        };
        auto interpolate = [](const Vec3& a, const Vec3& b, float da, float db) {
            const float denominator = da - db;
            const float t = std::fabs(denominator) < 1e-6f ? 0.5f :
                std::clamp(da / denominator, 0.0f, 1.0f);
            return a + (b - a) * t;
        };
        auto addTriangle = [&](const Vec3& a, const Vec3& b, const Vec3& c) {
            const uint32_t base = static_cast<uint32_t>(vertices.size());
            vertices.emplace_back(a, Vec3(0,1,0), 0, 0);
            vertices.emplace_back(b, Vec3(0,1,0), 0, 0);
            vertices.emplace_back(c, Vec3(0,1,0), 0, 0);
            indices.insert(indices.end(), {base, base+1, base+2});
        };

        for (int z = 0; z < nz_ - 1; ++z) {
            for (int y = 0; y < ny_ - 1; ++y) {
                for (int x = 0; x < nx_ - 1; ++x) {
                    Vec3 points[8];
                    float values[8];
                    for (int c = 0; c < 8; ++c) {
                        const int gx = x + cubeCorners[c][0];
                        const int gy = y + cubeCorners[c][1];
                        const int gz = z + cubeCorners[c][2];
                        points[c] = gridPoint(gx, gy, gz);
                        values[c] = field_[index(gx,gy,gz)];
                    }
                    for (const auto& tetra : tetrahedra) {
                        int inside[4], outside[4], insideCount=0, outsideCount=0;
                        for (int k = 0; k < 4; ++k) {
                            const int id = tetra[k];
                            if (values[id] < 0.0f) inside[insideCount++] = id;
                            else outside[outsideCount++] = id;
                        }
                        if (insideCount == 0 || insideCount == 4) continue;
                        if (insideCount == 1) {
                            const int a = inside[0];
                            addTriangle(
                                interpolate(points[a],points[outside[0]],values[a],values[outside[0]]),
                                interpolate(points[a],points[outside[1]],values[a],values[outside[1]]),
                                interpolate(points[a],points[outside[2]],values[a],values[outside[2]])
                            );
                        } else if (insideCount == 3) {
                            const int a = outside[0];
                            addTriangle(
                                interpolate(points[a],points[inside[0]],values[a],values[inside[0]]),
                                interpolate(points[a],points[inside[2]],values[a],values[inside[2]]),
                                interpolate(points[a],points[inside[1]],values[a],values[inside[1]])
                            );
                        } else {
                            const int a=inside[0], b=inside[1], c=outside[0], d=outside[1];
                            const Vec3 ac=interpolate(points[a],points[c],values[a],values[c]);
                            const Vec3 ad=interpolate(points[a],points[d],values[a],values[d]);
                            const Vec3 bc=interpolate(points[b],points[c],values[b],values[c]);
                            const Vec3 bd=interpolate(points[b],points[d],values[b],values[d]);
                            addTriangle(ac,ad,bc);
                            addTriangle(bc,ad,bd);
                        }
                    }
                }
            }
        }
        Mesh result(std::move(vertices), std::move(indices));
        if (result.isValid()) result.recalculateNormals();
        return result;
    }

private:
    size_t index(int x, int y, int z) const {
        return (static_cast<size_t>(z) * ny_ + static_cast<size_t>(y)) * nx_ +
               static_cast<size_t>(x);
    }
    Vec3 gridPoint(int x, int y, int z) const {
        return Vec3(origin_.x + x*spacing_, origin_.y + y*spacing_, origin_.z + z*spacing_);
    }
    static float distanceToSegment(const Vec3& p, const Vec3& a, const Vec3& b) {
        const Vec3 ab = b - a;
        const float lengthSquared = dot(ab, ab);
        const float t = lengthSquared > 1e-8f
            ? std::clamp(dot(p-a,ab)/lengthSquared,0.0f,1.0f) : 0.0f;
        return length(p - (a + ab*t));
    }

    Vec3 origin_;
    int nx_=0, ny_=0, nz_=0;
    float spacing_=0.5f, radius_=1.0f;
    std::vector<float> field_;
    std::vector<Vec3> path_;
};

} // namespace nova
