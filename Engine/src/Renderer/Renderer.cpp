#include "Renderer.h"
#include "ShaderProgram.h"
#include "../Geometry/PrimitiveBuilder.h"

#include <android/log.h>
#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <limits>
#include <unordered_set>

#define NOVA_LOG_TAG "NovaRenderer"
#define NOVA_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, NOVA_LOG_TAG, __VA_ARGS__)
#define NOVA_LOGI(...) __android_log_print(ANDROID_LOG_INFO, NOVA_LOG_TAG, __VA_ARGS__)

namespace {

constexpr float PI = 3.14159265358979323846f;

struct LineVertex {
    float x, y, z;
    float r, g, b, a;
};

Mat4 multiplyMatrix(const Mat4& a, const Mat4& b) {
    Mat4 result;
    for (int column = 0; column < 4; ++column) {
        for (int row = 0; row < 4; ++row) {
            result.m[column * 4 + row] =
                a.m[0 * 4 + row] * b.m[column * 4 + 0] +
                a.m[1 * 4 + row] * b.m[column * 4 + 1] +
                a.m[2 * 4 + row] * b.m[column * 4 + 2] +
                a.m[3 * 4 + row] * b.m[column * 4 + 3];
        }
    }
    return result;
}

Mat4 makeModelMatrix(const Transform& transform) {
    const float rx = transform.rotation.x * PI / 180.0f;
    const float ry = transform.rotation.y * PI / 180.0f;
    const float rz = transform.rotation.z * PI / 180.0f;

    const float cx = std::cos(rx);
    const float sx = std::sin(rx);
    const float cy = std::cos(ry);
    const float sy = std::sin(ry);
    const float cz = std::cos(rz);
    const float sz = std::sin(rz);

    Mat4 rotationX;
    rotationX.m[5] = cx;
    rotationX.m[6] = sx;
    rotationX.m[9] = -sx;
    rotationX.m[10] = cx;

    Mat4 rotationY;
    rotationY.m[0] = cy;
    rotationY.m[2] = -sy;
    rotationY.m[8] = sy;
    rotationY.m[10] = cy;

    Mat4 rotationZ;
    rotationZ.m[0] = cz;
    rotationZ.m[1] = sz;
    rotationZ.m[4] = -sz;
    rotationZ.m[5] = cz;

    Mat4 scale;
    scale.m[0] = transform.scale.x;
    scale.m[5] = transform.scale.y;
    scale.m[10] = transform.scale.z;

    Mat4 translation;
    translation.m[12] = transform.position.x;
    translation.m[13] = transform.position.y;
    translation.m[14] = transform.position.z;

    Mat4 rotation = multiplyMatrix(
        rotationZ,
        multiplyMatrix(rotationY, rotationX)
    );

    return multiplyMatrix(
        translation,
        multiplyMatrix(rotation, scale)
    );
}

void setMatrixUniform(
    const ShaderProgram& program,
    const char* name,
    const Mat4& matrix
) {
    GLint location = glGetUniformLocation(program.id(), name);
    if (location >= 0) {
        glUniformMatrix4fv(location, 1, GL_FALSE, matrix.m);
    }
}

void setFloatUniform(
    const ShaderProgram& program,
    const char* name,
    float value
) {
    GLint location = glGetUniformLocation(program.id(), name);
    if (location >= 0) glUniform1f(location, value);
}

void setIntUniform(
    const ShaderProgram& program,
    const char* name,
    int value
) {
    GLint location = glGetUniformLocation(program.id(), name);
    if (location >= 0) glUniform1i(location, value);
}

void setVec3Uniform(
    const ShaderProgram& program,
    const char* name,
    float x,
    float y,
    float z
) {
    GLint location = glGetUniformLocation(program.id(), name);
    if (location >= 0) glUniform3f(location, x, y, z);
}

const char* sceneVertexShader = R"(
#version 300 es
precision highp float;

layout(location=0) in vec3 aPosition;
layout(location=1) in vec3 aNormal;
layout(location=2) in vec2 aUV;

uniform mat4 uViewProjection;
uniform mat4 uModel;
uniform mat4 uLightViewProjection;

out vec3 vWorldPosition;
out vec3 vNormal;
out vec2 vUV;
out vec4 vShadowPosition;

void main() {
    vec4 world = uModel * vec4(aPosition, 1.0);
    vWorldPosition = world.xyz;
    vNormal = normalize(mat3(uModel) * aNormal);
    vUV = aUV;
    vShadowPosition = uLightViewProjection * world;
    gl_Position = uViewProjection * world;
}
)";

const char* sceneFragmentShader = R"(
#version 300 es
precision highp float;

in vec3 vWorldPosition;
in vec3 vNormal;
in vec2 vUV;
in vec4 vShadowPosition;

uniform vec4 uColor;
uniform vec3 uBaseColor;
uniform float uRoughness;
uniform float uMetallic;
uniform vec3 uCameraPosition;
uniform vec3 uLightDirection;
uniform vec3 uLightColor;
uniform vec3 uAmbientColor;
uniform sampler2D uShadowMap;
uniform int uUseShadows;
uniform int uViewMode;
uniform float uShadowStrength;
uniform float uExposure;

out vec4 fragColor;

const float PI = 3.14159265358979323846;

float shadowAmount(vec4 position) {
    vec3 projected = position.xyz / position.w;
    projected = projected * 0.5 + 0.5;

    if (projected.z > 1.0 ||
        projected.x < 0.0 || projected.x > 1.0 ||
        projected.y < 0.0 || projected.y > 1.0) {
        return 0.0;
    }

    float bias = max(0.0015 * (1.0 - dot(
        normalize(vNormal),
        normalize(-uLightDirection)
    )), 0.00025);

    vec2 texel = 1.0 / vec2(textureSize(uShadowMap, 0));
    float blocked = 0.0;

    for (int x = -1; x <= 1; ++x) {
        for (int y = -1; y <= 1; ++y) {
            float depth = texture(
                uShadowMap,
                projected.xy + vec2(float(x), float(y)) * texel
            ).r;

            blocked += projected.z - bias > depth ? 1.0 : 0.0;
        }
    }

    return blocked / 9.0;
}

float distributionGGX(vec3 N, vec3 H, float roughness) {
    float a = roughness * roughness;
    float a2 = a * a;
    float NdotH = max(dot(N, H), 0.0);
    float NdotH2 = NdotH * NdotH;

    float denominator =
        NdotH2 * (a2 - 1.0) + 1.0;

    return a2 / max(
        PI * denominator * denominator,
        0.000001
    );
}

float geometrySchlickGGX(float value, float k) {
    return value / max(value * (1.0 - k) + k, 0.001);
}

