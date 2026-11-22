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

    // Reference layout: a rectangular grey test arena with vivid yellow
    // geometry, a central round pedestal, and open sky around the platform.
    const Color floor = makeColor(0.5490196f, 0.5490196f, 0.5490196f);
    const Color wall = makeColor(0.6039216f, 0.6039216f, 0.6039216f);
    const Color yellow = makeColor(0.9490196f, 0.7176471f, 0.0196078f);
    const Color yellowShade = makeColor(0.78f, 0.39f, 0.055f);
    const Color concrete = makeColor(0.50f, 0.51f, 0.53f);

    addObject(scene, "Arena Floor", PrimitiveType::Plane,
        {0.0f, -0.08f, 0.0f}, {40.0f, 1.0f, 40.0f}, floor);

    addObject(scene, "North Perimeter Wall", PrimitiveType::Cube,
        {0.0f, 0.68f, -20.0f}, {40.4f, 1.4f, 0.28f}, wall);
    addObject(scene, "South Perimeter Wall", PrimitiveType::Cube,
        {0.0f, 0.68f, 20.0f}, {40.4f, 1.4f, 0.28f}, wall);
    addObject(scene, "East Perimeter Wall", PrimitiveType::Cube,
        {20.0f, 0.68f, 0.0f}, {0.28f, 1.4f, 40.0f}, wall);
    addObject(scene, "West Perimeter Wall", PrimitiveType::Cube,
        {-20.0f, 0.68f, 0.0f}, {0.28f, 1.4f, 40.0f}, wall);

    // Central two-level circular pedestal: grey body and yellow top.
    addObject(scene, "Central Pedestal Base", PrimitiveType::Cylinder,
        {0.0f, 0.22f, 0.0f}, {3.7f, 0.44f, 3.7f}, concrete);
    addObject(scene, "Central Yellow Platform", PrimitiveType::Cylinder,
        {0.0f, 0.51f, 0.0f}, {2.9f, 0.16f, 2.9f}, yellow);

    // Back row: large yellow columns and a low curved obstacle.
    addObject(scene, "Back Left Yellow Column", PrimitiveType::Cylinder,
        {-7.8f, 1.0f, -13.0f}, {1.65f, 2.0f, 1.65f}, yellow);
    addObject(scene, "Back Right Yellow Column", PrimitiveType::Cylinder,
        {8.0f, 1.0f, -13.0f}, {1.65f, 2.0f, 1.65f}, yellow);
    addObject(scene, "Back Center Curved Obstacle", PrimitiveType::HalfCylinder,
        {0.0f, 0.72f, -13.2f}, {5.0f, 1.45f, 2.2f}, yellow);

    // Left-side cylinders, staggered like the reference image.
    addObject(scene, "Left Front Yellow Cylinder", PrimitiveType::Cylinder,
        {-15.0f, 0.92f, 4.8f}, {2.35f, 1.85f, 2.35f}, yellowShade);
    addObject(scene, "Left Rear Yellow Cylinder", PrimitiveType::Cylinder,
        {-15.0f, 0.76f, -2.0f}, {2.1f, 1.52f, 2.1f}, yellow);
    addObject(scene, "Left Low Yellow Platform", PrimitiveType::HalfCylinder,
        {-11.4f, 0.42f, -8.0f}, {3.2f, 0.82f, 2.5f}, yellow);

    // Right-side angular yellow platform and a separate raised block.
    addObject(scene, "Right Yellow Wedge", PrimitiveType::Wedge,
        {13.0f, 0.68f, 2.5f}, {6.5f, 1.35f, 5.0f}, yellow,
        {0.0f, -18.0f, 0.0f});
    addObject(scene, "Right Yellow Raised Block", PrimitiveType::Cube,
        {10.5f, 0.72f, -8.5f}, {2.4f, 1.45f, 2.4f}, yellow);

    // Foreground yellow angular platform and smaller grey ramp detail.
    addObject(scene, "Foreground Yellow Wedge", PrimitiveType::Wedge,
        {-1.8f, 0.42f, 16.0f}, {8.5f, 0.85f, 5.2f}, yellow,
        {0.0f, 180.0f, 0.0f});
    addObject(scene, "Center Grey Ramp", PrimitiveType::Ramp,
        {-4.6f, 0.42f, 2.0f}, {3.4f, 0.85f, 2.5f}, concrete,
        {0.0f, 0.0f, -18.0f});
    addObject(scene, "Rear Grey Ramp", PrimitiveType::Ramp,
        {4.5f, 0.32f, -7.0f}, {2.8f, 0.65f, 2.0f}, concrete,
        {0.0f, 0.0f, 16.0f});

    scene.deselect();
}

}
