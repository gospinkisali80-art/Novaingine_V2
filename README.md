# Novaingine V2

An original native Android 3D engine project.

## Technologies

- C++17
- Java
- JNI
- OpenGL ES 3.0
- Android NDK
- CMake
- Gradle

## First milestone

The first milestone targets a native Android application
with a GLES 3.0 renderer, a rotating textured cube,
touch rotation and an Android landscape interface.

## Build

GitHub Actions builds the debug APK.

Open the Actions tab in the GitHub repository,
run the Novaingine V2 Android Build workflow,
and download the Novaingine-V2-debug artifact.

## Project status

Development in progress.


## Grok AI assistant (Android)

Novaingine's in-app AI assistant connects directly to the xAI Grok API. No local model, llama.cpp server, Termux runtime, Groq provider, or other model provider is used by the Android assistant.

### Connect Grok

1. Install and open Novaingine.
2. Open **Tools → AI Assistant**.
3. Tap **API KEY** and enter your xAI API key.
4. Enter a scene request such as "Build a detailed treehouse" or "Sculpt a dragon".
5. Grok can call Novaingine's validated scene tools. The app executes mesh creation, sculpt brush passes, transforms, terrain and foliage operations locally in the native engine.

The key is stored in the app's private SharedPreferences on the device. It is not embedded in the repository or APK source. Do not share the key or commit it to GitHub. This client-side storage is not a substitute for a secure server-side secret vault on a device that may be rooted or compromised.

**Billing warning:** xAI API access is billed by usage and is not the same as free access to Grok on its website. An API key alone does not provide free API credits. Check the current xAI API pricing and account billing before making requests. The app does not enforce a spending limit.

Official docs: [xAI API quickstart](https://docs.x.ai/overview), [Function calling](https://docs.x.ai/developers/tools/function-calling), [Pricing](https://x.ai/api).

## Blender desktop companion (separate from Android local AI)

Novaingine can import triangle meshes from binary glTF files (`.glb`) through
**Tools → WORLD BUILDING → Import Blender Model (.glb)**.

The Windows companion at `tools/blender_bridge/nova_groq_blender.py` asks
Groq Llama 3.3 70B for a validated mesh plan, opens Blender in background mode,
creates mesh objects and PBR base-color/roughness/metallic materials, saves a
`.blend` project, and exports a `.glb` file.

### Windows setup

1. Install Blender for Windows and Python 3.10 or newer.
2. Open PowerShell in the repository folder.
3. Set your Groq key for this PowerShell session (do not paste it into source code):
   `$env:GROQ_API_KEY = "YOUR_GROQ_API_KEY"`
4. Run the companion, replacing the Blender path and prompt:
   `python .\tools\blender_bridge\nova_groq_blender.py "A detailed modern two-storey house with a balcony and tiled roof" --blender "C:\Program Files\Blender Foundation\Blender 4.5\blender.exe" --output ".\Novaingine_Asset.glb"`
5. Copy the generated GLB to your Android phone.
6. In Novaingine open **Tools → Import Blender Model (.glb)** and select the file.

### Current importer scope

- Imports triangle mesh primitives and vertex positions, normals, UV0, and glTF PBR
  base-color, roughness, and metallic factors.
- Imports up to 200 mesh parts, with per-mesh vertex and scene-wide memory limits.
- GLB node hierarchy transforms, image textures, animation, skins, morph targets,
  non-triangle topology, and round-trip persistence of imported mesh buffers are
  not implemented yet. Imported mesh data is currently held in GPU memory for
  the active scene; save/load of a `.nova` file does not package the GLB data.
- The Groq companion uses bounded JSON mesh data rather than executing model-
  generated Python code. Its geometry detail is limited by the mesh-plan limits.

This is an initial working integration layer, not a full Blender runtime embedded
inside Android. Blender runs on the desktop; Novaingine imports the exported GLB.
