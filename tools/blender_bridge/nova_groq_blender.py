#!/usr/bin/env python3
"""Generate a validated mesh scene with Groq, then build and export it with Blender."""
import argparse
import json
import os
import pathlib
import subprocess
import sys
import urllib.error
import urllib.request

MODEL = "openai/gpt-oss-20b"
API_URL = "https://api.groq.com/openai/v1/chat/completions"

SYSTEM_PROMPT = """You are a professional 3D modeler creating a Blender scene for a mobile game engine.
Return ONLY valid JSON with this shape:
{
  "scene_name": "short name",
  "objects": [
    {
      "name": "unique mesh name",
      "vertices": [[x,y,z], ...],
      "faces": [[i,j,k,...], ...],
      "location": [x,y,z],
      "rotation": [x,y,z],
      "scale": [x,y,z],
      "color": [r,g,b,1],
      "roughness": 0.5,
      "metallic": 0.0
    }
  ]
}
Create actual connected, closed or intentionally open polygon meshes, not descriptions.
Use clean topology, consistent winding, useful bevel-like supporting geometry, realistic proportions,
separate logical mesh parts, and coherent materials. Coordinates are local to each object.
Keep the full asset centered near the origin and fit within roughly 20 Blender units.
Create between 3 and 12 mesh objects. Each object may have at most 500 vertices and 800 faces. Build recognizable assets from separate connected body parts, with intentional proportions and coherent topology.
Use triangles or quads. Face indices are zero-based. Do not include scripts, markdown, comments,
external files, textures, or unsupported fields. Avoid excessive tiny details. Make the requested
asset recognizable and structurally coherent. All numeric values must be finite.
"""

BLENDER_BUILD = r'''
import bpy, json, math, sys
from mathutils import Euler
args = sys.argv[sys.argv.index("--") + 1:]
plan_path, output_glb, output_blend = args[0], args[1], args[2]
with open(plan_path, "r", encoding="utf-8") as f:
    plan = json.load(f)
bpy.ops.object.select_all(action="SELECT")
bpy.ops.object.delete(use_global=False)
for item in plan["objects"]:
    mesh = bpy.data.meshes.new(item["name"] + " Mesh")
    mesh.from_pydata(item["vertices"], [], item["faces"])
    mesh.validate(verbose=False)
    mesh.update()
    obj = bpy.data.objects.new(item["name"], mesh)
    bpy.context.collection.objects.link(obj)
    obj.location = item["location"]
    obj.rotation_euler = Euler(item["rotation"], "XYZ")
    obj.scale = item["scale"]
    mat = bpy.data.materials.new(item["name"] + " Material")
    mat.diffuse_color = item["color"]
    mat.use_nodes = True
    bsdf = mat.node_tree.nodes.get("Principled BSDF")
    if bsdf:
        bsdf.inputs["Base Color"].default_value = item["color"]
        bsdf.inputs["Roughness"].default_value = item["roughness"]
        bsdf.inputs["Metallic"].default_value = item["metallic"]
    obj.data.materials.append(mat)
    for poly in mesh.polygons:
        poly.use_smooth = True
    bevel_width = max(0.0, min(float(item.get("bevel", 0.0)), 0.15))
    if bevel_width > 0.0001:
        bevel = obj.modifiers.new("Crafted Edge Bevel", "BEVEL")
        bevel.width = bevel_width
        bevel.segments = 3
        bevel.limit_method = "ANGLE"
    subdivision = max(0, min(int(item.get("subdivision", 0)), 2))
    if subdivision:
        sub = obj.modifiers.new("Surface Refinement", "SUBSURF")
        sub.levels = subdivision
        sub.render_levels = subdivision
    voxel_size = max(0.0, min(float(item.get("voxel_remesh", 0.0)), 0.25))
    if voxel_size > 0.0001:
        remesh = obj.modifiers.new("Voxel Remesh", "REMESH")
        remesh.mode = "VOXEL"
        remesh.voxel_size = voxel_size
    bpy.context.view_layer.objects.active = obj
    obj.select_set(True)
    bpy.ops.object.transform_apply(location=True, rotation=True, scale=True)
    obj.select_set(False)
bpy.ops.object.select_all(action="SELECT")
bpy.context.view_layer.objects.active = bpy.context.selected_objects[0] if bpy.context.selected_objects else None
bpy.ops.wm.save_as_mainfile(filepath=output_blend)
bpy.ops.export_scene.gltf(filepath=output_glb, export_format="GLB", export_apply=True)
print("NOVA_EXPORT_OK", output_glb, len(plan["objects"]))
'''

def finite_number(value):
    return isinstance(value, (int, float)) and math_is_finite(value)

def math_is_finite(value):
    return value == value and abs(value) != float("inf")

