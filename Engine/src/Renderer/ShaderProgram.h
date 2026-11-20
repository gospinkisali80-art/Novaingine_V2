#pragma once

#include <GLES3/gl3.h>
#include <android/log.h>

#include <cstddef>
#include <string>
#include <vector>

class ShaderProgram {
public:
    ShaderProgram() = default;

    ~ShaderProgram() {
        destroy();
    }

    ShaderProgram(const ShaderProgram&) = delete;
    ShaderProgram& operator=(const ShaderProgram&) = delete;

    ShaderProgram(ShaderProgram&& other) noexcept {
        program = other.program;
        other.program = 0;
    }

    ShaderProgram& operator=(ShaderProgram&& other) noexcept {
        if (this != &other) {
            destroy();
            program = other.program;
            other.program = 0;
        }
        return *this;
    }

    bool create(const char* vertexSource, const char* fragmentSource) {
        destroy();

        GLuint vertexShader =
            compileShader(GL_VERTEX_SHADER, vertexSource);

        if (vertexShader == 0) {
            return false;
        }

        GLuint fragmentShader =
            compileShader(GL_FRAGMENT_SHADER, fragmentSource);

        if (fragmentShader == 0) {
            glDeleteShader(vertexShader);
            return false;
        }

        GLuint linkedProgram = glCreateProgram();

        if (linkedProgram == 0) {
            glDeleteShader(vertexShader);
            glDeleteShader(fragmentShader);
            return false;
        }

        glAttachShader(linkedProgram, vertexShader);
        glAttachShader(linkedProgram, fragmentShader);
        glLinkProgram(linkedProgram);

        GLint linkStatus = GL_FALSE;
        glGetProgramiv(
            linkedProgram,
            GL_LINK_STATUS,
            &linkStatus
        );

        if (linkStatus != GL_TRUE) {
            GLint logLength = 0;

            glGetProgramiv(
                linkedProgram,
                GL_INFO_LOG_LENGTH,
                &logLength
            );

            if (logLength > 1) {
                std::vector<char> log(
                    static_cast<std::size_t>(logLength) + 1,
                    '\0'
                );

                glGetProgramInfoLog(
                    linkedProgram,
                    logLength,
                    nullptr,
                    log.data()
                );

                __android_log_print(
                    ANDROID_LOG_ERROR,
                    "Novaingine",
                    "Program link failed: %s",
                    log.data()
                );
            }

            glDetachShader(linkedProgram, vertexShader);
            glDetachShader(linkedProgram, fragmentShader);

            glDeleteShader(vertexShader);
            glDeleteShader(fragmentShader);
            glDeleteProgram(linkedProgram);

            return false;
        }

        glDetachShader(linkedProgram, vertexShader);
        glDetachShader(linkedProgram, fragmentShader);

        glDeleteShader(vertexShader);
        glDeleteShader(fragmentShader);

        program = linkedProgram;
        return true;
    }

    GLuint id() const {
        return program;
    }

    GLuint take() {
        GLuint result = program;
        program = 0;
        return result;
    }

    void use() const {
        if (program != 0) {
            glUseProgram(program);
        }
    }

    GLint uniform(const char* name) const {
        if (program == 0 || name == nullptr) {
            return -1;
        }

        return glGetUniformLocation(program, name);
    }

    bool valid() const {
        return program != 0;
    }

    void destroy() {
        if (program != 0) {
            glDeleteProgram(program);
            program = 0;
        }
    }

private:
    GLuint program = 0;

    static GLuint compileShader(
        GLenum type,
        const char* source
    ) {
        if (source == nullptr) {
            return 0;
        }

        GLuint shader = glCreateShader(type);

        if (shader == 0) {
            return 0;
        }

        glShaderSource(shader, 1, &source, nullptr);
        glCompileShader(shader);

        GLint compileStatus = GL_FALSE;

        glGetShaderiv(
            shader,
            GL_COMPILE_STATUS,
            &compileStatus
        );

        if (compileStatus != GL_TRUE) {
            GLint logLength = 0;

            glGetShaderiv(
                shader,
                GL_INFO_LOG_LENGTH,
                &logLength
            );

            if (logLength > 1) {
                std::vector<char> log(
                    static_cast<std::size_t>(logLength) + 1,
                    '\0'
                );

                glGetShaderInfoLog(
                    shader,
                    logLength,
                    nullptr,
                    log.data()
                );

                __android_log_print(
                    ANDROID_LOG_ERROR,
                    "Novaingine",
                    "Shader compilation failed: %s",
                    log.data()
                );
            }

            glDeleteShader(shader);
            return 0;
        }

        return shader;
    }
};
