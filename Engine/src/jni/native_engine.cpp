#include <jni.h>
#include <GLES3/gl3.h>
#include <android/log.h>

#include <cmath>
#include <vector>
#include <algorithm>

#define LOG_TAG "NovaingineV2"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

constexpr float PI = 3.14159265358979323846f;

GLuint program = 0;
GLuint vertexBuffer = 0;
GLuint texture = 0;

GLint mvpLocation = -1;
GLint modelLocation = -1;
GLint samplerLocation = -1;

int surfaceWidth = 1;
int surfaceHeight = 1;

float rotationY = 0.0f;
float rotationX = 0.0f;

float touchRotationX = 0.0f;
float touchRotationY = 0.0f;

float cameraDistance = 4.0f;

bool paused = false;

struct Vec3 {
    float x;
    float y;
    float z;
};

std::vector<Vec3> cubes = {
    {0.0f, 0.0f, 0.0f}
};

struct Mat4 {
    float m[16]{};
};

Mat4 identity() {
    Mat4 result{};

    result.m[0] = 1.0f;
    result.m[5] = 1.0f;
    result.m[10] = 1.0f;
    result.m[15] = 1.0f;

    return result;
}

Mat4 multiply(const Mat4& a, const Mat4& b) {
    Mat4 result{};

    for (int column = 0; column < 4; ++column) {
        for (int row = 0; row < 4; ++row) {
            for (int k = 0; k < 4; ++k) {
                result.m[column * 4 + row] +=
                    a.m[k * 4 + row] *
                    b.m[column * 4 + k];
            }
        }
    }

    return result;
}

Mat4 perspective(
        float fov,
        float aspect,
        float nearPlane,
        float farPlane) {

    Mat4 result{};

    const float f = 1.0f / std::tan(fov * 0.5f);

    result.m[0] = f / aspect;
    result.m[5] = f;

    result.m[10] =
            (farPlane + nearPlane) /
            (nearPlane - farPlane);

    result.m[11] = -1.0f;

    result.m[14] =
            (2.0f * farPlane * nearPlane) /
            (nearPlane - farPlane);

    return result;
}

Mat4 rotationMatrixX(float angle) {
    Mat4 result = identity();

    const float c = std::cos(angle);
    const float s = std::sin(angle);

    result.m[5] = c;
    result.m[6] = s;
    result.m[9] = -s;
    result.m[10] = c;

    return result;
}

Mat4 rotationMatrixY(float angle) {
    Mat4 result = identity();

    const float c = std::cos(angle);
    const float s = std::sin(angle);

    result.m[0] = c;
    result.m[2] = -s;
    result.m[8] = s;
    result.m[10] = c;

    return result;
}

Mat4 translation(float x, float y, float z) {
    Mat4 result = identity();

    result.m[12] = x;
    result.m[13] = y;
    result.m[14] = z;

    return result;
}

GLuint compileShader(GLenum type, const char* source) {

    GLuint shader = glCreateShader(type);

    if (shader == 0) {
        LOGE("Could not create shader.");
        return 0;
    }

    glShaderSource(shader, 1, &source, nullptr);
    glCompileShader(shader);

    GLint compiled = GL_FALSE;

    glGetShaderiv(shader, GL_COMPILE_STATUS, &compiled);

    if (compiled != GL_TRUE) {

        GLint length = 0;

        glGetShaderiv(shader, GL_INFO_LOG_LENGTH, &length);

        std::vector<char> log(
                static_cast<size_t>(std::max(length, 1)),
                '\0'
        );

        glGetShaderInfoLog(
                shader,
                length,
                nullptr,
                log.data()
        );

        LOGE("Shader compilation failed: %s", log.data());

        glDeleteShader(shader);

        return 0;
    }

    return shader;
}