def validate_plan(plan):
    if not isinstance(plan, dict) or not isinstance(plan.get("objects"), list):
        raise ValueError("Groq did not return a scene object list.")
    objects = plan["objects"]
    if not 1 <= len(objects) <= 24:
        raise ValueError("Scene must contain between 1 and 24 mesh parts.")
    total_vertices = 0
    cleaned = []
    for index, obj in enumerate(objects):
        if not isinstance(obj, dict):
            raise ValueError("Invalid mesh entry.")
        vertices, faces = obj.get("vertices"), obj.get("faces")
        if not isinstance(vertices, list) or not isinstance(faces, list):
            raise ValueError("Every mesh needs vertices and faces.")
        if not 3 <= len(vertices) <= 500 or not 1 <= len(faces) <= 800:
            raise ValueError("Mesh exceeds vertex/face limits.")
        total_vertices += len(vertices)
        if total_vertices > 12000:
            raise ValueError("Scene exceeds the total vertex limit.")
        verts = []
        for v in vertices:
            if not isinstance(v, list) or len(v) != 3 or not all(finite_number(n) and abs(n) <= 100 for n in v):
                raise ValueError("Invalid vertex coordinate.")
            verts.append([float(n) for n in v])
        checked_faces = []
        for face in faces:
            if not isinstance(face, list) or not 3 <= len(face) <= 4:
                raise ValueError("Faces must be triangles or quads.")
            if not all(isinstance(i, int) and 0 <= i < len(verts) for i in face):
                raise ValueError("Face index is outside the vertex array.")
            checked_faces.append(face)
        def vector(key, default, limit):
            value = obj.get(key, default)
            if not isinstance(value, list) or len(value) != 3 or not all(finite_number(n) and abs(n) <= limit for n in value):
                raise ValueError("Invalid " + key + " value.")
            return [float(n) for n in value]
        color = obj.get("color", [0.65, 0.68, 0.72, 1.0])
        if not isinstance(color, list) or len(color) != 4 or not all(finite_number(n) and 0 <= n <= 1 for n in color):
            raise ValueError("Material color must be four values between 0 and 1.")
        roughness = obj.get("roughness", 0.55)
        metallic = obj.get("metallic", 0.0)
        if not finite_number(roughness) or not 0.04 <= roughness <= 1:
            raise ValueError("Roughness must be between 0.04 and 1.")
        if not finite_number(metallic) or not 0 <= metallic <= 1:
            raise ValueError("Metallic must be between 0 and 1.")
        bevel = obj.get("bevel", 0.02)
        subdivision = obj.get("subdivision", 1)
        voxel_remesh = obj.get("voxel_remesh", 0.0)
        if not finite_number(bevel) or not 0 <= bevel <= 0.15:
            raise ValueError("Bevel must be between 0 and 0.15.")
        if not isinstance(subdivision, int) or not 0 <= subdivision <= 2:
            raise ValueError("Subdivision must be an integer from 0 to 2.")
        if not finite_number(voxel_remesh) or not 0 <= voxel_remesh <= 0.25:
            raise ValueError("Voxel remesh size must be between 0 and 0.25.")
        cleaned.append({
            "bevel": float(bevel), "subdivision": subdivision, "voxel_remesh": float(voxel_remesh),
            "name": str(obj.get("name", "Mesh Part " + str(index + 1)))[:64],
            "vertices": verts, "faces": checked_faces,
            "location": vector("location", [0, 0, 0], 100),
            "rotation": vector("rotation", [0, 0, 0], 6.28319),
            "scale": vector("scale", [1, 1, 1], 20),
            "color": [float(n) for n in color],
            "roughness": float(roughness), "metallic": float(metallic)
        })
    return {"scene_name": str(plan.get("scene_name", "Novaingine Asset"))[:64], "objects": cleaned}

def groq_generate(prompt, api_key):
    payload = {
        "model": MODEL,
        "messages": [
            {"role": "system", "content": SYSTEM_PROMPT},
            {"role": "user", "content": "Create this 3D asset: " + prompt}
        ],
        "temperature": 0.25,
        "max_completion_tokens": 6000,
        "reasoning_effort": "low",
        "include_reasoning": False,
        "response_format": {"type": "json_object"}
    }
    request = urllib.request.Request(
        API_URL, data=json.dumps(payload).encode("utf-8"),
        headers={"Authorization": "Bearer " + api_key, "Content-Type": "application/json"},
        method="POST"
    )
    try:
        with urllib.request.urlopen(request, timeout=180) as response:
            result = json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as exc:
        detail = exc.read().decode("utf-8", errors="replace")[:1500]
        raise RuntimeError("Groq API error " + str(exc.code) + ": " + detail)
    choices = result.get("choices", [])
    if not choices:
        raise RuntimeError("Groq returned no completion.")
    text = choices[0].get("message", {}).get("content")
    if not text:
        raise RuntimeError("Groq returned an empty model response.")
    return validate_plan(json.loads(text))

def main():
    parser = argparse.ArgumentParser(description="Groq + Blender model generator for Novaingine")
    parser.add_argument("prompt", help="Describe the 3D asset to create")
    parser.add_argument("--blender", required=True, help="Full path to blender.exe")
    parser.add_argument("--output", default="Novaingine_Asset.glb", help="Output GLB filename")
    args = parser.parse_args()
    api_key = os.environ.get("GROQ_API_KEY")
    if not api_key:
        raise SystemExit("Set GROQ_API_KEY in your environment first. Never put the key in this script.")
    blender = pathlib.Path(args.blender)
    if not blender.is_file():
        raise SystemExit("Blender executable not found: " + str(blender))
    output = pathlib.Path(args.output).resolve()
    output.parent.mkdir(parents=True, exist_ok=True)
    work = output.parent / (output.stem + "_work")
    work.mkdir(exist_ok=True)
    plan_path = work / "scene_plan.json"
    build_path = work / "build_scene.py"
    blend_path = output.with_suffix(".blend")
    plan = groq_generate(args.prompt, api_key)
    plan_path.write_text(json.dumps(plan, indent=2), encoding="utf-8")
    build_path.write_text(BLENDER_BUILD, encoding="utf-8")
    command = [str(blender), "--background", "--factory-startup", "--python", str(build_path),
               "--", str(plan_path), str(output), str(blend_path)]
    subprocess.run(command, check=True)
    if not output.is_file() or output.stat().st_size < 20:
        raise SystemExit("Blender did not produce a valid GLB output.")
    print("SUCCESS")
    print("GLB:", output)
    print("BLEND:", blend_path)
    print("Mesh parts:", len(plan["objects"]))
    print("Model:", MODEL)

if __name__ == "__main__":
    main()
