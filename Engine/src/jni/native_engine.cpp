#include <jni.h>
#include <android/log.h>

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <fstream>
#include <iomanip>
#include <mutex>

#include "../Camera/Camera.h"
#include "../Scene/Scene.h"
#include "../Scene/SceneFactory.h"
#include "../Renderer/Renderer.h"
#include "../Physics/PhysicsWorld.h"

#define NOVA_LOG_TAG "NovaEngine"
#define NOVA_LOG(...) __android_log_print(ANDROID_LOG_INFO, NOVA_LOG_TAG, __VA_ARGS__)

namespace {

std::mutex engineMutex;

Renderer gRenderer;
nova::Scene gScene;
nova::Camera gCamera;

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

bool makeScreenRay(
    float screenX,
    float screenY,
    nova::Vec3& ray
) {
    if (gViewportWidth <= 0 || gViewportHeight <= 0) {
        return false;
    }

    float nx = (2.0f * screenX / gViewportWidth) - 1.0f;
    float ny = 1.0f - (2.0f * screenY / gViewportHeight);

    float aspect =
        static_cast<float>(gViewportWidth) /
        static_cast<float>(gViewportHeight);

    float tangent = std::tan(radians(gCamera.fieldOfView) * 0.5f);

    nova::Vec3 direction =
        gCamera.forward() +
        gCamera.right() * (nx * tangent * aspect) +
        gCamera.up() * (ny * tangent);

    ray = nova::normalize(direction);

    return nova::length(ray) > 0.0001f;
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
        // Camera input is processed independently.
        // The editor scene remains stationary unless edited.
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
    gScene = loaded;
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
    object->transform.position = nova::Vec3(v[0], v[1], v[2]);
    object->transform.rotation = nova::Vec3(v[3], v[4], v[5]);
    object->transform.scale = nova::Vec3(
        std::max(0.01f, v[6]),
        std::max(0.01f, v[7]),
        std::max(0.01f, v[8])
    );
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

JNIEXPORT void JNICALL
Java_com_novaingine_v2_NativeBridge_nativeResetScene(
    JNIEnv*,
    jclass
) {
    std::lock_guard<std::mutex> lock(engineMutex);

    if (!gInitialized) {
        return;
    }

    nova::SceneFactory::buildDefaultScene(gScene);
    gCamera.reset();
    gVerticalVelocity = 0.0f;
    gPhysicsGrounded = false;
    gJumpWasPressed = false;

    NOVA_LOG(
        "Scene reset. Objects: %zu",
        gScene.objectCount()
    );
}

} // extern "C"
