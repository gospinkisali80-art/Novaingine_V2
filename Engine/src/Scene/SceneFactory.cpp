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

    const Color concrete = makeColor(0.57f, 0.58f, 0.60f);
    const Color concreteDark = makeColor(0.39f, 0.42f, 0.46f);
    const Color concreteLight = makeColor(0.68f, 0.69f, 0.70f);
    const Color orange = makeColor(0.96f, 0.43f, 0.045f);
    const Color orangeDark = makeColor(0.72f, 0.25f, 0.025f);
    const Color trim = makeColor(0.27f, 0.30f, 0.34f);

    // Long first-person test corridor, with an open roof so the sky and
    // directional sun remain visible from the editor camera.
    addObject(scene, "Tiled Concrete Corridor Floor", PrimitiveType::Plane,
        {0.0f, -0.06f, -8.0f}, {18.0f, 1.0f, 60.0f}, concrete);

    addObject(scene, "Left Concrete Boundary Wall", PrimitiveType::Cube,
        {-9.15f, 3.4f, -8.0f}, {0.55f, 6.8f, 60.0f}, concrete);
    addObject(scene, "Right Concrete Boundary Wall", PrimitiveType::Cube,
        {9.15f, 3.4f, -8.0f}, {0.55f, 6.8f, 60.0f}, concreteDark);
    addObject(scene, "Far Corridor Wall", PrimitiveType::Cube,
        {0.0f, 3.4f, -38.0f}, {18.8f, 6.8f, 0.55f}, concreteDark);

    // Wall footing and top coping create visible architectural edge detail.
    addObject(scene, "Left Wall Footing", PrimitiveType::Cube,
        {-8.78f, 0.32f, -8.0f}, {0.22f, 0.64f, 60.0f}, trim);
    addObject(scene, "Right Wall Footing", PrimitiveType::Cube,
        {8.78f, 0.32f, -8.0f}, {0.22f, 0.64f, 60.0f}, trim);
    addObject(scene, "Left Wall Coping", PrimitiveType::Cube,
        {-9.15f, 6.88f, -8.0f}, {0.72f, 0.20f, 60.0f}, concreteLight);
    addObject(scene, "Right Wall Coping", PrimitiveType::Cube,
        {9.15f, 6.88f, -8.0f}, {0.72f, 0.20f, 60.0f}, concreteLight);

    // Repeated orange structural pillars with dark inset bases and caps.
    for (int i = 0; i < 5; ++i) {
        float z = 10.0f - static_cast<float>(i) * 9.0f;
        float side = (i % 2 == 0) ? -1.0f : 1.0f;
        float x = side * 6.7f;

        addObject(scene, "Orange Structural Pillar " + std::to_string(i + 1),
            PrimitiveType::Cube, {x, 2.1f, z},
            {1.55f, 4.2f, 1.35f}, orange);
        addObject(scene, "Pillar Base " + std::to_string(i + 1),
            PrimitiveType::Cube, {x, 0.22f, z},
            {1.72f, 0.44f, 1.52f}, orangeDark);
        addObject(scene, "Pillar Cap " + std::to_string(i + 1),
            PrimitiveType::Cube, {x, 4.25f, z},
            {1.68f, 0.22f, 1.48f}, orangeDark);
    }

    // Raised side platforms and concrete retaining faces.
    addObject(scene, "Left Raised Walkway", PrimitiveType::Cube,
        {-6.4f, 0.62f, -10.0f}, {3.0f, 1.25f, 35.0f}, concreteLight);
    addObject(scene, "Right Raised Walkway", PrimitiveType::Cube,
        {6.4f, 0.62f, -17.0f}, {3.0f, 1.25f, 24.0f}, concrete);
    addObject(scene, "Left Walkway Retaining Face", PrimitiveType::Cube,
        {-4.85f, 0.62f, -10.0f}, {0.18f, 1.25f, 35.0f}, concreteDark);
    addObject(scene, "Right Walkway Retaining Face", PrimitiveType::Cube,
        {4.85f, 0.62f, -17.0f}, {0.18f, 1.25f, 24.0f}, concreteDark);

    // A broad sloped ramp and a short stair set make the corridor navigable.
    addObject(scene, "Main Concrete Ramp", PrimitiveType::Ramp,
        {-3.35f, 0.72f, -4.0f}, {3.1f, 1.45f, 7.0f}, concreteLight,
        {0.0f, 0.0f, -12.0f});
    for (int step = 0; step < 5; ++step) {
        float height = 0.22f + step * 0.24f;
        float z = -20.0f - step * 0.78f;
        addObject(scene, "Concrete Stair " + std::to_string(step + 1),
            PrimitiveType::Cube, {3.25f, height, z},
            {2.6f, 0.44f + step * 0.48f, 0.82f}, concreteLight);
    }

    // Low cover blocks, inset panels and wall buttresses provide scale.
    for (int i = 0; i < 4; ++i) {
        float z = 5.0f - i * 10.0f;
        float x = (i % 2 == 0) ? 2.0f : -2.0f;
        addObject(scene, "Concrete Cover Block " + std::to_string(i + 1),
            PrimitiveType::Cube, {x, 0.72f, z},
            {2.2f, 1.45f, 1.35f}, concrete);
    }

    for (int i = 0; i < 6; ++i) {
        float z = 15.0f - i * 9.5f;
        addObject(scene, "Left Wall Buttress " + std::to_string(i + 1),
            PrimitiveType::Cube, {-8.72f, 3.35f, z},
            {0.38f, 6.3f, 0.95f}, concreteLight);
        addObject(scene, "Right Wall Buttress " + std::to_string(i + 1),
            PrimitiveType::Cube, {8.72f, 3.35f, z},
            {0.38f, 6.3f, 0.95f}, concrete);
    }

    // Orange wall panels add deliberate color blocking without cluttering
    // the playable centerline.
    addObject(scene, "Far Orange Feature Panel", PrimitiveType::Cube,
        {0.0f, 2.5f, -37.55f}, {5.0f, 3.0f, 0.18f}, orangeDark);
    addObject(scene, "Right Orange Feature Panel", PrimitiveType::Cube,
        {8.72f, 2.6f, -26.0f}, {0.18f, 3.2f, 5.0f}, orange);

    scene.deselect();
}

}