float geometrySmith(vec3 N, vec3 V, vec3 L, float roughness) {
    float k = (roughness + 1.0);
    k = (k * k) / 8.0;

    float NdotV = max(dot(N, V), 0.001);
    float NdotL = max(dot(N, L), 0.001);

    return geometrySchlickGGX(NdotV, k) *
           geometrySchlickGGX(NdotL, k);
}

vec3 fresnelSchlick(float cosTheta, vec3 F0) {
    return F0 + (1.0 - F0) *
           pow(1.0 - clamp(cosTheta, 0.0, 1.0), 5.0);
}

void main() {
    vec3 N = normalize(vNormal);
    vec3 L = normalize(-uLightDirection);
    vec3 V = normalize(uCameraPosition - vWorldPosition);
    vec3 H = normalize(V + L);

    float rawNdotL = max(dot(N, L), 0.0);
    float NdotV = max(dot(N, V), 0.001);
    float NdotL = max(dot(N, L), 0.001);

    vec3 albedo = pow(max(uBaseColor, vec3(0.0)), vec3(2.2));

    float roughness = clamp(uRoughness, 0.04, 1.0);
    float metallic = clamp(uMetallic, 0.0, 1.0);

    // Material inspection modes.
    if (uViewMode == 5) {
        fragColor = vec4(N * 0.5 + 0.5, 1.0);
        return;
    }

    if (uViewMode == 6) {
        fragColor = vec4(vec3(roughness), 1.0);
        return;
    }

    if (uViewMode == 7) {
        fragColor = vec4(vec3(metallic), 1.0);
        return;
    }

    // Unlit mode.
    if (uViewMode == 1) {
        vec3 unlit = albedo * uExposure;
        unlit = (unlit * (2.51 * unlit + 0.03)) /
                (unlit * (2.43 * unlit + 0.59) + 0.14);
        unlit = clamp(unlit, 0.0, 1.0);
        unlit = pow(unlit, vec3(1.0 / 2.2));
        fragColor = vec4(unlit, uColor.a);
        return;
    }

    vec3 skyColor = pow(
        vec3(0.4314, 0.6078, 0.8392),
        vec3(2.2)
    );

    vec3 groundColor = pow(
        vec3(0.4784, 0.4549, 0.4078),
        vec3(2.2)
    );

    vec3 hemiColor = mix(
        groundColor,
        skyColor,
        N.y * 0.5 + 0.5
    );

    vec3 F0 = mix(vec3(0.04), albedo, metallic);
    vec3 F = fresnelSchlick(max(dot(H, V), 0.0), F0);

    float D = distributionGGX(N, H, roughness);
    float G = geometrySmith(N, V, L, roughness);

    vec3 specular = (D * G * F) /
        max(4.0 * NdotV * NdotL, 0.001);

    vec3 kd = (1.0 - F) * (1.0 - metallic);

    vec3 sunColor = pow(
        vec3(1.0, 0.9569, 0.8784),
        vec3(2.2)
    );

    float sunIntensity = 3.0;

    vec3 direct =
        (kd * albedo / PI + specular) *
        sunColor *
        sunIntensity *
        rawNdotL;

    float shadow = 0.0;

    if (uUseShadows == 1) {
        shadow = shadowAmount(vShadowPosition) * uShadowStrength;
    }

    direct *= (1.0 - shadow);

    vec3 ambient =
        hemiColor * albedo * (1.0 - metallic);

    // Small ambient specular keeps metallic surfaces visible.
    ambient += F0 * 0.03;

    vec3 result = ambient + direct;

    if (uViewMode == 3) {
        result = vec3(rawNdotL);
    } else if (uViewMode == 4) {
        result = ambient + direct;
    }

    result *= uExposure;

    // Preserve the existing ACES tone mapping and sRGB conversion.
    result = (
        result * (2.51 * result + 0.03)
    ) / (
        result * (2.43 * result + 0.59) + 0.14
    );

    result = clamp(result, 0.0, 1.0);
    result = pow(result, vec3(1.0 / 2.2));

    fragColor = vec4(result, uColor.a);
}
)";

const char* shadowVertexShader = R"(
#version 300 es
precision highp float;
layout(location=0) in vec3 aPosition;
uniform mat4 uLightViewProjection;
uniform mat4 uModel;
void main() {
    gl_Position = uLightViewProjection * uModel * vec4(aPosition, 1.0);
}
)";

const char* shadowFragmentShader = R"(
#version 300 es
precision highp float;
void main() {}
)";

const char* selectionVertexShader = R"(
#version 300 es
precision highp float;
layout(location=0) in vec3 aPosition;
uniform mat4 uViewProjection;
uniform mat4 uModel;
void main() {
    gl_Position = uViewProjection * uModel * vec4(aPosition, 1.0);
}
)";

const char* selectionFragmentShader = R"(
#version 300 es
precision highp float;
out vec4 fragColor;
void main() {
    fragColor = vec4(1.0);
}
)";

const char* fullscreenVertexShader = R"(
#version 300 es
precision highp float;
layout(location=0) in vec2 aPosition;
out vec2 vUV;
void main() {
    vUV = aPosition * 0.5 + 0.5;
    gl_Position = vec4(aPosition, 0.0, 1.0);
}
)";

const char* outlineFragmentShader = R"(
#version 300 es
precision highp float;
in vec2 vUV;

uniform sampler2D uScene;
uniform sampler2D uMask;
uniform vec2 uTexelSize;
uniform float uThickness;
uniform int uHasSelection;

out vec4 fragColor;

void main() {
    vec4 sceneColor = texture(uScene, vUV);

    if (uHasSelection == 0) {
        fragColor = sceneColor;
        return;
    }

    float center = texture(uMask, vUV).r;
    float edge = 0.0;

    for (int x = -3; x <= 3; ++x) {
        for (int y = -3; y <= 3; ++y) {
            vec2 offset = vec2(float(x), float(y)) *
                          uTexelSize * uThickness;

            float sampleValue = texture(uMask, vUV + offset).r;
            edge = max(edge, sampleValue);
        }
    }

    float outline = max(edge - center, 0.0);
    vec3 orange = vec3(1.0, 0.36, 0.0);

    fragColor = vec4(
        mix(sceneColor.rgb, orange, clamp(outline, 0.0, 1.0)),
        sceneColor.a
    );
}
)";

const char* skyFragmentShader = R"(
#version 300 es
precision highp float;
in vec2 vUV;
out vec4 fragColor;

