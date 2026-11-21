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

    const Color floor = makeColor(0.5490196f, 0.5490196f, 0.5490196f);
    const Color wall = makeColor(0.6039216f, 0.6039216f, 0.6039216f);
    const Color inner = makeColor(0.6039216f, 0.6039216f, 0.6039216f);
    const Color gray = makeColor(0.43f, 0.45f, 0.48f);
    const Color yellow = makeColor(0.9490196f, 0.7176471f, 0.0196078f);

    addObject(scene, "Arena Floor", PrimitiveType::Plane,
        {0.0f, -0.08f, 0.0f}, {40.0f, 1.0f, 40.0f}, floor);

    addObject(scene, "North Perimeter Wall", PrimitiveType::Cube,
        {0.0f, 0.72f, -20.0f}, {40.4f, 1.5f, 0.2f}, wall);

    addObject(scene, "South Perimeter Wall", PrimitiveType::Cube,
        {0.0f, 0.72f, 20.0f}, {40.4f, 1.5f, 0.2f}, wall);

    addObject(scene, "East Perimeter Wall", PrimitiveType::Cube,
        {20.0f, 0.72f, 0.0f}, {0.2f, 1.5f, 40.0f}, wall);

    addObject(scene, "West Perimeter Wall", PrimitiveType::Cube,
        {-20.0f, 0.72f, 0.0f}, {0.2f, 1.5f, 40.0f}, wall);

    addObject(scene, "North Inner Wall Face", PrimitiveType::Cube,
        {0.0f, 0.72f, -19.88f}, {39.8f, 1.45f, 0.025f}, inner);

    addObject(scene, "South Inner Wall Face", PrimitiveType::Cube,
        {0.0f, 0.72f, 19.88f}, {39.8f, 1.45f, 0.025f}, inner);

    addObject(scene, "East Inner Wall Face", PrimitiveType::Cube,
        {19.88f, 0.72f, 0.0f}, {0.025f, 1.45f, 39.8f}, inner);

    addObject(scene, "West Inner Wall Face", PrimitiveType::Cube,
        {-19.88f, 0.72f, 0.0f}, {0.025f, 1.45f, 39.8f}, inner);

    addObject(scene, "Central Yellow Platform", PrimitiveType::Cylinder,
        {0.0f, 0.38f, 0.0f}, {2.8f, 0.38f, 2.8f}, yellow);

    addObject(scene, "Central Yellow Upper Platform", PrimitiveType::Cylinder,
        {0.0f, 0.88f, 0.0f}, {1.35f, 0.22f, 1.35f}, yellow);

    addObject(scene, "Yellow Column 01", PrimitiveType::Cylinder,
        {-8.0f, 1.0f, -7.0f}, {0.55f, 1.0f, 0.55f}, yellow);

    addObject(scene, "Yellow Column 02", PrimitiveType::Cylinder,
        {8.0f, 1.0f, -7.0f}, {0.55f, 1.0f, 0.55f}, yellow);

    addObject(scene, "Yellow Column 03", PrimitiveType::Cylinder,
        {-8.0f, 1.0f, 7.0f}, {0.55f, 1.0f, 0.55f}, yellow);

    addObject(scene, "Yellow Column 04", PrimitiveType::Cylinder,
        {8.0f, 1.0f, 7.0f}, {0.55f, 1.0f, 0.55f}, yellow);

    addObject(scene, "Yellow Column 05", PrimitiveType::HalfCylinder,
        {-13.0f, 0.85f, 0.0f}, {0.8f, 0.85f, 0.8f}, yellow);

    addObject(scene, "Yellow Column 06", PrimitiveType::HalfCylinder,
        {13.0f, 0.85f, 0.0f}, {0.8f, 0.85f, 0.8f}, yellow);

    addObject(scene, "Gray Ramp A", PrimitiveType::Ramp,
        {-5.0f, 0.45f, -1.8f}, {3.5f, 0.9f, 2.2f}, gray,
        {0.0f, 0.0f, -18.0f});

    addObject(scene, "Gray Ramp B", PrimitiveType::Ramp,
        {-9.0f, 0.35f, 2.2f}, {2.8f, 0.7f, 2.0f}, gray,
        {0.0f, 0.0f, 16.0f});

    addObject(scene, "Northwest Yellow Wedge", PrimitiveType::Wedge,
        {-15.0f, 0.55f, -14.0f}, {3.0f, 1.1f, 3.0f}, yellow);

    addObject(scene, "Northeast Yellow Wedge", PrimitiveType::Wedge,
        {15.0f, 0.55f, -14.0f}, {3.0f, 1.1f, 3.0f}, yellow);

    addObject(scene, "Southwest Yellow Wedge", PrimitiveType::Wedge,
        {-15.0f, 0.55f, 14.0f}, {3.0f, 1.1f, 3.0f}, yellow);

    addObject(scene, "Southeast Yellow Wedge", PrimitiveType::Wedge,
        {15.0f, 0.55f, 14.0f}, {3.0f, 1.1f, 3.0f}, yellow);

    addObject(scene, "Foreground Gray Block", PrimitiveType::Cube,
        {-2.0f, 1.0f, 17.5f}, {5.0f, 2.0f, 3.0f}, gray);

    addMannequin(scene, {5.0f, 0.0f, -11.0f}, "Mannequin A");
    addMannequin(scene, {-11.0f, 0.0f, 5.0f}, "Mannequin B");

    scene.deselect();
}

}
