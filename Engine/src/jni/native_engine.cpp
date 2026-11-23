#include <jni.h>
#include <android/log.h>

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <fstream>
#include <iomanip>
#include <mutex>
#include <condition_variable>
#include <deque>
#include <thread>
#include <atomic>
#include <unordered_map>
#include <vector>

#include "../Camera/Camera.h"
#include "../Scene/Scene.h"
#include "../Scene/SceneFactory.h"
#include "../Renderer/Renderer.h"
#include "../Physics/PhysicsWorld.h"
#include "../Geometry/Mesh.h"
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
std::unordered_map<uint32_t, nova::Mesh> gCustomMeshes;
std::unordered_map<uint32_t, std::vector<float>> gSculptMasks;
std::unique_ptr<nova::SculptMesh> gSculptMesh;
uint32_t gSculptMeshId = 0;
bool gSculptMode = false;
bool gSculptStrokeActive = false;
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
            if(!gSculptMesh)continue;
            if(job.begin){gSculptMesh->beginStroke();continue;}
            if(job.end){gSculptMesh->endStroke();continue;}
            if(gSculptMesh->applyDab(job.center,job.normal,job.delta,job.brush)) {
                if(job.brush.type==7||job.brush.type==11)gSculptMaskDirty.store(true,std::memory_order_release);
                else gSculptMeshDirty.store(true,std::memory_order_release);
            }
        }
    }).detach();
}
void enqueueSculptJob(const SculptDabJob& job) {
    {
        std::lock_guard<std::mutex> lock(gSculptQueueMutex);
        if(gSculptQueue.size()>=256)gSculptQueue.pop_front();
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
    uint32_t selectedId = 0;
};

std::vector<SceneSnapshot> gUndoStack;
std::vector<SceneSnapshot> gRedoStack;
constexpr size_t MAX_UNDO_STEPS = 64;

SceneSnapshot captureScene(bool includeCustomMeshes = false) {
    SceneSnapshot snapshot;
    snapshot.objects = gScene.getObjects();
    snapshot.includesCustomMeshes = includeCustomMeshes;
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
    for (const nova::SceneObject& object : snapshot.objects) {
        gScene.addObject(object);
    }
    if (snapshot.selectedId != 0) {
        gScene.select(snapshot.selectedId);
    }
    gRenderer.releaseAllMeshes();
    for (const auto& entry : gCustomMeshes) gRenderer.uploadMesh(entry.first, entry.second);
    {
        std::lock_guard<std::mutex> qlock(gSculptQueueMutex);
        gSculptQueue.clear();
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
    const char* path = env->GetStringUTFChars(pathValue, nullptr);
    if (!path) return JNI_FALSE;
    std::lock_guard<std::mutex> lock(engineMutex);
    std::ofstream file(path, std::ios::out | std::ios::trunc);
    env->ReleaseStringUTFChars(pathValue, path);
    if (!file) return JNI_FALSE;

    file << "NOVA_SCENE 1\n" << gScene.objectCount() << "\n";
    file << std::setprecision(9);
    for (const nova::SceneObject& o : gScene.getObjects()) {
        file << o.id << " " << static_cast<int>(o.primitive) << " "
             << std::quoted(o.name) << " "
             << o.transform.position.x << " " << o.transform.position.y << " " << o.transform.position.z << " "
             << o.transform.rotation.x << " " << o.transform.rotation.y << " " << o.transform.rotation.z << " "
             << o.transform.scale.x << " " << o.transform.scale.y << " " << o.transform.scale.z << " "
             << o.color.r << " " << o.color.g << " " << o.color.b << " " << o.color.a << " "
             << o.material.roughness << " " << o.material.metallic << " "
             << o.visible << " " << o.castsShadow << " " << o.isStatic << "\n";
    }
    return file.good() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_novaingine_v2_NativeBridge_nativeLoadScene(
    JNIEnv* env, jclass, jstring pathValue
) {
    if (!pathValue) return JNI_FALSE;
    const char* path = env->GetStringUTFChars(pathValue, nullptr);
    if (!path) return JNI_FALSE;
    std::ifstream file(path);
    env->ReleaseStringUTFChars(pathValue, path);
    if (!file) return JNI_FALSE;

    std::string magic;
    int version = 0;
    size_t count = 0;
    if (!(file >> magic >> version >> count) ||
        magic != "NOVA_SCENE" || version != 1 || count > 10000) {
        return JNI_FALSE;
    }

    nova::Scene loaded;
    for (size_t i = 0; i < count; ++i) {
        nova::SceneObject o;
        int primitive = 0;
        if (!(file >> o.id >> primitive >> std::quoted(o.name)
              >> o.transform.position.x >> o.transform.position.y >> o.transform.position.z
              >> o.transform.rotation.x >> o.transform.rotation.y >> o.transform.rotation.z
              >> o.transform.scale.x >> o.transform.scale.y >> o.transform.scale.z
              >> o.color.r >> o.color.g >> o.color.b >> o.color.a
              >> o.material.roughness >> o.material.metallic
              >> o.visible >> o.castsShadow >> o.isStatic)) {
            return JNI_FALSE;
        }
        if (o.id == 0 || primitive < 0 || primitive > 7) return JNI_FALSE;
        o.primitive = static_cast<nova::PrimitiveType>(primitive);
        o.material.baseColor = nova::Vec3(o.color.r, o.color.g, o.color.b);
        if (!loaded.addObject(o)) return JNI_FALSE;
    }

    std::lock_guard<std::mutex> lock(engineMutex);
    recordUndo(true);
    gScene = loaded;
    gCustomMeshes.clear();
    gSculptMasks.clear();
    gSculptStrokeActive = false;
    gHasPreviousSculptHit = false;
    gRenderer.releaseAllMeshes();
    gVerticalVelocity = 0.0f;
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
    const int shape = std::clamp(static_cast<int>(shapeValue), 0, 1);
    const int density = std::clamp(static_cast<int>(densityValue), 16, 128);
    nova::Mesh mesh;
    if(shape==0) {
        { std::lock_guard<std::mutex> qlock(gSculptQueueMutex); gSculptQueue.clear(); }
        { std::lock_guard<std::mutex> dataLock(gSculptDataMutex);
          gSculptMesh.reset(new nova::SculptMesh(nova::SculptMesh::cubeSphere(density,1.0f)));
          gSculptMeshId=0; }
        if(!gSculptMesh->toMesh(mesh))return 0;
        gSculptMesh->uploadChunks();
    } else {
        mesh=nova::MeshSculptor::makePlane(density);
        { std::lock_guard<std::mutex> qlock(gSculptQueueMutex); gSculptQueue.clear(); }
        std::lock_guard<std::mutex> dataLock(gSculptDataMutex);
        gSculptMesh.reset();gSculptMeshId=0;
        gSculptMeshDirty.store(false,std::memory_order_release);
        gSculptMaskDirty.store(false,std::memory_order_release);
    }
    if (!mesh.isValid()) return 0;
    recordUndo(true);
    nova::Transform transform;
    transform.position = nova::Vec3(0.0f, 1.8f, 0.0f);
    const nova::Color color(0.68f, 0.72f, 0.80f, 1.0f);
    const uint32_t id = gScene.addObject(
        shape == 0 ? "Sculpt Sphere" : "Sculpt Plane",
        shape == 0 ? nova::PrimitiveType::Sphere : nova::PrimitiveType::Plane,
        transform, color);
    if (!gRenderer.uploadMesh(id, mesh)) {
        gScene.removeObject(id);
        return 0;
    }
    gCustomMeshes[id] = std::move(mesh);
    if(shape==0) {
        std::lock_guard<std::mutex> dataLock(gSculptDataMutex);
        gSculptMeshId=id;
        gSculptMeshDirty.store(false,std::memory_order_release);
        gSculptMaskDirty.store(false,std::memory_order_release);
        gSculptMasks[id]=gSculptMesh->mask;
    } else {
        gSculptMasks[id] = std::vector<float>(gCustomMeshes[id].vertexCount(), 0.0f);
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
    nova::Vec3 localOrigin=gCamera.position-object->transform.position;
    nova::SculptMesh::Hit hit=gSculptMesh->raycast(localOrigin,ray);
    if(!hit.found) {
        gRenderer.setSculptCursor(false,nova::Vec3(),nova::Vec3(0,1,0),0.18f);return;
    }
    gRenderer.setSculptCursor(true,hit.point+object->transform.position,hit.normal,
        std::clamp((float)radiusValue,0.02f,1.0f));
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
    const nova::Vec3 localOrigin=gCamera.position-object->transform.position;
    nova::SculptMesh::Hit hit;
    {
        std::unique_lock<std::mutex> dataLock(gSculptDataMutex,std::try_to_lock);
        if(!dataLock.owns_lock())return 0;
        hit=gSculptMesh->raycast(localOrigin,ray);
    }
    if(!hit.found){gHasPreviousSculptHit=false;gRenderer.setSculptCursor(false,nova::Vec3(),nova::Vec3(0,1,0),0.18f);return 0;}
    nova::SculptMesh::Brush brush;
    brush.radius=std::clamp((float)radiusValue,0.02f,1.0f);
    brush.strength=std::clamp((float)strengthValue,0.0f,1.0f);
    brush.hardness=std::clamp((float)falloffValue,0.0f,1.0f);
    brush.pressure=std::clamp((float)pressureValue,0.0f,1.0f);
    brush.type=std::clamp((int)brushValue,0,11);
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
    gRenderer.setSculptCursor(true,hit.point+object->transform.position,hit.normal,brush.radius);
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
    const int mode = std::clamp(static_cast<int>(modeValue), 0, 3);
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

    recordUndo();
    std::vector<uint32_t> oldTerrain;
    for (const nova::SceneObject& object : gScene.getObjects()) {
        if (object.name.rfind("Terrain Tile ", 0) == 0) {
            oldTerrain.push_back(object.id);
        }
    }
    for (uint32_t id : oldTerrain) gScene.removeObject(id);

    const nova::Color grass(0.25f, 0.42f, 0.17f, 1.0f);
    for (int z = -5; z <= 5; ++z) {
        for (int x = -5; x <= 5; ++x) {
            const float height = 0.15f;
            nova::Transform transform;
            transform.position = nova::Vec3(
                static_cast<float>(x),
                -0.08f + height * 0.5f,
                -4.0f + static_cast<float>(z)
            );
            transform.scale = nova::Vec3(0.96f, height, 0.96f);
            uint32_t id = gScene.addObject(
                "Terrain Tile " + std::to_string(x) + " " + std::to_string(z),
                nova::PrimitiveType::Cube,
                transform,
                grass
            );
            nova::SceneObject* tile = gScene.find(id);
            if (tile) {
                tile->material.baseColor = nova::Vec3(grass.r, grass.g, grass.b);
                tile->material.roughness = 0.96f;
                tile->material.metallic = 0.0f;
            }
        }
    }
    gScene.deselect();
    gRenderer.releaseAllMeshes();
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_novaingine_v2_NativeBridge_nativeSculptTerrain(
    JNIEnv*, jclass, jfloat centerXValue, jfloat centerZValue,
    jfloat radiusValue, jfloat strengthValue, jint modeValue
) {
    std::lock_guard<std::mutex> lock(engineMutex);
    if (!gInitialized) return JNI_FALSE;

    const float centerX = static_cast<float>(centerXValue);
    const float centerZ = static_cast<float>(centerZValue);
    const float radius = std::clamp(static_cast<float>(radiusValue), 0.5f, 8.0f);
    const float strength = std::clamp(static_cast<float>(strengthValue), 0.02f, 1.5f);
    const int mode = std::clamp(static_cast<int>(modeValue), 0, 3);

    std::vector<nova::SceneObject*> tiles;
    for (nova::SceneObject& object : gScene.getObjects()) {
        if (object.name.rfind("Terrain Tile ", 0) == 0) tiles.push_back(&object);
    }
    if (tiles.empty()) return JNI_FALSE;

    const SceneSnapshot beforeSculpt = captureScene();
    std::vector<float> oldHeights;
    oldHeights.reserve(tiles.size());
    for (const nova::SceneObject* tile : tiles) {
        oldHeights.push_back(tile->transform.scale.y);
    }

    bool changed = false;
    for (size_t i = 0; i < tiles.size(); ++i) {
        nova::SceneObject& tile = *tiles[i];
        const float dx = tile.transform.position.x - centerX;
        const float dz = tile.transform.position.z - centerZ;
        const float distance = std::sqrt(dx * dx + dz * dz);
        if (distance > radius) continue;

        const float falloff = 1.0f - distance / radius;
        const float smoothFalloff = falloff * falloff * (3.0f - 2.0f * falloff);
        float height = oldHeights[i];

        if (mode == 0) {
            height += strength * smoothFalloff;
        } else if (mode == 1) {
            height -= strength * smoothFalloff;
        } else if (mode == 2) {
            float sum = 0.0f;
            int count = 0;
            for (size_t j = 0; j < tiles.size(); ++j) {
                const float nx = tiles[j]->transform.position.x - tile.transform.position.x;
                const float nz = tiles[j]->transform.position.z - tile.transform.position.z;
                if (nx * nx + nz * nz <= 2.1f) {
                    sum += oldHeights[j];
                    ++count;
                }
            }
            if (count > 0) {
                const float average = sum / static_cast<float>(count);
                height += (average - height) * std::clamp(strength, 0.0f, 1.0f) * smoothFalloff;
            }
        } else {
            const float targetHeight = 0.75f;
            height += (targetHeight - height) * std::clamp(strength, 0.0f, 1.0f) * smoothFalloff;
        }

        height = std::clamp(height, 0.12f, 4.5f);
        if (std::fabs(height - oldHeights[i]) > 0.0001f) {
            tile.transform.scale.y = height;
            tile.transform.position.y = -0.08f + height * 0.5f;
            changed = true;
        }
    }

    if (changed) {
        gUndoStack.push_back(beforeSculpt);
        if (gUndoStack.size() > MAX_UNDO_STEPS) {
            gUndoStack.erase(gUndoStack.begin());
        }
        gRedoStack.clear();
    }
    return changed ? JNI_TRUE : JNI_FALSE;
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
    nova::SceneFactory::buildDefaultScene(gScene);
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

} // extern "C"
