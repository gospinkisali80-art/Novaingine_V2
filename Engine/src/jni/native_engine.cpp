#include <jni.h>
#include <android/log.h>

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <fstream>
#include <iomanip>
#include <sstream>
#include <mutex>
#include <condition_variable>
#include <deque>
#include <thread>
#include <atomic>
#include <unordered_map>
#include <vector>
#include <utility>
#include <cstdio>
#include <cstring>
#include <zlib.h>
#include <thread>

#include "../Camera/Camera.h"
#include "../Scene/Scene.h"
#include "../Scene/SceneFactory.h"
#include "../Renderer/Renderer.h"
#include "../Physics/PhysicsWorld.h"
#include "../Geometry/Mesh.h"
#include "../Geometry/LandscapeSystem.h"
#include "../Geometry/PrimitiveBuilder.h"
#include "../Geometry/MeshSculptor.h"
#include "../Geometry/MeshModeling.h"
#include "../Geometry/SculptMesh.h"
#include <memory>
#include "../VisualScripting/VisualScriptRuntime.h"
#define CGLTF_IMPLEMENTATION
#include "../ThirdParty/cgltf.h"

#define NOVA_LOG_TAG "NovaEngine"
#define NOVA_LOG(...) __android_log_print(ANDROID_LOG_INFO, NOVA_LOG_TAG, __VA_ARGS__)

