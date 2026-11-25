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
    // A new project starts as a genuinely empty level. The procedural sky,
    // clouds and directional sunlight are rendered by Renderer, not scene props.
    scene.clear();
}

void SceneFactory::buildShowcaseScene(Scene& scene) {
    scene.clear();

    const Color floor = makeColor(0.56f, 0.57f, 0.59f);
    const Color wall = makeColor(0.60f, 0.61f, 0.63f);
    const Color wallSide = makeColor(0.43f, 0.46f, 0.50f);
    const Color wallTrim = makeColor(0.30f, 0.33f, 0.37f);
    const Color yellow = makeColor(0.96f, 0.72f, 0.025f);
    const Color yellowSide = makeColor(0.78f, 0.48f, 0.015f);
    const Color yellowLight = makeColor(1.0f, 0.84f, 0.08f);

    // Open rectangular architectural arena viewed from human eye height.
    addObject(scene, "Arena Floor", PrimitiveType::Plane,
        {0.0f, -0.08f, -4.0f}, {34.0f, 1.0f, 34.0f}, floor);

    // Low perimeter walls preserve the open sky and first-person sightlines.
    addObject(scene, "Rear Boundary Wall", PrimitiveType::Cube,
        {0.0f, 1.35f, -21.0f}, {34.0f, 2.7f, 0.65f}, wallSide);
    addObject(scene, "Left Boundary Wall", PrimitiveType::Cube,
        {-17.0f, 1.35f, -4.0f}, {0.65f, 2.7f, 34.0f}, wall);
    addObject(scene, "Right Boundary Wall", PrimitiveType::Cube,
        {17.0f, 1.35f, -4.0f}, {0.65f, 2.7f, 34.0f}, wall);
    addObject(scene, "Front Boundary Wall", PrimitiveType::Cube,
        {0.0f, 1.35f, 13.0f}, {34.0f, 2.7f, 0.65f}, wall);

    addObject(scene, "Rear Wall Footing", PrimitiveType::Cube,
        {0.0f, 0.22f, -20.62f}, {34.0f, 0.44f, 0.18f}, wallTrim);
    addObject(scene, "Left Wall Footing", PrimitiveType::Cube,
        {-16.62f, 0.22f, -4.0f}, {0.18f, 0.44f, 34.0f}, wallTrim);
    addObject(scene, "Right Wall Footing", PrimitiveType::Cube,
        {16.62f, 0.22f, -4.0f}, {0.18f, 0.44f, 34.0f}, wallTrim);
    addObject(scene, "Rear Wall Coping", PrimitiveType::Cube,
        {0.0f, 2.78f, -21.0f}, {34.2f, 0.16f, 0.82f}, wall);
    addObject(scene, "Left Wall Coping", PrimitiveType::Cube,
        {-17.0f, 2.78f, -4.0f}, {0.82f, 0.16f, 34.2f}, wall);
    addObject(scene, "Right Wall Coping", PrimitiveType::Cube,
        {17.0f, 2.78f, -4.0f}, {0.82f, 0.16f, 34.2f}, wall);

    // Stepped circular centerpiece.
    addObject(scene, "Central Pedestal Base", PrimitiveType::Cylinder,
        {0.0f, 0.32f, -4.0f}, {2.25f, 0.64f, 2.25f}, wallSide);
    addObject(scene, "Central Yellow Pedestal Band", PrimitiveType::Cylinder,
        {0.0f, 0.70f, -4.0f}, {2.12f, 0.22f, 2.12f}, yellowSide);
    addObject(scene, "Central Yellow Pedestal Top", PrimitiveType::Cylinder,
        {0.0f, 0.91f, -4.0f}, {2.18f, 0.20f, 2.18f}, yellowLight);

    // Yellow columns distributed around the arena, leaving a clear center route.
    const Vec3 columnPositions[] = {
        {-12.0f, 0.0f, -14.0f}, {-12.0f, 0.0f, -3.0f},
        {12.0f, 0.0f, -13.0f}, {12.0f, 0.0f, -1.0f},
        {-6.0f, 0.0f, -18.0f}, {7.0f, 0.0f, -18.0f},
        {-7.0f, 0.0f, 7.0f}, {8.0f, 0.0f, 7.0f}
    };
    for (int i = 0; i < 8; ++i) {
        const Vec3 p = columnPositions[i];
        addObject(scene, "Yellow Architectural Column " + std::to_string(i + 1),
            PrimitiveType::Cube, {p.x, 1.55f, p.z},
            {1.8f, 3.1f, 1.8f}, yellow);
        addObject(scene, "Yellow Column Foot " + std::to_string(i + 1),
            PrimitiveType::Cube, {p.x, 0.16f, p.z},
            {2.0f, 0.32f, 2.0f}, yellowSide);
        addObject(scene, "Yellow Column Cap " + std::to_string(i + 1),
            PrimitiveType::Cube, {p.x, 3.16f, p.z},
            {1.95f, 0.18f, 1.95f}, yellowLight);
    }

    // Raised architectural platforms with sloped access ramps.
    addObject(scene, "Left Raised Platform", PrimitiveType::Cube,
        {-11.0f, 0.48f, 4.0f}, {5.0f, 0.96f, 8.0f}, wall);
    addObject(scene, "Left Platform Yellow Ramp", PrimitiveType::Ramp,
        {-7.5f, 0.55f, 4.0f}, {2.4f, 1.1f, 5.0f}, yellow,
        {0.0f, 0.0f, -12.0f});
    addObject(scene, "Right Raised Platform", PrimitiveType::Cube,
        {11.0f, 0.48f, -9.0f}, {5.0f, 0.96f, 8.0f}, wallSide);
    addObject(scene, "Right Platform Yellow Ramp", PrimitiveType::Ramp,
        {7.5f, 0.55f, -9.0f}, {2.4f, 1.1f, 5.0f}, yellow,
        {0.0f, 0.0f, 12.0f});

    // Broad geometric wedges and low yellow platforms.
    addObject(scene, "Rear Left Yellow Wedge", PrimitiveType::Ramp,
        {-8.5f, 0.95f, -14.5f}, {4.2f, 1.9f, 4.8f}, yellow,
        {0.0f, 0.0f, -18.0f});
    addObject(scene, "Rear Right Yellow Wedge", PrimitiveType::Ramp,
        {8.5f, 0.95f, -14.5f}, {4.2f, 1.9f, 4.8f}, yellow,
        {0.0f, 0.0f, 18.0f});
    addObject(scene, "Front Left Yellow Platform", PrimitiveType::Cube,
        {-5.5f, 0.30f, 8.0f}, {5.0f, 0.60f, 3.8f}, yellow);
    addObject(scene, "Front Right Yellow Platform", PrimitiveType::Cube,
        {5.5f, 0.30f, 8.0f}, {5.0f, 0.60f, 3.8f}, yellowLight);

    // Concrete access steps beside the centerpiece.
    for (int i = 0; i < 4; ++i) {
        float h = 0.18f + i * 0.16f;
        addObject(scene, "Central Access Step " + std::to_string(i + 1),
            PrimitiveType::Cube, {3.8f, h, -4.0f - i * 0.82f},
            {1.9f, 0.36f + i * 0.32f, 0.86f}, wall);
    }

    // Repeated wall piers add scale and architectural rhythm.
    for (int i = 0; i < 5; ++i) {
        float z = -17.0f + i * 6.0f;
        addObject(scene, "Left Wall Pier " + std::to_string(i + 1),
            PrimitiveType::Cube, {-16.55f, 1.38f, z},
            {0.42f, 2.76f, 0.55f}, wall);
        addObject(scene, "Right Wall Pier " + std::to_string(i + 1),
            PrimitiveType::Cube, {16.55f, 1.38f, z},
            {0.42f, 2.76f, 0.55f}, wallSide);
    }

    scene.deselect();
}

}