void main() {
    float height = clamp(vUV.y, 0.0, 1.0);

    vec3 horizon = vec3(0.66, 0.80, 0.91);
    vec3 zenith = vec3(0.16, 0.34, 0.62);

    vec3 sky = mix(horizon, zenith, smoothstep(0.0, 1.0, height));

    float cloudNoise =
        sin(vUV.x * 18.0 + sin(vUV.y * 8.0)) *
        sin(vUV.y * 22.0 + vUV.x * 5.0);

    float clouds = smoothstep(0.62, 0.92, cloudNoise) *
                   smoothstep(0.20, 0.75, height);

    sky = mix(sky, vec3(0.88, 0.92, 0.96), clouds * 0.22);

    fragColor = vec4(sky, 1.0);
}
)";

const char* gridVertexShader = R"(
#version 300 es
precision highp float;
layout(location=0) in vec3 aPosition;
uniform mat4 uViewProjection;
void main() {
    gl_Position = uViewProjection * vec4(aPosition, 1.0);
}
)";

const char* gridFragmentShader = R"(
#version 300 es
precision highp float;
uniform vec4 uGridColor;
out vec4 fragColor;
void main() {
    fragColor = uGridColor;
}
)";

const char* lineVertexShader = R"(
#version 300 es
precision highp float;
layout(location=0) in vec3 aPosition;
layout(location=1) in vec4 aColor;
uniform mat4 uViewProjection;
out vec4 vColor;
void main() {
    vColor = aColor;
    gl_Position = uViewProjection * vec4(aPosition, 1.0);
}
)";

const char* lineFragmentShader = R"(
#version 300 es
precision highp float;
in vec4 vColor;
out vec4 fragColor;
void main() {
    fragColor = vColor;
}
)";

Mesh buildMeshForObject(const SceneObject& object) {
    switch (object.primitive) {
        case PrimitiveType::Cube:
            return nova::PrimitiveBuilder::createCube();
        case PrimitiveType::Cylinder:
            return nova::PrimitiveBuilder::createCylinder(32, true);
        case PrimitiveType::Ramp:
            return nova::PrimitiveBuilder::createRamp();
        case PrimitiveType::Wedge:
            return nova::PrimitiveBuilder::createWedge();
        case PrimitiveType::Sphere:
            return nova::PrimitiveBuilder::createSphere(16, 24);
        case PrimitiveType::Plane:
            return nova::PrimitiveBuilder::createPlane();
        case PrimitiveType::HalfCylinder:
            return nova::PrimitiveBuilder::createHalfCylinder(32);
        case PrimitiveType::Capsule:
            return nova::PrimitiveBuilder::createCapsule(20);
        default:
            return nova::PrimitiveBuilder::createCube();
    }
}

} // anonymous namespace

Renderer::Renderer() = default;

Renderer::~Renderer() {
    shutdown();
}

bool Renderer::initialize() {
    if (initialized_) return true;

    const char* version = reinterpret_cast<const char*>(glGetString(GL_VERSION));
    if (!version) {
        NOVA_LOGE("No active OpenGL ES context");
        return false;
    }

    NOVA_LOGI("Initializing renderer: %s", version);

    glEnable(GL_DEPTH_TEST);
    glDepthFunc(GL_LEQUAL);
    glEnable(GL_CULL_FACE);
    glCullFace(GL_BACK);
    glFrontFace(GL_CCW);
    glDisable(GL_BLEND);
    glClearDepthf(1.0f);

    if (!createPrograms()) {
        NOVA_LOGE("Shader program creation failed");
        shutdown();
        return false;
    }

    createFullscreenQuad();
    createGridGeometry();
    createLineGeometry();

    glGenTextures(1, &whiteTexture_);
    glBindTexture(GL_TEXTURE_2D, whiteTexture_);
    const unsigned char white[4] = {255, 255, 255, 255};
    glTexImage2D(
        GL_TEXTURE_2D, 0, GL_RGBA8, 1, 1, 0,
        GL_RGBA, GL_UNSIGNED_BYTE, white
    );
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);

    initialized_ = true;

    if (!createRenderTargets()) {
        NOVA_LOGE("Render target creation failed");
        shutdown();
        return false;
    }

    resize(width_, height_);
    NOVA_LOGI("Renderer initialized");
    return true;
}

void Renderer::shutdown() {
    if (!initialized_ &&
        !sceneProgram_.valid() &&
        fullscreenVAO_ == 0) {
        return;
    }

    releaseAllMeshes();
    destroyRenderTargets();
    destroySelectionTarget();
    destroyShadowTarget();
    destroyPrograms();
    destroyFullscreenQuad();
    destroyGridGeometry();
    destroyLineGeometry();

    if (whiteTexture_) {
        glDeleteTextures(1, &whiteTexture_);
        whiteTexture_ = 0;
    }

    initialized_ = false;
}

bool Renderer::createPrograms() {
    bool success = true;

    success &= sceneProgram_.create(sceneVertexShader, sceneFragmentShader);
    success &= shadowProgram_.create(shadowVertexShader, shadowFragmentShader);
    success &= selectionMaskProgram_.create(selectionVertexShader, selectionFragmentShader);
    success &= outlineProgram_.create(fullscreenVertexShader, outlineFragmentShader);
    success &= compositeProgram_.create(fullscreenVertexShader, outlineFragmentShader);
    success &= skyProgram_.create(fullscreenVertexShader, skyFragmentShader);
    success &= gridProgram_.create(gridVertexShader, gridFragmentShader);
    success &= lineProgram_.create(lineVertexShader, lineFragmentShader);

    return success;
}

void Renderer::destroyPrograms() {
    sceneProgram_.destroy();
    shadowProgram_.destroy();
    selectionMaskProgram_.destroy();
    outlineProgram_.destroy();
    compositeProgram_.destroy();
    skyProgram_.destroy();
    gridProgram_.destroy();
    lineProgram_.destroy();
}

bool Renderer::createRenderTargets() {
    if (!resizeRenderTargets(width_, height_)) return false;
    if (!createSelectionTarget()) return false;
    if (!createShadowTarget()) return false;
    return true;
}

bool Renderer::resizeRenderTargets(int width, int height) {
    width = std::max(1, width);
    height = std::max(1, height);

    destroyRenderTargets();

    sceneTarget_.width = width;
    sceneTarget_.height = height;

    glGenFramebuffers(1, &sceneTarget_.framebuffer);
    glBindFramebuffer(GL_FRAMEBUFFER, sceneTarget_.framebuffer);

    glGenTextures(1, &sceneTarget_.colorTexture);
    glBindTexture(GL_TEXTURE_2D, sceneTarget_.colorTexture);
    glTexImage2D(
        GL_TEXTURE_2D, 0, GL_RGBA8, width, height, 0,
        GL_RGBA, GL_UNSIGNED_BYTE, nullptr
    );
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

    glFramebufferTexture2D(
        GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0,
        GL_TEXTURE_2D, sceneTarget_.colorTexture, 0
    );

    glGenTextures(1, &sceneTarget_.depthTexture);
    glBindTexture(GL_TEXTURE_2D, sceneTarget_.depthTexture);
    glTexImage2D(
        GL_TEXTURE_2D, 0, GL_DEPTH_COMPONENT24, width, height, 0,
        GL_DEPTH_COMPONENT, GL_UNSIGNED_INT, nullptr
    );
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

    glFramebufferTexture2D(
        GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT,
        GL_TEXTURE_2D, sceneTarget_.depthTexture, 0
    );

    GLenum drawBuffers[] = {GL_COLOR_ATTACHMENT0};
    glDrawBuffers(1, drawBuffers);

    sceneTarget_.valid =
        glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE;

    glBindFramebuffer(GL_FRAMEBUFFER, 0);

    if (!sceneTarget_.valid) {
        NOVA_LOGE("Scene framebuffer incomplete");
        return false;
    }

    return true;
}

