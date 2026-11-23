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


## Groq AI assistant (Android)

Novaingine's Android AI assistant uses the Groq Chat Completions API with the `openai/gpt-oss-120b` model. It does not use xAI/Grok, local llama.cpp, Termux, or a local model server.

### Connect Groq

1. Open **Tools → AI Assistant**.
2. Tap **API KEY** and enter a Groq API key managed by the account owner/parent or guardian.
3. Describe an object or scene, for example "Build a detailed treehouse" or "Create a low-poly sports car".
4. Groq returns chat responses and function calls. Novaingine validates and executes its mesh creation, sculpting, transforms, terrain and foliage tools locally in the native engine.

Endpoint: `https://api.groq.com/openai/v1/chat/completions`

Model: `openai/gpt-oss-120b`

The API key is stored in the app's private SharedPreferences on the device. It is not embedded in the repository or APK source. Never share the key or commit it to GitHub. This client-side storage is not a substitute for a secure server-side secret vault.

**Free-tier note:** Groq provides a rate-limited free tier. Limits depend on the account and model and may change; check the Groq Limits page. Requests may be rejected when an account reaches its limits. The older `llama-3.3-70b-versatile` model from the original Python example was retired from free and developer tiers on August 16, 2026; Novaingine therefore uses the current GPT-OSS 120B model instead.

Official docs: [Groq models](https://console.groq.com/docs/models), [Rate limits](https://console.groq.com/docs/rate-limits), [Tool use](https://console.groq.com/docs/tool-use/local-tool-calling).

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
