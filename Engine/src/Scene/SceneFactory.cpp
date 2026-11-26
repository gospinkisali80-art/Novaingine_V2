#include "SceneFactory.h"

namespace nova {

namespace {

Color makeColor(float r, float g, float b) {
    return Color(r, g, b, 1.0f);
}

void addObject(
    Scene& scene,
    const std::string& name,
    PrimitiveType primitive,
    Vec3 position,
    Vec3 scale,
    Color color,
    Vec3 rotation = Vec3(0.0f, 0.0f, 0.0f)
) {
    Transform transform;
    transform.position = position;
    transform.rotation = rotation;
    transform.scale = scale;

    const uint32_t objectId =
        scene.addObject(name, primitive, transform, color);

    SceneObject* object = scene.find(objectId);
    if (object != nullptr) {
        object->material.baseColor =
            Vec3(color.r, color.g, color.b);
        object->material.roughness = 0.6f;
        object->material.metallic = 0.0f;

        if (name.find("Yellow") != std::string::npos ||
            name.find("Platform") != std::string::npos) {
            object->material.roughness = 0.45f;
        }

        if (name == "Arena Floor") {
            object->material.roughness = 0.85f;
        }

        if (name.find("Wall") != std::string::npos) {
            object->material.roughness = 0.9f;
        }
    }
}

void addMannequin(Scene& scene, Vec3 origin, const std::string& prefix) {
    Color body = makeColor(0.42f, 0.44f, 0.47f);
    Color head = makeColor(0.58f, 0.59f, 0.61f);
    Color joints = makeColor(0.34f, 0.36f, 0.39f);

    addObject(scene, prefix + " Torso", PrimitiveType::Cube,
        {origin.x, origin.y + 1.05f, origin.z},
        {0.48f, 0.72f, 0.25f}, body);

    addObject(scene, prefix + " Head", PrimitiveType::Sphere,
        {origin.x, origin.y + 1.68f, origin.z},
        {0.25f, 0.28f, 0.25f}, head);

    addObject(scene, prefix + " Left Arm", PrimitiveType::Cylinder,
        {origin.x - 0.34f, origin.y + 1.08f, origin.z},
        {0.12f, 0.48f, 0.12f}, body,
        {0.0f, 0.0f, -12.0f});

    addObject(scene, prefix + " Right Arm", PrimitiveType::Cylinder,
        {origin.x + 0.34f, origin.y + 1.08f, origin.z},
        {0.12f, 0.48f, 0.12f}, body,
        {0.0f, 0.0f, 12.0f});

    addObject(scene, prefix + " Left Leg", PrimitiveType::Cylinder,
        {origin.x - 0.14f, origin.y + 0.38f, origin.z},
        {0.14f, 0.42f, 0.14f}, joints);

    addObject(scene, prefix + " Right Leg", PrimitiveType::Cylinder,
        {origin.x + 0.14f, origin.y + 0.38f, origin.z},
        {0.14f, 0.42f, 0.14f}, joints);
}

}

void SceneFactory::buildDefaultScene(Scene& scene) {
    scene.clear();
    Transform transform;
    transform.position = Vec3(0.0f, 1.0f, 0.0f);
    transform.scale = Vec3(1.0f, 1.0f, 1.0f);
    const Color cubeColor = makeColor(0.62f, 0.64f, 0.67f);
    const uint32_t cubeId = scene.addObject("Cube", PrimitiveType::Cube, transform, cubeColor);
    SceneObject* cube = scene.find(cubeId);
    if (cube != nullptr) {
        cube->material.baseColor = Vec3(cubeColor.r, cubeColor.g, cubeColor.b);
        cube->material.roughness = 0.72f;
        cube->material.metallic = 0.0f;
        cube->castsShadow = true;
    }
    scene.select(cubeId);
}

}