bool Renderer::createSelectionTarget() {
    destroySelectionTarget();

    selectionTarget_.width = width_;
    selectionTarget_.height = height_;

    glGenFramebuffers(1, &selectionTarget_.framebuffer);
    glBindFramebuffer(GL_FRAMEBUFFER, selectionTarget_.framebuffer);

    glGenTextures(1, &selectionTarget_.colorTexture);
    glBindTexture(GL_TEXTURE_2D, selectionTarget_.colorTexture);
    glTexImage2D(
        GL_TEXTURE_2D, 0, GL_RGBA8, width_, height_, 0,
        GL_RGBA, GL_UNSIGNED_BYTE, nullptr
    );
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

    glFramebufferTexture2D(
        GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0,
        GL_TEXTURE_2D, selectionTarget_.colorTexture, 0
    );

    glGenTextures(1, &selectionTarget_.depthTexture);
    glBindTexture(GL_TEXTURE_2D, selectionTarget_.depthTexture);
    glTexImage2D(
        GL_TEXTURE_2D, 0, GL_DEPTH_COMPONENT24, width_, height_, 0,
        GL_DEPTH_COMPONENT, GL_UNSIGNED_INT, nullptr
    );
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);

    glFramebufferTexture2D(
        GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT,
        GL_TEXTURE_2D, selectionTarget_.depthTexture, 0
    );

    GLenum drawBuffers[] = {GL_COLOR_ATTACHMENT0};
    glDrawBuffers(1, drawBuffers);

    selectionTarget_.valid =
        glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE;

    glBindFramebuffer(GL_FRAMEBUFFER, 0);

    if (!selectionTarget_.valid) {
        NOVA_LOGE("Selection framebuffer incomplete");
        return false;
    }

    return true;
}

bool Renderer::createShadowTarget() {
    destroyShadowTarget();

    shadowTarget_.resolution = std::clamp(
        settings_.shadowResolution, 256, 2048
    );

    glGenFramebuffers(1, &shadowTarget_.framebuffer);
    glBindFramebuffer(GL_FRAMEBUFFER, shadowTarget_.framebuffer);

    glGenTextures(1, &shadowTarget_.depthTexture);
    glBindTexture(GL_TEXTURE_2D, shadowTarget_.depthTexture);

    glTexImage2D(
        GL_TEXTURE_2D, 0, GL_DEPTH_COMPONENT24,
        shadowTarget_.resolution, shadowTarget_.resolution,
        0, GL_DEPTH_COMPONENT, GL_UNSIGNED_INT, nullptr
    );

    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_COMPARE_MODE, GL_NONE);

    glFramebufferTexture2D(
        GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT,
        GL_TEXTURE_2D, shadowTarget_.depthTexture, 0
    );

    glDrawBuffers(0, nullptr);
    glReadBuffer(GL_NONE);

    shadowTarget_.valid =
        glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE;

    glBindFramebuffer(GL_FRAMEBUFFER, 0);

    if (!shadowTarget_.valid) {
        NOVA_LOGE("Shadow framebuffer incomplete");
        return false;
    }

    return true;
}

void Renderer::destroyRenderTargets() {
    if (sceneTarget_.colorTexture) glDeleteTextures(1, &sceneTarget_.colorTexture);
    if (sceneTarget_.depthTexture) glDeleteTextures(1, &sceneTarget_.depthTexture);
    if (sceneTarget_.framebuffer) glDeleteFramebuffers(1, &sceneTarget_.framebuffer);

    sceneTarget_ = RenderTarget{};
}

void Renderer::destroySelectionTarget() {
    if (selectionTarget_.colorTexture) glDeleteTextures(1, &selectionTarget_.colorTexture);
    if (selectionTarget_.depthTexture) glDeleteTextures(1, &selectionTarget_.depthTexture);
    if (selectionTarget_.framebuffer) glDeleteFramebuffers(1, &selectionTarget_.framebuffer);

    selectionTarget_ = RenderTarget{};
}

void Renderer::destroyShadowTarget() {
    if (shadowTarget_.depthTexture) glDeleteTextures(1, &shadowTarget_.depthTexture);
    if (shadowTarget_.framebuffer) glDeleteFramebuffers(1, &shadowTarget_.framebuffer);

    shadowTarget_ = ShadowTarget{};
}

void Renderer::createFullscreenQuad() {
    const float vertices[] = {
        -1.0f, -1.0f,
         1.0f, -1.0f,
        -1.0f,  1.0f,
         1.0f,  1.0f
    };

    glGenVertexArrays(1, &fullscreenVAO_);
    glGenBuffers(1, &fullscreenVBO_);

    glBindVertexArray(fullscreenVAO_);
    glBindBuffer(GL_ARRAY_BUFFER, fullscreenVBO_);
    glBufferData(GL_ARRAY_BUFFER, sizeof(vertices), vertices, GL_STATIC_DRAW);

    glEnableVertexAttribArray(0);
    glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, 2 * sizeof(float), nullptr);

    glBindVertexArray(0);
}

void Renderer::destroyFullscreenQuad() {
    if (fullscreenVBO_) glDeleteBuffers(1, &fullscreenVBO_);
    if (fullscreenVAO_) glDeleteVertexArrays(1, &fullscreenVAO_);

    fullscreenVBO_ = 0;
    fullscreenVAO_ = 0;
}

