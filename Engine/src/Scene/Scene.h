#pragma once

#include <algorithm>
#include <cstdint>
#include <string>
#include <vector>

#include "../Camera/Camera.h"

namespace nova {

enum class PrimitiveType : int {
    Cube = 0,
    Cylinder = 1,
    Ramp = 2,
    Wedge = 3,
    Sphere = 4,
    Plane = 5,
    HalfCylinder = 6,
    Capsule = 7
};

struct Color {

    float r;
    float g;
    float b;
    float a;

    Color()
        : r(1.0f), g(1.0f), b(1.0f), a(1.0f) {}

    Color(
        float red,
        float green,
        float blue,
        float alpha = 1.0f
    )
        : r(red), g(green), b(blue), a(alpha) {}
};

struct Transform {

    Vec3 position;
    Vec3 rotation;
    Vec3 scale;

    Transform()
        : position(0.0f, 0.0f, 0.0f),
          rotation(0.0f, 0.0f, 0.0f),
          scale(1.0f, 1.0f, 1.0f) {}
};

struct SceneObject {

    uint32_t id = 0;

    std::string name;

    PrimitiveType primitive = PrimitiveType::Cube;

    Transform transform;

    Color color;

    bool visible = true;

    bool castsShadow = true;

    bool isStatic = true;

    SceneObject() = default;

    SceneObject(
        uint32_t objectId,
        const std::string& objectName,
        PrimitiveType objectPrimitive,
        const Transform& objectTransform,
        const Color& objectColor
    )
        : id(objectId),
          name(objectName),
          primitive(objectPrimitive),
          transform(objectTransform),
          color(objectColor) {}
};

class Scene {

public:

    Scene() = default;

    uint32_t addObject(
        const std::string& name,
        PrimitiveType primitive,
        const Transform& transform,
        const Color& color
    ) {

        SceneObject object(
            nextObjectId++,
            name,
            primitive,
            transform,
            color
        );

        objects.push_back(object);

        selectedIndex = static_cast<int>(
            objects.size() - 1
        );

        return object.id;
    }

    bool addObject(const SceneObject& object) {

        if (object.id == 0 || find(object.id) != nullptr) {
            return false;
        }

        objects.push_back(object);

        nextObjectId = std::max(
            nextObjectId,
            object.id + 1
        );

        return true;
    }

    bool removeObject(uint32_t id) {

        for (size_t i = 0; i < objects.size(); ++i) {

            if (objects[i].id != id) {
                continue;
            }

            objects.erase(objects.begin() + i);

            if (selectedIndex == static_cast<int>(i)) {
                selectedIndex = -1;
            } else if (selectedIndex > static_cast<int>(i)) {
                --selectedIndex;
            }

            return true;
        }

        return false;
    }

    SceneObject* find(uint32_t id) {

        for (SceneObject& object : objects) {
            if (object.id == id) {
                return &object;
            }
        }

        return nullptr;
    }

    const SceneObject* find(uint32_t id) const {

        for (const SceneObject& object : objects) {
            if (object.id == id) {
                return &object;
            }
        }

        return nullptr;
    }

    SceneObject* get(size_t index) {

        if (index >= objects.size()) {
            return nullptr;
        }

        return &objects[index];
    }

    const SceneObject* get(size_t index) const {

        if (index >= objects.size()) {
            return nullptr;
        }

        return &objects[index];
    }

    std::vector<SceneObject>& getObjects() {
        return objects;
    }

    const std::vector<SceneObject>& getObjects() const {
        return objects;
    }

    size_t objectCount() const {
        return objects.size();
    }

    bool empty() const {
        return objects.empty();
    }

    void clear() {
        objects.clear();
        selectedIndex = -1;
        nextObjectId = 1;
    }

    bool select(uint32_t id) {

        for (size_t i = 0; i < objects.size(); ++i) {

            if (objects[i].id == id) {
                selectedIndex = static_cast<int>(i);
                return true;
            }
        }

        return false;
    }

    bool selectIndex(int index) {

        if (index < 0 ||
            index >= static_cast<int>(objects.size())) {
            return false;
        }

        selectedIndex = index;
        return true;
    }

    void deselect() {
        selectedIndex = -1;
    }

    SceneObject* selected() {

        if (selectedIndex < 0 ||
            selectedIndex >= static_cast<int>(objects.size())) {
            return nullptr;
        }

        return &objects[static_cast<size_t>(selectedIndex)];
    }

    const SceneObject* selected() const {

        if (selectedIndex < 0 ||
            selectedIndex >= static_cast<int>(objects.size())) {
            return nullptr;
        }

        return &objects[static_cast<size_t>(selectedIndex)];
    }

    int selectedIndexValue() const {
        return selectedIndex;
    }

    uint32_t selectedId() const {

        const SceneObject* object = selected();

        return object ? object->id : 0;
    }

    bool moveObject(
        uint32_t id,
        const Vec3& amount
    ) {

        SceneObject* object = find(id);

        if (object == nullptr) {
            return false;
        }

        object->transform.position =
            object->transform.position + amount;

        return true;
    }

    bool setTransform(
        uint32_t id,
        const Transform& transform
    ) {

        SceneObject* object = find(id);

        if (object == nullptr) {
            return false;
        }

        object->transform = transform;

        return true;
    }

    bool setVisibility(
        uint32_t id,
        bool visible
    ) {

        SceneObject* object = find(id);

        if (object == nullptr) {
            return false;
        }

        object->visible = visible;

        return true;
    }

    bool setShadow(
        uint32_t id,
        bool enabled
    ) {

        SceneObject* object = find(id);

        if (object == nullptr) {
            return false;
        }

        object->castsShadow = enabled;

        return true;
    }

private:

    std::vector<SceneObject> objects;

    uint32_t nextObjectId = 1;

    int selectedIndex = -1;
};

}