namespace {

std::mutex engineMutex;

Renderer gRenderer;
nova::Scene gScene;
nova::LandscapeSystem gLandscape;
struct LandscapeWorldTile {
    int tileX = 0, tileZ = 0, lod = 0;
    uint32_t objectId = 0;
    bool loaded = true;
    nova::LandscapeSystem data;
    nova::Mesh fullMesh;
};
std::unordered_map<uint64_t, LandscapeWorldTile> gLandscapeWorldTiles;
uint64_t landscapeTileKey(int x, int z) {
    return (static_cast<uint64_t>(static_cast<uint32_t>(x)) << 32) |
           static_cast<uint32_t>(z);
}
float landscapeTileOriginX(int x) { return -32.0f + static_cast<float>(x) * 64.0f; }
float landscapeTileOriginZ(int z) { return -36.0f + static_cast<float>(z) * 64.0f; }

struct LandscapeRigidBody { nova::Vec3 velocity; float mass=1.0f; float restitution=0.12f; float friction=0.7f; };
std::unordered_map<uint32_t, LandscapeRigidBody> gLandscapeRigidBodies;
std::unordered_map<uint32_t, nova::Mesh> gCustomMeshes;
std::unordered_map<uint32_t, std::vector<float>> gSculptMasks;
std::unique_ptr<nova::SculptMesh> gSculptMesh;
uint32_t gSculptMeshId = 0;
bool gSculptMode = false;
bool gManualSculptWorkspaceActive = false;
bool gSculptWorkspacePreviousShowGrid = false;
bool gSculptWorkspacePreviousShowBounds = false;
bool gSculptWorkspacePreviousShowSky = true;
bool gSculptWorkspacePreviousShowShadows = true;
std::vector<std::pair<uint32_t, bool>> gSculptWorkspaceVisibility;
bool gSculptStrokeActive = false;
bool gTerrainStrokeActive = false;
bool gTerrainStrokeHasUndo = false;
int gTerrainLodLevel = -1;
bool gTerrainChunksDirty = true;
int gLandscapeChunkSize = 32;
bool gHasPreviousSculptHit = false;
nova::Vec3 gPreviousSculptHit;
nova::Vec3 gPreviousSculptNormal;
nova::VisualScript gVisualScript;
nova::VisualScriptRuntime gVisualScriptRuntime;
bool gVisualScriptStarted = false;
nova::Camera gCamera;

struct SculptDabJob {
    nova::Vec3 center, normal, delta;
    nova::SculptMesh::Brush brush;
    bool begin=false;
    bool end=false;
};
std::mutex gSculptDataMutex;
std::mutex gSculptQueueMutex;
std::condition_variable gSculptQueueCv;
std::deque<SculptDabJob> gSculptQueue;
std::thread gSculptWorker;
std::atomic<bool> gSculptWorkerStarted{false};
std::atomic<bool> gSculptMeshDirty{false};
std::atomic<bool> gSculptMaskDirty{false};
std::atomic<uint32_t> gSculptJobsPending{0};



void startSculptWorker() {
    bool expected=false;
    if(!gSculptWorkerStarted.compare_exchange_strong(expected,true))return;
    std::thread([](){
        for(;;) {
            SculptDabJob job;
            {
                std::unique_lock<std::mutex> lock(gSculptQueueMutex);
                gSculptQueueCv.wait(lock,[](){return !gSculptQueue.empty();});
                job=std::move(gSculptQueue.front());
                gSculptQueue.pop_front();
            }
            std::lock_guard<std::mutex> dataLock(gSculptDataMutex);
            if(!gSculptMesh) { gSculptJobsPending.fetch_sub(1, std::memory_order_release); continue; }
            if(job.begin){gSculptMesh->beginStroke();gSculptJobsPending.fetch_sub(1,std::memory_order_release);continue;}
            if(job.end){gSculptMesh->endStroke();gSculptJobsPending.fetch_sub(1,std::memory_order_release);continue;}
            if(gSculptMesh->applyDab(job.center,job.normal,job.delta,job.brush)) {
                if(job.brush.type==7||job.brush.type==11)gSculptMaskDirty.store(true,std::memory_order_release);
                else gSculptMeshDirty.store(true,std::memory_order_release);
            }
            gSculptJobsPending.fetch_sub(1, std::memory_order_release);
        }
    }).detach();
}
void enqueueSculptJob(const SculptDabJob& job) {
    gSculptJobsPending.fetch_add(1, std::memory_order_acq_rel);
    {
        std::lock_guard<std::mutex> lock(gSculptQueueMutex);
        if(gSculptQueue.size()>=4096){gSculptQueue.pop_front();gSculptJobsPending.fetch_sub(1,std::memory_order_release);}
        gSculptQueue.push_back(job);
    }
    gSculptQueueCv.notify_one();
}





bool gInitialized = false;
bool gPaused = false;
bool gWorldSpace = true;
bool gSnapEnabled = true;
bool gPhysicsEnabled = false;
bool gPhysicsGrounded = false;
bool gJumpWasPressed = false;
float gVerticalVelocity = 0.0f;

int gTransformMode = 0;

float gGridSnap = 10.0f;
float gRotationSnap = 10.0f;
float gScaleSnap = 0.25f;
float gCameraSpeed = 3.0f;

struct SceneSnapshot {
    std::vector<nova::SceneObject> objects;
    std::unordered_map<uint32_t, nova::Mesh> customMeshes;
    std::unordered_map<uint32_t, std::vector<float>> sculptMasks;
    bool includesCustomMeshes = false;
    bool includesLandscape = false;
    nova::LandscapeSystem landscape;
    std::unordered_map<uint64_t, LandscapeWorldTile> worldTiles;
    uint32_t selectedId = 0;
};

std::vector<SceneSnapshot> gUndoStack;
std::vector<SceneSnapshot> gRedoStack;
constexpr size_t MAX_UNDO_STEPS = 64;

SceneSnapshot captureScene(bool includeCustomMeshes = false) {
    SceneSnapshot snapshot;
    snapshot.objects = gScene.getObjects();
    snapshot.includesCustomMeshes = includeCustomMeshes;
    snapshot.includesLandscape = includeCustomMeshes;
    if (includeCustomMeshes) { snapshot.landscape = gLandscape; snapshot.worldTiles = gLandscapeWorldTiles; }
    if (includeCustomMeshes) { snapshot.customMeshes = gCustomMeshes; snapshot.sculptMasks = gSculptMasks; }
    snapshot.selectedId = gScene.selectedId();
    return snapshot;
}

void recordUndo(bool includeCustomMeshes = false) {
    gUndoStack.push_back(captureScene(includeCustomMeshes));
    if (gUndoStack.size() > MAX_UNDO_STEPS) {
        gUndoStack.erase(gUndoStack.begin());
    }
    gRedoStack.clear();
}

void restoreScene(const SceneSnapshot& snapshot) {
    gScene.clear();
    if (snapshot.includesCustomMeshes) { gCustomMeshes = snapshot.customMeshes; gSculptMasks = snapshot.sculptMasks; }
    if (snapshot.includesLandscape) { gLandscape = snapshot.landscape; gLandscapeWorldTiles = snapshot.worldTiles; }
    for (const nova::SceneObject& object : snapshot.objects) {
        gScene.addObject(object);
    }
    if (snapshot.selectedId != 0) {
        gScene.select(snapshot.selectedId);
    }
    gRenderer.releaseAllMeshes();
    gTerrainLodLevel = -1;
    gTerrainChunksDirty = true;
    for (const auto& entry : gCustomMeshes) gRenderer.uploadMesh(entry.first, entry.second);
    {
        std::lock_guard<std::mutex> qlock(gSculptQueueMutex);
        { const uint32_t dropped = static_cast<uint32_t>(gSculptQueue.size()); gSculptQueue.clear(); if (dropped) gSculptJobsPending.fetch_sub(dropped, std::memory_order_release); }
    }
    {
        std::lock_guard<std::mutex> dataLock(gSculptDataMutex);
        auto restored=gCustomMeshes.find(gSculptMeshId);
        if(restored!=gCustomMeshes.end()&&gSculptMode) {
            gSculptMesh.reset(new nova::SculptMesh());
            const auto& verts=restored->second.getVertices();
            gSculptMesh->positions.reserve(verts.size());
            gSculptMesh->normals.reserve(verts.size());
            for(const auto& v:verts){gSculptMesh->positions.push_back(v.position);gSculptMesh->normals.push_back(v.normal);}
            gSculptMesh->indices=restored->second.getIndices();
            gSculptMesh->mask=gSculptMasks[gSculptMeshId];
            gSculptMesh->buildAdjacency();gSculptMesh->buildChunks();gSculptMesh->buildBVH();
        } else if(gSculptMode) { gSculptMesh.reset();gSculptMeshId=0; }
        gSculptMeshDirty.store(false,std::memory_order_release);
    gSculptMaskDirty.store(false,std::memory_order_release);
    }
    gVerticalVelocity = 0.0f;
    gPhysicsGrounded = false;
    gJumpWasPressed = false;
}

bool applyUndo(bool undo) {
    std::vector<SceneSnapshot>& source = undo ? gUndoStack : gRedoStack;
    std::vector<SceneSnapshot>& destination = undo ? gRedoStack : gUndoStack;
    if (source.empty()) return false;

    destination.push_back(captureScene(source.back().includesCustomMeshes));
    if (destination.size() > MAX_UNDO_STEPS) {
        destination.erase(destination.begin());
    }

    SceneSnapshot target = source.back();
    source.pop_back();
    restoreScene(target);
    return true;
}

int gViewportWidth = 1;
int gViewportHeight = 1;

constexpr float PI = 3.14159265358979323846f;
constexpr float RAD_TO_DEG = 57.29577951308232f;

float radians(float degrees) {
    return degrees / RAD_TO_DEG;
}

float snapValue(float value, float step) {
    if (step <= 0.0001f) {
        return value;
    }

    return std::round(value / step) * step;
}

void applyShowFlags(int flags) {
    RendererSettings settings = gRenderer.getSettings();

    settings.showGrid = (flags & 1) != 0;
    settings.showShadows = (flags & 2) != 0;
    settings.showSky = (flags & 4) != 0;
    settings.showCharacters = (flags & 8) != 0;
    settings.showStatistics = (flags & 16) != 0;
    settings.showBounds = (flags & 32) != 0;

    settings.showSelectionOutline = true;

    gRenderer.setSettings(settings);
}

void setCameraDirection(const nova::Vec3& direction) {
    nova::Vec3 dir = nova::normalize(direction);

    if (nova::length(dir) < 0.0001f) {
        return;
    }

    gCamera.yaw = std::atan2(dir.z, dir.x) * RAD_TO_DEG;
    gCamera.pitch = std::asin(
        std::clamp(dir.y, -1.0f, 1.0f)
    ) * RAD_TO_DEG;

    gCamera.pitch = std::clamp(
        gCamera.pitch,
        -89.0f,
        89.0f
    );
}

nova::Mat4 multiplyMat4(const nova::Mat4& a, const nova::Mat4& b) {
    nova::Mat4 out;
    for (int col=0;col<4;col++) for(int row=0;row<4;row++) {
        out.m[col*4+row]=0.0f;
        for(int k=0;k<4;k++) out.m[col*4+row]+=a.m[k*4+row]*b.m[col*4+k];
    }
    return out;
}

bool invertMat4(const nova::Mat4& input, nova::Mat4& output) {
    float a[4][8]{};
    for(int r=0;r<4;r++){for(int col=0;col<4;col++)a[r][col]=input.m[col*4+r];a[r][r+4]=1.0f;}
    for(int col=0;col<4;col++){
        int pivot=col;for(int r=col+1;r<4;r++)if(std::fabs(a[r][col])>std::fabs(a[pivot][col]))pivot=r;
        if(std::fabs(a[pivot][col])<1e-9f)return false;
        if(pivot!=col)for(int k=0;k<8;k++)std::swap(a[pivot][k],a[col][k]);
        float d=a[col][col];for(int k=0;k<8;k++)a[col][k]/=d;
        for(int r=0;r<4;r++)if(r!=col){float q=a[r][col];for(int k=0;k<8;k++)a[r][k]-=q*a[col][k];}
    }
    for(int r=0;r<4;r++)for(int col=0;col<4;col++)output.m[col*4+r]=a[r][col+4];
    return true;
}

nova::Vec3 unprojectPoint(const nova::Mat4& inverse, float x, float y, float z, bool& ok) {
    float v[4]={x,y,z,1.0f},o[4]={0,0,0,0};
    for(int row=0;row<4;row++)for(int col=0;col<4;col++)o[row]+=inverse.m[col*4+row]*v[col];
    if(std::fabs(o[3])<1e-8f){ok=false;return nova::Vec3();}
    ok=true;return nova::Vec3(o[0]/o[3],o[1]/o[3],o[2]/o[3]);
}

bool makeScreenRay(float screenX, float screenY, nova::Vec3& ray) {
    if(gViewportWidth<=0||gViewportHeight<=0)return false;
    float nx=(2.0f*screenX/gViewportWidth)-1.0f;
    float ny=1.0f-(2.0f*screenY/gViewportHeight);
    nova::Mat4 vp=multiplyMat4(gCamera.projectionMatrix((float)gViewportWidth/(float)gViewportHeight),gCamera.viewMatrix());
    nova::Mat4 inverse;
    if(!invertMat4(vp,inverse))return false;
    bool nearOk=false,farOk=false;
    nova::Vec3 nearPoint=unprojectPoint(inverse,nx,ny,-1.0f,nearOk);
    nova::Vec3 farPoint=unprojectPoint(inverse,nx,ny,1.0f,farOk);
    if(!nearOk||!farOk)return false;
    ray=nova::normalize(farPoint-nearPoint);
    return nova::length(ray)>0.0001f;
}

float terrainHeightAt(float worldX, float worldZ, bool& found, bool allowHole = false);

bool syncLandscapeMeshFromData() {
    nova::SceneObject* terrain = nullptr;
    for (nova::SceneObject& object : gScene.getObjects()) {
        if (object.name == "Landscape Terrain") { terrain = &object; break; }
    }
    if (!terrain || !gLandscape.valid()) return false;
    auto meshIt = gCustomMeshes.find(terrain->id);
    if (meshIt == gCustomMeshes.end()) return false;
    const auto heights = gLandscape.heights();
    const auto weights = gLandscape.weights();
    if (heights.size() != 129u * 129u || weights.size() != heights.size()) return false;
    std::vector<nova::Vertex> vertices = meshIt->second.getVertices();
    if (vertices.size() != heights.size()) return false;
    for (size_t i=0;i<vertices.size();++i) {
        vertices[i].position.y = std::clamp(heights[i], -12.0f, 24.0f);
        for (int c=0;c<4;++c) vertices[i].layerWeights[c] = weights[i][c];
    }
    std::vector<uint32_t> indices;
    indices.reserve(128u*128u*6u);
    for (int z=0;z<128;++z) for (int x=0;x<128;++x) {
        if (gLandscape.isHole(x,z)) continue;
        const uint32_t a=static_cast<uint32_t>(z*129+x), b=a+1, c=a+129, d=c+1;
        indices.insert(indices.end(), {a,c,b,b,c,d});
    }
    nova::Mesh updated;
    updated.setData(std::move(vertices), std::move(indices));
    updated.recalculateNormals();
    if (!updated.isValid()) return false;
    meshIt->second = std::move(updated);
    const bool ok = gRenderer.uploadMesh(terrain->id, meshIt->second);
    if (ok) { gTerrainLodLevel = -1; gTerrainChunksDirty = true; }
    return ok;
}

bool terrainScreenHit(float screenX, float screenY, float& hitX, float& hitZ, float& hitY, bool allowHole = false) {
    nova::Vec3 ray;
    if (!makeScreenRay(screenX, screenY, ray)) return false;
    const nova::Vec3 origin=gCamera.position;
    for(float distance=0.15f;distance<=500.0f;distance+=0.12f){
        const nova::Vec3 p=origin+ray*distance;
        bool found=false;float h=terrainHeightAt(p.x,p.z,found,allowHole);
        if(found&&p.y<=h+0.04f){hitX=p.x;hitZ=p.z;hitY=h;return true;}
    }
    return false;
}

float terrainHeightAt(float worldX, float worldZ, bool& found, bool allowHole) {
    found = false;
    for (const nova::SceneObject& object : gScene.getObjects()) {
        if (object.name != "Landscape Terrain") continue;
        auto meshIt = gCustomMeshes.find(object.id);
        if (meshIt == gCustomMeshes.end()) continue;
        const auto& vertices = meshIt->second.getVertices();
        constexpr int resolution = 129;
        constexpr float spacing = 0.5f;
        const float gx = (worldX + 32.0f) / spacing;
        const float gz = (worldZ + 36.0f) / spacing;
        if (gx < 0.0f || gz < 0.0f || gx > 128.0f || gz > 128.0f ||
            vertices.size() != static_cast<size_t>(resolution * resolution)) continue;
        if (!allowHole && gLandscape.valid() && gLandscape.isHole(std::min(127, static_cast<int>(gx)),
                std::min(127, static_cast<int>(gz)))) continue;
        const int x0 = std::min(127, static_cast<int>(std::floor(gx)));
        const int z0 = std::min(127, static_cast<int>(std::floor(gz)));
        const int x1 = x0 + 1, z1 = z0 + 1;
        const float tx = gx - x0, tz = gz - z0;
        const float h00 = vertices[static_cast<size_t>(z0 * resolution + x0)].position.y;
        const float h10 = vertices[static_cast<size_t>(z0 * resolution + x1)].position.y;
        const float h01 = vertices[static_cast<size_t>(z1 * resolution + x0)].position.y;
        const float h11 = vertices[static_cast<size_t>(z1 * resolution + x1)].position.y;
        const float h0 = h00 + (h10 - h00) * tx;
        const float h1 = h01 + (h11 - h01) * tx;
        found = true;
        return h0 + (h1 - h0) * tz;
    }
    const int tileX = static_cast<int>(std::floor((worldX + 32.0f) / 64.0f));
    const int tileZ = static_cast<int>(std::floor((worldZ + 36.0f) / 64.0f));
    auto tileIt = gLandscapeWorldTiles.find(landscapeTileKey(tileX, tileZ));
    if (tileIt != gLandscapeWorldTiles.end()) {
        const LandscapeWorldTile& tile = tileIt->second;
        if (tile.data.valid() && tile.fullMesh.getVertices().size() == 129u * 129u) {
            const float gx = (worldX - landscapeTileOriginX(tile.tileX)) / tile.data.spacing();
            const float gz = (worldZ - landscapeTileOriginZ(tile.tileZ)) / tile.data.spacing();
            if (gx >= 0.0f && gz >= 0.0f && gx <= 128.0f && gz <= 128.0f) {
                const int x0 = std::min(127, static_cast<int>(std::floor(gx)));
                const int z0 = std::min(127, static_cast<int>(std::floor(gz)));
                if (allowHole || !tile.data.isHole(x0, z0)) {
                    const int x1 = x0 + 1, z1 = z0 + 1;
                    const float tx = gx - x0, tz = gz - z0;
                    const auto& vertices = tile.fullMesh.getVertices();
                    const float h00 = vertices[static_cast<size_t>(z0 * 129 + x0)].position.y;
                    const float h10 = vertices[static_cast<size_t>(z0 * 129 + x1)].position.y;
                    const float h01 = vertices[static_cast<size_t>(z1 * 129 + x0)].position.y;
                    const float h11 = vertices[static_cast<size_t>(z1 * 129 + x1)].position.y;
                    const float h0 = h00 + (h10 - h00) * tx;
                    const float h1 = h01 + (h11 - h01) * tx;
                    found = true;
                    return h0 + (h1 - h0) * tz;
                }
            }
        }
    }
    return 0.0f;
}

nova::Mesh buildWorldTileMesh(const nova::LandscapeSystem& data, int tileX, int tileZ, int stride) {
    if (!data.valid()) return nova::Mesh();
    const auto heights = data.heights();
    const auto weights = data.weights();
    if (heights.size() != 129u * 129u || weights.size() != heights.size()) return nova::Mesh();
    stride = std::clamp(stride, 1, 8);
    const int side = 128 / stride + 1;
    std::vector<nova::Vertex> vertices;
    std::vector<uint32_t> indices;
    vertices.reserve(static_cast<size_t>(side * side));
    indices.reserve(static_cast<size_t>((side - 1) * (side - 1) * 6));
    const float ox = landscapeTileOriginX(tileX), oz = landscapeTileOriginZ(tileZ);
    for (int z = 0; z <= 128; z += stride) {
        for (int x = 0; x <= 128; x += stride) {
            const size_t index = static_cast<size_t>(z * 129 + x);
            nova::Vertex vertex(nova::Vec3(ox + x * data.spacing(),
                    std::clamp(heights[index], -12.0f, 24.0f),
                    oz + z * data.spacing()), nova::Vec3(0,1,0),
                    static_cast<float>(x) / 128.0f, static_cast<float>(z) / 128.0f);
            for (int c = 0; c < 4; ++c) vertex.layerWeights[c] = weights[index][c];
            vertices.push_back(vertex);
        }
    }
    for (int z = 0; z < side - 1; ++z) for (int x = 0; x < side - 1; ++x) {
        const int gx = x * stride, gz = z * stride;
        if (data.isHole(gx, gz)) continue;
        const uint32_t a = static_cast<uint32_t>(z * side + x);
        const uint32_t b = a + 1, c = a + static_cast<uint32_t>(side), d = c + 1;
        indices.insert(indices.end(), {a,c,b,b,c,d});
    }
    nova::Mesh mesh(std::move(vertices), std::move(indices));
    if (mesh.isValid()) mesh.recalculateNormals();
    return mesh;
}

bool syncWorldTileMeshFromData(LandscapeWorldTile& tile) {
    if (tile.objectId == 0) return false;
    tile.fullMesh = buildWorldTileMesh(tile.data, tile.tileX, tile.tileZ, 1);
    if (!tile.fullMesh.isValid()) return false;
    gCustomMeshes[tile.objectId] = tile.fullMesh;
    tile.lod = 0;
    tile.loaded = true;
    nova::SceneObject* object = gScene.find(tile.objectId);
    if (object) object->visible = true;
    return gRenderer.uploadMesh(tile.objectId, gCustomMeshes[tile.objectId]);
}

LandscapeWorldTile* findLandscapeWorldTileAt(float worldX, float worldZ) {
    for (auto& entry : gLandscapeWorldTiles) {
        LandscapeWorldTile& tile = entry.second;
        const float gx = (worldX - landscapeTileOriginX(tile.tileX)) / tile.data.spacing();
        const float gz = (worldZ - landscapeTileOriginZ(tile.tileZ)) / tile.data.spacing();
        if (gx >= 0.0f && gz >= 0.0f && gx <= 128.0f && gz <= 128.0f) return &tile;
    }
    return nullptr;
}

const nova::LandscapeSystem* landscapeDataForTile(int tileX, int tileZ) {
    if (tileX == 0 && tileZ == 0) return gLandscape.valid() ? &gLandscape : nullptr;
    auto found = gLandscapeWorldTiles.find(landscapeTileKey(tileX, tileZ));
    return found == gLandscapeWorldTiles.end() ? nullptr : &found->second.data;
}

bool createLandscapeWorldTile(int tileX, int tileZ, const std::vector<float>& sourceHeights) {
    if (!gInitialized || (tileX == 0 && tileZ == 0) ||
        std::abs(tileX) > 10000 || std::abs(tileZ) > 10000 ||
        sourceHeights.size() != 129u * 129u || gLandscapeWorldTiles.size() >= 256u) return false;
    const uint64_t key = landscapeTileKey(tileX, tileZ);
    if (gLandscapeWorldTiles.count(key)) return false;

    LandscapeWorldTile tile;
    tile.tileX = tileX;
    tile.tileZ = tileZ;
    const float originX = landscapeTileOriginX(tileX);
    const float originZ = landscapeTileOriginZ(tileZ);
    if (!tile.data.initialize(129, 129, 0.5f, originX, originZ)) return false;
    tile.data.buildChunks(gLandscapeChunkSize);
    std::vector<float> heights = sourceHeights;

    auto neighborHeights = [&](int nx, int nz) -> std::vector<float> {
        const nova::LandscapeSystem* neighbor = landscapeDataForTile(nx, nz);
        return neighbor ? neighbor->heights() : std::vector<float>();
    };
    const std::vector<float> west = neighborHeights(tileX - 1, tileZ);
    const std::vector<float> east = neighborHeights(tileX + 1, tileZ);
    const std::vector<float> south = neighborHeights(tileX, tileZ - 1);
    const std::vector<float> north = neighborHeights(tileX, tileZ + 1);
    if (west.size() == heights.size())
        for (int z = 0; z < 129; ++z) heights[static_cast<size_t>(z * 129)] = west[static_cast<size_t>(z * 129 + 128)];
    if (east.size() == heights.size())
        for (int z = 0; z < 129; ++z) heights[static_cast<size_t>(z * 129 + 128)] = east[static_cast<size_t>(z * 129)];
    if (south.size() == heights.size())
        for (int x = 0; x < 129; ++x) heights[static_cast<size_t>(x)] = south[static_cast<size_t>(128 * 129 + x)];
    if (north.size() == heights.size())
        for (int x = 0; x < 129; ++x) heights[static_cast<size_t>(128 * 129 + x)] = north[static_cast<size_t>(x)];
    if (!tile.data.setBaseHeights(heights)) return false;

    const nova::Color grass(0.28f, 0.40f, 0.19f, 1.0f);
    nova::Transform transform;
    const std::string name = "Landscape Tile " + std::to_string(tileX) + " " + std::to_string(tileZ);
    tile.objectId = gScene.addObject(name, nova::PrimitiveType::Plane, transform, grass);
    nova::SceneObject* object = gScene.find(tile.objectId);
    if (!object) return false;
    object->material.baseColor = nova::Vec3(grass.r, grass.g, grass.b);
    object->material.roughness = 0.94f;
    tile.fullMesh = buildWorldTileMesh(tile.data, tileX, tileZ, 1);
    if (!tile.fullMesh.isValid()) {
        gScene.removeObject(tile.objectId);
        return false;
    }
    gCustomMeshes[tile.objectId] = tile.fullMesh;
    if (!gRenderer.uploadMesh(tile.objectId, gCustomMeshes[tile.objectId])) {
        gCustomMeshes.erase(tile.objectId);
        gScene.removeObject(tile.objectId);
        return false;
    }
    tile.loaded = true;
    tile.lod = 0;
    gLandscapeWorldTiles.emplace(key, std::move(tile));
    gScene.select(object->id);
    return true;
}

nova::LandscapeSystem* landscapeAtWorld(float worldX, float worldZ) {
    if (gLandscape.valid()) {
        const float gx = (worldX - gLandscape.originX()) / gLandscape.spacing();
        const float gz = (worldZ - gLandscape.originZ()) / gLandscape.spacing();
        if (gx >= 0.0f && gz >= 0.0f && gx <= 128.0f && gz <= 128.0f) return &gLandscape;
    }
    LandscapeWorldTile* tile = findLandscapeWorldTileAt(worldX, worldZ);
    return tile ? &tile->data : nullptr;
}

bool syncLandscapeAtWorld(float worldX, float worldZ) {
    nova::LandscapeSystem* data = landscapeAtWorld(worldX, worldZ);
    if (!data) return false;
    if (data == &gLandscape) return syncLandscapeMeshFromData();
    LandscapeWorldTile* tile = findLandscapeWorldTileAt(worldX, worldZ);
    return tile && syncWorldTileMeshFromData(*tile);
}

void updateLandscapeWorldStreaming() {
    for (auto& entry : gLandscapeWorldTiles) {
        LandscapeWorldTile& tile = entry.second;
        nova::SceneObject* object = gScene.find(tile.objectId);
        if (!object) continue;
        const float centerX = landscapeTileOriginX(tile.tileX) + 32.0f;
        const float centerZ = landscapeTileOriginZ(tile.tileZ) + 32.0f;
        const float dx = gCamera.position.x - centerX;
        const float dz = gCamera.position.z - centerZ;
        const float distance = std::sqrt(dx * dx + dz * dz);
        if (distance > 420.0f) {
            if (tile.loaded) gRenderer.releaseMesh(tile.objectId);
            tile.loaded = false;
            object->visible = false;
            continue;
        }
        const int lod = distance < 90.0f ? 0 : (distance < 180.0f ? 1 : (distance < 300.0f ? 2 : 3));
        object->visible = true;
        if (!tile.loaded || tile.lod != lod || !gCustomMeshes.count(tile.objectId)) {
            const int stride = lod == 0 ? 1 : (lod == 1 ? 2 : (lod == 2 ? 4 : 8));
            nova::Mesh lodMesh = buildWorldTileMesh(tile.data, tile.tileX, tile.tileZ, stride);
            if (lodMesh.isValid()) {
                gCustomMeshes[tile.objectId] = std::move(lodMesh);
                tile.lod = lod;
                tile.loaded = gRenderer.uploadMesh(tile.objectId, gCustomMeshes[tile.objectId]);
            }
        }
    }
}

nova::Mesh buildTerrainLodMesh(const nova::Mesh& source, int stride) {
    constexpr int resolution = 129;
    const auto& sourceVertices = source.getVertices();
    if (sourceVertices.size() != resolution * resolution) return nova::Mesh();
    stride = std::clamp(stride, 1, 8);
    const int side = 128 / stride + 1;
    std::vector<nova::Vertex> vertices;
    std::vector<uint32_t> indices;
    vertices.reserve(static_cast<size_t>(side * side));
    indices.reserve(static_cast<size_t>((side - 1) * (side - 1) * 6));
    for (int z = 0; z <= 128; z += stride) {
        for (int x = 0; x <= 128; x += stride) {
            vertices.push_back(sourceVertices[static_cast<size_t>(z * resolution + x)]);
        }
    }
    for (int z = 0; z < side - 1; ++z) {
        for (int x = 0; x < side - 1; ++x) {
            const uint32_t a = static_cast<uint32_t>(z * side + x);
            const uint32_t b = a + 1;
            const uint32_t c = a + static_cast<uint32_t>(side);
            const uint32_t d = c + 1;
            indices.insert(indices.end(), {a, c, b, b, c, d});
        }
    }
    nova::Mesh result(std::move(vertices), std::move(indices));
    result.recalculateNormals();
    return result;
}

nova::Mesh buildLandscapeChunkMesh(const nova::Mesh& source,
    const nova::LandscapeSystem::Chunk& chunk, int stride) {
    const auto& src=source.getVertices();
    stride=std::clamp(stride,1,8);
    const int side=chunk.quads/stride+1;
    if(side<2)return nova::Mesh();
    std::vector<nova::Vertex> vertices;
    std::vector<uint32_t> indices;
    vertices.reserve(static_cast<size_t>(side*side));
    for(int z=0;z<=chunk.quads;z+=stride)
        for(int x=0;x<=chunk.quads;x+=stride){
            const int gx=chunk.x+x,gz=chunk.z+z;
            vertices.push_back(src[static_cast<size_t>(gz*129+gx)]);
        }
    for(int z=0;z<side-1;++z)for(int x=0;x<side-1;++x){
        const int gx=chunk.x+x*stride,gz=chunk.z+z*stride;
        if(gLandscape.isHole(gx,gz))continue;
        const uint32_t a=static_cast<uint32_t>(z*side+x),b=a+1,c=a+side,d=c+1;
        indices.insert(indices.end(),{a,c,b,b,c,d});
    }
    nova::Mesh result(std::move(vertices),std::move(indices));
    if(result.isValid())result.recalculateNormals();
    return result;
}

void removeLandscapeChunkObjects() {
    std::vector<uint32_t> remove;
    for(const auto& object:gScene.getObjects())
        if(object.name.rfind("Landscape Chunk ",0)==0)remove.push_back(object.id);
    for(uint32_t id:remove){gRenderer.releaseMesh(id);gCustomMeshes.erase(id);gScene.removeObject(id);}
    gTerrainChunksDirty=true;
}

void updateTerrainLod() {
    nova::SceneObject* terrain=nullptr;
    for(nova::SceneObject& object:gScene.getObjects())
        if(object.name=="Landscape Terrain"){terrain=&object;break;}
    if(!terrain||!gLandscape.valid())return;
    terrain->visible=false;
    auto rootIt=gCustomMeshes.find(terrain->id);
    if(rootIt==gCustomMeshes.end())return;
    const nova::Mesh rootMesh=rootIt->second;

    const auto& chunks=gLandscape.chunks();
    for(size_t i=0;i<chunks.size();++i){
        const auto& chunk=chunks[i];
        const float cx=gLandscape.originX()+(chunk.x+chunk.quads*0.5f)*gLandscape.spacing();
        const float cz=gLandscape.originZ()+(chunk.z+chunk.quads*0.5f)*gLandscape.spacing();
        const float dx=gCamera.position.x-cx,dz=gCamera.position.z-cz;
        const float distance=std::sqrt(dx*dx+dz*dz);
        const bool shouldLoad=distance<240.0f;
        const int lod=distance<65.0f?0:(distance<125.0f?1:(distance<190.0f?2:3));
        const bool wasLoaded=chunk.loaded;
        const int oldLod=chunk.lod;
        gLandscape.setChunkState(i,shouldLoad,lod);

        const std::string name="Landscape Chunk "+std::to_string(i);
        nova::SceneObject* chunkObject=nullptr;
        for(nova::SceneObject& object:gScene.getObjects())
            if(object.name==name){chunkObject=&object;break;}

        if(!shouldLoad){
            if(chunkObject){const uint32_t id=chunkObject->id;gRenderer.releaseMesh(id);gCustomMeshes.erase(id);gScene.removeObject(id);}
            continue;
        }
        if(!chunkObject){
            nova::Transform transform;
            const nova::Color grass(0.28f,0.40f,0.19f,1.0f);
            const uint32_t id=gScene.addObject(name,nova::PrimitiveType::Plane,transform,grass);
            chunkObject=gScene.find(id);
            if(!chunkObject)continue;
            chunkObject->material.baseColor=nova::Vec3(grass.r,grass.g,grass.b);
            chunkObject->material.roughness=0.94f;
        }
        if(gTerrainChunksDirty||!wasLoaded||oldLod!=lod||!gCustomMeshes.count(chunkObject->id)){
            const int stride=lod==0?1:(lod==1?2:(lod==2?4:8));
            nova::Mesh chunkMesh=buildLandscapeChunkMesh(rootMesh,chunk,stride);
            if(chunkMesh.isValid()){
                gCustomMeshes[chunkObject->id]=std::move(chunkMesh);
                gRenderer.uploadMesh(chunkObject->id,gCustomMeshes[chunkObject->id]);
            }
        }
    }
    gTerrainChunksDirty=false;
}

void stepLandscapeRigidBodies(float dt) {
    dt = std::clamp(dt, 0.0f, 0.05f);
    if (dt <= 0.0f || gLandscapeRigidBodies.empty()) return;

    for (auto it = gLandscapeRigidBodies.begin(); it != gLandscapeRigidBodies.end();) {
        nova::SceneObject* object = gScene.find(it->first);
        if (!object || !object->visible) {
            it = gLandscapeRigidBodies.erase(it);
            continue;
        }

        LandscapeRigidBody& body = it->second;
        const float halfX = std::max(0.05f, std::fabs(object->transform.scale.x) * 0.5f);
        const float halfY = std::max(0.05f, std::fabs(object->transform.scale.y) * 0.5f);
        const float halfZ = std::max(0.05f, std::fabs(object->transform.scale.z) * 0.5f);
        const float previousBottom = object->transform.position.y - halfY;

        body.velocity.y -= 9.81f * dt;
        object->transform.position = object->transform.position + body.velocity * dt;

        bool foundGround = false;
        const float ground = terrainHeightAt(object->transform.position.x,
                                             object->transform.position.z, foundGround);
        if (foundGround && object->transform.position.y - halfY < ground) {
            object->transform.position.y = ground + halfY;
            if (body.velocity.y < 0.0f) body.velocity.y = -body.velocity.y * body.restitution;
            if (std::fabs(body.velocity.y) < 0.35f) body.velocity.y = 0.0f;
            const float frictionStep = std::clamp(body.friction * dt, 0.0f, 1.0f);
            body.velocity.x *= 1.0f - frictionStep;
            body.velocity.z *= 1.0f - frictionStep;
        }

        for (const nova::SceneObject& other : gScene.getObjects()) {
            if (other.id == object->id || !other.visible || !other.isStatic ||
                other.name == "Landscape Terrain" ||
                other.name.rfind("Landscape Chunk ", 0) == 0) continue;
            const auto box = nova::PhysicsWorld::boundsFor(other);
            if (!box.valid) continue;
            const nova::Vec3 position = object->transform.position;
            if (position.x + halfX <= box.min.x || position.x - halfX >= box.max.x ||
                position.z + halfZ <= box.min.z || position.z - halfZ >= box.max.z) continue;
            if (previousBottom >= box.max.y - 0.12f &&
                position.y - halfY <= box.max.y) {
                object->transform.position.y = box.max.y + halfY;
                if (body.velocity.y < 0.0f) body.velocity.y = -body.velocity.y * body.restitution;
                if (std::fabs(body.velocity.y) < 0.35f) body.velocity.y = 0.0f;
            }
        }

        for (const auto& otherBody : gLandscapeRigidBodies) {
            if (otherBody.first <= object->id) continue;
            nova::SceneObject* other = gScene.find(otherBody.first);
            if (!other || !other->visible) continue;
            const float otherHalfX = std::max(0.05f, std::fabs(other->transform.scale.x) * 0.5f);
            const float otherHalfY = std::max(0.05f, std::fabs(other->transform.scale.y) * 0.5f);
            const float otherHalfZ = std::max(0.05f, std::fabs(other->transform.scale.z) * 0.5f);
            const float dx = object->transform.position.x - other->transform.position.x;
            const float dz = object->transform.position.z - other->transform.position.z;
            const float overlapX = halfX + otherHalfX - std::fabs(dx);
            const float overlapZ = halfZ + otherHalfZ - std::fabs(dz);
            const float bottomA = object->transform.position.y - halfY;
            const float bottomB = other->transform.position.y - otherHalfY;
            const float overlapY = halfY + otherHalfY -
                std::fabs(object->transform.position.y - other->transform.position.y);
            if (overlapX <= 0.0f || overlapZ <= 0.0f || overlapY <= 0.0f) continue;
            if (previousBottom >= other->transform.position.y - otherHalfY - 0.12f &&
                bottomA < other->transform.position.y + otherHalfY) {
                object->transform.position.y = other->transform.position.y + otherHalfY + halfY;
                if (body.velocity.y < 0.0f)
                    body.velocity.y = -body.velocity.y * std::min(body.restitution, otherBody.second.restitution);
                if (std::fabs(body.velocity.y) < 0.35f) body.velocity.y = 0.0f;
            } else if (bottomB >= object->transform.position.y - halfY - 0.12f &&
                       bottomB < object->transform.position.y + halfY) {
                other->transform.position.y = object->transform.position.y + halfY + otherHalfY;
            }
        }
        ++it;
    }
}

// Transform helpers use the same Rz * Ry * Rx order as Renderer::makeModelMatrix.
nova::Vec3 rotateByObject(const nova::Vec3& v, const nova::Vec3& degrees) {
    const float k=3.14159265358979323846f/180.0f;
    const float x=degrees.x*k,y=degrees.y*k,z=degrees.z*k;
    const float cx=std::cos(x),sx=std::sin(x),cy=std::cos(y),sy=std::sin(y),cz=std::cos(z),sz=std::sin(z);
    const nova::Vec3 rx(v.x, v.y*cx-v.z*sx, v.y*sx+v.z*cx);
    const nova::Vec3 ry(rx.x*cy+rx.z*sy, rx.y, -rx.x*sy+rx.z*cy);
    return nova::Vec3(ry.x*cz-ry.y*sz, ry.x*sz+ry.y*cz, ry.z);
}
nova::Vec3 inverseRotateByObject(const nova::Vec3& v, const nova::Vec3& degrees) {
    const float k=3.14159265358979323846f/180.0f;
    const float x=-degrees.x*k,y=-degrees.y*k,z=-degrees.z*k;
    const float cx=std::cos(x),sx=std::sin(x),cy=std::cos(y),sy=std::sin(y),cz=std::cos(z),sz=std::sin(z);
    const nova::Vec3 rz(v.x*cz-v.y*sz, v.x*sz+v.y*cz, v.z);
    const nova::Vec3 ry(rz.x*cy+rz.z*sy, rz.y, -rz.x*sy+rz.z*cy);
    return nova::Vec3(ry.x, ry.y*cx-ry.z*sx, ry.y*sx+ry.z*cx);
}
nova::Vec3 worldToSculptLocalPoint(const nova::Vec3& world, const nova::Transform& t) {
    nova::Vec3 p=inverseRotateByObject(world-t.position,t.rotation);
    return nova::Vec3(p.x/std::max(0.01f,std::fabs(t.scale.x)),
                      p.y/std::max(0.01f,std::fabs(t.scale.y)),
                      p.z/std::max(0.01f,std::fabs(t.scale.z)));
}
nova::Vec3 worldToSculptLocalVector(const nova::Vec3& world, const nova::Transform& t) {
    nova::Vec3 p=inverseRotateByObject(world,t.rotation);
    return nova::Vec3(p.x/std::max(0.01f,std::fabs(t.scale.x)),
                      p.y/std::max(0.01f,std::fabs(t.scale.y)),
                      p.z/std::max(0.01f,std::fabs(t.scale.z)));
}
nova::Vec3 sculptLocalToWorldPoint(const nova::Vec3& local, const nova::Transform& t) {
    return t.position+rotateByObject(nova::Vec3(local.x*t.scale.x,local.y*t.scale.y,local.z*t.scale.z),t.rotation);
}
nova::Vec3 sculptLocalToWorldNormal(const nova::Vec3& local, const nova::Transform& t) {
    return nova::normalize(rotateByObject(nova::Vec3(local.x/std::max(0.01f,std::fabs(t.scale.x)),
        local.y/std::max(0.01f,std::fabs(t.scale.y)),local.z/std::max(0.01f,std::fabs(t.scale.z))),t.rotation));
}
float sculptLocalRadius(float worldRadius, const nova::Transform& t) {
    const float sx=std::max(0.01f,std::fabs(t.scale.x));
    const float sy=std::max(0.01f,std::fabs(t.scale.y));
    const float sz=std::max(0.01f,std::fabs(t.scale.z));
    return worldRadius/std::cbrt(sx*sy*sz);
}

bool selectObjectAt(float screenX, float screenY) {
    nova::Vec3 ray;

    if (!makeScreenRay(screenX, screenY, ray)) {
        return false;
    }

    float closestDistance = 1000000.0f;
    uint32_t closestId = 0;

    const nova::Vec3 origin = gCamera.position;

    for (const nova::SceneObject& object : gScene.getObjects()) {
        if (!object.visible) {
            continue;
        }

        nova::Vec3 toObject =
            object.transform.position - origin;

        float distanceAlongRay = nova::dot(toObject, ray);

        if (distanceAlongRay <= 0.0f) {
            continue;
        }

        nova::Vec3 closestPoint =
            origin + ray * distanceAlongRay;

        nova::Vec3 difference =
            object.transform.position - closestPoint;

        float radius = std::max({
            std::fabs(object.transform.scale.x),
            std::fabs(object.transform.scale.y),
            std::fabs(object.transform.scale.z)
        });

        radius = std::max(radius * 0.9f, 0.15f);

        if (nova::length(difference) <= radius &&
            distanceAlongRay < closestDistance) {
            closestDistance = distanceAlongRay;
            closestId = object.id;
        }
    }

    if (closestId != 0) {
        return gScene.select(closestId);
    }

    gScene.deselect();
    return false;
}

void focusSelectedObject() {
    nova::SceneObject* object = gScene.selected();

    if (object == nullptr) {
        return;
    }

    nova::Vec3 target = object->transform.position;

    float objectSize = std::max({
        std::fabs(object->transform.scale.x),
        std::fabs(object->transform.scale.y),
        std::fabs(object->transform.scale.z)
    });

    float distance = std::max(objectSize * 3.5f, 4.0f);

    gCamera.position = target + nova::Vec3(
        distance * 0.55f,
        distance * 0.45f,
        distance
    );

    setCameraDirection(target - gCamera.position);
}

void setCameraPreset(int view) {
    const float distance = 32.0f;

    switch (view) {
        case 0:
            gCamera.reset();
            break;

        case 1:
            gCamera.position = nova::Vec3(0.0f, distance, 0.0f);
            gCamera.yaw = -90.0f;
            gCamera.pitch = -89.0f;
            break;

        case 2:
            gCamera.position = nova::Vec3(0.0f, -distance, 0.0f);
            gCamera.yaw = -90.0f;
            gCamera.pitch = 89.0f;
            break;

        case 3:
            gCamera.position = nova::Vec3(-distance, 0.0f, 0.0f);
            gCamera.yaw = 0.0f;
            gCamera.pitch = 0.0f;
            break;

        case 4:
            gCamera.position = nova::Vec3(distance, 0.0f, 0.0f);
            gCamera.yaw = 180.0f;
            gCamera.pitch = 0.0f;
            break;

        case 5:
            gCamera.position = nova::Vec3(0.0f, 0.0f, distance);
            gCamera.yaw = -90.0f;
            gCamera.pitch = 0.0f;
            break;

        case 6:
            gCamera.position = nova::Vec3(0.0f, 0.0f, -distance);
            gCamera.yaw = 90.0f;
            gCamera.pitch = 0.0f;
            break;

        default:
            gCamera.reset();
            break;
    }
}

void moveSelected(
    float deltaX,
    float deltaY,
    float deltaZ
) {
    nova::SceneObject* object = gScene.selected();

    if (object == nullptr) {
        return;
    }

    nova::Transform& transform = object->transform;

    if (gTransformMode == 3) {
        transform.scale.x += deltaX;
        transform.scale.y += deltaY;
        transform.scale.z += deltaZ;

        if (gSnapEnabled) {
            transform.scale.x = std::max(
                0.01f,
                snapValue(transform.scale.x, gScaleSnap)
            );

            transform.scale.y = std::max(
                0.01f,
                snapValue(transform.scale.y, gScaleSnap)
            );

            transform.scale.z = std::max(
                0.01f,
                snapValue(transform.scale.z, gScaleSnap)
            );
        }

        return;
    }

    if (gTransformMode == 2) {
        transform.rotation.x += deltaX;
        transform.rotation.y += deltaY;
        transform.rotation.z += deltaZ;

        if (gSnapEnabled) {
            transform.rotation.x =
                snapValue(transform.rotation.x, gRotationSnap);

            transform.rotation.y =
                snapValue(transform.rotation.y, gRotationSnap);

            transform.rotation.z =
                snapValue(transform.rotation.z, gRotationSnap);
        }

        return;
    }

    nova::Vec3 movement(deltaX, deltaY, deltaZ);

    if (!gWorldSpace) {
        float angle = radians(gCamera.yaw);

        float localX =
            movement.x * std::cos(angle) -
            movement.z * std::sin(angle);

        float localZ =
            movement.x * std::sin(angle) +
            movement.z * std::cos(angle);

        movement.x = localX;
        movement.z = localZ;
    }

    transform.position = transform.position + movement;

    if (gSnapEnabled) {
        transform.position.x =
            snapValue(transform.position.x, gGridSnap);

        transform.position.y =
            snapValue(transform.position.y, gGridSnap);

        transform.position.z =
            snapValue(transform.position.z, gGridSnap);
    }
}


bool decodeGray16Png(const uint8_t* data, size_t size,
                     std::vector<uint16_t>& samples, int& width, int& height) {
    static const uint8_t signature[8] = {137,80,78,71,13,10,26,10};
    if (!data || size < 8 || std::memcmp(data, signature, 8) != 0) return false;
    auto readBE32 = [](const uint8_t* p) -> uint32_t {
        return (static_cast<uint32_t>(p[0]) << 24) |
               (static_cast<uint32_t>(p[1]) << 16) |
               (static_cast<uint32_t>(p[2]) << 8) |
                static_cast<uint32_t>(p[3]);
    };
    width = height = 0;
    int bitDepth = 0, colorType = -1, interlace = -1;
    std::vector<uint8_t> compressed;
    size_t offset = 8;
    bool hasHeader = false, hasEnd = false;
    while (offset + 12 <= size) {
        const uint32_t length = readBE32(data + offset);
        offset += 4;
        if (length > size - offset - 8) return false;
        const uint8_t* type = data + offset;
        const uint8_t* payload = data + offset + 4;
        const uint32_t storedCrc = readBE32(payload + length);
        uLong calculatedCrc = crc32(0L, Z_NULL, 0);
        calculatedCrc = crc32(calculatedCrc, type, static_cast<uInt>(length + 4u));
        if (static_cast<uint32_t>(calculatedCrc) != storedCrc) return false;
        if (std::memcmp(type, "IHDR", 4) == 0) {
            if (length != 13) return false;
            const uint32_t w = readBE32(payload), h = readBE32(payload + 4);
            if (w != 129 || h != 129) return false;
            width = static_cast<int>(w); height = static_cast<int>(h);
            bitDepth = payload[8]; colorType = payload[9]; interlace = payload[12];
            if (bitDepth != 16 || colorType != 0 || payload[10] != 0 ||
                payload[11] != 0 || interlace != 0) return false;
            hasHeader = true;
        } else if (std::memcmp(type, "IDAT", 4) == 0) {
            if (!hasHeader || compressed.size() + length > 8u * 1024u * 1024u) return false;
            compressed.insert(compressed.end(), payload, payload + length);
        } else if (std::memcmp(type, "IEND", 4) == 0) {
            hasEnd = true;
            break;
        }
        offset += static_cast<size_t>(length) + 8u;
    }
    if (!hasHeader || !hasEnd || compressed.empty()) return false;

    constexpr size_t bytesPerPixel = 2;
    const size_t rowBytes = static_cast<size_t>(width) * bytesPerPixel;
    const size_t filteredSize = (rowBytes + 1u) * static_cast<size_t>(height);
    std::vector<uint8_t> filtered(filteredSize);
    uLongf outputSize = static_cast<uLongf>(filtered.size());
    const int inflateResult = uncompress(filtered.data(), &outputSize,
        compressed.data(), static_cast<uLong>(compressed.size()));
    if (inflateResult != Z_OK || outputSize != filtered.size()) return false;

    std::vector<uint8_t> decoded(rowBytes * static_cast<size_t>(height));
    auto paeth = [](int a, int b, int c) -> int {
        const int p = a + b - c;
        const int pa = std::abs(p - a), pb = std::abs(p - b), pc = std::abs(p - c);
        return pa <= pb && pa <= pc ? a : (pb <= pc ? b : c);
    };
    for (int y = 0; y < height; ++y) {
        const size_t srcRow = static_cast<size_t>(y) * (rowBytes + 1u);
        const uint8_t filter = filtered[srcRow];
        if (filter > 4) return false;
        for (size_t x = 0; x < rowBytes; ++x) {
            const int raw = filtered[srcRow + 1u + x];
            const int left = x >= bytesPerPixel ? decoded[static_cast<size_t>(y) * rowBytes + x - bytesPerPixel] : 0;
            const int up = y > 0 ? decoded[static_cast<size_t>(y - 1) * rowBytes + x] : 0;
            const int upperLeft = (y > 0 && x >= bytesPerPixel)
                ? decoded[static_cast<size_t>(y - 1) * rowBytes + x - bytesPerPixel] : 0;
            int predictor = 0;
            if (filter == 1) predictor = left;
            else if (filter == 2) predictor = up;
            else if (filter == 3) predictor = (left + up) / 2;
            else if (filter == 4) predictor = paeth(left, up, upperLeft);
            decoded[static_cast<size_t>(y) * rowBytes + x] =
                static_cast<uint8_t>((raw + predictor) & 0xFF);
        }
    }

    samples.resize(static_cast<size_t>(width) * static_cast<size_t>(height));
    for (size_t i = 0; i < samples.size(); ++i) {
        const uint8_t high = decoded[i * 2u];
        const uint8_t low = decoded[i * 2u + 1u];
        samples[i] = static_cast<uint16_t>((static_cast<uint16_t>(high) << 8) | low);
    }
    return true;
}

} // namespace