void Renderer::createGridGeometry() {
    std::vector<float> vertices;

    const int divisions = 40;
    const float extent = 20.0f;

    for (int i = 0; i <= divisions; ++i) {
        float position = -extent + (2.0f * extent * i / divisions);

        vertices.insert(vertices.end(), {
            position, 0.012f, -extent,
            position, 0.012f, extent,
            -extent, 0.012f, position,
            extent, 0.012f, position
        });
    }

    gridVertexCount_ = static_cast<GLsizei>(vertices.size() / 3);

    glGenVertexArrays(1, &gridVAO_);
    glGenBuffers(1, &gridVBO_);

    glBindVertexArray(gridVAO_);
    glBindBuffer(GL_ARRAY_BUFFER, gridVBO_);
    glBufferData(
        GL_ARRAY_BUFFER,
        static_cast<GLsizeiptr>(vertices.size() * sizeof(float)),
        vertices.data(),
        GL_STATIC_DRAW
    );

    glEnableVertexAttribArray(0);
    glVertexAttribPointer(0, 3, GL_FLOAT, GL_FALSE, 3 * sizeof(float), nullptr);

    glBindVertexArray(0);
}

void Renderer::destroyGridGeometry() {
    if (gridVBO_) glDeleteBuffers(1, &gridVBO_);
    if (gridVAO_) glDeleteVertexArrays(1, &gridVAO_);

    gridVBO_ = 0;
    gridVAO_ = 0;
    gridVertexCount_ = 0;
}

void Renderer::createLineGeometry() {
    glGenVertexArrays(1, &lineVAO_);
    glGenBuffers(1, &lineVBO_);

    glBindVertexArray(lineVAO_);
    glBindBuffer(GL_ARRAY_BUFFER, lineVBO_);

    glEnableVertexAttribArray(0);
    glVertexAttribPointer(
        0, 3, GL_FLOAT, GL_FALSE,
        sizeof(LineVertex),
        reinterpret_cast<void*>(offsetof(LineVertex, x))
    );

    glEnableVertexAttribArray(1);
    glVertexAttribPointer(
        1, 4, GL_FLOAT, GL_FALSE,
        sizeof(LineVertex),
        reinterpret_cast<void*>(offsetof(LineVertex, r))
    );

    glBindVertexArray(0);
}

void Renderer::destroyLineGeometry() {
    if (lineVBO_) glDeleteBuffers(1, &lineVBO_);
    if (lineVAO_) glDeleteVertexArrays(1, &lineVAO_);

    lineVBO_ = 0;
    lineVAO_ = 0;
}

void Renderer::resize(int width, int height) {
    width_ = std::max(1, width);
    height_ = std::max(1, height);

    glViewport(0, 0, width_, height_);

    if (initialized_) {
        resizeRenderTargets(width_, height_);
        createSelectionTarget();
    }
}

void Renderer::beginFrame() {
    if (!initialized_) return;

    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    glViewport(0, 0, width_, height_);

    glClearColor(clearR_, clearG_, clearB_, clearA_);
    glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);

    statistics_.drawCalls = 0;
    statistics_.triangles = 0;
    statistics_.vertices = 0;
    statistics_.visibleObjects = 0;
    statistics_.shadowDrawCalls = 0;
}

void Renderer::endFrame() {
    if (!initialized_) return;
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
}

Mat4 Renderer::calculateLightViewProjection() const {
    nova::Vec3 direction(
        lightDirection_[0],
        lightDirection_[1],
        lightDirection_[2]
    );

    direction = nova::normalize(direction);

    nova::Vec3 center(0.0f, 0.0f, 0.0f);
    nova::Vec3 eye = center - direction * 55.0f;

    Mat4 view = Mat4::lookAt(
        eye,
        center,
        nova::Vec3(0.0f, 1.0f, 0.0f)
    );

    // Orthographic projection covering the editor arena.
    Mat4 projection;
    for (int i = 0; i < 16; ++i) projection.m[i] = 0.0f;

    const float left = -28.0f;
    const float right = 28.0f;
    const float bottom = -28.0f;
    const float top = 28.0f;
    const float nearPlane = 0.1f;
    const float farPlane = 120.0f;

    projection.m[0] = 2.0f / (right - left);
    projection.m[5] = 2.0f / (top - bottom);
    projection.m[10] = -2.0f / (farPlane - nearPlane);
    projection.m[12] = -(right + left) / (right - left);
    projection.m[13] = -(top + bottom) / (top - bottom);
    projection.m[14] = -(farPlane + nearPlane) / (farPlane - nearPlane);
    projection.m[15] = 1.0f;

    return multiplyMatrix(projection, view);
}

void Renderer::bindCameraUniforms(
    ShaderProgram& program,
    const Camera& camera
) {
    Mat4 viewProjection = multiplyMatrix(
        camera.projectionMatrix(
            static_cast<float>(width_) / static_cast<float>(height_)
        ),
        camera.viewMatrix()
    );

    setMatrixUniform(program, "uViewProjection", viewProjection);
}

void Renderer::bindObjectUniforms(
    ShaderProgram& program,
    const SceneObject& object
) {
    Mat4 model = makeModelMatrix(object.transform);
    setMatrixUniform(program, "uModel", model);

    GLint colorLocation = glGetUniformLocation(program.id(), "uColor");
    if (colorLocation >= 0) {
        glUniform4f(
            colorLocation,
            object.color.r,
            object.color.g,
            object.color.b,
            object.color.a
        );
    }

    setVec3Uniform(
        program, "uBaseColor",
        object.material.baseColor.x,
        object.material.baseColor.y,
        object.material.baseColor.z
    );
    setFloatUniform(program, "uRoughness", object.material.roughness);
    setFloatUniform(program, "uMetallic", object.material.metallic);
}

bool Renderer::ensureObjectGPU(const SceneObject& object) {
    auto found = gpuBuffers_.find(object.id);
    if (found != gpuBuffers_.end() && found->second.valid) {
        return true;
    }

    Mesh mesh = buildMeshForObject(object);
    if (!mesh.isValid()) {
        NOVA_LOGE("Invalid mesh for object %u (%s)", object.id, object.name.c_str());
        return false;
    }

    return uploadMesh(object.id, mesh);
}

