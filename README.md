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


## On-device AI assistant (Android)

The in-app AI assistant now connects to a **local llama.cpp server running on the same Android phone**. It does not send prompts to Groq or another cloud AI provider. This is an Ollama-style local OpenAI-compatible endpoint, but it is **llama.cpp, not the official Ollama Android app**.

### Phone setup (Termux)

Use Termux on the same phone as Novaingine. The first setup needs internet to download the runtime and model; after that, inference can run offline.

1. Install the build tools:
   `pkg update && pkg upgrade -y`
   `pkg install git cmake clang make curl -y`
2. Build llama.cpp's server:
   `git clone https://github.com/ggml-org/llama.cpp.git`
   `cd llama.cpp`
   `cmake -B build -DCMAKE_BUILD_TYPE=Release -DGGML_OPENMP=OFF`
   `cmake --build build --config Release --target llama-server -j 4`
3. Download the recommended Qwen3 4B Q4_K_M model (about 2.5 GB):
   `mkdir -p ~/models`
   `curl -L --fail -o ~/models/Qwen3-4B-Q4_K_M.gguf https://huggingface.co/Qwen/Qwen3-4B-GGUF/resolve/main/Qwen3-4B-Q4_K_M.gguf`
4. Start the server and leave Termux running:
   `~/llama.cpp/build/bin/llama-server -m ~/models/Qwen3-4B-Q4_K_M.gguf --host 127.0.0.1 --port 8080 -c 2048 -t 4 --alias nova-local --jinja`
5. Open Novaingine → Tools → AI Assistant. It connects to `http://127.0.0.1:8080/v1/chat/completions`.

### Android memory notes

- Start with Qwen3 4B Q4_K_M and a 2048-token context. The model file is approximately 2.5 GB.
- The phone's 8 GB physical RAM is the relevant capacity; the extra 5 GB extended RAM is storage-backed and much slower.
- If Android closes Termux or Novaingine due to memory pressure, use a smaller 1.7B GGUF model or reduce the context to 1024.
- Keep the server bound to `127.0.0.1`; do not expose it to the Wi-Fi network.
- The assistant's existing scene tools still execute inside Novaingine. Local inference generates the tool calls; the engine validates and applies them.
- The server must be running before sending a prompt. Novaingine does not yet start or manage the Termux process itself.

Official runtime and model references: [llama.cpp Android guide](https://github.com/ggml-org/llama.cpp/blob/master/docs/android.md), [Qwen3 4B GGUF](https://huggingface.co/Qwen/Qwen3-4B-GGUF).

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