extern "C" {

JNIEXPORT void JNICALL
Java_com_novaingine_v2_NativeBridge_nativeInit(
    JNIEnv*,
    jclass
) {
    std::lock_guard<std::mutex> lock(engineMutex);

    if (gInitialized) {
        return;
    }

    NOVA_LOG("Initializing Novaingine V2");

    gCamera.reset();

    nova::SceneFactory::buildDefaultScene(gScene);

    if (!gRenderer.initialize()) {
        NOVA_LOG("Renderer initialization failed");
        gInitialized = false;
        return;
    }

    gRenderer.setClearColor(
        0.12f,
        0.22f,
        0.38f,
        1.0f
    );

    gRenderer.setViewportEnabled(true);
    gRenderer.setViewMode(0);

    applyShowFlags(31);

    gInitialized = true;
    startSculptWorker();

    NOVA_LOG(
        "Novaingine initialized. Scene objects: %zu",
        gScene.objectCount()
    );
}

JNIEXPORT void JNICALL
Java_com_novaingine_v2_NativeBridge_nativeResize(
    JNIEnv*,
    jclass,
    jint width,
    jint height
) {
    std::lock_guard<std::mutex> lock(engineMutex);

    gViewportWidth = std::max(1, static_cast<int>(width));
    gViewportHeight = std::max(1, static_cast<int>(height));

    if (gInitialized) {
        gRenderer.resize(gViewportWidth, gViewportHeight);
    }
}

JNIEXPORT void JNICALL
Java_com_novaingine_v2_NativeBridge_nativeRender(
    JNIEnv*,
    jclass,
    jfloat deltaTime
) {
    std::lock_guard<std::mutex> lock(engineMutex);

    if (!gInitialized) {
        return;
    }

    float dt = std::clamp(
        static_cast<float>(deltaTime),
        0.0f,
        0.1f
    );

    if (!gPaused) {
        if (gVisualScript.enabled) {
            if (!gVisualScriptStarted) {
                gVisualScriptRuntime.execute(gVisualScript, gScene, true, dt);
                gVisualScriptStarted = true;
            }
            gVisualScriptRuntime.execute(gVisualScript, gScene, false, dt);
        }
    }

    if(gSculptMaskDirty.load(std::memory_order_acquire)) {
        std::unique_lock<std::mutex> dataLock(gSculptDataMutex,std::try_to_lock);
        if(dataLock.owns_lock()&&gSculptMesh&&gSculptMeshId!=0) {
            gSculptMasks[gSculptMeshId]=gSculptMesh->mask;
            gSculptMaskDirty.store(false,std::memory_order_release);
        }
    }
    if(gSculptMeshDirty.load(std::memory_order_acquire)) {
        std::unique_lock<std::mutex> dataLock(gSculptDataMutex,std::try_to_lock);
        if(dataLock.owns_lock()&&gSculptMesh&&gSculptMeshId!=0) {
            nova::Mesh updated;
            if(gSculptMesh->toMesh(updated)) {
                gSculptMesh->uploadChunks();
                gCustomMeshes[gSculptMeshId]=std::move(updated);
                gSculptMasks[gSculptMeshId]=gSculptMesh->mask;
                gRenderer.uploadMesh(gSculptMeshId,gCustomMeshes[gSculptMeshId]);
                gSculptMeshDirty.store(false,std::memory_order_release);
            }
        }
    }

    if (!gPaused) stepLandscapeRigidBodies(dt);
    updateTerrainLod();
    updateLandscapeWorldStreaming();
    gRenderer.beginFrame();
    gRenderer.render(gScene, gCamera);
    gRenderer.endFrame();
}

JNIEXPORT void JNICALL
Java_com_novaingine_v2_NativeBridge_nativeTouch(
    JNIEnv*,
    jclass,
    jfloat deltaX,
    jfloat deltaY
) {
    std::lock_guard<std::mutex> lock(engineMutex);

    if (!gInitialized || gPaused) {
        return;
    }

    gCamera.rotate(
        static_cast<float>(deltaX),
        static_cast<float>(deltaY)
    );
}

JNIEXPORT void JNICALL
Java_com_novaingine_v2_NativeBridge_nativeOrbitSelected(
    JNIEnv*, jclass, jfloat deltaX, jfloat deltaY
) {
    std::lock_guard<std::mutex> lock(engineMutex);
    if (!gInitialized || gPaused) return;

    nova::SceneObject* selected = gScene.selected();
    if (selected == nullptr) return;

    const nova::Vec3 target = selected->transform.position;
    nova::Vec3 offset = gCamera.position - target;
    float distance = nova::length(offset);
    if (distance < 0.25f) distance = 4.0f;

    float yaw = std::atan2(offset.z, offset.x);
    float pitch = std::asin(std::clamp(offset.y / distance, -1.0f, 1.0f));
    yaw += static_cast<float>(deltaX) * 0.008f;
    pitch -= static_cast<float>(deltaY) * 0.008f;
    pitch = std::clamp(pitch, -1.48f, 1.48f);

    const float horizontal = distance * std::cos(pitch);
    gCamera.position = target + nova::Vec3(
        horizontal * std::cos(yaw),
        distance * std::sin(pitch),
        horizontal * std::sin(yaw)
    );
    setCameraDirection(target - gCamera.position);
}

JNIEXPORT void JNICALL
Java_com_novaingine_v2_NativeBridge_nativeZoom(
    JNIEnv*,
    jclass,
    jfloat scale
) {
    std::lock_guard<std::mutex> lock(engineMutex);

    if (!gInitialized) {
        return;
    }

    gCamera.zoom(static_cast<float>(scale));
}

JNIEXPORT void JNICALL
Java_com_novaingine_v2_NativeBridge_nativePan(
    JNIEnv*,
    jclass,
    jfloat deltaX,
    jfloat deltaY
) {
    std::lock_guard<std::mutex> lock(engineMutex);

    if (!gInitialized || gPaused) {
        return;
    }

    float scale = std::max(gCamera.fieldOfView, 20.0f) * 0.0025f;

    gCamera.pan(
        -static_cast<float>(deltaX) * scale,
        static_cast<float>(deltaY) * scale
    );
}

JNIEXPORT void JNICALL
Java_com_novaingine_v2_NativeBridge_nativeFly(
    JNIEnv*,
    jclass,
    jfloat forward,
    jfloat right,
    jfloat vertical,
    jfloat deltaTime
) {
    std::lock_guard<std::mutex> lock(engineMutex);

    if (!gInitialized || gPaused) {
        return;
    }

    gCamera.movementSpeed = gCameraSpeed;

    const float dt = std::clamp(
        static_cast<float>(deltaTime), 0.0f, 0.05f
    );

    if (gPhysicsEnabled) {
        nova::PhysicsWorld::step(
            gScene,
            gCamera,
            static_cast<float>(forward),
            static_cast<float>(right),
            static_cast<float>(vertical),
            dt,
            gCameraSpeed,
            gVerticalVelocity,
            gPhysicsGrounded,
            gJumpWasPressed
        );

        // Landscape collision samples the actual editable heightfield instead of
        // treating the terrain as a flat primitive plane.
        bool hasLandscapeGround = false;
        const float landscapeGround = terrainHeightAt(
            gCamera.position.x, gCamera.position.z, hasLandscapeGround);
        if (hasLandscapeGround) {
            const float minimumCameraY =
                landscapeGround + nova::PhysicsWorld::PLAYER_HEIGHT;
            if (gCamera.position.y < minimumCameraY) {
                gCamera.position.y = minimumCameraY;
                if (gVerticalVelocity < 0.0f) gVerticalVelocity = 0.0f;
                gPhysicsGrounded = true;
                gCamera.target = gCamera.position + gCamera.forward() * 10.0f;
            }
        }
    } else {
        gCamera.move(
            static_cast<float>(forward),
            static_cast<float>(right),
            static_cast<float>(vertical),
            dt
        );
    }
}

JNIEXPORT void JNICALL
Java_com_novaingine_v2_NativeBridge_nativeFocus(
    JNIEnv*,
    jclass,
    jfloat screenX,
    jfloat screenY
) {
    std::lock_guard<std::mutex> lock(engineMutex);

    if (!gInitialized) {
        return;
    }

    if (screenX >= 0.0f && screenY >= 0.0f) {
        selectObjectAt(
            static_cast<float>(screenX),
            static_cast<float>(screenY)
        );
    }

    focusSelectedObject();
}

JNIEXPORT void JNICALL
Java_com_novaingine_v2_NativeBridge_nativeSelect(
    JNIEnv*,
    jclass,
    jfloat screenX,
    jfloat screenY
) {
    std::lock_guard<std::mutex> lock(engineMutex);

    if (!gInitialized) {
        return;
    }

    selectObjectAt(
        static_cast<float>(screenX),
        static_cast<float>(screenY)
    );
}

JNIEXPORT void JNICALL
Java_com_novaingine_v2_NativeBridge_nativeSetViewMode(
    JNIEnv*,
    jclass,
    jint mode
) {
    std::lock_guard<std::mutex> lock(engineMutex);

    if (!gInitialized) {
        return;
    }

    int safeMode = std::clamp(
        static_cast<int>(mode),
        0,
        7
    );

    gRenderer.setViewMode(safeMode);
}

JNIEXPORT void JNICALL
Java_com_novaingine_v2_NativeBridge_nativeSetCameraView(
    JNIEnv*,
    jclass,
    jint view
) {
    std::lock_guard<std::mutex> lock(engineMutex);

    if (!gInitialized) {
        return;
    }

    setCameraPreset(static_cast<int>(view));
}

JNIEXPORT void JNICALL
Java_com_novaingine_v2_NativeBridge_nativeSetShowFlags(
    JNIEnv*,
    jclass,
    jint flags
) {
    std::lock_guard<std::mutex> lock(engineMutex);

    if (!gInitialized) {
        return;
    }

    applyShowFlags(static_cast<int>(flags));
}

JNIEXPORT void JNICALL
Java_com_novaingine_v2_NativeBridge_nativeSetTransformMode(
    JNIEnv*,
    jclass,
    jint mode
) {
    std::lock_guard<std::mutex> lock(engineMutex);

    gTransformMode = std::clamp(
        static_cast<int>(mode),
        0,
        3
    );
}

JNIEXPORT void JNICALL
Java_com_novaingine_v2_NativeBridge_nativeSetWorldSpace(
    JNIEnv*,
    jclass,
    jboolean worldSpace
) {
    std::lock_guard<std::mutex> lock(engineMutex);

    gWorldSpace = worldSpace == JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_novaingine_v2_NativeBridge_nativeSetSnap(
    JNIEnv*,
    jclass,
    jboolean enabled,
    jfloat grid,
    jfloat rotation,
    jfloat scale
) {
    std::lock_guard<std::mutex> lock(engineMutex);

    gSnapEnabled = enabled == JNI_TRUE;

    gGridSnap = std::max(0.001f, static_cast<float>(grid));
    gRotationSnap = std::max(0.001f, static_cast<float>(rotation));
    gScaleSnap = std::max(0.001f, static_cast<float>(scale));
}

JNIEXPORT void JNICALL
Java_com_novaingine_v2_NativeBridge_nativeMoveSelected(
    JNIEnv*,
    jclass,
    jfloat deltaX,
    jfloat deltaY,
    jfloat deltaZ
) {
    std::lock_guard<std::mutex> lock(engineMutex);

    if (!gInitialized) {
        return;
    }

    if (gScene.selected() != nullptr) recordUndo();
    moveSelected(
        static_cast<float>(deltaX),
        static_cast<float>(deltaY),
        static_cast<float>(deltaZ)
    );
}

JNIEXPORT void JNICALL
Java_com_novaingine_v2_NativeBridge_nativeSetCameraSpeed(
    JNIEnv*,
    jclass,
    jfloat speed
) {
    std::lock_guard<std::mutex> lock(engineMutex);

    gCameraSpeed = std::clamp(
        static_cast<float>(speed),
        0.1f,
        50.0f
    );

    gCamera.movementSpeed = gCameraSpeed;
}

JNIEXPORT void JNICALL
Java_com_novaingine_v2_NativeBridge_nativeSetExposure(
    JNIEnv*,
    jclass,
    jfloat exposure
) {
    std::lock_guard<std::mutex> lock(engineMutex);

    if (!gInitialized) return;

    RendererSettings settings = gRenderer.getSettings();
    settings.exposure = std::clamp(
        static_cast<float>(exposure), 0.25f, 4.0f
    );
    gRenderer.setSettings(settings);
}

JNIEXPORT void JNICALL
Java_com_novaingine_v2_NativeBridge_nativeSetWorldEnvironment(
    JNIEnv* env, jclass, jfloatArray values
) {
    if (values == nullptr || env->GetArrayLength(values) < 28) return;
    jfloat data[28];
    env->GetFloatArrayRegion(values, 0, 28, data);
    if (env->ExceptionCheck()) { env->ExceptionClear(); return; }

    std::lock_guard<std::mutex> lock(engineMutex);
    if (!gInitialized) return;

    RendererSettings settings = gRenderer.getSettings();
    nova::Vec3 sunDirection(data[0], data[1], data[2]);
    sunDirection = nova::normalize(sunDirection);
    if (nova::length(sunDirection) > 0.0001f) {
        settings.directionalLightDirection[0] = sunDirection.x;
        settings.directionalLightDirection[1] = sunDirection.y;
        settings.directionalLightDirection[2] = sunDirection.z;
    }
    for (int i = 0; i < 3; ++i) {
        settings.directionalLightColor[i] = data[3 + i];
        settings.fogColor[i] = data[21 + i];
    }
    settings.directionalLightIntensity = data[6];
    settings.directionalLightEnabled = data[7] >= 0.5f;
    settings.skyLightEnabled = data[8] >= 0.5f;
    settings.skyLightIntensity = data[9];
    settings.skyAtmosphereEnabled = data[10] >= 0.5f;
    settings.atmosphereRayleigh = data[11];
    settings.atmosphereMie = data[12];
    settings.volumetricCloudsEnabled = data[13] >= 0.5f;
    settings.cloudCoverage = data[14];
    settings.cloudDensity = data[15];
    settings.cloudSpeed = data[16];
    settings.heightFogEnabled = data[17] >= 0.5f;
    settings.fogDensity = data[18];
    settings.fogHeight = data[19];
    settings.fogFalloff = data[20];
    settings.bloomEnabled = data[24] >= 0.5f;
    settings.bloomIntensity = data[25];
    settings.colorContrast = data[26];
    settings.colorSaturation = data[27];
    gRenderer.setSettings(settings);
}

JNIEXPORT void JNICALL
Java_com_novaingine_v2_NativeBridge_nativeSetPhysicsEnabled(
    JNIEnv*,
    jclass,
    jboolean enabled
) {
    std::lock_guard<std::mutex> lock(engineMutex);

    gPhysicsEnabled = enabled == JNI_TRUE;
    gVerticalVelocity = 0.0f;
    gPhysicsGrounded = false;
    gJumpWasPressed = false;

    if (gPhysicsEnabled) {
        gCamera.position.y = std::max(
            gCamera.position.y,
            nova::PhysicsWorld::PLAYER_HEIGHT + 0.05f
        );
        gCamera.target = gCamera.position + gCamera.forward() * 10.0f;
    }

    NOVA_LOG("Character physics %s",
        gPhysicsEnabled ? "enabled" : "disabled");
}

JNIEXPORT void JNICALL
Java_com_novaingine_v2_NativeBridge_nativeSetPaused(
    JNIEnv*,
    jclass,
    jboolean paused
) {
    std::lock_guard<std::mutex> lock(engineMutex);

    gPaused = paused == JNI_TRUE;
}



JNIEXPORT jboolean JNICALL
Java_com_novaingine_v2_NativeBridge_nativeSaveScene(
    JNIEnv* env, jclass, jstring pathValue
) {
    if (!pathValue) return JNI_FALSE;
    const char* rawPath = env->GetStringUTFChars(pathValue, nullptr);
    if (!rawPath) return JNI_FALSE;
    const std::string path(rawPath);
    env->ReleaseStringUTFChars(pathValue, rawPath);

    std::lock_guard<std::mutex> lock(engineMutex);
    while (gSculptJobsPending.load(std::memory_order_acquire) != 0) {
        std::this_thread::yield();
    }
    std::lock_guard<std::mutex> sculptLock(gSculptDataMutex);

    const std::string temporaryPath = path + ".tmp";
    std::ofstream file(temporaryPath, std::ios::out | std::ios::trunc);
    if (!file) return JNI_FALSE;
    file << std::setprecision(9);
    file << "NOVA_PROJECT 3\n";
    file << gScene.objectCount() << " " << gScene.selectedId() << " "
         << gSculptMeshId << "\n";
    file << gCamera.position.x << " " << gCamera.position.y << " " << gCamera.position.z << " "
         << gCamera.target.x << " " << gCamera.target.y << " " << gCamera.target.z << " "
         << gCamera.yaw << " " << gCamera.pitch << " " << gCamera.fieldOfView << " "
         << gCamera.nearPlane << " " << gCamera.farPlane << " " << gCamera.movementSpeed << "\n";

    for (const nova::SceneObject& o : gScene.getObjects()) {
        file << o.id << " " << static_cast<int>(o.primitive) << " "
             << std::quoted(o.name) << " "
             << o.transform.position.x << " " << o.transform.position.y << " " << o.transform.position.z << " "
             << o.transform.rotation.x << " " << o.transform.rotation.y << " " << o.transform.rotation.z << " "
             << o.transform.scale.x << " " << o.transform.scale.y << " " << o.transform.scale.z << " "
             << o.color.r << " " << o.color.g << " " << o.color.b << " " << o.color.a << " "
             << o.material.baseColor.x << " " << o.material.baseColor.y << " " << o.material.baseColor.z << " "
             << o.material.roughness << " " << o.material.metallic << " "
             << o.visible << " " << o.castsShadow << " " << o.isStatic << "\n";
    }

    file << gCustomMeshes.size() << "\n";
    for (const auto& entry : gCustomMeshes) {
        const nova::Mesh* meshToSave = &entry.second;
        for (const auto& tileEntry : gLandscapeWorldTiles) {
            if (tileEntry.second.objectId == entry.first) {
                meshToSave = &tileEntry.second.fullMesh;
                break;
            }
        }
        const auto& vertices = meshToSave->getVertices();
        const auto& indices = meshToSave->getIndices();
        file << entry.first << " " << vertices.size() << " " << indices.size() << "\n";
        for (const auto& v : vertices) {
            file << v.position.x << " " << v.position.y << " " << v.position.z << " "
                 << v.normal.x << " " << v.normal.y << " " << v.normal.z << " "
                 << v.u << " " << v.v << " "
                 << v.layerWeights[0] << " " << v.layerWeights[1] << " "
                 << v.layerWeights[2] << " " << v.layerWeights[3] << "\n";
        }
        for (uint32_t index : indices) file << index << " ";
        file << "\n";
        auto maskIt = gSculptMasks.find(entry.first);
        const size_t maskCount = maskIt == gSculptMasks.end() ? 0 : maskIt->second.size();
        file << maskCount << "\n";
        if (maskIt != gSculptMasks.end()) {
            for (float value : maskIt->second) file << value << " ";
            file << "\n";
        }
    }

    file << (gSculptMesh ? 1 : 0) << "\n";
    if (gSculptMesh) {
        file << gSculptMesh->positions.size() << " " << gSculptMesh->indices.size() << "\n";
        for (size_t i = 0; i < gSculptMesh->positions.size(); ++i) {
            const auto& p = gSculptMesh->positions[i];
            const nova::Vec3 n = i < gSculptMesh->normals.size()
                ? gSculptMesh->normals[i] : nova::Vec3(0, 1, 0);
            const float mask = i < gSculptMesh->mask.size() ? gSculptMesh->mask[i] : 0.0f;
            file << p.x << " " << p.y << " " << p.z << " "
                 << n.x << " " << n.y << " " << n.z << " " << mask << "\n";
        }
        for (uint32_t index : gSculptMesh->indices) file << index << " ";
        file << "\n";
    }

    file << gVisualScript.enabled << " " << gVisualScript.runOnBeginPlay << " "
         << gVisualScript.runOnTick << " " << std::quoted(gVisualScript.name) << " "
         << gVisualScript.nodes.size() << "\n";
    for (const auto& node : gVisualScript.nodes) {
        file << node.id << " " << static_cast<int>(node.type) << " "
             << node.next << " " << node.trueNext << " " << node.falseNext << " "
             << node.objectId << " "
             << node.vectorValue.x << " " << node.vectorValue.y << " " << node.vectorValue.z << " "
             << node.value << " " << node.compareValue << " " << node.condition << "\n";
    }

    file.flush();
    const bool written = file.good();
    file.close();
    if (!written) {
        std::remove(temporaryPath.c_str());
        return JNI_FALSE;
    }
    if (std::rename(temporaryPath.c_str(), path.c_str()) != 0) {
        std::remove(temporaryPath.c_str());
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_novaingine_v2_NativeBridge_nativeLoadScene(
    JNIEnv* env, jclass, jstring pathValue
) {
    if (!pathValue) return JNI_FALSE;
    const char* rawPath = env->GetStringUTFChars(pathValue, nullptr);
    if (!rawPath) return JNI_FALSE;
    const std::string path(rawPath);
    env->ReleaseStringUTFChars(pathValue, rawPath);
    std::ifstream file(path);
    if (!file) return JNI_FALSE;

    std::string magic;
    int version = 0;
    size_t objectCount = 0;
    uint32_t selectedId = 0, sculptMeshId = 0;
    if (!(file >> magic >> version)) return JNI_FALSE;
    const bool legacy = magic == "NOVA_SCENE" && version == 1;
    const bool current = magic == "NOVA_PROJECT" && (version == 2 || version == 3);
    const bool hasLayerWeights = current && version >= 3;
    if (!legacy && !current) return JNI_FALSE;
    if (!(file >> objectCount) || objectCount > 10000) return JNI_FALSE;
    if (current && !(file >> selectedId >> sculptMeshId)) return JNI_FALSE;

    nova::Camera loadedCamera;
    if (current) {
        if (!(file >> loadedCamera.position.x >> loadedCamera.position.y >> loadedCamera.position.z
              >> loadedCamera.target.x >> loadedCamera.target.y >> loadedCamera.target.z
              >> loadedCamera.yaw >> loadedCamera.pitch >> loadedCamera.fieldOfView
              >> loadedCamera.nearPlane >> loadedCamera.farPlane >> loadedCamera.movementSpeed))
            return JNI_FALSE;
    }

    nova::Scene loadedScene;
    for (size_t i = 0; i < objectCount; ++i) {
        nova::SceneObject o;
        int primitive = 0;
        if (!(file >> o.id >> primitive >> std::quoted(o.name)
              >> o.transform.position.x >> o.transform.position.y >> o.transform.position.z
              >> o.transform.rotation.x >> o.transform.rotation.y >> o.transform.rotation.z
              >> o.transform.scale.x >> o.transform.scale.y >> o.transform.scale.z
              >> o.color.r >> o.color.g >> o.color.b >> o.color.a))
            return JNI_FALSE;
        if (current) {
            if (!(file >> o.material.baseColor.x >> o.material.baseColor.y >> o.material.baseColor.z
                  >> o.material.roughness >> o.material.metallic
                  >> o.visible >> o.castsShadow >> o.isStatic)) return JNI_FALSE;
        } else {
            if (!(file >> o.material.roughness >> o.material.metallic
                  >> o.visible >> o.castsShadow >> o.isStatic)) return JNI_FALSE;
            o.material.baseColor = nova::Vec3(o.color.r, o.color.g, o.color.b);
        }
        if (o.id == 0 || primitive < 0 || primitive > 7 ||
            !std::isfinite(o.transform.position.x) || !std::isfinite(o.transform.position.y) ||
            !std::isfinite(o.transform.position.z) || !std::isfinite(o.transform.scale.x) ||
            !std::isfinite(o.transform.scale.y) || !std::isfinite(o.transform.scale.z))
            return JNI_FALSE;
        o.primitive = static_cast<nova::PrimitiveType>(primitive);
        if (!loadedScene.addObject(o)) return JNI_FALSE;
    }

    std::unordered_map<uint32_t, nova::Mesh> loadedMeshes;
    std::unordered_map<uint32_t, std::vector<float>> loadedMasks;
    std::unique_ptr<nova::SculptMesh> loadedSculpt;
    nova::VisualScript loadedScript;

    if (current) {
        size_t meshCount = 0;
        if (!(file >> meshCount) || meshCount > 10000) return JNI_FALSE;
        for (size_t m = 0; m < meshCount; ++m) {
            uint32_t id = 0;
            size_t vertexCount = 0, indexCount = 0;
            if (!(file >> id >> vertexCount >> indexCount) ||
                id == 0 || vertexCount == 0 || vertexCount > 5000000 ||
                indexCount == 0 || indexCount > 30000000 || indexCount % 3 != 0)
                return JNI_FALSE;
            std::vector<nova::Vertex> vertices;
            vertices.reserve(vertexCount);
            for (size_t v = 0; v < vertexCount; ++v) {
                nova::Vertex vertex;
                if (!(file >> vertex.position.x >> vertex.position.y >> vertex.position.z
                      >> vertex.normal.x >> vertex.normal.y >> vertex.normal.z
                      >> vertex.u >> vertex.v)) return JNI_FALSE;
                if (hasLayerWeights) {
                    for (float& weight : vertex.layerWeights) {
                        if (!(file >> weight) || !std::isfinite(weight)) return JNI_FALSE;
                        weight = std::clamp(weight, 0.0f, 1.0f);
                    }
                } else {
                    vertex.layerWeights[0] = 1.0f;
                }
                if (!std::isfinite(vertex.position.x) || !std::isfinite(vertex.position.y) ||
                    !std::isfinite(vertex.position.z) || !std::isfinite(vertex.normal.x) ||
                    !std::isfinite(vertex.normal.y) || !std::isfinite(vertex.normal.z) ||
                    !std::isfinite(vertex.u) || !std::isfinite(vertex.v)) return JNI_FALSE;
                vertices.push_back(vertex);
            }
            std::vector<uint32_t> indices(indexCount);
            for (uint32_t& index : indices) {
                if (!(file >> index) || index >= vertexCount) return JNI_FALSE;
            }
            nova::Mesh mesh;
            mesh.setData(std::move(vertices), std::move(indices));
            if (!mesh.isValid() || !loadedMeshes.emplace(id, std::move(mesh)).second)
                return JNI_FALSE;
            size_t maskCount = 0;
            if (!(file >> maskCount) || (maskCount != 0 && maskCount != vertexCount))
                return JNI_FALSE;
            std::vector<float> mask(maskCount);
            for (float& value : mask) {
                if (!(file >> value) || !std::isfinite(value)) return JNI_FALSE;
                value = std::clamp(value, 0.0f, 1.0f);
            }
            loadedMasks.emplace(id, std::move(mask));
        }

        int hasSculpt = 0;
        if (!(file >> hasSculpt) || (hasSculpt != 0 && hasSculpt != 1)) return JNI_FALSE;
        if (hasSculpt) {
            size_t vertexCount = 0, indexCount = 0;
            if (!(file >> vertexCount >> indexCount) ||
                vertexCount == 0 || vertexCount > 5000000 ||
                indexCount == 0 || indexCount > 30000000 || indexCount % 3 != 0)
                return JNI_FALSE;
            loadedSculpt.reset(new nova::SculptMesh());
            loadedSculpt->positions.resize(vertexCount);
            loadedSculpt->normals.resize(vertexCount);
            loadedSculpt->mask.resize(vertexCount);
            for (size_t i = 0; i < vertexCount; ++i) {
                auto& p = loadedSculpt->positions[i];
                auto& n = loadedSculpt->normals[i];
                float& mask = loadedSculpt->mask[i];
                if (!(file >> p.x >> p.y >> p.z >> n.x >> n.y >> n.z >> mask))
                    return JNI_FALSE;
                if (!std::isfinite(p.x) || !std::isfinite(p.y) || !std::isfinite(p.z) ||
                    !std::isfinite(n.x) || !std::isfinite(n.y) || !std::isfinite(n.z) ||
                    !std::isfinite(mask)) return JNI_FALSE;
                mask = std::clamp(mask, 0.0f, 1.0f);
            }
            loadedSculpt->indices.resize(indexCount);
            for (uint32_t& index : loadedSculpt->indices) {
                if (!(file >> index) || index >= vertexCount) return JNI_FALSE;
            }
            loadedSculpt->buildAdjacency();
            loadedSculpt->buildChunks();
            loadedSculpt->buildBVH();
            if (sculptMeshId == 0 || loadedScene.find(sculptMeshId) == nullptr)
                return JNI_FALSE;
            nova::Mesh sculptRenderMesh;
            if (!loadedSculpt->toMesh(sculptRenderMesh)) return JNI_FALSE;
            loadedMeshes[sculptMeshId] = std::move(sculptRenderMesh);
            loadedMasks[sculptMeshId] = loadedSculpt->mask;
        } else if (sculptMeshId != 0) {
            return JNI_FALSE;
        }

        size_t nodeCount = 0;
        if (!(file >> loadedScript.enabled >> loadedScript.runOnBeginPlay
              >> loadedScript.runOnTick >> std::quoted(loadedScript.name)
              >> nodeCount) || nodeCount > 128) return JNI_FALSE;
        loadedScript.nodes.reserve(nodeCount);
        for (size_t i = 0; i < nodeCount; ++i) {
            nova::VisualNode node;
            int type = 0;
            if (!(file >> node.id >> type >> node.next >> node.trueNext >> node.falseNext
                  >> node.objectId >> node.vectorValue.x >> node.vectorValue.y >> node.vectorValue.z
                  >> node.value >> node.compareValue >> node.condition)) return JNI_FALSE;
            if (node.id == 0 || type < 0 || type > 8 ||
                !std::isfinite(node.vectorValue.x) || !std::isfinite(node.vectorValue.y) ||
                !std::isfinite(node.vectorValue.z) || !std::isfinite(node.value) ||
                !std::isfinite(node.compareValue)) return JNI_FALSE;
            node.type = static_cast<nova::VisualNodeType>(type);
            loadedScript.nodes.push_back(node);
        }
    }

    std::lock_guard<std::mutex> lock(engineMutex);
    while (gSculptJobsPending.load(std::memory_order_acquire) != 0) {
        std::this_thread::yield();
    }
    std::lock_guard<std::mutex> sculptLock(gSculptDataMutex);
    recordUndo(true);
    gRenderer.releaseAllMeshes();
    gScene = std::move(loadedScene);
    if (current && selectedId != 0) gScene.select(selectedId);
    gUndoStack.clear();
    gRedoStack.clear();
    gCustomMeshes = std::move(loadedMeshes);
    gLandscape = nova::LandscapeSystem();
    gLandscapeWorldTiles.clear();

    auto restoreLandscapeData = [](nova::LandscapeSystem& data, const nova::Mesh& mesh,
                                   float originX, float originZ) -> bool {
        const auto& vertices = mesh.getVertices();
        if (vertices.size() != 129u * 129u) return false;
        if (!data.initialize(129, 129, 0.5f, originX, originZ)) return false;
        std::vector<float> heights(vertices.size());
        std::vector<std::array<float,4>> weights(vertices.size());
        for (size_t i = 0; i < vertices.size(); ++i) {
            heights[i] = std::clamp(vertices[i].position.y, -12.0f, 24.0f);
            for (int c = 0; c < 4; ++c) weights[i][c] = vertices[i].layerWeights[c];
        }
        if (!data.setBaseHeights(heights) || !data.setBaseWeights(weights)) return false;
        std::vector<uint8_t> triangleCount(128u * 128u, 0);
        const auto& indices = mesh.getIndices();
        for (size_t i = 0; i + 2 < indices.size(); i += 3) {
            const uint32_t a = indices[i], b = indices[i+1], c = indices[i+2];
            const int minX = std::min({static_cast<int>(a % 129u), static_cast<int>(b % 129u), static_cast<int>(c % 129u)});
            const int minZ = std::min({static_cast<int>(a / 129u), static_cast<int>(b / 129u), static_cast<int>(c / 129u)});
            if (minX < 128 && minZ < 128) {
                auto& count = triangleCount[static_cast<size_t>(minZ * 128 + minX)];
                if (count < 2) ++count;
            }
        }
        for (int z = 0; z < 128; ++z) for (int x = 0; x < 128; ++x)
            if (triangleCount[static_cast<size_t>(z * 128 + x)] < 2) data.setHole(x, z, true);
        return true;
    };

    for (const nova::SceneObject& object : gScene.getObjects()) {
        auto meshIt = gCustomMeshes.find(object.id);
        if (meshIt == gCustomMeshes.end()) continue;
        if (object.name == "Landscape Terrain") {
            restoreLandscapeData(gLandscape, meshIt->second, -32.0f, -36.0f);
        } else if (object.name.rfind("Landscape Tile ", 0) == 0) {
            int tileX = 0, tileZ = 0;
            if (std::sscanf(object.name.c_str(), "Landscape Tile %d %d", &tileX, &tileZ) != 2) continue;
            LandscapeWorldTile tile;
            tile.tileX = tileX; tile.tileZ = tileZ; tile.objectId = object.id;
            if (!restoreLandscapeData(tile.data, meshIt->second,
                    landscapeTileOriginX(tileX), landscapeTileOriginZ(tileZ))) continue;
            tile.fullMesh = meshIt->second;
            tile.loaded = true; tile.lod = 0;
            gLandscapeWorldTiles.emplace(landscapeTileKey(tileX, tileZ), std::move(tile));
        }
    }
    gSculptMasks = std::move(loadedMasks);
    gSculptMesh = std::move(loadedSculpt);
    gSculptMeshId = current ? sculptMeshId : 0;
    gVisualScript = std::move(loadedScript);
    gVisualScriptStarted = false;
    if (current) gCamera = loadedCamera;
    else gCamera.reset();
    gTerrainLodLevel = -1;
    gTerrainChunksDirty = true;
    gSculptMode = false;
    gSculptStrokeActive = false;
    gHasPreviousSculptHit = false;
    gRenderer.setSculptCursor(false, nova::Vec3(), nova::Vec3(0, 1, 0), 0.18f);
    gSculptMeshDirty.store(false, std::memory_order_release);
    gSculptMaskDirty.store(false, std::memory_order_release);
    for (const auto& entry : gCustomMeshes) {
        if (!gRenderer.uploadMesh(entry.first, entry.second)) return JNI_FALSE;
    }
    if (gSculptMesh) {
        if (!gSculptMesh->uploadChunks()) return JNI_FALSE;
    }
    gVerticalVelocity = 0.0f;
    gPhysicsEnabled = false;
    gPhysicsGrounded = false;
    gJumpWasPressed = false;
    return JNI_TRUE;
}

JNIEXPORT jobjectArray JNICALL
Java_com_novaingine_v2_NativeBridge_nativeGetSceneObjects(
    JNIEnv* env, jclass
) {
    std::lock_guard<std::mutex> lock(engineMutex);
    jclass stringClass = env->FindClass("java/lang/String");
    jobjectArray result = env->NewObjectArray(
        static_cast<jsize>(gScene.objectCount()), stringClass, nullptr
    );
    for (jsize i = 0; i < static_cast<jsize>(gScene.objectCount()); ++i) {
        const nova::SceneObject* object = gScene.get(static_cast<size_t>(i));
        if (!object) continue;
        std::string row = std::to_string(object->id) + "|" + object->name;
        jstring value = env->NewStringUTF(row.c_str());
        env->SetObjectArrayElement(result, i, value);
        env->DeleteLocalRef(value);
    }
    return result;
}

JNIEXPORT void JNICALL
Java_com_novaingine_v2_NativeBridge_nativeSelectObjectById(
    JNIEnv*, jclass, jint objectId
) {
    std::lock_guard<std::mutex> lock(engineMutex);
    if (gInitialized) gScene.select(static_cast<uint32_t>(objectId));
}

JNIEXPORT jfloatArray JNICALL
Java_com_novaingine_v2_NativeBridge_nativeGetSelectedTransform(
    JNIEnv* env, jclass
) {
    std::lock_guard<std::mutex> lock(engineMutex);
    jfloat values[9] = {};
    const nova::SceneObject* object = gScene.selected();
    if (object) {
        values[0] = object->transform.position.x;
        values[1] = object->transform.position.y;
        values[2] = object->transform.position.z;
        values[3] = object->transform.rotation.x;
        values[4] = object->transform.rotation.y;
        values[5] = object->transform.rotation.z;
        values[6] = object->transform.scale.x;
        values[7] = object->transform.scale.y;
        values[8] = object->transform.scale.z;
    }
    jfloatArray result = env->NewFloatArray(9);
    env->SetFloatArrayRegion(result, 0, 9, values);
    return result;
}

JNIEXPORT jfloatArray JNICALL
Java_com_novaingine_v2_NativeBridge_nativeProjectSelectedPosition(
    JNIEnv* env, jclass
) {
    std::lock_guard<std::mutex> lock(engineMutex);
    jfloat values[4] = {0.0f, 0.0f, 2.0f, 0.0f};
    const nova::SceneObject* object = gScene.selected();
    if (gInitialized && object && gViewportWidth > 0 && gViewportHeight > 0) {
        const nova::Vec3& p = object->transform.position;
        nova::Mat4 vp = multiplyMat4(
            gCamera.projectionMatrix(static_cast<float>(gViewportWidth) /
                                     static_cast<float>(gViewportHeight)),
            gCamera.viewMatrix()
        );
        const float x = p.x, y = p.y, z = p.z;
        const float clipX = vp.m[0] * x + vp.m[4] * y + vp.m[8] * z + vp.m[12];
        const float clipY = vp.m[1] * x + vp.m[5] * y + vp.m[9] * z + vp.m[13];
        const float clipZ = vp.m[2] * x + vp.m[6] * y + vp.m[10] * z + vp.m[14];
        const float clipW = vp.m[3] * x + vp.m[7] * y + vp.m[11] * z + vp.m[15];
        if (clipW > 0.0001f) {
            values[0] = clipX / clipW;
            values[1] = clipY / clipW;
            values[2] = clipZ / clipW;
            values[3] = clipW;
        }
    }
    jfloatArray result = env->NewFloatArray(4);
    if (result) env->SetFloatArrayRegion(result, 0, 4, values);
    return result;
}

JNIEXPORT jfloatArray JNICALL
Java_com_novaingine_v2_NativeBridge_nativeProjectSelectedGizmo(JNIEnv* env, jclass) {
    std::lock_guard<std::mutex> lock(engineMutex);
    jfloat values[16] = {};
    const nova::SceneObject* object = gScene.selected();
    if (gInitialized && object && gViewportWidth > 0 && gViewportHeight > 0) {
        const nova::Mat4 vp = multiplyMat4(
            gCamera.projectionMatrix((float)gViewportWidth/(float)gViewportHeight),
            gCamera.viewMatrix());
        const nova::Vec3 origin = object->transform.position;
        const nova::Vec3 points[4] = {
            origin, origin + nova::Vec3(1.0f,0.0f,0.0f),
            origin + nova::Vec3(0.0f,1.0f,0.0f),
            origin + nova::Vec3(0.0f,0.0f,1.0f)
        };
        for (int i=0;i<4;i++) {
            const nova::Vec3& p=points[i];
            const float x=vp.m[0]*p.x+vp.m[4]*p.y+vp.m[8]*p.z+vp.m[12];
            const float y=vp.m[1]*p.x+vp.m[5]*p.y+vp.m[9]*p.z+vp.m[13];
            const float z=vp.m[2]*p.x+vp.m[6]*p.y+vp.m[10]*p.z+vp.m[14];
            const float w=vp.m[3]*p.x+vp.m[7]*p.y+vp.m[11]*p.z+vp.m[15];
            const int o=i*4;
            if (w>0.0001f) { values[o]=x/w; values[o+1]=y/w; values[o+2]=z/w; values[o+3]=w; }
        }
    }
    jfloatArray result=env->NewFloatArray(16);
    if(result)env->SetFloatArrayRegion(result,0,16,values);
    return result;
}

JNIEXPORT void JNICALL
Java_com_novaingine_v2_NativeBridge_nativeSetSelectedTransform(
    JNIEnv* env, jclass, jfloatArray values
) {
    if (!values || env->GetArrayLength(values) < 9) return;
    jfloat v[9];
    env->GetFloatArrayRegion(values, 0, 9, v);
    std::lock_guard<std::mutex> lock(engineMutex);
    if (!gInitialized) return;
    nova::SceneObject* object = gScene.selected();
    if (!object) return;
    recordUndo();
    object->transform.position = nova::Vec3(v[0], v[1], v[2]);
    object->transform.rotation = nova::Vec3(v[3], v[4], v[5]);
    object->transform.scale = nova::Vec3(
        std::max(0.01f, v[6]),
        std::max(0.01f, v[7]),
        std::max(0.01f, v[8])
    );
}


JNIEXPORT void JNICALL
Java_com_novaingine_v2_NativeBridge_nativeBeginTransformEdit(JNIEnv*, jclass) {
    std::lock_guard<std::mutex> lock(engineMutex);
    if (gInitialized && gScene.selected()) recordUndo();
}

JNIEXPORT void JNICALL
Java_com_novaingine_v2_NativeBridge_nativeUpdateSelectedTransform(JNIEnv* env, jclass, jfloatArray values) {
    if (!values || env->GetArrayLength(values) < 9) return;
    jfloat v[9];
    env->GetFloatArrayRegion(values,0,9,v);
    if (env->ExceptionCheck()) { env->ExceptionClear(); return; }
    std::lock_guard<std::mutex> lock(engineMutex);
    if (!gInitialized) return;
    nova::SceneObject* object=gScene.selected();
    if (!object) return;
    object->transform.position=nova::Vec3(v[0],v[1],v[2]);
    object->transform.rotation=nova::Vec3(v[3],v[4],v[5]);
    object->transform.scale=nova::Vec3(std::max(0.01f,v[6]),std::max(0.01f,v[7]),std::max(0.01f,v[8]));
}

JNIEXPORT jint JNICALL
Java_com_novaingine_v2_NativeBridge_nativeImportGlb(JNIEnv* env, jclass, jstring pathValue) {
    if (!pathValue) return -1;
    const char* rawPath = env->GetStringUTFChars(pathValue, nullptr);
    if (!rawPath) return -1;
    const std::string path(rawPath);
    env->ReleaseStringUTFChars(pathValue, rawPath);
    cgltf_options options{};
    cgltf_data* data = nullptr;
    if (cgltf_parse_file(&options, path.c_str(), &data) != cgltf_result_success || !data) return -1;
    if (cgltf_load_buffers(&options, data, path.c_str()) != cgltf_result_success ||
        cgltf_validate(data) != cgltf_result_success) { cgltf_free(data); return -1; }

    std::lock_guard<std::mutex> lock(engineMutex);
    if (!gInitialized) { cgltf_free(data); return -1; }
    size_t imported = 0, totalVertices = 0;
    recordUndo(true);
    for (cgltf_size mi = 0; mi < data->meshes_count && imported < 200; ++mi) {
        const cgltf_mesh& src = data->meshes[mi];
        for (cgltf_size pi = 0; pi < src.primitives_count && imported < 200; ++pi) {
            const cgltf_primitive& prim = src.primitives[pi];
            if (prim.type != cgltf_primitive_type_triangles) continue;
            const cgltf_accessor *pos = nullptr, *norm = nullptr, *uv = nullptr;
            for (cgltf_size ai = 0; ai < prim.attributes_count; ++ai) {
                const cgltf_attribute& at = prim.attributes[ai];
                if (at.type == cgltf_attribute_type_position) pos = at.data;
                else if (at.type == cgltf_attribute_type_normal) norm = at.data;
                else if (at.type == cgltf_attribute_type_texcoord && at.index == 0) uv = at.data;
            }
            if (!pos || pos->count < 3 || pos->count > 250000 ||
                totalVertices + pos->count > 750000) continue;
            const size_t count = static_cast<size_t>(pos->count);
            nova::Mesh mesh;
            mesh.reserve(count, prim.indices ? static_cast<size_t>(prim.indices->count) : count * 3);
            bool valid = true;
            for (size_t i = 0; i < count; ++i) {
                cgltf_float p[3]={0,0,0}, n[3]={0,1,0}, t[2]={0,0};
                if (!cgltf_accessor_read_float(pos, i, p, 3)) { valid=false; break; }
                if (norm) cgltf_accessor_read_float(norm, i, n, 3);
                if (uv) cgltf_accessor_read_float(uv, i, t, 2);
                mesh.addVertex(nova::Vertex(nova::Vec3(p[0],p[1],p[2]),
                    nova::normalize(nova::Vec3(n[0],n[1],n[2])), t[0], t[1]));
            }
            if (!valid) continue;
            if (prim.indices) {
                const size_t ic = static_cast<size_t>(prim.indices->count);
                if (ic < 3 || ic > 750000 || ic % 3) continue;
                for (size_t i=0; i<ic; i+=3) {
                    cgltf_size a=cgltf_accessor_read_index(prim.indices,i);
                    cgltf_size b=cgltf_accessor_read_index(prim.indices,i+1);
                    cgltf_size d=cgltf_accessor_read_index(prim.indices,i+2);
                    if (a>=count || b>=count || d>=count) { valid=false; break; }
                    mesh.addTriangle(static_cast<uint32_t>(a),static_cast<uint32_t>(b),static_cast<uint32_t>(d));
                }
            } else {
                if (count % 3) continue;
                for (size_t i=0;i<count;i+=3)
                    mesh.addTriangle(static_cast<uint32_t>(i),static_cast<uint32_t>(i+1),static_cast<uint32_t>(i+2));
            }
            if (!valid || !mesh.isValid()) continue;
            if (!norm) mesh.recalculateNormals();
            nova::Color color(0.72f,0.74f,0.78f,1.0f);
            float roughness=0.65f, metallic=0.0f;
            if (prim.material) {
                const auto& m=prim.material->pbr_metallic_roughness;
                color=nova::Color(m.base_color_factor[0],m.base_color_factor[1],m.base_color_factor[2],m.base_color_factor[3]);
                roughness=std::clamp(m.roughness_factor,0.04f,1.0f);
                metallic=std::clamp(m.metallic_factor,0.0f,1.0f);
            }
            std::string name=src.name ? src.name : "Blender Mesh";
            if (src.primitives_count>1) name += " "+std::to_string(pi+1);
            nova::Transform transform;
            uint32_t id=gScene.addObject(name,nova::PrimitiveType::Cube,transform,color);
            nova::SceneObject* object=gScene.find(id);
            if (!object || !gRenderer.uploadMesh(id,mesh)) { gScene.removeObject(id); continue; }
            gCustomMeshes[id] = mesh;
            object->material.baseColor=nova::Vec3(color.r,color.g,color.b);
            object->material.roughness=roughness;
            object->material.metallic=metallic;
            totalVertices+=count;
            ++imported;
        }
    }
    cgltf_free(data);
    if (!imported) { gRenderer.releaseAllMeshes(); return 0; }
    gScene.deselect();
    return static_cast<jint>(imported);
}

JNIEXPORT jint JNICALL
Java_com_novaingine_v2_NativeBridge_nativeCreateSculptMesh(
    JNIEnv*, jclass, jint shapeValue, jint densityValue
) {
    std::lock_guard<std::mutex> lock(engineMutex);
    if (!gInitialized) return 0;
    // Sculpt Shape Library: sphere, plane, cube, cylinder, cone, torus,
    // capsule, wedge and a higher-density rounded sphere.
    const int shape = std::clamp(static_cast<int>(shapeValue), 0, 8);
    const int density = std::clamp(static_cast<int>(densityValue), 16, 128);
    nova::Mesh mesh;
    if (shape == 0 || shape == 8) {
        auto sculpt = std::make_unique<nova::SculptMesh>(
            nova::SculptMesh::cubeSphere(shape == 8 ? std::min(128, density * 2) : density, 1.0f));
        if (!sculpt->toMesh(mesh)) return 0;
        sculpt->uploadChunks();
        { std::lock_guard<std::mutex> qlock(gSculptQueueMutex); gSculptQueue.clear(); }
        std::lock_guard<std::mutex> dataLock(gSculptDataMutex);
        gSculptMesh = std::move(sculpt);
        gSculptMeshId = 0;
    } else {
        switch (shape) {
            case 1: mesh = nova::MeshSculptor::makePlane(density); break;
            case 2: mesh = nova::PrimitiveBuilder::createCube(); break;
            case 3: mesh = nova::PrimitiveBuilder::createCylinder(std::min(128, density)); break;
            case 4: {
                const int segments = std::clamp(density, 16, 128);
                const float pi = 3.14159265358979323846f;
                for (int i = 0; i < segments; ++i) {
                    const float a0 = 2.0f*pi*i/segments, a1 = 2.0f*pi*(i+1)/segments;
                    const uint32_t s = static_cast<uint32_t>(mesh.vertexCount());
                    mesh.addVertex(nova::Vertex(nova::Vec3(0,0.5f,0),nova::Vec3(0,1,0),0.5f,1));
                    mesh.addVertex(nova::Vertex(nova::Vec3(0.5f*std::cos(a0),-0.5f,0.5f*std::sin(a0)),nova::Vec3(std::cos(a0),0.5f,std::sin(a0)),0,0));
                    mesh.addVertex(nova::Vertex(nova::Vec3(0.5f*std::cos(a1),-0.5f,0.5f*std::sin(a1)),nova::Vec3(std::cos(a1),0.5f,std::sin(a1)),1,0));
                    mesh.addTriangle(s,s+1,s+2);
                    const uint32_t b = static_cast<uint32_t>(mesh.vertexCount());
                    mesh.addVertex(nova::Vertex(nova::Vec3(0,-0.5f,0),nova::Vec3(0,-1,0),0.5f,0.5f));
                    mesh.addVertex(nova::Vertex(nova::Vec3(0.5f*std::cos(a1),-0.5f,0.5f*std::sin(a1)),nova::Vec3(0,-1,0),1,0));
                    mesh.addVertex(nova::Vertex(nova::Vec3(0.5f*std::cos(a0),-0.5f,0.5f*std::sin(a0)),nova::Vec3(0,-1,0),0,0));
                    mesh.addTriangle(b,b+1,b+2);
                }
                mesh.recalculateNormals();
                break;
            }
            case 5: {
                const int major = std::clamp(density, 16, 96), minor = std::clamp(density/2, 8, 48);
                const float pi = 3.14159265358979323846f, majorR = 0.34f, tubeR = 0.16f;
                for (int i=0;i<=major;i++) for(int j=0;j<=minor;j++) {
                    const float u=2*pi*i/major, v=2*pi*j/minor;
                    const float rr=majorR+tubeR*std::cos(v);
                    nova::Vec3 p(rr*std::cos(u),tubeR*std::sin(v),rr*std::sin(u));
                    mesh.addVertex(nova::Vertex(p,nova::normalize(nova::Vec3(std::cos(v)*std::cos(u),std::sin(v),std::cos(v)*std::sin(u))),static_cast<float>(i)/major,static_cast<float>(j)/minor));
                }
                for(int i=0;i<major;i++) for(int j=0;j<minor;j++) {
                    uint32_t a=i*(minor+1)+j,b=a+minor+1;
                    mesh.addTriangle(a,b,a+1); mesh.addTriangle(a+1,b,b+1);
                }
                break;
            }
            case 6: mesh = nova::PrimitiveBuilder::createCapsule(std::min(64, density)); break;
            case 7: mesh = nova::PrimitiveBuilder::createWedge(); break;
        }
        if (!mesh.isValid()) return 0;
        auto sculpt = std::make_unique<nova::SculptMesh>();
        const auto& sourceVertices = mesh.getVertices();
        sculpt->positions.reserve(sourceVertices.size());
        sculpt->normals.reserve(sourceVertices.size());
        for (const auto& vertex : sourceVertices) {
            sculpt->positions.push_back(vertex.position);
            sculpt->normals.push_back(vertex.normal);
        }
        sculpt->indices = mesh.getIndices();
        sculpt->mask.assign(sculpt->positions.size(), 0.0f);
        sculpt->buildAdjacency();
        sculpt->buildChunks();
        sculpt->buildBVH();
        { std::lock_guard<std::mutex> qlock(gSculptQueueMutex); gSculptQueue.clear(); }
        std::lock_guard<std::mutex> dataLock(gSculptDataMutex);
        gSculptMesh = std::move(sculpt);
        gSculptMeshId = 0;
        gSculptMeshDirty.store(false, std::memory_order_release);
        gSculptMaskDirty.store(false, std::memory_order_release);
    }
    if (!mesh.isValid()) return 0;
    recordUndo(true);
    nova::Transform transform;
    // Place new sculpt primitives at the Blender-style world origin.
    transform.position = nova::Vec3(0.0f, 0.0f, 0.0f);
    const nova::Color color(0.68f, 0.72f, 0.80f, 1.0f);
    const uint32_t id = gScene.addObject(
        std::string("Sculpt ") + (shape == 0 ? "Sphere" : shape == 1 ? "Plane" : shape == 2 ? "Cube" : shape == 3 ? "Cylinder" : shape == 4 ? "Cone" : shape == 5 ? "Torus" : shape == 6 ? "Capsule" : shape == 7 ? "Wedge" : "Rounded Sphere"),
        shape == 1 ? nova::PrimitiveType::Plane : shape == 3 ? nova::PrimitiveType::Cylinder : shape == 6 ? nova::PrimitiveType::Capsule : shape == 0 || shape == 8 ? nova::PrimitiveType::Sphere : nova::PrimitiveType::Cube,
        transform, color);
    if (!gRenderer.uploadMesh(id, mesh)) {
        gScene.removeObject(id);
        return 0;
    }
    gCustomMeshes[id] = std::move(mesh);
    {
        std::lock_guard<std::mutex> dataLock(gSculptDataMutex);
        gSculptMeshId=id;
        if(gSculptMesh) gSculptMasks[id]=gSculptMesh->mask;
        else gSculptMasks[id]=std::vector<float>(gCustomMeshes[id].vertexCount(),0.0f);
        gSculptMeshDirty.store(false,std::memory_order_release);
        gSculptMaskDirty.store(false,std::memory_order_release);
    }
    if (nova::SceneObject* object = gScene.find(id)) {
        object->material.baseColor = nova::Vec3(0.68f, 0.72f, 0.80f);
        object->material.roughness = 0.72f;
        object->material.metallic = 0.0f;
    }
    gScene.select(id);
    return static_cast<jint>(id);
}


JNIEXPORT jint JNICALL
Java_com_novaingine_v2_NativeBridge_nativeEnterSculptMode(JNIEnv*, jclass) {
    std::lock_guard<std::mutex> lock(engineMutex);
    if (!gInitialized) return 0;
    gRenderer.setSculptCursor(false, nova::Vec3(), nova::Vec3(0,1,0), 0.18f);
    gRenderer.releaseAllMeshes();
    gScene.clear();
    gCustomMeshes.clear();
    gSculptMasks.clear();
    { std::lock_guard<std::mutex> qlock(gSculptQueueMutex); gSculptQueue.clear(); }
    { std::lock_guard<std::mutex> dataLock(gSculptDataMutex); gSculptMesh.reset(); }
    gSculptMeshDirty.store(false,std::memory_order_release);
    gSculptMaskDirty.store(false,std::memory_order_release);
    gSculptStrokeActive = false;
    gHasPreviousSculptHit = false;
    gSculptMode = true;

    { std::lock_guard<std::mutex> dataLock(gSculptDataMutex); gSculptMesh.reset(new nova::SculptMesh(nova::SculptMesh::cubeSphere(64, 1.0f))); }
    nova::Mesh renderMesh;
    { std::lock_guard<std::mutex> dataLock(gSculptDataMutex); if (!gSculptMesh->toMesh(renderMesh)) { gSculptMesh.reset(); return 0; }
    gSculptMesh->uploadChunks(); }

    nova::Transform transform;
    transform.position = nova::Vec3(0.0f, 1.8f, 0.0f);
    gSculptMeshId = gScene.addObject("Sculpt Sphere", nova::PrimitiveType::Sphere,
        transform, nova::Color(0.68f, 0.72f, 0.80f, 1.0f));
    if (!gRenderer.uploadMesh(gSculptMeshId, renderMesh)) {
        gScene.clear(); gSculptMesh.reset(); gSculptMeshId = 0; return 0;
    }
    gCustomMeshes[gSculptMeshId] = std::move(renderMesh);
    gSculptMasks[gSculptMeshId].assign(gSculptMesh->positions.size(), 0.0f);
    if (nova::SceneObject* object = gScene.find(gSculptMeshId)) {
        object->material.baseColor = nova::Vec3(0.68f,0.72f,0.80f);
        object->material.roughness = 0.72f;
        object->material.metallic = 0.0f;
        object->castsShadow = true;
    }
    gScene.select(gSculptMeshId);
    gCamera.position = nova::Vec3(0.0f, 1.8f, 4.2f);
    setCameraDirection(nova::Vec3(0.0f, 0.0f, -1.0f));
    RendererSettings settings = gRenderer.getSettings();
    settings.showGrid = false;
    settings.showBounds = false;
    settings.showSky = true;
    settings.showShadows = true;
    settings.showSelectionOutline = false;
    gRenderer.setSettings(settings);
    return static_cast<jint>(gSculptMeshId);
}

JNIEXPORT void JNICALL
Java_com_novaingine_v2_NativeBridge_nativeUpdateSculptCursor(
    JNIEnv*, jclass, jfloat screenX, jfloat screenY, jfloat radiusValue, jboolean active
) {
    std::lock_guard<std::mutex> lock(engineMutex);
    if(!gInitialized||!gSculptMode||!active||gSculptMeshId==0) {
        gRenderer.setSculptCursor(false,nova::Vec3(),nova::Vec3(0,1,0),0.18f);return;
    }
    nova::Vec3 ray;
    if(!makeScreenRay((float)screenX,(float)screenY,ray)) {
        gRenderer.setSculptCursor(false,nova::Vec3(),nova::Vec3(0,1,0),0.18f);return;
    }
    const nova::SceneObject* object=gScene.find(gSculptMeshId);
    if(!object)return;
    std::unique_lock<std::mutex> dataLock(gSculptDataMutex,std::try_to_lock);
    if(!dataLock.owns_lock()||!gSculptMesh)return;
    nova::Vec3 localOrigin=worldToSculptLocalPoint(gCamera.position,object->transform);
    nova::Vec3 localRay=nova::normalize(worldToSculptLocalVector(ray,object->transform));
    nova::SculptMesh::Hit hit=gSculptMesh->raycast(localOrigin,localRay);
    if(!hit.found) {
        gRenderer.setSculptCursor(false,nova::Vec3(),nova::Vec3(0,1,0),0.18f);return;
    }
    gRenderer.setSculptCursor(true,sculptLocalToWorldPoint(hit.point,object->transform),
        sculptLocalToWorldNormal(hit.normal,object->transform),
        std::clamp((float)radiusValue,0.02f,8.0f));
}

JNIEXPORT jboolean JNICALL
Java_com_novaingine_v2_NativeBridge_nativeExportSelectedObj(
    JNIEnv* env, jclass, jstring pathValue
) {
    if (!pathValue) return JNI_FALSE;
    const char* raw = env->GetStringUTFChars(pathValue, nullptr);
    if (!raw) return JNI_FALSE;
    const std::string path(raw);
    env->ReleaseStringUTFChars(pathValue, raw);
    std::lock_guard<std::mutex> lock(engineMutex);
    const uint32_t id = gScene.selectedId();
    auto found = gCustomMeshes.find(id);
    if (!gInitialized || found == gCustomMeshes.end()) return JNI_FALSE;
    std::ofstream out(path);
    if (!out) return JNI_FALSE;
    const auto& vertices = found->second.getVertices();
    const auto& indices = found->second.getIndices();
    out << "# Novaingine sculpt export\n";
    for (const auto& v : vertices) out << "v " << v.position.x << " " << v.position.y << " " << v.position.z << "\n";
    for (const auto& v : vertices) out << "vt " << v.u << " " << v.v << "\n";
    for (const auto& v : vertices) out << "vn " << v.normal.x << " " << v.normal.y << " " << v.normal.z << "\n";
    for (size_t i = 0; i + 2 < indices.size(); i += 3) {
        const uint32_t a = indices[i] + 1, b = indices[i + 1] + 1, c = indices[i + 2] + 1;
        out << "f " << a << "/" << a << "/" << a << " "
            << b << "/" << b << "/" << b << " "
            << c << "/" << c << "/" << c << "\n";
    }
    return out.good() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_novaingine_v2_NativeBridge_nativeSetManualSculptWorkspace(JNIEnv*, jclass, jboolean enabled) {
    std::lock_guard<std::mutex> lock(engineMutex);
    if (!gInitialized) return;

    const bool entering = enabled == JNI_TRUE;
    if (entering == gManualSculptWorkspaceActive) return;

    RendererSettings settings = gRenderer.getSettings();
    if (entering) {
        // Blender-like sculpt viewport: neutral charcoal world, visible floor grid,
        // and no arena environment or decorative scene props.
        settings.showSky = false;
        settings.showGrid = true;
        settings.showBounds = false;
        settings.showShadows = false;
        gSculptWorkspaceVisibility.clear();
        gSculptWorkspaceVisibility.reserve(gScene.objectCount());
        for (nova::SceneObject& object : gScene.getObjects()) {
            gSculptWorkspaceVisibility.emplace_back(object.id, object.visible);
            // Keep an already active sculpt target visible, but hide every arena
            // prop, wall, floor mesh and other scene object behind the workspace.
            if (object.id != gSculptMeshId) object.visible = false;
        }
        const RendererSettings previousSettings = gRenderer.getSettings();
        gSculptWorkspacePreviousShowGrid = previousSettings.showGrid;
        gSculptWorkspacePreviousShowBounds = previousSettings.showBounds;
        gSculptWorkspacePreviousShowSky = previousSettings.showSky;
        gSculptWorkspacePreviousShowShadows = previousSettings.showShadows;
        gRenderer.setSettings(settings);
        gManualSculptWorkspaceActive = true;
    } else {
        for (const auto& saved : gSculptWorkspaceVisibility) {
            if (nova::SceneObject* object = gScene.find(saved.first)) {
                object->visible = saved.second;
            }
        }
        gSculptWorkspaceVisibility.clear();
        settings.showGrid = gSculptWorkspacePreviousShowGrid;
        settings.showBounds = gSculptWorkspacePreviousShowBounds;
        settings.showSky = gSculptWorkspacePreviousShowSky;
        settings.showShadows = gSculptWorkspacePreviousShowShadows;
        gRenderer.setSettings(settings);
        gManualSculptWorkspaceActive = false;
    }
}

JNIEXPORT void JNICALL
Java_com_novaingine_v2_NativeBridge_nativeSetSculptMode(JNIEnv*, jclass, jboolean enabled) {
    std::lock_guard<std::mutex> lock(engineMutex);
    gSculptMode = enabled == JNI_TRUE;
    if (!gSculptMode && gSculptStrokeActive) {
        gSculptStrokeActive=false;gHasPreviousSculptHit=false;
        enqueueSculptJob(SculptDabJob{nova::Vec3(),nova::Vec3(),nova::Vec3(),nova::SculptMesh::Brush{},false,true});
    }
}

JNIEXPORT void JNICALL
Java_com_novaingine_v2_NativeBridge_nativeBeginSculptStroke(JNIEnv*, jclass) {
    std::lock_guard<std::mutex> lock(engineMutex);
    if (!gInitialized || !gSculptMode) return;
    const uint32_t id=gScene.selectedId();
    if(id==0||gCustomMeshes.find(id)==gCustomMeshes.end())return;
    recordUndo(true);
    gSculptStrokeActive=true;
    gHasPreviousSculptHit=false;
    enqueueSculptJob(SculptDabJob{nova::Vec3(),nova::Vec3(),nova::Vec3(),nova::SculptMesh::Brush{},true,false});
}

JNIEXPORT jint JNICALL
Java_com_novaingine_v2_NativeBridge_nativeSculptAtScreen(
    JNIEnv*, jclass, jfloat screenX, jfloat screenY,
    jfloat radiusValue, jfloat strengthValue, jint brushValue,
    jfloat falloffValue, jint symmetryAxisValue, jboolean subtractValue,
    jfloat pressureValue
) {
    std::lock_guard<std::mutex> lock(engineMutex);
    if(!gInitialized||!gSculptMode||!gSculptStrokeActive)return 0;
    const uint32_t id=gScene.selectedId();
    nova::SceneObject* object=gScene.find(id);
    if(!object||id!=gSculptMeshId||!gSculptMesh)return -1;
    nova::Vec3 ray;
    if(!makeScreenRay(screenX,screenY,ray)){gHasPreviousSculptHit=false;gRenderer.setSculptCursor(false,nova::Vec3(),nova::Vec3(0,1,0),0.18f);return 0;}
    const nova::Vec3 localOrigin=worldToSculptLocalPoint(gCamera.position,object->transform);
    const nova::Vec3 localRay=nova::normalize(worldToSculptLocalVector(ray,object->transform));
    if(nova::length(localRay)<0.0001f)return 0;
    nova::SculptMesh::Hit hit;
    {
        std::unique_lock<std::mutex> dataLock(gSculptDataMutex,std::try_to_lock);
        if(!dataLock.owns_lock())return 0;
        hit=gSculptMesh->raycast(localOrigin,localRay);
    }
    if(!hit.found){gHasPreviousSculptHit=false;gRenderer.setSculptCursor(false,nova::Vec3(),nova::Vec3(0,1,0),0.18f);return 0;}
    nova::SculptMesh::Brush brush;
    brush.radius=std::clamp(sculptLocalRadius((float)radiusValue,object->transform),0.02f,8.0f);
    brush.strength=std::clamp((float)strengthValue,0.0f,1.0f);
    brush.hardness=std::clamp((float)falloffValue,0.0f,1.0f);
    brush.pressure=std::clamp((float)pressureValue,0.0f,1.0f);
    brush.type=std::clamp((int)brushValue,0,24);
    brush.symmetryAxis=std::clamp((int)symmetryAxisValue,-1,2);
    brush.subtract=subtractValue==JNI_TRUE;

    if(!gHasPreviousSculptHit) {
        enqueueSculptJob(SculptDabJob{hit.point,hit.normal,nova::Vec3(),brush,false,false});
    } else {
        nova::Vec3 segment=hit.point-gPreviousSculptHit;
        nova::Vec3 view=ray;
        segment=segment-view*nova::dot(segment,view);
        float distance=nova::length(hit.point-gPreviousSculptHit);
        float spacing=std::max(0.005f,brush.radius*0.25f);
        int steps=std::clamp((int)std::ceil(distance/spacing),1,128);
        nova::Vec3 previous=gPreviousSculptHit;
        nova::Vec3 previousNormal=gPreviousSculptNormal;
        for(int step=1;step<=steps;step++) {
            float t=(float)step/(float)steps;
            nova::Vec3 point=previous+(hit.point-previous)*t;
            nova::Vec3 normal=nova::normalize(previousNormal+(hit.normal-previousNormal)*t);
            enqueueSculptJob(SculptDabJob{point,normal,segment/(float)steps,brush,false,false});
        }
    }
    gPreviousSculptHit=hit.point;
    gPreviousSculptNormal=hit.normal;
    gHasPreviousSculptHit=true;
    gRenderer.setSculptCursor(true,sculptLocalToWorldPoint(hit.point,object->transform),sculptLocalToWorldNormal(hit.normal,object->transform),(float)radiusValue);
    return 1;
}

JNIEXPORT void JNICALL
Java_com_novaingine_v2_NativeBridge_nativeEndSculptStroke(JNIEnv*, jclass) {
    std::lock_guard<std::mutex> lock(engineMutex);
    if(!gInitialized||!gSculptStrokeActive)return;
    gSculptStrokeActive=false;
    gHasPreviousSculptHit=false;
    enqueueSculptJob(SculptDabJob{nova::Vec3(),nova::Vec3(),nova::Vec3(),nova::SculptMesh::Brush{},false,true});
}
JNIEXPORT jint JNICALL
Java_com_novaingine_v2_NativeBridge_nativeSculptSelectedMesh(
    JNIEnv*, jclass, jfloat cxValue, jfloat cyValue, jfloat czValue,
    jfloat radiusValue, jfloat strengthValue, jint modeValue, jboolean symmetryValue
) {
    std::lock_guard<std::mutex> lock(engineMutex);
    if (!gInitialized) return 0;
    const uint32_t id = gScene.selectedId();
    auto found = gCustomMeshes.find(id);
    if (id == 0 || found == gCustomMeshes.end()) return -1;
    const float cx = std::clamp(static_cast<float>(cxValue), -1000.0f, 1000.0f);
    const float cy = std::clamp(static_cast<float>(cyValue), -1000.0f, 1000.0f);
    const float cz = std::clamp(static_cast<float>(czValue), -1000.0f, 1000.0f);
    const float radius = std::clamp(static_cast<float>(radiusValue), 0.01f, 100.0f);
    const float strength = std::clamp(static_cast<float>(strengthValue), 0.001f, 2.0f);
    const int mode = std::clamp(static_cast<int>(modeValue), 0, 6);
    const bool symmetry = symmetryValue == JNI_TRUE;
    const nova::Mesh& source = found->second;
    std::vector<nova::Vertex> vertices = source.getVertices();
    const std::vector<uint32_t>& indices = source.getIndices();
    std::vector<nova::Vec3> neighborSums;
    std::vector<uint32_t> neighborCounts;
    if (mode == 1) {
        neighborSums.resize(vertices.size());
        neighborCounts.resize(vertices.size(), 0);
        for (size_t i = 0; i + 2 < indices.size(); i += 3) {
            const uint32_t a = indices[i], b = indices[i + 1], d = indices[i + 2];
            if (a >= vertices.size() || b >= vertices.size() || d >= vertices.size()) continue;
            neighborSums[a] = neighborSums[a] + vertices[b].position + vertices[d].position;
            neighborSums[b] = neighborSums[b] + vertices[a].position + vertices[d].position;
            neighborSums[d] = neighborSums[d] + vertices[a].position + vertices[b].position;
            neighborCounts[a] += 2; neighborCounts[b] += 2; neighborCounts[d] += 2;
        }
    }
    bool changed = false;
    const nova::Vec3 center(cx, cy, cz);
    for (size_t vi = 0; vi < vertices.size(); ++vi) {
        nova::Vertex& vertex = vertices[vi];
        const nova::Vec3 p = vertex.position;
        const nova::Vec3 mirrored(-p.x, p.y, p.z);
        const float primaryDistance = nova::length(p - center);
        const float mirroredDistance = symmetry ? nova::length(mirrored - center) : 1.0e9f;
        const bool useMirror = mirroredDistance < primaryDistance;
        const float distance = std::min(primaryDistance, mirroredDistance);
        if (distance > radius) continue;
        const float t = 1.0f - distance / radius;
        const float falloff = t * t * (3.0f - 2.0f * t);
        nova::Vec3 next = p;
        if (mode == 0 || mode == 3) {
            nova::Vec3 normal = vertex.normal;
            if (useMirror) normal.x = -normal.x;
            next = p + normal * (strength * falloff);
        } else if (mode == 1 && neighborCounts[vi] > 0) {
            const nova::Vec3 average = neighborSums[vi] * (1.0f / static_cast<float>(neighborCounts[vi]));
            next = p + (average - p) * std::min(1.0f, strength * falloff);
        } else if (mode == 2) {
            next.y += (cy - p.y) * std::min(1.0f, strength * falloff);
        }
        if (nova::length(next - p) > 0.000001f) { vertex.position = next; changed = true; }
    }
    if (!changed) return 0;
    recordUndo(true);
    nova::Mesh sculpted(std::move(vertices), indices);
    sculpted.recalculateNormals();
    if (!gRenderer.uploadMesh(id, sculpted)) return 0;
    found->second = std::move(sculpted);
    return 1;
}
JNIEXPORT jint JNICALL
Java_com_novaingine_v2_NativeBridge_nativeAISculptSelectedMesh(
    JNIEnv*, jclass, jfloat cxValue, jfloat cyValue, jfloat czValue,
    jfloat radiusValue, jfloat strengthValue, jint brushValue,
    jfloat falloffValue, jboolean symmetryValue,
    jfloat dragXValue, jfloat dragYValue, jfloat dragZValue
) {
    std::lock_guard<std::mutex> lock(engineMutex);
    if (!gInitialized) return -2;
    const uint32_t id = gScene.selectedId();
    auto found = gCustomMeshes.find(id);
    if (id == 0 || found == gCustomMeshes.end()) return -1;

    const nova::Vec3 center(
        std::clamp(static_cast<float>(cxValue), -1000.0f, 1000.0f),
        std::clamp(static_cast<float>(cyValue), -1000.0f, 1000.0f),
        std::clamp(static_cast<float>(czValue), -1000.0f, 1000.0f));
    const float radius = std::clamp(static_cast<float>(radiusValue), 0.01f, 3.0f);
    const float strength = std::clamp(static_cast<float>(strengthValue), 0.001f, 0.5f);
    const float falloff = std::clamp(static_cast<float>(falloffValue), 0.0f, 1.0f);
    const int brushIndex = std::clamp(static_cast<int>(brushValue), 0, 7);
    const nova::Vec3 drag(
        std::clamp(static_cast<float>(dragXValue), -2.0f, 2.0f),
        std::clamp(static_cast<float>(dragYValue), -2.0f, 2.0f),
        std::clamp(static_cast<float>(dragZValue), -2.0f, 2.0f));

    nova::Mesh edited = found->second;
    std::vector<float> masks = gSculptMasks[id];
    const bool changed = nova::MeshSculptor::apply(
        edited, masks, center, drag, radius, strength,
        static_cast<nova::SculptBrush>(brushIndex), falloff,
        symmetryValue == JNI_TRUE);
    if (!changed) return 0;

    recordUndo(true);
    if (!gRenderer.uploadMesh(id, edited)) return -3;
    found->second = std::move(edited);
    gSculptMasks[id] = std::move(masks);
    return 1;
}

JNIEXPORT jint JNICALL
Java_com_novaingine_v2_NativeBridge_nativeAISculptStroke(
    JNIEnv* env, jclass, jfloatArray pointArray,
    jfloat radiusValue, jfloat strengthValue, jint brushValue,
    jfloat hardnessValue, jint symmetryAxisValue, jboolean subtractValue,
    jfloat pressureValue, jfloat dragXValue, jfloat dragYValue, jfloat dragZValue
) {
    if(!pointArray)return -5;
    const jsize length=env->GetArrayLength(pointArray);
    if(length<3||length>192||length%3!=0)return -5;
    std::vector<jfloat> values(static_cast<size_t>(length));
    env->GetFloatArrayRegion(pointArray,0,length,values.data());
    if(env->ExceptionCheck()){env->ExceptionClear();return -5;}
    for(float value:values)if(!std::isfinite(value)||std::fabs(value)>1000.0f)return -5;

    std::lock_guard<std::mutex> engineLock(engineMutex);
    if(!gInitialized)return -2;
    const uint32_t id=gScene.selectedId();
    if(id==0||id!=gSculptMeshId||gCustomMeshes.find(id)==gCustomMeshes.end())return -1;
    {
        std::lock_guard<std::mutex> dataLock(gSculptDataMutex);
        if(!gSculptMesh)return -4;
    }
    nova::SculptMesh::Brush brush;
    brush.radius=std::clamp(static_cast<float>(radiusValue),0.02f,3.0f);
    brush.strength=std::clamp(static_cast<float>(strengthValue),0.0f,1.0f);
    brush.hardness=std::clamp(static_cast<float>(hardnessValue),0.0f,1.0f);
    brush.pressure=std::clamp(static_cast<float>(pressureValue),0.0f,1.0f);
    brush.type=std::clamp(static_cast<int>(brushValue),0,24);
    brush.symmetryAxis=std::clamp(static_cast<int>(symmetryAxisValue),-1,2);
    brush.subtract=subtractValue==JNI_TRUE;
    const size_t pointCount=static_cast<size_t>(length/3);
    const nova::Vec3 drag(
        std::clamp(static_cast<float>(dragXValue),-2.0f,2.0f),
        std::clamp(static_cast<float>(dragYValue),-2.0f,2.0f),
        std::clamp(static_cast<float>(dragZValue),-2.0f,2.0f));
    auto pointAt=[&](size_t i){return nova::Vec3(values[i*3],values[i*3+1],values[i*3+2]);};

    const float spacing=std::max(0.005f,brush.radius*0.25f);
    size_t estimatedDabs=1;
    if(pointCount>1) {
        for(size_t i=0;i+1<pointCount;i++) {
            float distance=nova::length(pointAt(i+1)-pointAt(i));
            estimatedDabs+=(size_t)std::max(1,(int)std::ceil(distance/spacing));
            if(estimatedDabs>240)return -6;
        }
    }
    // Store an undo snapshot for each AI-authored sculpt stroke before it is queued.
    recordUndo(true);
    startSculptWorker();
    enqueueSculptJob(SculptDabJob{nova::Vec3(),nova::Vec3(),nova::Vec3(),brush,true,false});
    int dabCount=0;
    const nova::Vec3 up(0.0f,1.0f,0.0f);
    if(pointCount==1&&brush.type==2&&nova::length(drag)>1e-7f) {
        nova::Vec3 start=pointAt(0);
        enqueueSculptJob(SculptDabJob{start,up,nova::Vec3(),brush,false,false});
        enqueueSculptJob(SculptDabJob{start+drag,up,drag,brush,false,false});
        dabCount=2;
    } else if(pointCount==1) {
        enqueueSculptJob(SculptDabJob{pointAt(0),up,drag,brush,false,false});
        dabCount=1;
    } else {
        nova::Vec3 first=pointAt(0);
        enqueueSculptJob(SculptDabJob{first,up,nova::Vec3(),brush,false,false});
        dabCount=1;
        for(size_t segmentIndex=0;segmentIndex+1<pointCount;segmentIndex++) {
            nova::Vec3 a=pointAt(segmentIndex),b=pointAt(segmentIndex+1);
            nova::Vec3 segment=b-a;
            int steps=std::max(1,(int)std::ceil(nova::length(segment)/spacing));
            for(int step=1;step<=steps;step++) {
                float t=(float)step/(float)steps;
                nova::Vec3 center=a+segment*t;
                nova::Vec3 delta=segment*(1.0f/(float)steps);
                enqueueSculptJob(SculptDabJob{center,up,delta,brush,false,false});
                ++dabCount;
            }
        }
    }
    enqueueSculptJob(SculptDabJob{nova::Vec3(),nova::Vec3(),nova::Vec3(),brush,false,true});
    return dabCount;
}

JNIEXPORT jint JNICALL
Java_com_novaingine_v2_NativeBridge_nativeCreateMeshPart(
    JNIEnv* env, jclass, jfloatArray positionArray, jintArray triangleArray,
    jstring nameValue, jfloat x, jfloat y, jfloat z, jfloat red, jfloat green, jfloat blue
) {
    if (!positionArray || !triangleArray) return 0;
    const jsize pc=env->GetArrayLength(positionArray), ic=env->GetArrayLength(triangleArray);
    if(pc<9 || pc>9000 || pc%3 || ic<3 || ic>18000 || ic%3) return 0;
    std::vector<jfloat> p(static_cast<size_t>(pc)); std::vector<jint> ix(static_cast<size_t>(ic));
    env->GetFloatArrayRegion(positionArray,0,pc,p.data()); env->GetIntArrayRegion(triangleArray,0,ic,ix.data());
    if(env->ExceptionCheck()){env->ExceptionClear();return 0;}
    std::string name="AI Mesh Part";
    if(nameValue){const char* raw=env->GetStringUTFChars(nameValue,nullptr);if(raw){name=raw;env->ReleaseStringUTFChars(nameValue,raw);}}
    std::lock_guard<std::mutex> lock(engineMutex); if(!gInitialized)return 0;
    const size_t vc=static_cast<size_t>(pc/3); nova::Mesh mesh; mesh.reserve(vc,static_cast<size_t>(ic));
    for(size_t i=0;i<vc;i++){float a=p[i*3],b=p[i*3+1],c=p[i*3+2];if(!std::isfinite(a)||!std::isfinite(b)||!std::isfinite(c)||std::fabs(a)>20||std::fabs(b)>20||std::fabs(c)>20)return 0;mesh.addVertex(nova::Vertex(nova::Vec3(a,b,c),nova::Vec3(0,1,0),0,0));}
    for(size_t i=0;i<static_cast<size_t>(ic);i+=3){jint a=ix[i],b=ix[i+1],c=ix[i+2];if(a<0||b<0||c<0||static_cast<size_t>(a)>=vc||static_cast<size_t>(b)>=vc||static_cast<size_t>(c)>=vc)return 0;mesh.addTriangle(static_cast<uint32_t>(a),static_cast<uint32_t>(b),static_cast<uint32_t>(c));}
    if(!mesh.isValid())return 0; mesh.recalculateNormals(); recordUndo(true);
    nova::Transform transform; transform.position=nova::Vec3(x,y,z);
    const nova::Color color(std::clamp(red,0.0f,1.0f),std::clamp(green,0.0f,1.0f),std::clamp(blue,0.0f,1.0f),1.0f);
    const uint32_t id=gScene.addObject(name,nova::PrimitiveType::Cube,transform,color);
    if(!gRenderer.uploadMesh(id,mesh)){gScene.removeObject(id);return 0;}
    gCustomMeshes[id]=mesh; gSculptMasks[id]=std::vector<float>(mesh.vertexCount(),0.0f);
    if(nova::SceneObject* object=gScene.find(id)){object->material.baseColor=nova::Vec3(color.r,color.g,color.b);object->material.roughness=0.58f;object->material.metallic=0.0f;}
    gScene.select(id); return static_cast<jint>(id);
}

JNIEXPORT jint JNICALL
Java_com_novaingine_v2_NativeBridge_nativeAddPrimitive(
    JNIEnv* env, jclass, jint primitiveValue, jstring nameValue,
    jfloat x, jfloat y, jfloat z, jfloat sx, jfloat sy, jfloat sz,
    jfloat red, jfloat green, jfloat blue
) {
    const char* rawName = nameValue ? env->GetStringUTFChars(nameValue, nullptr) : nullptr;
    const std::string objectName = rawName ? rawName : "AI Object";
    std::lock_guard<std::mutex> lock(engineMutex);
    if (!gInitialized) {
        if (rawName) env->ReleaseStringUTFChars(nameValue, rawName);
        return 0;
    }
    const int primitiveIndex = std::clamp(static_cast<int>(primitiveValue), 0, 5);
    const nova::PrimitiveType primitive = static_cast<nova::PrimitiveType>(primitiveIndex);
    recordUndo();
    nova::Transform transform;
    transform.position = nova::Vec3(x, y, z);
    transform.scale = nova::Vec3(
        std::max(0.01f, std::fabs(sx)),
        std::max(0.01f, std::fabs(sy)),
        std::max(0.01f, std::fabs(sz))
    );
    const nova::Color color(
        std::clamp(static_cast<float>(red), 0.0f, 1.0f),
        std::clamp(static_cast<float>(green), 0.0f, 1.0f),
        std::clamp(static_cast<float>(blue), 0.0f, 1.0f),
        1.0f
    );
    const uint32_t id = gScene.addObject(objectName, primitive, transform, color);
    if (nova::SceneObject* object = gScene.find(id)) {
        object->material.baseColor = nova::Vec3(color.r, color.g, color.b);
        object->material.roughness = 0.72f;
        object->material.metallic = 0.0f;
    }
    gRenderer.releaseAllMeshes();
    if (rawName) env->ReleaseStringUTFChars(nameValue, rawName);
    return static_cast<jint>(id);
}

JNIEXPORT jint JNICALL
Java_com_novaingine_v2_NativeBridge_nativePaintFoliage(
    JNIEnv*, jclass, jfloat centerXValue, jfloat centerZValue,
    jfloat radiusValue, jint countValue, jint kindValue
) {
    std::lock_guard<std::mutex> lock(engineMutex);
    if (!gInitialized) return 0;

    const float centerX = static_cast<float>(centerXValue);
    const float centerZ = static_cast<float>(centerZValue);
    const float radius = std::clamp(static_cast<float>(radiusValue), 1.0f, 30.0f);
    const int count = std::clamp(static_cast<int>(countValue), 1, 60);
    const int kind = std::clamp(static_cast<int>(kindValue), 0, 1);

    size_t existing = 0;
    for (const nova::SceneObject& object : gScene.getObjects()) {
        if (object.name.rfind("Foliage ", 0) == 0) ++existing;
    }
    if (existing >= 240) return 0;
    const int objectsPerPlant = kind == 0 ? 2 : 1;
    const int accepted = std::min(
        count, static_cast<int>((240 - existing) / objectsPerPlant)
    );
    if (accepted <= 0) return 0;

    recordUndo();
    uint32_t seed = static_cast<uint32_t>(
        std::fabs(centerX * 173.0f + centerZ * 997.0f) * 1000.0f
    ) + static_cast<uint32_t>(gScene.objectCount() * 7919u) + 0x9e3779b9u;
    auto random01 = [&seed]() -> float {
        seed = seed * 1664525u + 1013904223u;
        return static_cast<float>((seed >> 8) & 0x00ffffffu) / 16777215.0f;
    };

    for (int i = 0; i < accepted; ++i) {
        const float angle = random01() * 6.28318530718f;
        const float distance = std::sqrt(random01()) * radius;
        const float x = centerX + std::cos(angle) * distance;
        const float z = centerZ + std::sin(angle) * distance;

        nova::Transform stemTransform;
        stemTransform.position = nova::Vec3(x, kind == 0 ? 0.58f : 0.38f, z);
        stemTransform.scale = kind == 0
            ? nova::Vec3(0.13f, 0.58f, 0.13f)
            : nova::Vec3(0.48f, 0.34f, 0.48f);
        const nova::Color stemColor = kind == 0
            ? nova::Color(0.27f, 0.13f, 0.055f, 1.0f)
            : nova::Color(0.12f, 0.34f, 0.075f, 1.0f);
        const uint32_t stemId = gScene.addObject(
            "Foliage " + std::string(kind == 0 ? "Tree Trunk " : "Bush ") + std::to_string(i + 1),
            kind == 0 ? nova::PrimitiveType::Cylinder : nova::PrimitiveType::Sphere,
            stemTransform, stemColor
        );
        if (nova::SceneObject* stem = gScene.find(stemId)) {
            stem->material.baseColor = nova::Vec3(stemColor.r, stemColor.g, stemColor.b);
            stem->material.roughness = 0.92f;
            stem->material.metallic = 0.0f;
        }

        if (kind == 0) {
            nova::Transform crownTransform;
            crownTransform.position = nova::Vec3(x, 1.62f + random01() * 0.22f, z);
            const float size = 0.58f + random01() * 0.28f;
            crownTransform.scale = nova::Vec3(size, size * 1.12f, size);
            const float green = 0.30f + random01() * 0.16f;
            const nova::Color leaves(0.10f, green, 0.075f, 1.0f);
            const uint32_t crownId = gScene.addObject(
                "Foliage Tree Canopy " + std::to_string(i + 1),
                nova::PrimitiveType::Sphere, crownTransform, leaves
            );
            if (nova::SceneObject* canopy = gScene.find(crownId)) {
                canopy->material.baseColor = nova::Vec3(leaves.r, leaves.g, leaves.b);
                canopy->material.roughness = 0.88f;
                canopy->material.metallic = 0.0f;
            }
        }
    }

    gScene.deselect();
    gRenderer.releaseAllMeshes();
    return static_cast<jint>(accepted);
}

JNIEXPORT jint JNICALL
Java_com_novaingine_v2_NativeBridge_nativeClearFoliage(
    JNIEnv*, jclass
) {
    std::lock_guard<std::mutex> lock(engineMutex);
    if (!gInitialized) return 0;

    std::vector<uint32_t> removeIds;
    for (const nova::SceneObject& object : gScene.getObjects()) {
        if (object.name.rfind("Foliage ", 0) == 0) removeIds.push_back(object.id);
    }
    if (removeIds.empty()) return 0;

    recordUndo();
    for (uint32_t id : removeIds) gScene.removeObject(id);
    gRenderer.releaseAllMeshes();
    return static_cast<jint>(removeIds.size());
}


JNIEXPORT jboolean JNICALL
Java_com_novaingine_v2_NativeBridge_nativeCreateTerrain(
    JNIEnv*, jclass
) {
    std::lock_guard<std::mutex> lock(engineMutex);
    if (!gInitialized) return JNI_FALSE;

    // Replace the old block terrain with one connected, editable heightfield.
    recordUndo(true);
    std::vector<uint32_t> oldTerrain;
    for (const nova::SceneObject& object : gScene.getObjects()) {
        if (object.name == "Landscape Terrain" ||
            object.name.rfind("Terrain Tile ", 0) == 0 ||
            object.name.rfind("Landscape Chunk ", 0) == 0 ||
            object.name.rfind("Landscape Tile ", 0) == 0) {
            oldTerrain.push_back(object.id);
        }
    }
    gLandscapeWorldTiles.clear();
    for (uint32_t id : oldTerrain) {
        gRenderer.releaseMesh(id);
        gCustomMeshes.erase(id);
        gSculptMasks.erase(id);
        gScene.removeObject(id);
    }

    gLandscape.initialize(129, 129, 0.5f, -32.0f, -36.0f);
    gLandscape.buildChunks(gLandscapeChunkSize);
    constexpr int resolution = 129;
    constexpr float halfExtent = 32.0f;
    constexpr float spacing = (halfExtent * 2.0f) / (resolution - 1);
    std::vector<nova::Vertex> vertices;
    std::vector<uint32_t> indices;
    vertices.reserve(static_cast<size_t>(resolution) * resolution);
    indices.reserve(static_cast<size_t>(resolution - 1) * (resolution - 1) * 6);

    for (int z = 0; z < resolution; ++z) {
        for (int x = 0; x < resolution; ++x) {
            const float u = static_cast<float>(x) / (resolution - 1);
            const float v = static_cast<float>(z) / (resolution - 1);
            const float px = -halfExtent + x * spacing;
            const float pz = -4.0f - halfExtent + z * spacing;
            vertices.emplace_back(
                nova::Vec3(px, 0.0f, pz),
                nova::Vec3(0.0f, 1.0f, 0.0f), u, v
            );
            vertices.back().layerWeights[0] = 1.0f;
        }
    }
    for (int z = 0; z < resolution - 1; ++z) {
        for (int x = 0; x < resolution - 1; ++x) {
            const uint32_t a = static_cast<uint32_t>(z * resolution + x);
            const uint32_t b = a + 1;
            const uint32_t c = a + static_cast<uint32_t>(resolution);
            const uint32_t d = c + 1;
            indices.insert(indices.end(), {a, c, b, b, c, d});
        }
    }

    nova::Mesh terrainMesh(std::move(vertices), std::move(indices));
    terrainMesh.recalculateNormals();
    if (!terrainMesh.isValid()) return JNI_FALSE;

    const nova::Color grass(0.28f, 0.40f, 0.19f, 1.0f);
    nova::Transform transform;
    const uint32_t terrainId = gScene.addObject(
        "Landscape Terrain", nova::PrimitiveType::Plane, transform, grass
    );
    nova::SceneObject* terrain = gScene.find(terrainId);
    if (!terrain) return JNI_FALSE;
    terrain->material.baseColor = nova::Vec3(grass.r, grass.g, grass.b);
    terrain->material.roughness = 0.94f;
    terrain->material.metallic = 0.0f;

    gCustomMeshes[terrainId] = terrainMesh;
    if (!gRenderer.uploadMesh(terrainId, terrainMesh)) {
        gCustomMeshes.erase(terrainId);
        gScene.removeObject(terrainId);
        return JNI_FALSE;
    }
    terrain->visible = false;
    gTerrainLodLevel = -1;
    gTerrainChunksDirty = true;
    gScene.select(terrainId);
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_novaingine_v2_NativeBridge_nativeCreateLandscapeWorldTile(
    JNIEnv* env, jclass, jint tileX, jint tileZ, jfloatArray heightArray
) {
    std::lock_guard<std::mutex> lock(engineMutex);
    if (!gInitialized || !heightArray || env->GetArrayLength(heightArray) != 129 * 129)
        return JNI_FALSE;
    std::vector<float> heights(129u * 129u);
    env->GetFloatArrayRegion(heightArray, 0, static_cast<jsize>(heights.size()), heights.data());
    if (env->ExceptionCheck()) { env->ExceptionClear(); return JNI_FALSE; }
    for (float& value : heights) {
        if (!std::isfinite(value)) return JNI_FALSE;
        value = std::clamp(value, -12.0f, 24.0f);
    }
    recordUndo(true);
    return createLandscapeWorldTile(static_cast<int>(tileX), static_cast<int>(tileZ), heights)
        ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_novaingine_v2_NativeBridge_nativeRemoveLandscapeWorldTile(
    JNIEnv*, jclass, jint tileX, jint tileZ
) {
    std::lock_guard<std::mutex> lock(engineMutex);
    if (!gInitialized || (tileX == 0 && tileZ == 0)) return JNI_FALSE;
    const uint64_t key = landscapeTileKey(static_cast<int>(tileX), static_cast<int>(tileZ));
    auto found = gLandscapeWorldTiles.find(key);
    if (found == gLandscapeWorldTiles.end()) return JNI_FALSE;
    recordUndo(true);
    const uint32_t id = found->second.objectId;
    gRenderer.releaseMesh(id);
    gCustomMeshes.erase(id);
    gScene.removeObject(id);
    gLandscapeWorldTiles.erase(found);
    return JNI_TRUE;
}

JNIEXPORT jint JNICALL
Java_com_novaingine_v2_NativeBridge_nativeGetLandscapeWorldTileCount(JNIEnv*, jclass) {
    std::lock_guard<std::mutex> lock(engineMutex);
    return static_cast<jint>(gLandscapeWorldTiles.size());
}

JNIEXPORT jboolean JNICALL
Java_com_novaingine_v2_NativeBridge_nativeSculptTerrain(
    JNIEnv*, jclass, jfloat centerXValue, jfloat centerZValue,
    jfloat radiusValue, jfloat strengthValue, jint modeValue
) {
    std::lock_guard<std::mutex> lock(engineMutex);
    if (!gInitialized || (!gLandscape.valid() && gLandscapeWorldTiles.empty())) return JNI_FALSE;
    const float x = static_cast<float>(centerXValue);
    const float z = static_cast<float>(centerZValue);
    nova::LandscapeSystem* target = landscapeAtWorld(x, z);
    if (!target) return JNI_FALSE;
    const float radius=std::clamp(static_cast<float>(radiusValue),0.5f,32.0f);
    const float strength=std::clamp(static_cast<float>(strengthValue),0.005f,8.0f);
    const int mode=std::clamp(static_cast<int>(modeValue),0,6);
    recordUndo(true);
    return target->sculpt(x,z,radius,strength,mode) && syncLandscapeAtWorld(x,z)
        ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_novaingine_v2_NativeBridge_nativeSetTerrainHeights(
    JNIEnv* env, jclass, jfloatArray heightArray
) {
    std::lock_guard<std::mutex> lock(engineMutex);
    if (!gInitialized || !heightArray || env->GetArrayLength(heightArray) != 129*129 || !gLandscape.valid()) return JNI_FALSE;
    std::vector<float> heights(129u*129u);
    env->GetFloatArrayRegion(heightArray,0,static_cast<jsize>(heights.size()),heights.data());
    if(env->ExceptionCheck()){env->ExceptionClear();return JNI_FALSE;}
    recordUndo(true);
    gLandscape.initialize(129,129,0.5f,-32.0f,-36.0f);
    if(!gLandscape.setBaseHeights(heights))return JNI_FALSE;
    return syncLandscapeMeshFromData()?JNI_TRUE:JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_novaingine_v2_NativeBridge_nativeSetTerrainTexture(
    JNIEnv* env, jclass, jint layer, jint mapType, jint width, jint height, jintArray pixelArray
) {
    std::lock_guard<std::mutex> lock(engineMutex);
    if (!gInitialized || !pixelArray || layer < 0 || layer >= 4 ||
        mapType < 0 || mapType >= 3 || width < 1 || height < 1 ||
        width > 2048 || height > 2048) return JNI_FALSE;
    const jsize count = env->GetArrayLength(pixelArray);
    if (count != width * height) return JNI_FALSE;
    std::vector<jint> argb(static_cast<size_t>(count));
    env->GetIntArrayRegion(pixelArray, 0, count, argb.data());
    if (env->ExceptionCheck()) { env->ExceptionClear(); return JNI_FALSE; }
    std::vector<uint8_t> rgba(static_cast<size_t>(count) * 4u);
    for (jsize i = 0; i < count; ++i) {
        const uint32_t pixel = static_cast<uint32_t>(argb[static_cast<size_t>(i)]);
        const size_t out = static_cast<size_t>(i) * 4u;
        rgba[out + 0] = static_cast<uint8_t>((pixel >> 16) & 0xFFu);
        rgba[out + 1] = static_cast<uint8_t>((pixel >> 8) & 0xFFu);
        rgba[out + 2] = static_cast<uint8_t>(pixel & 0xFFu);
        rgba[out + 3] = 255u;
    }
    return gRenderer.setTerrainTexture(layer, mapType, width, height, rgba.data())
        ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_novaingine_v2_NativeBridge_nativeSetTerrainHeightmapPng16(
    JNIEnv* env, jclass, jbyteArray pngArray
) {
    std::lock_guard<std::mutex> lock(engineMutex);
    if (!gInitialized || !gLandscape.valid() || !pngArray) return JNI_FALSE;
    const jsize length = env->GetArrayLength(pngArray);
    if (length < 64 || length > 16 * 1024 * 1024) return JNI_FALSE;
    std::vector<uint8_t> bytes(static_cast<size_t>(length));
    env->GetByteArrayRegion(pngArray, 0, length, reinterpret_cast<jbyte*>(bytes.data()));
    if (env->ExceptionCheck()) { env->ExceptionClear(); return JNI_FALSE; }
    std::vector<uint16_t> samples;
    int width = 0, height = 0;
    if (!decodeGray16Png(bytes.data(), bytes.size(), samples, width, height) ||
        width != 129 || height != 129 || samples.size() != 129u * 129u) return JNI_FALSE;
    std::vector<float> heights(samples.size());
    for (size_t i = 0; i < samples.size(); ++i)
        heights[i] = -12.0f + (static_cast<float>(samples[i]) / 65535.0f) * 36.0f;
    recordUndo(true);
    gLandscape.initialize(129, 129, 0.5f, -32.0f, -36.0f);
    if (!gLandscape.setBaseHeights(heights)) return JNI_FALSE;
    return syncLandscapeMeshFromData() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jfloatArray JNICALL
Java_com_novaingine_v2_NativeBridge_nativeGetTerrainHeights(JNIEnv* env, jclass) {
    std::lock_guard<std::mutex> lock(engineMutex);
    if (!gInitialized || !gLandscape.valid()) return nullptr;
    const auto heights = gLandscape.heights();
    jfloatArray result = env->NewFloatArray(static_cast<jsize>(heights.size()));
    if (result) env->SetFloatArrayRegion(result, 0, static_cast<jsize>(heights.size()), heights.data());
    return result;
}

JNIEXPORT jboolean JNICALL
Java_com_novaingine_v2_NativeBridge_nativeSculptTerrainAtScreen(
    JNIEnv*, jclass, jfloat screenX, jfloat screenY,
    jfloat radiusValue, jfloat strengthValue, jint modeValue, jint phase
) {
    std::lock_guard<std::mutex> lock(engineMutex);
    if(!gInitialized||(!gLandscape.valid()&&gLandscapeWorldTiles.empty()))return JNI_FALSE;
    if(phase==1){gTerrainStrokeActive=true;gTerrainStrokeHasUndo=false;}
    if(phase==2){gTerrainStrokeActive=false;gTerrainStrokeHasUndo=false;gRenderer.setSculptCursor(false,nova::Vec3(),nova::Vec3(0,1,0),0.18f);return JNI_TRUE;}
    float hitX=0,hitZ=0,hitY=0;
    if(!terrainScreenHit(screenX,screenY,hitX,hitZ,hitY)){gRenderer.setSculptCursor(false,nova::Vec3(),nova::Vec3(0,1,0),0.18f);return JNI_FALSE;}
    const float radius=std::clamp(static_cast<float>(radiusValue),0.5f,24.0f);
    const float strength=std::clamp(static_cast<float>(strengthValue),0.005f,2.5f);
    const int mode=std::clamp(static_cast<int>(modeValue),0,6);
    if(!gTerrainStrokeActive||!gTerrainStrokeHasUndo){recordUndo(true);if(gTerrainStrokeActive)gTerrainStrokeHasUndo=true;}
    nova::LandscapeSystem* target = landscapeAtWorld(hitX, hitZ);
    if (!target) return JNI_FALSE;
    const bool changed = target->sculpt(hitX, hitZ, radius, strength, mode);
    gRenderer.setSculptCursor(true,nova::Vec3(hitX,hitY+0.02f,hitZ),nova::Vec3(0,1,0),radius);
    if(!changed)return JNI_FALSE;
    return syncLandscapeAtWorld(hitX, hitZ)?JNI_TRUE:JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_novaingine_v2_NativeBridge_nativePaintTerrainAtScreen(
    JNIEnv*, jclass, jfloat screenX, jfloat screenY,
    jfloat radiusValue, jfloat strengthValue, jint material, jint phase
) {
    std::lock_guard<std::mutex> lock(engineMutex);
    if(!gInitialized||(!gLandscape.valid()&&gLandscapeWorldTiles.empty()))return JNI_FALSE;
    if(phase==1){gTerrainStrokeActive=true;gTerrainStrokeHasUndo=false;}
    if(phase==2){gTerrainStrokeActive=false;gTerrainStrokeHasUndo=false;gRenderer.setSculptCursor(false,nova::Vec3(),nova::Vec3(0,1,0),0.18f);return JNI_TRUE;}
    float x=0,z=0,y=0;if(!terrainScreenHit(screenX,screenY,x,z,y))return JNI_FALSE;
    if(!gTerrainStrokeActive||!gTerrainStrokeHasUndo){recordUndo(true);if(gTerrainStrokeActive)gTerrainStrokeHasUndo=true;}
    const float radius=std::clamp(static_cast<float>(radiusValue),0.5f,24.0f);
    const float strength=std::clamp(static_cast<float>(strengthValue),0.0f,1.0f);
    const int layer=std::clamp(static_cast<int>(material),0,3);
    nova::LandscapeSystem* target = landscapeAtWorld(x, z);
    if (!target) return JNI_FALSE;
    bool changed = target->paint(x,z,radius,strength,layer);
    gRenderer.setSculptCursor(true,nova::Vec3(x,y+0.02f,z),nova::Vec3(0,1,0),radius);
    return changed&&syncLandscapeAtWorld(x,z)?JNI_TRUE:JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_novaingine_v2_NativeBridge_nativeRunTerrainErosion(
    JNIEnv*, jclass, jint type, jint iterations, jfloat strength
) {
    std::lock_guard<std::mutex> lock(engineMutex);
    if(!gInitialized||!gLandscape.valid())return JNI_FALSE;
    recordUndo(true);
    bool ok=false;
    if(type==0)ok=gLandscape.hydraulicErosion(iterations,0.8f,2.2f,
        std::clamp(static_cast<float>(strength),0.001f,1.0f),0.28f);
    else ok=gLandscape.thermalErosion(iterations,
        std::clamp(0.08f+strength*0.35f,0.02f,2.0f),
        std::clamp(0.04f+strength*0.08f,0.005f,0.35f));
    return ok&&syncLandscapeMeshFromData()?JNI_TRUE:JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_com_novaingine_v2_NativeBridge_nativeAddLandscapeLayer(JNIEnv* env,jclass,jstring nameValue) {
    std::lock_guard<std::mutex> lock(engineMutex);
    if(!gInitialized||!gLandscape.valid()||!nameValue)return -1;
    const char* chars=env->GetStringUTFChars(nameValue,nullptr);
    if(!chars)return -1;std::string name(chars);env->ReleaseStringUTFChars(nameValue,chars);
    recordUndo(true);return gLandscape.addLayer(name);
}

JNIEXPORT jboolean JNICALL
Java_com_novaingine_v2_NativeBridge_nativeSelectLandscapeLayer(JNIEnv*,jclass,jint index) {
    std::lock_guard<std::mutex> lock(engineMutex);
    return gLandscape.selectLayer(index)?JNI_TRUE:JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_novaingine_v2_NativeBridge_nativeSetLandscapeLayerState(
    JNIEnv*,jclass,jint index,jboolean visible,jboolean locked,jfloat heightAlpha,jfloat paintAlpha
) {
    std::lock_guard<std::mutex> lock(engineMutex);
    if(!gInitialized)return JNI_FALSE;recordUndo(true);
    bool ok=gLandscape.setLayerState(index,visible==JNI_TRUE,locked==JNI_TRUE,heightAlpha,paintAlpha);
    return ok&&syncLandscapeMeshFromData()?JNI_TRUE:JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_novaingine_v2_NativeBridge_nativeMoveLandscapeLayer(JNIEnv*,jclass,jint from,jint to) {
    std::lock_guard<std::mutex> lock(engineMutex);if(!gInitialized)return JNI_FALSE;
    recordUndo(true);bool ok=gLandscape.moveLayer(from,to);
    return ok&&syncLandscapeMeshFromData()?JNI_TRUE:JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_novaingine_v2_NativeBridge_nativeDeleteLandscapeLayer(JNIEnv*,jclass,jint index) {
    std::lock_guard<std::mutex> lock(engineMutex);if(!gInitialized)return JNI_FALSE;
    recordUndo(true);bool ok=gLandscape.deleteLayer(index);
    return ok&&syncLandscapeMeshFromData()?JNI_TRUE:JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_novaingine_v2_NativeBridge_nativeSetTerrainHoleAtScreen(
    JNIEnv*,jclass,jfloat screenX,jfloat screenY,jboolean makeHole
) {
    std::lock_guard<std::mutex> lock(engineMutex);
    if(!gInitialized||(!gLandscape.valid()&&gLandscapeWorldTiles.empty()))return JNI_FALSE;
    float x=0,z=0,y=0;if(!terrainScreenHit(screenX,screenY,x,z,y,true))return JNI_FALSE;
    nova::LandscapeSystem* target = landscapeAtWorld(x,z);
    if (!target) return JNI_FALSE;
    const int gx=std::clamp(static_cast<int>((x-target->originX())/target->spacing()),0,target->width()-2);
    const int gz=std::clamp(static_cast<int>((z-target->originZ())/target->spacing()),0,target->height()-2);
    recordUndo(true);
    return target->setHole(gx,gz,makeHole==JNI_TRUE)&&syncLandscapeAtWorld(x,z)?JNI_TRUE:JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_novaingine_v2_NativeBridge_nativeAddLandscapeSpline(
    JNIEnv* env,jclass,jfloatArray pointArray
) {
    std::lock_guard<std::mutex> lock(engineMutex);
    if(!gInitialized||(!gLandscape.valid()&&gLandscapeWorldTiles.empty())||!pointArray)return JNI_FALSE;
    const jsize count=env->GetArrayLength(pointArray);
    if(count<6||count%3!=0||count>12288)return JNI_FALSE;
    std::vector<jfloat> values(static_cast<size_t>(count));
    env->GetFloatArrayRegion(pointArray,0,count,values.data());
    if(env->ExceptionCheck()){env->ExceptionClear();return JNI_FALSE;}
    std::vector<nova::LandscapeSystem::SplinePoint> points;
    points.reserve(static_cast<size_t>(count/3));
    for(jsize i=0;i<count;i+=3)points.push_back({values[i],values[i+1],std::clamp(values[i+2],0.5f,32.0f)});
    nova::LandscapeSystem* target = landscapeAtWorld(points.front().x, points.front().z);
    if (!target) return JNI_FALSE;
    for (const auto& point : points) {
        const float gx = (point.x - target->originX()) / target->spacing();
        const float gz = (point.z - target->originZ()) / target->spacing();
        if (gx < 0.0f || gz < 0.0f || gx > target->width()-1 || gz > target->height()-1)
            return JNI_FALSE;
    }
    recordUndo(true);
    return target->addSpline(points)&&syncLandscapeAtWorld(points.front().x,points.front().z)?JNI_TRUE:JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_com_novaingine_v2_NativeBridge_nativeGetLandscapeLayerCount(JNIEnv*,jclass) {
    std::lock_guard<std::mutex> lock(engineMutex);return static_cast<jint>(gLandscape.layers().size());
}
JNIEXPORT jstring JNICALL
Java_com_novaingine_v2_NativeBridge_nativeGetLandscapeLayerName(JNIEnv* env,jclass,jint index) {
    std::lock_guard<std::mutex> lock(engineMutex);
    const auto& layers=gLandscape.layers();if(index<0||index>=static_cast<jint>(layers.size()))return nullptr;
    return env->NewStringUTF(layers[static_cast<size_t>(index)].name.c_str());
}

JNIEXPORT jfloatArray JNICALL
Java_com_novaingine_v2_NativeBridge_nativeGetLandscapeLayerState(JNIEnv* env,jclass,jint index) {
    std::lock_guard<std::mutex> lock(engineMutex);
    const auto& layers=gLandscape.layers();
    if(index<0||index>=static_cast<jint>(layers.size()))return nullptr;
    const auto& layer=layers[static_cast<size_t>(index)];
    const jfloat values[4]={layer.visible?1.0f:0.0f,layer.locked?1.0f:0.0f,layer.heightAlpha,layer.paintAlpha};
    jfloatArray result=env->NewFloatArray(4);if(result)env->SetFloatArrayRegion(result,0,4,values);return result;
}

JNIEXPORT jint JNICALL
Java_com_novaingine_v2_NativeBridge_nativeGetLandscapeChunkCount(JNIEnv*,jclass) {
    std::lock_guard<std::mutex> lock(engineMutex);
    size_t count = gLandscape.valid() ? gLandscape.chunks().size() : 0;
    for (const auto& tile : gLandscapeWorldTiles) count += tile.second.data.chunks().size();
    return static_cast<jint>(count);
}

JNIEXPORT jboolean JNICALL
Java_com_novaingine_v2_NativeBridge_nativeSetLandscapeChunkSize(JNIEnv*,jclass,jint size) {
    std::lock_guard<std::mutex> lock(engineMutex);
    if(!gLandscape.valid() && gLandscapeWorldTiles.empty())return JNI_FALSE;
    recordUndo(true);
    gLandscapeChunkSize = std::clamp(static_cast<int>(size), 8, 128);
    if (gLandscape.valid()) {
        removeLandscapeChunkObjects();
        gLandscape.buildChunks(gLandscapeChunkSize);
    }
    for (auto& tile : gLandscapeWorldTiles) tile.second.data.buildChunks(gLandscapeChunkSize);
    gTerrainChunksDirty = true;
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_novaingine_v2_NativeBridge_nativeSetSelectedRigidBody(
    JNIEnv*,jclass,jboolean enabled,jfloat mass,jfloat restitution,jfloat friction
) {
    std::lock_guard<std::mutex> lock(engineMutex);
    if(!gInitialized)return JNI_FALSE;
    nova::SceneObject* selected=gScene.selected();if(!selected||selected->name=="Landscape Terrain")return JNI_FALSE;
    if(enabled==JNI_TRUE){
        LandscapeRigidBody body;
        body.mass=std::clamp(static_cast<float>(mass),0.05f,1000.0f);
        body.restitution=std::clamp(static_cast<float>(restitution),0.0f,0.9f);
        body.friction=std::clamp(static_cast<float>(friction),0.0f,2.0f);
        gLandscapeRigidBodies[selected->id]=body;
    }else gLandscapeRigidBodies.erase(selected->id);
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_novaingine_v2_NativeBridge_nativeCreateLandscapeCave(
    JNIEnv* env,jclass,jfloatArray pathArray,jfloat radiusValue
) {
    std::lock_guard<std::mutex> lock(engineMutex);
    if(!gInitialized||!gLandscape.valid()||!pathArray)return JNI_FALSE;
    const jsize count=env->GetArrayLength(pathArray);
    if(count<6||count%3!=0||count>3072)return JNI_FALSE;
    std::vector<jfloat> raw(static_cast<size_t>(count));
    env->GetFloatArrayRegion(pathArray,0,count,raw.data());
    if(env->ExceptionCheck()){env->ExceptionClear();return JNI_FALSE;}
    std::vector<nova::Vec3> path;
    for(jsize i=0;i<count;i+=3)path.emplace_back(raw[i],raw[i+1],raw[i+2]);
    const float radius=std::clamp(static_cast<float>(radiusValue),0.5f,8.0f);
    recordUndo(true);
    constexpr int sides=16;
    std::vector<nova::Vertex> vertices;
    std::vector<uint32_t> indices;
    vertices.reserve(path.size()*sides);
    for(size_t i=0;i<path.size();++i){
        nova::Vec3 tangent;
        if(i==0)tangent=nova::normalize(path[1]-path[0]);
        else if(i+1==path.size())tangent=nova::normalize(path[i]-path[i-1]);
        else tangent=nova::normalize(path[i+1]-path[i-1]);
        nova::Vec3 reference=std::fabs(tangent.y)<0.92f?nova::Vec3(0,1,0):nova::Vec3(1,0,0);
        nova::Vec3 right=nova::normalize(nova::cross(tangent,reference));
        nova::Vec3 up=nova::normalize(nova::cross(right,tangent));
        for(int side=0;side<sides;++side){
            const float angle=6.28318530718f*side/sides;
            nova::Vec3 offset=(right*std::cos(angle)+up*std::sin(angle))*radius;
            vertices.emplace_back(path[i]+offset,nova::Vec3(0,1,0),
                static_cast<float>(side)/sides,static_cast<float>(i)/(path.size()-1));
        }
    }
    for(size_t i=0;i+1<path.size();++i)for(int side=0;side<sides;++side){
        const uint32_t a=static_cast<uint32_t>(i*sides+side);
        const uint32_t b=static_cast<uint32_t>(i*sides+(side+1)%sides);
        const uint32_t c=a+sides,d=b+sides;
        indices.insert(indices.end(),{a,c,b,b,c,d});
    }
    nova::Mesh caveMesh(std::move(vertices),std::move(indices));
    caveMesh.recalculateNormals();if(!caveMesh.isValid())return JNI_FALSE;
    nova::Transform transform;
    const nova::Color caveColor(0.16f,0.14f,0.12f,1.0f);
    const uint32_t caveId=gScene.addObject("Landscape Cave Tunnel",nova::PrimitiveType::Sphere,transform,caveColor);
    nova::SceneObject* cave=gScene.find(caveId);if(!cave)return JNI_FALSE;
    cave->material.baseColor=nova::Vec3(caveColor.r,caveColor.g,caveColor.b);
    cave->material.roughness=0.98f;cave->material.metallic=0.0f;
    gCustomMeshes[caveId]=std::move(caveMesh);
    if(!gRenderer.uploadMesh(caveId,gCustomMeshes[caveId])){gCustomMeshes.erase(caveId);gScene.removeObject(caveId);return JNI_FALSE;}

    for(const nova::Vec3& endPoint:{path.front(),path.back()}){
        const int cx=std::clamp(static_cast<int>((endPoint.x+32.0f)/0.5f),0,127);
        const int cz=std::clamp(static_cast<int>((endPoint.z+36.0f)/0.5f),0,127);
        const int cells=std::max(1,static_cast<int>(std::ceil(radius/0.5f)));
        for(int z=std::max(0,cz-cells);z<=std::min(127,cz+cells);++z)
            for(int x=std::max(0,cx-cells);x<=std::min(127,cx+cells);++x){
                const float dx=(x-cx)*0.5f,dz=(z-cz)*0.5f;
                if(dx*dx+dz*dz<=radius*radius)gLandscape.setHole(x,z,true);
            }
    }
    syncLandscapeMeshFromData();
    return JNI_TRUE;
}

JNIEXPORT jint JNICALL
Java_com_novaingine_v2_NativeBridge_nativeGetFps(
    JNIEnv*,
    jclass
) {
    std::lock_guard<std::mutex> lock(engineMutex);

    if (!gInitialized) {
        return 0;
    }

    return static_cast<jint>(gRenderer.statistics().fps);
}

JNIEXPORT jint JNICALL
Java_com_novaingine_v2_NativeBridge_nativeGetDrawCalls(
    JNIEnv*,
    jclass
) {
    std::lock_guard<std::mutex> lock(engineMutex);

    if (!gInitialized) {
        return 0;
    }

    return static_cast<jint>(
        gRenderer.statistics().drawCalls
    );
}

JNIEXPORT jint JNICALL
Java_com_novaingine_v2_NativeBridge_nativeGetTriangles(
    JNIEnv*,
    jclass
) {
    std::lock_guard<std::mutex> lock(engineMutex);

    if (!gInitialized) {
        return 0;
    }

    return static_cast<jint>(
        gRenderer.statistics().triangles
    );
}

JNIEXPORT jboolean JNICALL
Java_com_novaingine_v2_NativeBridge_nativeSetVisualScript(
    JNIEnv* env, jclass, jintArray nodeData, jfloatArray nodeValues, jboolean enabled
) {
    std::lock_guard<std::mutex> lock(engineMutex);
    if (!gInitialized) return JNI_FALSE;
    if (enabled != JNI_TRUE || !nodeData || !nodeValues) {
        gVisualScript.nodes.clear(); gVisualScript.enabled = false; gVisualScriptStarted = false;
        return JNI_TRUE;
    }
    const jsize dataLength = env->GetArrayLength(nodeData);
    const jsize valueLength = env->GetArrayLength(nodeValues);
    if (dataLength <= 0 || dataLength % 6 != 0 || valueLength != (dataLength / 6) * 6 || dataLength / 6 > 128) return JNI_FALSE;
    std::vector<jint> data(static_cast<size_t>(dataLength));
    std::vector<jfloat> values(static_cast<size_t>(valueLength));
    env->GetIntArrayRegion(nodeData, 0, dataLength, data.data());
    env->GetFloatArrayRegion(nodeValues, 0, valueLength, values.data());
    if (env->ExceptionCheck()) { env->ExceptionClear(); return JNI_FALSE; }
    nova::VisualScript next;
    next.enabled = true; next.runOnBeginPlay = true; next.runOnTick = true;
    next.nodes.reserve(static_cast<size_t>(dataLength / 6));
    for (jsize i = 0; i < dataLength / 6; ++i) {
        const size_t d = static_cast<size_t>(i) * 6;
        const size_t v = static_cast<size_t>(i) * 6;
        if (data[d] <= 0 || data[d + 1] < 0 || data[d + 1] > 8) return JNI_FALSE;
        nova::VisualNode node;
        node.id = static_cast<uint32_t>(data[d]);
        node.type = static_cast<nova::VisualNodeType>(data[d + 1]);
        node.next = static_cast<uint32_t>(std::max(0, data[d + 2]));
        node.trueNext = static_cast<uint32_t>(std::max(0, data[d + 3]));
        node.falseNext = static_cast<uint32_t>(std::max(0, data[d + 4]));
        node.objectId = static_cast<uint32_t>(std::max(0, data[d + 5]));
        node.vectorValue = nova::Vec3(values[v], values[v + 1], values[v + 2]);
        node.value = values[v + 3]; node.compareValue = values[v + 4]; node.condition = values[v + 5] >= 0.5f;
        next.nodes.push_back(node);
    }
    gVisualScript = std::move(next);
    gVisualScriptStarted = false;
    return JNI_TRUE;
}
JNIEXPORT jboolean JNICALL
Java_com_novaingine_v2_NativeBridge_nativeUndo(
    JNIEnv*, jclass
) {
    std::lock_guard<std::mutex> lock(engineMutex);
    return (gInitialized && applyUndo(true)) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_novaingine_v2_NativeBridge_nativeRedo(
    JNIEnv*, jclass
) {
    std::lock_guard<std::mutex> lock(engineMutex);
    return (gInitialized && applyUndo(false)) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_novaingine_v2_NativeBridge_nativeCreateEmptyScene(
    JNIEnv*, jclass
) {
    std::lock_guard<std::mutex> lock(engineMutex);
    if (!gInitialized) return;
    recordUndo(true);
    gSculptMode = false;
    gSculptStrokeActive = false;
    gHasPreviousSculptHit = false;
    { std::lock_guard<std::mutex> dataLock(gSculptDataMutex); gSculptMesh.reset(); }
    { std::lock_guard<std::mutex> qlock(gSculptQueueMutex); gSculptQueue.clear(); }
    gSculptMeshDirty.store(false, std::memory_order_release);
    gSculptMaskDirty.store(false, std::memory_order_release);
    gSculptMeshId = 0;
    gRenderer.setSculptCursor(false, nova::Vec3(), nova::Vec3(0,1,0), 0.18f);
    gScene.clear();
    gCustomMeshes.clear();
    gSculptMasks.clear();
    gVisualScript = nova::VisualScript();
    gVisualScriptStarted = false;
    gUndoStack.clear();
    gRedoStack.clear();
    gRenderer.releaseAllMeshes();
    gCamera.reset();
    gPhysicsEnabled = false;
    gVerticalVelocity = 0.0f;
    gPhysicsGrounded = false;
    gJumpWasPressed = false;
}

JNIEXPORT void JNICALL
Java_com_novaingine_v2_NativeBridge_nativeResetScene(
    JNIEnv*,
    jclass
) {
    std::lock_guard<std::mutex> lock(engineMutex);

    if (!gInitialized) {
        return;
    }

    recordUndo(true);
    gSculptMode = false;
    gSculptStrokeActive = false;
    gHasPreviousSculptHit = false;
    { std::lock_guard<std::mutex> dataLock(gSculptDataMutex); gSculptMesh.reset(); }
    { std::lock_guard<std::mutex> qlock(gSculptQueueMutex); gSculptQueue.clear(); }
    gSculptMeshDirty.store(false,std::memory_order_release);
    gSculptMeshId = 0;
    gRenderer.setSculptCursor(false, nova::Vec3(), nova::Vec3(0,1,0), 0.18f);
    nova::SceneFactory::buildShowcaseScene(gScene);
    gCustomMeshes.clear();
    gSculptMasks.clear();
    gRenderer.releaseAllMeshes();
    gCamera.reset();
    gVerticalVelocity = 0.0f;
    gPhysicsGrounded = false;
    gJumpWasPressed = false;

    NOVA_LOG(
        "Scene reset. Objects: %zu",
        gScene.objectCount()
    );
}


JNIEXPORT jint JNICALL
Java_com_novaingine_v2_NativeBridge_nativeModelSelectedMesh(
    JNIEnv*, jclass, jint operationValue, jint elementIndex, jfloat amountValue
) {
    std::lock_guard<std::mutex> lock(engineMutex);
    if (!gInitialized) return -2;
    const uint32_t id = gScene.selectedId();
    auto found = gCustomMeshes.find(id);
    if (id == 0 || found == gCustomMeshes.end()) return -1;
    const int operation = std::clamp(static_cast<int>(operationValue), 0, 6);
    const int element = std::clamp(static_cast<int>(elementIndex), 0, 300000);
    const float amount = std::clamp(static_cast<float>(amountValue), 0.001f, 5.0f);
    nova::Mesh edited = found->second;
    bool changed = false;
    switch (operation) {
        case 0: changed = nova::MeshModeling::extrudeFace(edited, static_cast<size_t>(element), amount); break;
        case 1: changed = nova::MeshModeling::insetFace(edited, static_cast<size_t>(element), std::clamp(amount, 0.01f, 0.90f)); break;
        case 2: changed = nova::MeshModeling::bevelEdge(edited, static_cast<size_t>(element), amount); break;
        case 3: changed = nova::MeshModeling::loopCut(edited); break;
        case 4: changed = nova::MeshModeling::subdivide(edited, std::clamp(element, 1, 3)); break;
        case 5: changed = nova::MeshModeling::mergeNearby(edited, amount); break;
        case 6: changed = nova::MeshModeling::knifeTriangle(edited, static_cast<size_t>(element)); break;
    }
    if (!changed || !edited.isValid()) return 0;
    if (edited.vertexCount() > 180000 || edited.triangleCount() > 300000) return -3;
    recordUndo(true);
    if (!gRenderer.uploadMesh(id, edited)) return -4;
    found->second = std::move(edited);
    gSculptMasks[id].resize(found->second.vertexCount(), 0.0f);
    return 1;
}


JNIEXPORT jint JNICALL
Java_com_novaingine_v2_NativeBridge_nativeRefineSculptMesh(
    JNIEnv*, jclass
) {
    std::lock_guard<std::mutex> engineLock(engineMutex);
    if (!gInitialized || !gSculptMode || gSculptMeshId == 0 ||
        gScene.selectedId() != gSculptMeshId || !gSculptMesh) return -1;
    if (gSculptJobsPending.load(std::memory_order_acquire) != 0) return -2;

    std::lock_guard<std::mutex> dataLock(gSculptDataMutex);
    if (!gSculptMesh) return -3;
    recordUndo(true);
    if (!gSculptMesh->subdivideOnce()) return -3;

    nova::Mesh refined;
    if (!gSculptMesh->toMesh(refined) || !refined.isValid()) return -4;
    if (!gRenderer.uploadMesh(gSculptMeshId, refined)) return -5;
    gCustomMeshes[gSculptMeshId] = std::move(refined);
    gSculptMasks[gSculptMeshId] = gSculptMesh->mask;
    gSculptMeshDirty.store(false, std::memory_order_release);
    gSculptMaskDirty.store(false, std::memory_order_release);
    return 1;
}

} // extern "C"