bool Renderer::uploadMesh(uint32_t objectId, const Mesh& mesh) {
    if (!mesh.isValid()) return false;

    releaseMesh(objectId);

    GPUBuffer buffer;

    const auto& vertices = mesh.getVertices();
    const auto& indices = mesh.getIndices();

    std::vector<uint32_t> edges;
    std::unordered_set<uint64_t> uniqueEdges;

    auto addEdge = [&](uint32_t a, uint32_t b) {
        uint32_t low = std::min(a, b);
        uint32_t high = std::max(a, b);
        uint64_t key = (static_cast<uint64_t>(low) << 32) | high;

        if (uniqueEdges.insert(key).second) {
            edges.push_back(a);
            edges.push_back(b);
        }
    };

    for (size_t i = 0; i + 2 < indices.size(); i += 3) {
        uint32_t a = indices[i];
        uint32_t b = indices[i + 1];
        uint32_t c = indices[i + 2];

        addEdge(a, b);
        addEdge(b, c);
        addEdge(c, a);
    }

    glGenVertexArrays(1, &buffer.vao);
    glGenBuffers(1, &buffer.vertexBuffer);
    glGenBuffers(1, &buffer.indexBuffer);
    glGenBuffers(1, &buffer.edgeIndexBuffer);

    glBindVertexArray(buffer.vao);

    glBindBuffer(GL_ARRAY_BUFFER, buffer.vertexBuffer);
    glBufferData(
        GL_ARRAY_BUFFER,
        static_cast<GLsizeiptr>(vertices.size() * sizeof(nova::Vertex)),
        vertices.data(),
        GL_STATIC_DRAW
    );

    glEnableVertexAttribArray(0);
    glVertexAttribPointer(
        0, 3, GL_FLOAT, GL_FALSE, sizeof(nova::Vertex),
        reinterpret_cast<void*>(offsetof(nova::Vertex, position))
    );

    glEnableVertexAttribArray(1);
    glVertexAttribPointer(
        1, 3, GL_FLOAT, GL_FALSE, sizeof(nova::Vertex),
        reinterpret_cast<void*>(offsetof(nova::Vertex, normal))
    );

    glEnableVertexAttribArray(2);
    glVertexAttribPointer(
        2, 2, GL_FLOAT, GL_FALSE, sizeof(nova::Vertex),
        reinterpret_cast<void*>(offsetof(nova::Vertex, u))
    );

    glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, buffer.indexBuffer);
    glBufferData(
        GL_ELEMENT_ARRAY_BUFFER,
        static_cast<GLsizeiptr>(indices.size() * sizeof(uint32_t)),
        indices.data(),
        GL_STATIC_DRAW
    );

    glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, buffer.edgeIndexBuffer);
    glBufferData(
        GL_ELEMENT_ARRAY_BUFFER,
        static_cast<GLsizeiptr>(edges.size() * sizeof(uint32_t)),
        edges.data(),
        GL_STATIC_DRAW
    );

    glBindVertexArray(0);

    buffer.indexCount = static_cast<GLsizei>(indices.size());
    buffer.edgeIndexCount = static_cast<GLsizei>(edges.size());
    buffer.vertexCount = static_cast<GLsizei>(vertices.size());
    buffer.valid = true;

    gpuBuffers_[objectId] = buffer;

    statistics_.gpuMemoryBytes +=
        vertices.size() * sizeof(nova::Vertex) +
        indices.size() * sizeof(uint32_t) +
        edges.size() * sizeof(uint32_t);

    return true;
}

void Renderer::releaseGPUBuffer(GPUBuffer& buffer) {
    if (buffer.edgeIndexBuffer) glDeleteBuffers(1, &buffer.edgeIndexBuffer);
    if (buffer.indexBuffer) glDeleteBuffers(1, &buffer.indexBuffer);
    if (buffer.vertexBuffer) glDeleteBuffers(1, &buffer.vertexBuffer);
    if (buffer.vao) glDeleteVertexArrays(1, &buffer.vao);

    buffer = GPUBuffer{};
}

void Renderer::releaseMesh(uint32_t objectId) {
    auto found = gpuBuffers_.find(objectId);
    if (found == gpuBuffers_.end()) return;

    releaseGPUBuffer(found->second);
    gpuBuffers_.erase(found);
}

void Renderer::releaseAllMeshes() {
    for (auto& entry : gpuBuffers_) {
        releaseGPUBuffer(entry.second);
    }

    gpuBuffers_.clear();
    statistics_.gpuMemoryBytes = 0;
}

void Renderer::renderShadowMap(
    Scene& scene,
    const Camera& camera
) {
    (void)camera;

    if (!settings_.showShadows ||
        !settings_.useShadowMapping ||
        !shadowTarget_.valid) {
        return;
    }

    glBindFramebuffer(GL_FRAMEBUFFER, shadowTarget_.framebuffer);
    glViewport(
        0, 0,
        shadowTarget_.resolution,
        shadowTarget_.resolution
    );

    glClear(GL_DEPTH_BUFFER_BIT);
    glEnable(GL_DEPTH_TEST);
    glEnable(GL_CULL_FACE);
    glCullFace(GL_FRONT);

    shadowProgram_.use();

    Mat4 lightVP = calculateLightViewProjection();
    setMatrixUniform(shadowProgram_, "uLightViewProjection", lightVP);

    for (const SceneObject& object : scene.getObjects()) {
        if (!object.visible || !object.castsShadow) continue;
        if (!ensureObjectGPU(object)) continue;

        GPUBuffer& buffer = gpuBuffers_[object.id];

        setMatrixUniform(
            shadowProgram_,
            "uModel",
            makeModelMatrix(object.transform)
        );

        glBindVertexArray(buffer.vao);
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, buffer.indexBuffer);
        glDrawElements(
            GL_TRIANGLES,
            buffer.indexCount,
            GL_UNSIGNED_INT,
            nullptr
        );

        ++statistics_.shadowDrawCalls;
    }

    glBindVertexArray(0);
    glCullFace(GL_BACK);
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
}

void Renderer::renderSky(const Camera& camera) {
    (void)camera;

    if (!settings_.showSky) {
        glClearColor(0.66f, 0.80f, 0.91f, 1.0f);
        glClear(GL_COLOR_BUFFER_BIT);
        return;
    }

    glDisable(GL_DEPTH_TEST);
    glDepthMask(GL_FALSE);

    skyProgram_.use();
    glBindVertexArray(fullscreenVAO_);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);

    glBindVertexArray(0);
    glDepthMask(GL_TRUE);
    glEnable(GL_DEPTH_TEST);

    ++statistics_.drawCalls;
}

void Renderer::renderGrid(const Camera& camera) {
    if (!settings_.showGrid || !gridVAO_) return;

    Mat4 viewProjection = multiplyMatrix(
        camera.projectionMatrix(
            static_cast<float>(width_) / static_cast<float>(height_)
        ),
        camera.viewMatrix()
    );

    gridProgram_.use();
    setMatrixUniform(gridProgram_, "uViewProjection", viewProjection);

    GLint colorLocation = glGetUniformLocation(gridProgram_.id(), "uGridColor");
    if (colorLocation >= 0) {
        glUniform4f(colorLocation, 0.30f, 0.32f, 0.34f, 0.62f);
    }

    glEnable(GL_BLEND);
    glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
    glDepthMask(GL_FALSE);

    glBindVertexArray(gridVAO_);
    glDrawArrays(GL_LINES, 0, gridVertexCount_);

    glBindVertexArray(0);

    glDepthMask(GL_TRUE);
    glDisable(GL_BLEND);

    ++statistics_.drawCalls;
}