GLuint createProgram() {

    const char* vertexSource = R"(
        #version 300 es
        precision highp float;

        layout(location = 0) in vec3 aPosition;
        layout(location = 1) in vec3 aNormal;
        layout(location = 2) in vec2 aUV;

        uniform mat4 uMVP;
        uniform mat4 uModel;

        out vec3 vNormal;
        out vec2 vUV;

        void main() {
            gl_Position = uMVP * vec4(aPosition, 1.0);
            vNormal = mat3(uModel) * aNormal;
            vUV = aUV;
        }
    )";

    const char* fragmentSource = R"(
        #version 300 es
        precision highp float;

        in vec3 vNormal;
        in vec2 vUV;

        uniform sampler2D uTexture;

        out vec4 fragColor;

        void main() {

            vec3 normal = normalize(vNormal);

            vec3 lightDirection =
                    normalize(vec3(-0.5, 0.8, 1.0));

            float diffuse = max(
                    dot(normal, lightDirection),
                    0.0
            );

            float lighting = 0.25 + diffuse * 0.75;

            vec4 texel = texture(uTexture, vUV);

            fragColor = vec4(
                    texel.rgb * lighting,
                    texel.a
            );
        }
    )";

    GLuint vertex =
            compileShader(GL_VERTEX_SHADER, vertexSource);

    GLuint fragment =
            compileShader(GL_FRAGMENT_SHADER, fragmentSource);

    if (vertex == 0 || fragment == 0) {

        if (vertex != 0) {
            glDeleteShader(vertex);
        }

        if (fragment != 0) {
            glDeleteShader(fragment);
        }

        return 0;
    }

    GLuint result = glCreateProgram();

    glAttachShader(result, vertex);
    glAttachShader(result, fragment);

    glLinkProgram(result);

    GLint linked = GL_FALSE;

    glGetProgramiv(result, GL_LINK_STATUS, &linked);

    if (linked != GL_TRUE) {

        GLint length = 0;

        glGetProgramiv(result, GL_INFO_LOG_LENGTH, &length);

        std::vector<char> log(
                static_cast<size_t>(std::max(length, 1)),
                '\0'
        );

        glGetProgramInfoLog(
                result,
                length,
                nullptr,
                log.data()
        );

        LOGE("Program linking failed: %s", log.data());

        glDeleteProgram(result);

        result = 0;
    }

    glDeleteShader(vertex);
    glDeleteShader(fragment);

    return result;
}

void addFace(
        std::vector<float>& vertices,
        const float corners[4][3],
        float nx,
        float ny,
        float nz) {

    constexpr float uv[4][2] = {
        {0.0f, 0.0f},
        {1.0f, 0.0f},
        {1.0f, 1.0f},
        {0.0f, 1.0f}
    };

    constexpr int indices[6] = {
        0, 1, 2, 0, 2, 3
    };

    for (int index : indices) {

        const int i = index;

        vertices.push_back(corners[i][0]);
        vertices.push_back(corners[i][1]);
        vertices.push_back(corners[i][2]);

        vertices.push_back(nx);
        vertices.push_back(ny);
        vertices.push_back(nz);

        vertices.push_back(uv[i][0]);
        vertices.push_back(uv[i][1]);
    }
}

void createCube() {

    std::vector<float> vertices;

    vertices.reserve(36 * 8);

    const float faces[6][4][3] = {

        {
            {-1,-1, 1}, { 1,-1, 1},
            { 1, 1, 1}, {-1, 1, 1}
        },

        {
            { 1,-1,-1}, {-1,-1,-1},
            {-1, 1,-1}, { 1, 1,-1}
        },

        {
            {-1, 1, 1}, { 1, 1, 1},
            { 1, 1,-1}, {-1, 1,-1}
        },

        {
            {-1,-1,-1}, { 1,-1,-1},
            { 1,-1, 1}, {-1,-1, 1}
        },

        {
            { 1,-1, 1}, { 1,-1,-1},
            { 1, 1,-1}, { 1, 1, 1}
        },

        {
            {-1,-1,-1}, {-1,-1, 1},
            {-1, 1, 1}, {-1, 1,-1}
        }
    };

    const float normals[6][3] = {

        { 0, 0, 1},
        { 0, 0,-1},
        { 0, 1, 0},
        { 0,-1, 0},
        { 1, 0, 0},
        {-1, 0, 0}
    };

    for (int face = 0; face < 6; ++face) {

        addFace(
                vertices,
                faces[face],
                normals[face][0],
                normals[face][1],
                normals[face][2]
        );
    }

    glGenBuffers(1, &vertexBuffer);

    glBindBuffer(
            GL_ARRAY_BUFFER,
            vertexBuffer
    );

    glBufferData(
            GL_ARRAY_BUFFER,
            static_cast<GLsizeiptr>(
                    vertices.size() * sizeof(float)
            ),
            vertices.data(),
            GL_STATIC_DRAW
    );
}

