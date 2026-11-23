#pragma once

#include <algorithm>
#include <cstdint>
#include <string>
#include <unordered_map>
#include <vector>

#include "../Scene/Scene.h"

namespace nova {

enum class VisualNodeType : uint8_t {
    BeginPlay,
    Tick,
    MoveObject,
    SetObjectVisible = 4,
    Branch = 5,
    End = 6,
    RotateObject = 7,
    ScaleObject = 8
};

struct VisualNode {
    uint32_t id = 0;
    VisualNodeType type = VisualNodeType::End;

    // Execution outputs. Branch uses trueNext and falseNext.
    uint32_t next = 0;
    uint32_t trueNext = 0;
    uint32_t falseNext = 0;

    // Object target. Zero means the currently selected object.
    uint32_t objectId = 0;

    // MoveObject applies this world-space offset.
    Vec3 vectorValue = Vec3();

    // Branch compares this value against compareValue.
    float value = 0.0f;
    float compareValue = 0.0f;
    bool condition = true;
};

struct VisualScript {
    std::string name = "New Visual Script";
    std::vector<VisualNode> nodes;
    bool enabled = true;
    bool runOnBeginPlay = true;
    bool runOnTick = false;
};

struct VisualScriptResult {
    bool success = false;
    uint32_t executedNodes = 0;
    std::string message;
};

class VisualScriptRuntime {
public:
    static constexpr uint32_t MaxNodesPerRun = 256;

    VisualScriptResult execute(
        const VisualScript& script,
        Scene& scene,
        bool beginPlay,
        float deltaTime = 0.0f) const {

        VisualScriptResult result;

        if (!script.enabled) {
            result.message = "Script is disabled";
            return result;
        }

        if ((beginPlay && !script.runOnBeginPlay) ||
            (!beginPlay && !script.runOnTick)) {
            result.success = true;
            result.message = "Event not enabled for this script";
            return result;
        }

        std::unordered_map<uint32_t, const VisualNode*> lookup;
        lookup.reserve(script.nodes.size());

        for (const VisualNode& node : script.nodes) {
            if (node.id == 0 || lookup.count(node.id) != 0) {
                result.message = "Invalid or duplicate visual node ID";
                return result;
            }
            lookup.emplace(node.id, &node);
        }

        uint32_t current = 0;
        for (const VisualNode& node : script.nodes) {
            if ((beginPlay && node.type == VisualNodeType::BeginPlay) ||
                (!beginPlay && node.type == VisualNodeType::Tick)) {
                current = node.id;
                break;
            }
        }

        if (current == 0) {
            result.success = true;
            result.message = "No matching event node";
            return result;
        }

        for (uint32_t step = 0;
             current != 0 && step < MaxNodesPerRun;
             ++step) {

            const auto found = lookup.find(current);
            if (found == lookup.end()) {
                result.message = "Execution link points to a missing node";
                return result;
            }

            const VisualNode& node = *found->second;
            ++result.executedNodes;

            uint32_t targetId = node.objectId != 0
                ? node.objectId : scene.selectedId();

            switch (node.type) {
                case VisualNodeType::BeginPlay:
                case VisualNodeType::Tick:
                    current = node.next;
                    break;

                case VisualNodeType::MoveObject: {
                    const float factor = beginPlay ? 1.0f : std::clamp(deltaTime, 0.0f, 0.1f);
                    if (!scene.moveObject(targetId, node.vectorValue * factor)) {
                        result.message = "Move node could not find its target object";
                        return result;
                    }
                    current = node.next;
                    break;
                }

                case VisualNodeType::RotateObject: {
                    SceneObject* object = scene.find(targetId);
                    if (!object) { result.message = "Rotate node could not find its target object"; return result; }
                    const float factor = beginPlay ? 1.0f : std::clamp(deltaTime, 0.0f, 0.1f);
                    object->transform.rotation = object->transform.rotation + node.vectorValue * factor;
                    current = node.next;
                    break;
                }

                case VisualNodeType::ScaleObject: {
                    SceneObject* object = scene.find(targetId);
                    if (!object) { result.message = "Scale node could not find its target object"; return result; }
                    const float factor = beginPlay ? 1.0f : std::clamp(deltaTime, 0.0f, 0.1f);
                    object->transform.scale = object->transform.scale + node.vectorValue * factor;
                    object->transform.scale.x = std::max(0.01f, object->transform.scale.x);
                    object->transform.scale.y = std::max(0.01f, object->transform.scale.y);
                    object->transform.scale.z = std::max(0.01f, object->transform.scale.z);
                    current = node.next;
                    break;
                }

                case VisualNodeType::SetObjectVisible:
                    if (!scene.setVisibility(targetId, node.condition)) {
                        result.message = "Visibility node could not find its target object";
                        return result;
                    }
                    current = node.next;
                    break;

                case VisualNodeType::Branch:
                    current = node.value >= node.compareValue
                        ? node.trueNext : node.falseNext;
                    break;

                case VisualNodeType::End:
                    result.success = true;
                    result.message = "Visual script completed";
                    return result;
            }
        }

        if (current != 0) {
            result.message = "Execution stopped at the node safety limit";
            return result;
        }

        result.success = true;
        result.message = "Visual script completed";
        return result;
    }
};

} // namespace nova