void Renderer::renderObject(
    Scene& scene,
    const SceneObject& object,
    const Camera& camera,
    bool selectionPass,
    bool shadowPass
) {
    (void)scene;
    (void)camera;

    if (!object.visible) return;
    if (!ensureObjectGPU(object)) return;

    GPUBuffer& buffer = gpuBuffers_[object.id];

    if (shadowPass) {
        shadowProgram_.use();
        setMatrixUniform(
            shadowProgram_,
            "uModel",
            makeModelMatrix(object.transform)
        );
    } else if (selectionPass) {
        selectionMaskProgram_.use();
        bindCameraUniforms(selectionMaskProgram_, camera);
        setMatrixUniform(
            selectionMaskProgram_,
            "uModel",
            makeModelMatrix(object.transform)
        );
    } else {
        sceneProgram_.use();

        bindCameraUniforms(sceneProgram_, camera);
        bindObjectUniforms(sceneProgram_, object);

        setVec3Uniform(
            sceneProgram_,
            "uCameraPosition",
            camera.position.x,
            camera.position.y,
            camera.position.z
        );

        Mat4 lightVP = calculateLightViewProjection();
        setMatrixUniform(sceneProgram_, "uLightViewProjection", lightVP);

        setVec3Uniform(
            sceneProgram_,
            "uLightDirection",
            lightDirection_[0],
            lightDirection_[1],
            lightDirection_[2]
        );

        setVec3Uniform(
            sceneProgram_,
            "uLightColor",
            lightColor_[0],
            lightColor_[1],
            lightColor_[2]
        );

        setVec3Uniform(
            sceneProgram_,
            "uAmbientColor",
            ambientColor_[0],
            ambientColor_[1],
            ambientColor_[2]
        );

        setIntUniform(
            sceneProgram_,
            "uUseShadows",
            settings_.showShadows && settings_.useShadowMapping &&
            shadowTarget_.valid ? 1 : 0
        );

        setIntUniform(sceneProgram_, "uViewMode", viewMode_);
        setFloatUniform(sceneProgram_, "uShadowStrength", settings_.shadowStrength);
        setFloatUniform(sceneProgram_, "uExposure", settings_.exposure);

        glActiveTexture(GL_TEXTURE1);
        glBindTexture(GL_TEXTURE_2D, shadowTarget_.depthTexture);
        setIntUniform(sceneProgram_, "uShadowMap", 1);
        glActiveTexture(GL_TEXTURE0);
    }

    glBindVertexArray(buffer.vao);
    glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, buffer.indexBuffer);

    glDrawElements(
        GL_TRIANGLES,
        buffer.indexCount,
        GL_UNSIGNED_INT,
        nullptr
    );

    if (!selectionPass && !shadowPass) {
        ++statistics_.drawCalls;
        statistics_.triangles +=
            static_cast<uint32_t>(buffer.indexCount / 3);
        statistics_.vertices +=
            static_cast<uint32_t>(buffer.vertexCount);
        ++statistics_.visibleObjects;
    }

    glBindVertexArray(0);
}

void Renderer::renderAllObjects(
    Scene& scene,
    const Camera& camera,
    bool selectionPass,
    bool shadowPass
) {
    for (const SceneObject& object : scene.getObjects()) {
        if (!object.visible) continue;
        if (selectionPass && object.id != scene.selectedId()) continue;
        if (shadowPass && !object.castsShadow) continue;

        renderObject(
            scene,
            object,
            camera,
            selectionPass,
            shadowPass
        );
    }
}

void Renderer::renderScenePass(
    Scene& scene,
    const Camera& camera
) {
    glBindFramebuffer(GL_FRAMEBUFFER, sceneTarget_.framebuffer);
    glViewport(0, 0, width_, height_);

    glEnable(GL_DEPTH_TEST);
    glDepthMask(GL_TRUE);
    glEnable(GL_CULL_FACE);
    glCullFace(GL_BACK);

    renderSky(camera);

    glClear(GL_DEPTH_BUFFER_BIT);

    renderAllObjects(scene, camera, false, false);
    renderGrid(camera);

    if (settings_.showBounds) {
        renderBounds(scene, camera);
    }

    if (settings_.showWireframe || viewMode_ == 2) {
        renderWireframe(scene, camera);
    }
}

void Renderer::renderSelectionMask(
    Scene& scene,
    const Camera& camera
) {
    if (!settings_.useSelectionMask ||
        !settings_.showSelectionOutline ||
        scene.selected() == nullptr ||
        !selectionTarget_.valid) {
        return;
    }

    glBindFramebuffer(GL_FRAMEBUFFER, selectionTarget_.framebuffer);
    glViewport(0, 0, width_, height_);

    glClearColor(0, 0, 0, 0);
    glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);

    glEnable(GL_DEPTH_TEST);
    glEnable(GL_CULL_FACE);

    renderAllObjects(scene, camera, true, false);

    glBindFramebuffer(GL_FRAMEBUFFER, 0);
}

void Renderer::renderSelectionOutline() {
    if (!sceneTarget_.valid) return;

    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    glViewport(0, 0, width_, height_);

    glDisable(GL_DEPTH_TEST);
    glDepthMask(GL_FALSE);

    outlineProgram_.use();

    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, sceneTarget_.colorTexture);
    setIntUniform(outlineProgram_, "uScene", 0);

    glActiveTexture(GL_TEXTURE1);
    glBindTexture(GL_TEXTURE_2D, selectionTarget_.colorTexture);
    setIntUniform(outlineProgram_, "uMask", 1);

    GLint texelLocation = glGetUniformLocation(
        outlineProgram_.id(), "uTexelSize"
    );

    if (texelLocation >= 0) {
        glUniform2f(
            texelLocation,
            1.0f / static_cast<float>(width_),
            1.0f / static_cast<float>(height_)
        );
    }

    setFloatUniform(
        outlineProgram_,
        "uThickness",
        settings_.outlineThickness
    );

    setIntUniform(
        outlineProgram_,
        "uHasSelection",
        settings_.showSelectionOutline ? 1 : 0
    );

    glBindVertexArray(fullscreenVAO_);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    glBindVertexArray(0);

    glActiveTexture(GL_TEXTURE0);
    glDepthMask(GL_TRUE);
    glEnable(GL_DEPTH_TEST);

    ++statistics_.drawCalls;
}

void Renderer::renderComposite() {
    renderSelectionOutline();
}

