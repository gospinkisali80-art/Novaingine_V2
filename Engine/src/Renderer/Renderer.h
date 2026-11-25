#pragma once

#include <GLES3/gl3.h>

#include <cstdint>
#include <string>
#include <unordered_map>
#include <vector>

#include "../Camera/Camera.h"
#include "../Geometry/Mesh.h"
#include "../Scene/Scene.h"
#include "ShaderProgram.h"
using namespace nova;

struct RendererSettings {
    bool showGrid = false;
    bool showShadows = true;
    bool showSky = true;
    bool showCharacters = true;
    bool showStatistics = true;
    bool showBounds = false;
    bool showSelectionOutline = true;
    bool showWireframe = false;

    bool useShadowMapping = true;
    bool useSelectionMask = true;

    float exposure = 1.0f;
    float shadowStrength = 0.75f;
    float outlineThickness = 2.0f;

    int shadowResolution = 1024;

    // Mobile world-environment controls (OpenGL ES 3.x).
    bool directionalLightEnabled = true;
    bool skyLightEnabled = true;
    bool skyAtmosphereEnabled = true;
    bool volumetricCloudsEnabled = true;
    bool heightFogEnabled = false;
    bool bloomEnabled = true;

    float directionalLightDirection[3] = {-0.45f, -0.80f, -0.35f};
    float directionalLightColor[3] = {1.0f, 0.96f, 0.88f};
    float directionalLightIntensity = 3.0f;

    float skyLightColor[3] = {0.34f, 0.40f, 0.50f};
    float skyLightIntensity = 0.45f;
    float atmosphereRayleigh = 1.0f;
    float atmosphereMie = 0.12f;

    float cloudCoverage = 0.55f;
    float cloudDensity = 0.75f;
    float cloudSpeed = 0.002f;

    float fogDensity = 0.0f;
    float fogHeight = 0.0f;
    float fogFalloff = 0.2f;
    float fogColor[3] = {0.70f, 0.76f, 0.82f};

    float bloomIntensity = 0.15f;
    float colorContrast = 1.0f;
    float colorSaturation = 1.0f;
};

struct RendererStatistics {
    float fps = 0.0f;
    float frameTimeMs = 0.0f;

    uint32_t drawCalls = 0;
    uint32_t triangles = 0;
    uint32_t vertices = 0;
    uint32_t visibleObjects = 0;
    uint32_t shadowDrawCalls = 0;

    uint64_t gpuMemoryBytes = 0;
};

struct GPUBuffer {
    GLuint vao = 0;
    GLuint vertexBuffer = 0;
    GLuint indexBuffer = 0;
    GLuint edgeIndexBuffer = 0;

    GLsizei indexCount = 0;
    GLsizei edgeIndexCount = 0;
    GLsizei vertexCount = 0;

    bool valid = false;
};

struct RenderTarget {
    GLuint framebuffer = 0;
    GLuint colorTexture = 0;
    GLuint depthTexture = 0;

    int width = 0;
    int height = 0;

    bool valid = false;
};

struct ShadowTarget {
    GLuint framebuffer = 0;
    GLuint depthTexture = 0;

    int resolution = 0;

    bool valid = false;
};

struct RenderLine {
    float x1;
    float y1;
    float z1;

    float x2;
    float y2;
    float z2;

    float r;
    float g;
    float b;
    float a;
};

class Renderer {
public:
    Renderer();
    ~Renderer();

    Renderer(const Renderer&) = delete;
    Renderer& operator=(const Renderer&) = delete;

    bool initialize();
    void shutdown();

    void resize(int width, int height);

    void beginFrame();
    void render(Scene& scene, Camera& camera);
    void setSculptCursor(bool visible, const Vec3& point, const Vec3& normal, float radius);
    void endFrame();

    bool uploadMesh(uint32_t objectId, const Mesh& mesh);
    void releaseMesh(uint32_t objectId);
    void releaseAllMeshes();

    void setViewMode(int mode);
    int getViewMode() const;

    void setSettings(const RendererSettings& settings);
    const RendererSettings& getSettings() const;

    const RendererStatistics& statistics() const;