void createCheckerTexture() {

    constexpr int size = 64;

    std::vector<unsigned char> pixels(
            size * size * 4
    );

    for (int y = 0; y < size; ++y) {

        for (int x = 0; x < size; ++x) {

            const bool light =
                    ((x / 8) + (y / 8)) % 2 == 0;

            const int offset =
                    (y * size + x) * 4;

            pixels[offset + 0] = light ? 220 : 35;
            pixels[offset + 1] = light ? 155 : 65;
            pixels[offset + 2] = light ? 65 : 125;
            pixels[offset + 3] = 255;
        }
    }

    glGenTextures(1, &texture);

    glBindTexture(
            GL_TEXTURE_2D,
            texture
    );

    glTexParameteri(
            GL_TEXTURE_2D,
            GL_TEXTURE_MIN_FILTER,
            GL_LINEAR
    );

    glTexParameteri(
            GL_TEXTURE_2D,
            GL_TEXTURE_MAG_FILTER,
            GL_LINEAR
    );

    glTexParameteri(
            GL_TEXTURE_2D,
            GL_TEXTURE_WRAP_S,
            GL_REPEAT
    );

    glTexParameteri(
            GL_TEXTURE_2D,
            GL_TEXTURE_WRAP_T,
            GL_REPEAT
    );

    glTexImage2D(
            GL_TEXTURE_2D,
            0,
            GL_RGBA,
            size,
            size,
            0,
            GL_RGBA,
            GL_UNSIGNED_BYTE,
            pixels.data()
    );
}

void configureVertexAttributes() {

    glBindBuffer(
            GL_ARRAY_BUFFER,
            vertexBuffer
    );

    constexpr GLsizei stride =
            8 * sizeof(float);

    glEnableVertexAttribArray(0);

    glVertexAttribPointer(
            0,
            3,
            GL_FLOAT,
            GL_FALSE,
            stride,
            reinterpret_cast<void*>(0)
    );

    glEnableVertexAttribArray(1);

    glVertexAttribPointer(
            1,
            3,
            GL_FLOAT,
            GL_FALSE,
            stride,
            reinterpret_cast<void*>(3 * sizeof(float))
    );

    glEnableVertexAttribArray(2);

    glVertexAttribPointer(
            2,
            2,
            GL_FLOAT,
            GL_FALSE,
            stride,
            reinterpret_cast<void*>(6 * sizeof(float))
    );
}

} // namespace

extern "C" JNIEXPORT void JNICALL
Java_com_novaingine_v2_NativeBridge_nativeInit(
        JNIEnv*, jclass) {

    glClearColor(
            0.055f,
            0.075f,
            0.11f,
            1.0f
    );

    glEnable(GL_DEPTH_TEST);
    glDepthFunc(GL_LEQUAL);

    glEnable(GL_CULL_FACE);
    glCullFace(GL_BACK);

    program = createProgram();

    if (program == 0) {

        LOGE("Renderer initialization failed.");

        return;
    }

    mvpLocation =
            glGetUniformLocation(program, "uMVP");

    modelLocation =
            glGetUniformLocation(program, "uModel");

    samplerLocation =
            glGetUniformLocation(program, "uTexture");

    createCube();
    createCheckerTexture();
    configureVertexAttributes();

    LOGI("Novaingine V2 GLES3 renderer initialized.");
}

extern "C" JNIEXPORT void JNICALL
Java_com_novaingine_v2_NativeBridge_nativeResize(
        JNIEnv*, jclass, jint width, jint height) {

    surfaceWidth = std::max(
            1,
            static_cast<int>(width)
    );

    surfaceHeight = std::max(
            1,
            static_cast<int>(height)
    );

    glViewport(
            0,
            0,
            surfaceWidth,
            surfaceHeight
    );
}