void Renderer::renderWireframe(
    Scene& scene,
    const Camera& camera
) {
    Mat4 viewProjection = multiplyMatrix(
        camera.projectionMatrix(
            static_cast<float>(width_) / static_cast<float>(height_)
        ),
        camera.viewMatrix()
    );

    glEnable(GL_DEPTH_TEST);
    glPolygonOffset(-1.0f, -1.0f);

    for (const SceneObject& object : scene.getObjects()) {
        if (!object.visible || !ensureObjectGPU(object)) continue;

        GPUBuffer& buffer = gpuBuffers_[object.id];

        lineProgram_.use();
        setMatrixUniform(lineProgram_, "uViewProjection", viewProjection);

        glBindVertexArray(buffer.vao);
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, buffer.edgeIndexBuffer);

        GLint colorLocation = glGetAttribLocation(lineProgram_.id(), "aColor");
        (void)colorLocation;

        glBindBuffer(GL_ARRAY_BUFFER, lineVBO_);

        glDrawElements(
            GL_LINES,
            buffer.edgeIndexCount,
            GL_UNSIGNED_INT,
            nullptr
        );

        // The edge pass uses a dedicated simple color program below.
        // A constant orange-gray wireframe is applied by the line shader.
    }

    glBindVertexArray(0);
}

void Renderer::addBoundingBoxLines(
    const SceneObject& object,
    std::vector<RenderLine>& output
) {
    const float hx = std::fabs(object.transform.scale.x) * 0.5f;
    const float hy = std::fabs(object.transform.scale.y) * 0.5f;
    const float hz = std::fabs(object.transform.scale.z) * 0.5f;

    nova::Vec3 corners[8] = {
        {-hx, -hy, -hz},
        { hx, -hy, -hz},
        { hx,  hy, -hz},
        {-hx,  hy, -hz},
        {-hx, -hy,  hz},
        { hx, -hy,  hz},
        { hx,  hy,  hz},
        {-hx,  hy,  hz}
    };

    Mat4 model = makeModelMatrix(object.transform);

    auto transformPoint = [&](const nova::Vec3& point) {
        nova::Vec3 result;

        result.x = model.m[0] * point.x +
                   model.m[4] * point.y +
                   model.m[8] * point.z +
                   model.m[12];

        result.y = model.m[1] * point.x +
                   model.m[5] * point.y +
                   model.m[9] * point.z +
                   model.m[13];

        result.z = model.m[2] * point.x +
                   model.m[6] * point.y +
                   model.m[10] * point.z +
                   model.m[14];

        return result;
    };

    const int edges[12][2] = {
        {0,1},{1,2},{2,3},{3,0},
        {4,5},{5,6},{6,7},{7,4},
        {0,4},{1,5},{2,6},{3,7}
    };

    for (const auto& edge : edges) {
        nova::Vec3 a = transformPoint(corners[edge[0]]);
        nova::Vec3 b = transformPoint(corners[edge[1]]);

        output.push_back({
            a.x, a.y, a.z,
            b.x, b.y, b.z,
            0.1f, 0.85f, 1.0f, 1.0f
        });
    }
}

void Renderer::drawLines(
    const std::vector<RenderLine>& lines,
    const Camera& camera
) {
    if (lines.empty() || !lineVAO_) return;

    std::vector<LineVertex> vertices;
    vertices.reserve(lines.size() * 2);

    for (const RenderLine& line : lines) {
        vertices.push_back({
            line.x1, line.y1, line.z1,
            line.r, line.g, line.b, line.a
        });

        vertices.push_back({
            line.x2, line.y2, line.z2,
            line.r, line.g, line.b, line.a
        });
    }

    Mat4 viewProjection = multiplyMatrix(
        camera.projectionMatrix(
            static_cast<float>(width_) / static_cast<float>(height_)
        ),
        camera.viewMatrix()
    );

    lineProgram_.use();
    setMatrixUniform(lineProgram_, "uViewProjection", viewProjection);

    glBindVertexArray(lineVAO_);
    glBindBuffer(GL_ARRAY_BUFFER, lineVBO_);
    glBufferData(
        GL_ARRAY_BUFFER,
        static_cast<GLsizeiptr>(vertices.size() * sizeof(LineVertex)),
        vertices.data(),
        GL_DYNAMIC_DRAW
    );

    glDrawArrays(GL_LINES, 0, static_cast<GLsizei>(vertices.size()));

    glBindVertexArray(0);
    ++statistics_.drawCalls;
}

void Renderer::renderBounds(
    Scene& scene,
    const Camera& camera
) {
    frameLines_.clear();

    for (const SceneObject& object : scene.getObjects()) {
        if (object.visible) {
            addBoundingBoxLines(object, frameLines_);
        }
    }

    drawLines(frameLines_, camera);
}

void Renderer::updateStatistics(const Scene& scene) {
    static auto previousTime = std::chrono::steady_clock::now();

    auto now = std::chrono::steady_clock::now();
    float delta = std::chrono::duration<float>(now - previousTime).count();
    previousTime = now;

    if (delta > 0.0f && delta < 1.0f) {
        statistics_.frameTimeMs = delta * 1000.0f;
        statistics_.fps = 1.0f / delta;
    }

    statistics_.visibleObjects = 0;

    for (const SceneObject& object : scene.getObjects()) {
        if (object.visible) ++statistics_.visibleObjects;
    }
}

void Renderer::render(Scene& scene, Camera& camera) {
    if (!initialized_ || !viewportEnabled_) return;

    updateStatistics(scene);

    renderShadowMap(scene, camera);
    renderScenePass(scene, camera);

    if (settings_.useSelectionMask &&
        settings_.showSelectionOutline &&
        scene.selected() != nullptr) {
        renderSelectionMask(scene, camera);
    }

    renderComposite();

    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    glViewport(0, 0, width_, height_);
}

void Renderer::setViewMode(int mode) {
    viewMode_ = std::clamp(mode, 0, 7);

    settings_.showWireframe = viewMode_ == 2;

    if (viewMode_ == 1) {
        settings_.showShadows = false;
    } else if (viewMode_ == 0) {
        settings_.showShadows = true;
    }
}

int Renderer::getViewMode() const {
    return viewMode_;
}

void Renderer::setSettings(const RendererSettings& settings) {
    bool shadowResolutionChanged =
        settings_.shadowResolution != settings.shadowResolution;

    settings_ = settings;

    if (shadowResolutionChanged && initialized_) {
        createShadowTarget();
    }
}

const RendererSettings& Renderer::getSettings() const {
    return settings_;
}

const RendererStatistics& Renderer::statistics() const {
    return statistics_;
}

void Renderer::setClearColor(
    float r,
    float g,
    float b,
    float a
) {
    clearR_ = r;
    clearG_ = g;
    clearB_ = b;
    clearA_ = a;
}

void Renderer::setViewportEnabled(bool enabled) {
    viewportEnabled_ = enabled;
}

bool Renderer::isInitialized() const {
    return initialized_;
}

int Renderer::width() const {
    return width_;
}

int Renderer::height() const {
    return height_;
}