    void setClearColor(
        float r,
        float g,
        float b,
        float a = 1.0f
    );

    void setViewportEnabled(bool enabled);
    bool isInitialized() const;

    int width() const;
    int height() const;

private:
    bool createPrograms();
    bool createRenderTargets();
    bool createSelectionTarget();
    bool createShadowTarget();

    bool resizeRenderTargets(
        int width,
        int height
    );

    void destroyPrograms();
    void destroyRenderTargets();
    void destroySelectionTarget();
    void destroyShadowTarget();

    void createFullscreenQuad();
    void destroyFullscreenQuad();

    void createGridGeometry();
    void destroyGridGeometry();

    void createLineGeometry();
    void destroyLineGeometry();

    void renderShadowMap(
        Scene& scene,
        const Camera& camera
    );

    void renderScenePass(
        Scene& scene,
        const Camera& camera
    );

    void renderSelectionMask(
        Scene& scene,
        const Camera& camera
    );

    void renderWireframe(
        Scene& scene,
        const Camera& camera
    );

    void renderBounds(
        Scene& scene,
        const Camera& camera
    );

    void renderSelectionOutline();

    void renderComposite();

    void renderSky(
        const Camera& camera
    );

    void renderGrid(
        const Camera& camera
    );

    void renderObject(
        Scene& scene,
        const SceneObject& object,
        const Camera& camera,
        bool selectionPass,
        bool shadowPass
    );

    void renderAllObjects(
        Scene& scene,
        const Camera& camera,
        bool selectionPass,
        bool shadowPass
    );

    bool ensureObjectGPU(
        const SceneObject& object
    );

    void releaseGPUBuffer(
        GPUBuffer& buffer
    );

    void drawLines(
        const std::vector<RenderLine>& lines,
        const Camera& camera
    );

    void addBoundingBoxLines(
        const SceneObject& object,
        std::vector<RenderLine>& output
    );

    void updateStatistics(
        const Scene& scene
    );

    Mat4 calculateLightViewProjection() const;

    void bindCameraUniforms(
        ShaderProgram& program,
        const Camera& camera
    );

    void bindObjectUniforms(
        ShaderProgram& program,
        const SceneObject& object
    );

private:
    bool initialized_ = false;
    bool viewportEnabled_ = true;

    int width_ = 1;
    int height_ = 1;

    int viewMode_ = 0;

    float clearR_ = 0.08f;
    float clearG_ = 0.12f;
    float clearB_ = 0.20f;
    float clearA_ = 1.0f;

    float elapsedTime_ = 0.0f;
    float statisticsTimer_ = 0.0f;

    RendererSettings settings_;
    RendererStatistics statistics_;

    std::unordered_map<uint32_t, GPUBuffer> gpuBuffers_;

    ShaderProgram sceneProgram_;
    ShaderProgram shadowProgram_;
    ShaderProgram selectionMaskProgram_;
    ShaderProgram outlineProgram_;
    ShaderProgram compositeProgram_;
    ShaderProgram skyProgram_;
    ShaderProgram gridProgram_;
    ShaderProgram lineProgram_;

    RenderTarget sceneTarget_;
    RenderTarget selectionTarget_;
    ShadowTarget shadowTarget_;

    GLuint fullscreenVAO_ = 0;
    GLuint fullscreenVBO_ = 0;

    GLuint gridVAO_ = 0;
    GLuint gridVBO_ = 0;
    GLsizei gridVertexCount_ = 0;

    GLuint lineVAO_ = 0;
    GLuint lineVBO_ = 0;

    std::vector<RenderLine> frameLines_;
    bool sculptCursorVisible_ = false;
    Vec3 sculptCursorPoint_;
    Vec3 sculptCursorNormal_{0.0f, 1.0f, 0.0f};
    float sculptCursorRadius_ = 0.18f;

    GLuint whiteTexture_ = 0;

    float lightDirection_[3] = {
        -0.45f,
        -0.80f,
        -0.35f
    };

    float lightColor_[3] = {
        1.0f,
        0.96f,
        0.88f
    };

    float ambientColor_[3] = {
        0.34f,
        0.40f,
        0.50f
    };
};