extern "C" JNIEXPORT void JNICALL
Java_com_novaingine_v2_NativeBridge_nativeTouch(
        JNIEnv*, jclass, jfloat deltaX, jfloat deltaY) {

    touchRotationY += deltaX * 0.006f;
    touchRotationX += deltaY * 0.006f;

    touchRotationX = std::clamp(
            touchRotationX,
            -1.2f,
            1.2f
    );
}

extern "C" JNIEXPORT void JNICALL
Java_com_novaingine_v2_NativeBridge_nativeZoom(
        JNIEnv*, jclass, jfloat scale) {

    if (!std::isfinite(scale) || scale <= 0.0f) {
        return;
    }

    cameraDistance /= scale;

    cameraDistance = std::clamp(
            cameraDistance,
            2.5f,
            12.0f
    );
}

extern "C" JNIEXPORT void JNICALL
Java_com_novaingine_v2_NativeBridge_nativeAddCube(
        JNIEnv*, jclass) {

    if (cubes.size() >= 100) {
        LOGI("Cube limit reached.");
        return;
    }

    const int index =
            static_cast<int>(cubes.size());

    const float spacing = 2.5f;

    const int column = index % 3;
    const int row = (index / 3) % 3;
    const int layer = index / 9;

    const float x =
            (static_cast<float>(column) - 1.0f) * spacing;

    const float y =
            (static_cast<float>(row) - 1.0f) * spacing;

    const float z =
            -static_cast<float>(layer) * spacing;

    cubes.push_back({x, y, z});

    LOGI("Cube added. Total cubes: %zu", cubes.size());
}

extern "C" JNIEXPORT void JNICALL
Java_com_novaingine_v2_NativeBridge_nativeSetPaused(
        JNIEnv*, jclass, jboolean value) {

    paused = (value == JNI_TRUE);

    LOGI(
            "Engine animation %s",
            paused ? "paused" : "playing"
    );
}

extern "C" JNIEXPORT void JNICALL
Java_com_novaingine_v2_NativeBridge_nativeRender(
        JNIEnv*, jclass, jfloat deltaTime) {

    if (program == 0 ||
        vertexBuffer == 0 ||
        texture == 0) {

        glClear(
                GL_COLOR_BUFFER_BIT |
                GL_DEPTH_BUFFER_BIT
        );

        return;
    }

    const float dt = std::clamp(
            static_cast<float>(deltaTime),
            0.0f,
            0.1f
    );

    if (!paused) {

        rotationY += dt * 0.65f;
        rotationX += dt * 0.25f;
    }

    glClear(
            GL_COLOR_BUFFER_BIT |
            GL_DEPTH_BUFFER_BIT
    );

    glUseProgram(program);

    const float aspect =
            static_cast<float>(surfaceWidth) /
            static_cast<float>(surfaceHeight);

    const Mat4 projection = perspective(
            60.0f * PI / 180.0f,
            aspect,
            0.1f,
            100.0f
    );

    const Mat4 view = translation(
            0.0f,
            0.0f,
            -cameraDistance
    );

    const Mat4 rotation = multiply(
            rotationMatrixY(rotationY + touchRotationY),
            rotationMatrixX(rotationX + touchRotationX)
    );

    glActiveTexture(GL_TEXTURE0);

    glBindTexture(
            GL_TEXTURE_2D,
            texture
    );

    glUniform1i(
            samplerLocation,
            0
    );

    configureVertexAttributes();

    for (const Vec3& cube : cubes) {

        const Mat4 position = translation(
                cube.x,
                cube.y,
                cube.z
        );

        const Mat4 model = multiply(
                position,
                rotation
        );

        const Mat4 mvp = multiply(
                projection,
                multiply(view, model)
        );

        glUniformMatrix4fv(
                mvpLocation,
                1,
                GL_FALSE,
                mvp.m
        );

        glUniformMatrix4fv(
                modelLocation,
                1,
                GL_FALSE,
                model.m
        );

        glDrawArrays(
                GL_TRIANGLES,
                0,
                36
        );
    }
}
