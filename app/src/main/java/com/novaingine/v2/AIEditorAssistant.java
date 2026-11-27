package com.novaingine.v2;

import android.app.Activity;
import android.text.InputType;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Groq cloud agent. Tool calls are validated and executed locally by Novaingine. */
public final class AIEditorAssistant {
    private static final String MODEL_ID = "qwen/qwen3.8-27b";
    private static final String GROQ_CHAT_URL = "https://api.groq.com/openai/v1/chat/completions";
    private static final String PREFS_NAME = "nova_groq_settings";
    private static final String PREF_API_KEY = "groq_api_key";
    private TextView modelInfo;
    private final Activity activity;
    private final EngineSurface engine;
    private final ManualSculptWorkspace meshyWorkspace;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final JSONArray history = new JSONArray();
    private TextView transcript;
    private EditText prompt;
    private ScrollView scroll;
    private AlertDialog dialog;
    private boolean busy;

    public AIEditorAssistant(Activity activity, EngineSurface engine) {
        this(activity, engine, null);
    }

    public AIEditorAssistant(Activity activity, EngineSurface engine, ManualSculptWorkspace meshyWorkspace) {
        this.activity = activity;
        this.engine = engine;
        this.meshyWorkspace = meshyWorkspace;
        JSONObject system = new JSONObject();
        try {
            system.put("role", "system");
            system.put("content", "You are NOVA AI, powered by Groq, the modeling and scene-building assistant inside Novaingine. When asked to build an object, character, vehicle, building, prop or environment, CREATE REAL VISIBLE 3D GEOMETRY IN THE CURRENT SCENE, not just explain how. For a detailed fantasy humanoid such as an orc, use a dedicated character-production plan rather than a generic sphere or primitive pile: establish a consistent front-facing coordinate convention (X width, Y height, Z depth), total height about 4.5-5.5 world units, and create connected overlapping anatomical masses for pelvis, ribcage, abdomen, neck, skull, jaw, brow, cheekbones, nose, ears, shoulders, upper/lower arms, hands, thighs, calves, feet. Build the head as a focal asset with a projecting lower jaw, heavy brow ridge, cheek planes, nostril forms, separate upper/lower lip forms, visible tusks, pointed ears, and separate eyeball forms. Add shoulder/forearm armor, chest straps, belt, layered plates, cloth/leather pieces and restrained surface ornaments as distinct named meshes. Use ellipsoids only for primary masses; use tapered sweep meshes for tusks, horns, fingers, straps, seams and trim; use custom mesh parts for armor silhouettes and facial planes. Ensure limbs attach and overlap at joints, hands have a palm and individually shaped fingers, feet have toes/boots, and the silhouette reads clearly in front, side and three-quarter views. Use the dense sculpt mesh for broad organic transitions and apply multiple local sculpt strokes with Draw/Clay/Inflate, Grab, Crease, Pinch, Flatten and Smooth to refine the head, muscles and joints. Keep armor and clothing separate from skin so materials can differ. Use material contrast between skin, leather, metal and cloth; do not pretend geometry alone creates painted pores, wrinkles or texture maps. Create in stages (body blockout, head, limbs/hands/feet, armor/clothing, face/tusks, surface refinement), inspect after every stage, and fix disconnected or badly proportioned parts before continuing. Aim for a complete recognizable character with coherent anatomy, not a handful of primitive shapes. If the available geometry tools cannot support a requested detail, state that limitation instead of claiming it was created. Prefer create_lathe_mesh for rotational mechanical/architectural parts and create_ellipsoid_mesh for proportioned organic/body forms; these generate real high-resolution meshes locally with very few API tokens. Before building, plan the object's silhouette, proportions, primary masses, secondary forms, and small details. Build substantial assets from 10-24 deliberately proportioned parts when the request needs that complexity, grouped into coherent assemblies; never make a pile of unrelated primitives. Use create_ellipsoid_mesh for smooth organic masses, create_lathe_mesh for rotational parts with varied radius profiles, and create_sweep_mesh for curved limbs, pipes, cables, horns, handles, rails, branches, trim and other continuous forms. Sweep paths must follow the actual shape; vary per-point radii to taper ends and avoid blunt uniform tubes. Use create_mesh_part for distinctive hard-surface or irregular forms. Give every part a purposeful name, plausible scale, sensible overlap/joints, and consistent materials. Make the silhouette recognizable from a distance first, then add layered panels, seams, rims, recesses and small details. Avoid floating disconnected details and excessive symmetry where the real object is asymmetric. After each group inspect_scene, continue refining, and verify the final object count and placement. Build recognizable silhouettes, varied cross-sections, layered forms, and connected surfaces. After each logical group inspect_scene, then continue refining. Do not stop after creating a base shape. Each mesh part supports up to 3000 vertices and 6000 triangles, local coordinates -20..20. Use extrude_face, inset_face, bevel_edge, loop_cut, subdivide_mesh, merge_vertices, and knife_cut on the selected mesh to perform real topology edits. Use faceIndex or edgeIndex starting at 0 unless scene inspection supplies a better index. Subdivision is capped at 3 levels and Android mesh complexity is capped. Do not claim a tool operation succeeded unless its result confirms success. Use coherent surfaces and sensible placement; do not make every object from identical boxes. After creating parts, inspect the scene and report exactly what was created. For organic assets, create a dense sculpt mesh first (sphere for characters/creatures/rounded props; plane for terrain-like forms), density 64-80 when mobile performance permits. Then use sculpt_selected_mesh as a real SculptMesh brush stroke tool, not the old low-detail mesh modifier. Brush centers and every point are LOCAL mesh coordinates; X is left/right, Y is up, Z is depth. Use points as a flat list of xyz triplets to draw continuous paths; the engine interpolates dabs at 25% of brush radius. If points are omitted, x/y/z is a single dab. Build organic forms in stages: primary masses with Draw/Clay/Inflate, pull silhouette features with Grab, carve separations with Crease/Pinch, flatten planes with Flatten/Scrape, protect finished regions with Mask, then Smooth lightly to remove lumps. Use several deliberate strokes at different centers and radii rather than one giant dab. Use X symmetry for centered characters and vehicles, Y/Z only when appropriate. Grab accepts dragX/dragY/dragZ for a one-point pull or a points path for a continuous pull. Radius is 0.02-1.0 world units, strength 0-1, hardness/falloff 0-1; subtract reverses Draw, Clay and Inflate. Wait for queued strokes to process before relying on later scene inspection. The AI must make recognizable silhouettes, proportionate anatomy, layered forms, and refined transitions, not merely inflate a sphere. If editing an existing object, inspect and select it first. Preserve all existing scene content unless the user explicitly asks to remove it. Keep Blender desktop bridge and GLB import available. Use existing terrain and scene tools when relevant. Keep operations moderate for Android performance. Never claim a model exists unless creation tools confirm success. If a geometry or sculpt tool fails, read its exact error, diagnose the likely cause, and automatically try one corrected alternative (adjust parameters, inspect/select the correct mesh, or use a safer supported operation). Do not repeat an identical failing call. If the corrected retry also fails, stop and explain the actual reason. Never claim success without a successful tool result. Be honest about current sculpting limits and do not claim Blender or Unreal parity.");
            history.put(system);
            if (meshyWorkspace != null) {
                JSONObject meshSystem = new JSONObject();
                meshSystem.put("role", "system");
                meshSystem.put("content", "You are NOVA AI inside Meshy3D. All scene inspection and edits MUST target the Meshy3D document, never the separate Nova Engine runtime scene. Use only the Meshy3D tools provided. Inspect first, preserve existing objects, create actual indexed mesh geometry, use named objects and sensible proportions, and verify tool results. For component operations enter Edit Mode and select components before modifying them. Meshy3D exposes real object transforms, vertex/edge/face selection, extrude, inset, bevel, subdivision, smoothing, merge, UV unwrap, material settings, shader/geometry nodes and animation keys. Do not claim Blender parity or pretend an unsupported feature worked. Use the full 25-brush sculpt tool when appropriate. Texture paint can create a real texture and paint brush dabs in UV image pixel coordinates. Never claim that unsupported node connections or unsupported unwrap algorithms were created.");
                history.put(meshSystem);
            }
        } catch (Exception ignored) {}
    }

    public void show() {
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(12), dp(10), dp(12), dp(8));
        root.setBackgroundColor(0xFF171D27);

        LinearLayout top = new LinearLayout(activity);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(activity);
        title.setText("NOVA AI  •  GROQ CLOUD");
        title.setTextColor(0xFFFFB45C);
        title.setTextSize(16);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        top.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        Button setupButton = new Button(activity);
        setupButton.setText("API KEY");
        setupButton.setOnClickListener(v -> showGroqKeyDialog());
        top.addView(setupButton);
        root.addView(top);

        modelInfo = new TextView(activity);
        updateModelInfo();
        modelInfo.setTextColor(0xFF9EADBE);
        modelInfo.setTextSize(10);
        root.addView(modelInfo);

        scroll = new ScrollView(activity);
        scroll.setFillViewport(true);
        transcript = new TextView(activity);
        transcript.setTextColor(Color.WHITE);
        transcript.setTextSize(13);
        transcript.setPadding(dp(10), dp(10), dp(10), dp(10));
        transcript.setText("Novaingine Groq AI is ready to connect.\n\nAdd your Groq API key using API KEY, then try: “Sculpt a detailed dragon” or “Build a modern house.”\n\nGroq offers a rate-limited free tier. Check your account limits before sending requests.");
        scroll.addView(transcript);
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(-1, 0, 1);
        scrollParams.topMargin = dp(8);
        scrollParams.bottomMargin = dp(8);
        root.addView(scroll, scrollParams);

        LinearLayout sendRow = new LinearLayout(activity);
        sendRow.setGravity(Gravity.CENTER_VERTICAL);
        prompt = new EditText(activity);
        prompt.setSingleLine(false);
        prompt.setMinLines(1);
        prompt.setMaxLines(3);
        prompt.setTextColor(Color.WHITE);
        prompt.setHintTextColor(0xFF9EADBE);
        prompt.setHint("Describe what you want Novaingine to build or sculpt...");
        prompt.setBackgroundColor(0xFF2A3442);
        sendRow.addView(prompt, new LinearLayout.LayoutParams(0, -2, 1));
        Button send = new Button(activity);
        send.setText("SEND");
        send.setOnClickListener(v -> sendPrompt());
        sendRow.addView(send);
        root.addView(sendRow);

        dialog = new AlertDialog.Builder(activity).setView(root)
                .setNegativeButton("CLOSE", null).create();
        dialog.show();
        dialog.getWindow().setLayout(
                Math.min(activity.getResources().getDisplayMetrics().widthPixels - dp(28), dp(680)),
                Math.min(activity.getResources().getDisplayMetrics().heightPixels - dp(40), dp(520))
        );
    }


    private String getModel() { return MODEL_ID; }

    private void updateModelInfo() {
        if (modelInfo != null) {
            boolean configured = !getApiKey().isEmpty();
            modelInfo.setText("Provider: Groq Qwen3.8-27B  |  " + (configured ? "API key saved on this device" : "API key required"));
        }
    }

    private String getApiKey() {
        return activity.getSharedPreferences(PREFS_NAME, Activity.MODE_PRIVATE)
                .getString(PREF_API_KEY, "").trim();
    }

    private void showGroqKeyDialog() {
        EditText keyInput = new EditText(activity);
        keyInput.setSingleLine(true);
        keyInput.setHint("Paste your Groq API key");
        keyInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        keyInput.setText(getApiKey());
        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(8), dp(20), dp(4));
        TextView warning = new TextView(activity);
        warning.setText("Stored in this app's private settings. Never share it or commit it to GitHub. Groq's free tier has usage limits. Ask a parent or guardian to manage the provider account and API key.");
        warning.setTextColor(0xFFB8C5D5);
        warning.setTextSize(12);
        content.addView(warning);
        content.addView(keyInput);
        new AlertDialog.Builder(activity).setTitle("Connect Groq").setView(content)
                .setPositiveButton("SAVE KEY", (d, w) -> {
                    String key = keyInput.getText().toString().trim();
                    if (key.isEmpty()) activity.getSharedPreferences(PREFS_NAME, Activity.MODE_PRIVATE).edit().remove(PREF_API_KEY).apply();
                    else activity.getSharedPreferences(PREFS_NAME, Activity.MODE_PRIVATE).edit().putString(PREF_API_KEY, key).apply();
                    updateModelInfo();
                    append(key.isEmpty() ? "\\nGroq API key cleared.\\n" : "\\nGroq API key saved on this device.\\n");
                })
                .setNegativeButton("CANCEL", null)
                .setNeutralButton("CLEAR", (d, w) -> {
                    activity.getSharedPreferences(PREFS_NAME, Activity.MODE_PRIVATE).edit().remove(PREF_API_KEY).apply();
                    updateModelInfo();
                }).show();
    }

    private void sendPrompt() {
        if (busy) return;
        String text = prompt.getText().toString().trim();
        if (text.isEmpty()) return;
        prompt.setText("");
        compactHistoryForNextTurn();
        append("\nYOU: " + text + "\n");
        busy = true;
        boolean sceneRequest = isSceneRequest(text);
        append(sceneRequest
                ? "GROQ: Planning and sculpting your scene...\\n"
                : "GROQ: Thinking...\\n");
        worker.execute(() -> {
            try {
                JSONObject user = new JSONObject();
                user.put("role", "user");
                user.put("content", text);
                history.put(user);
                String answer = sceneRequest ? runAgent() : runConversation();
                main.post(() -> {
                    append(answer + "\n");
                    busy = false;
                });
            } catch (Exception ex) {
                main.post(() -> {
                    append("Connection error: " + safeMessage(ex) + "\n");
                    busy = false;
                });
            }
        });
    }

    // Ordinary conversation deliberately omits the large scene-tool schema.
    // This keeps simple chat prompts fast and within the phone's context window.
    private boolean isSceneRequest(String text) {
        String q = text.toLowerCase(java.util.Locale.US);
        String[] actionWords = {
                "build ", "create ", "make ", "sculpt ", "model ", "generate ",
                "add ", "paint ", "place ", "move ", "rotate ", "scale ",
                "select ", "delete ", "remove ", "undo", "redo",
                "terrain", "mesh", "object", "scene", "house", "building",
                "character", "human", "face", "tree", "bush", "foliage",
                "extrude", "inset", "bevel", "loop cut", "loop-cut", "subdivide", "subdivision",
                "merge vertices", "merge by distance", "knife", "topology", "edit mesh", "vertex", "edge", "face mode"
        };
        for (String word : actionWords) {
            if (q.contains(word)) return true;
        }
        return false;
    }

    private String runConversation() throws Exception {
        // Fast chat path: do not prefill the long scene-agent instructions or old tool history.
        // Keep only a short system message and the latest user message for ordinary conversation.
        JSONArray chatMessages = new JSONArray();
        JSONObject chatSystem = new JSONObject();
        chatSystem.put("role", "system");
        chatSystem.put("content", meshyWorkspace != null
                ? "You are Nova AI inside Meshy3D. Answer questions about the current modeling workflow clearly. For actual edits, the agent tool path is used when the user asks you to create, modify, sculpt, paint or transform the document. Never claim a change unless a tool confirms it."
                : "You are Novaingine's helpful AI assistant. Answer clearly and briefly. Do not claim to change the 3D scene; use scene tools only for explicit modeling requests.");
        chatMessages.put(chatSystem);

        JSONObject latestUser = null;
        for (int i = history.length() - 1; i >= 1; i--) {
            JSONObject item = history.optJSONObject(i);
            if (item != null && "user".equals(item.optString("role"))) {
                latestUser = item;
                break;
            }
        }
        if (latestUser == null) throw new Exception("No user message was found.");
        String userText = latestUser.optString("content", "");
        userText = userText.replaceFirst("^/no_think\\\\n", "");
        userText = userText.replaceFirst("^/no_think\\n", "");
        JSONObject chatUser = new JSONObject();
        chatUser.put("role", "user");
        chatUser.put("content", userText);
        chatMessages.put(chatUser);

        JSONObject body = new JSONObject();
        body.put("model", getModel());
        body.put("messages", chatMessages);
        body.put("reasoning_effort", "low");
        body.put("max_completion_tokens", 1024);
        JSONObject response = postGroqModel(body);
        JSONObject choice = response.getJSONArray("choices").getJSONObject(0);
        JSONObject message = choice.getJSONObject("message");
        String content = message.optString("content", "").trim();
        if (content.isEmpty()) {
            String reason = choice.optString("finish_reason", "unknown");
            throw new Exception("Groq returned no text (finish reason: " + reason
                    + "). Try a shorter message or check your Groq API access and model availability.");
        }
        JSONObject assistant = new JSONObject();
        assistant.put("role", "assistant");
        assistant.put("content", content);
        history.put(assistant);
        return content;
    }

    private String runAgent() throws Exception {
        JSONArray tools = buildTools();
        int operationCount = 0;
        for (int step = 0; step < 12; step++) {
            JSONObject body = new JSONObject();
            body.put("model", getModel());
            body.put("messages", history);
            body.put("tools", tools);
            body.put("tool_choice", "auto");
            body.put("reasoning_effort", "medium");
            body.put("max_completion_tokens", 4096);
            body.put("parallel_tool_calls", false);
            JSONObject response = postGroqModel(body);
            JSONObject message = response.getJSONArray("choices").getJSONObject(0).getJSONObject("message");
            JSONArray calls = message.optJSONArray("tool_calls");
            if (calls == null || calls.length() == 0) {
                String content = message.optString("content", "").trim();
                JSONObject assistant = new JSONObject();
                assistant.put("role", "assistant");
                assistant.put("content", content);
                history.put(assistant);
                if (content.isEmpty()) {
                    return "Groq returned an empty completion. No scene changes were made. Please retry.";
                }
                return content;
            }
            JSONObject assistant = new JSONObject();
            assistant.put("role", "assistant");
            assistant.put("content", message.optString("content", ""));
            assistant.put("tool_calls", calls);
            history.put(assistant);
            for (int i = 0; i < calls.length(); i++) {
                JSONObject call = calls.getJSONObject(i);
                String id = call.optString("id");
                JSONObject fn = call.getJSONObject("function");
                String name = fn.getString("name");
                JSONObject args = new JSONObject(fn.optString("arguments", "{}"));
                if (++operationCount > 48) return "I reached the safe 48-operation limit. The scene contains the completed operations so far; give me another instruction to continue.";
                String result = executeTool(name, args);
                JSONObject toolMessage = new JSONObject();
                toolMessage.put("role", "tool");
                toolMessage.put("tool_call_id", id);
                toolMessage.put("content", result);
                history.put(toolMessage);
                final String status = "Tool: " + name + " → " + result + "\n";
                main.post(() -> append(status));
            }
        }
        return "I stopped after 12 tool steps to keep the editor responsive. You can give me the next instruction.";
    }

    private JSONObject postGroqModel(JSONObject body) throws Exception {
        String apiKey = getApiKey();
        if (apiKey.isEmpty()) throw new Exception("Add your Groq API key using the API KEY button first.");

        // Retry only transient network/server/rate-limit failures. Authentication,
        // invalid-request and other permanent 4xx errors are returned immediately.
        final int maxAttempts = 3;
        Exception lastError = null;
        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(GROQ_CHAT_URL).openConnection();
                connection.setRequestMethod("POST");
                connection.setConnectTimeout(8000);
                connection.setReadTimeout(180000);
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json");
                connection.setRequestProperty("Authorization", "Bearer " + apiKey);
                byte[] data = body.toString().getBytes(StandardCharsets.UTF_8);
                try (OutputStream out = connection.getOutputStream()) { out.write(data); }

                int code = connection.getResponseCode();
                java.io.InputStream stream = code >= 200 && code < 300
                        ? connection.getInputStream() : connection.getErrorStream();
                StringBuilder result = new StringBuilder();
                if (stream != null) {
                    try (BufferedReader reader = new BufferedReader(
                            new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = reader.readLine()) != null) result.append(line);
                    }
                }
                String responseText = result.toString();
                JSONObject json;
                try {
                    json = new JSONObject(responseText);
                } catch (Exception parseError) {
                    throw new Exception("Groq returned an unreadable response (HTTP " + code + ").");
                }
                if (code >= 200 && code < 300) return json;

                String message = json.optJSONObject("error") == null ? json.toString()
                        : json.getJSONObject("error").optString("message", json.toString());
                Exception apiError = new Exception("Groq API HTTP " + code + ": " + message);
                boolean temporary = code == 408 || code == 425 || code == 429
                        || code == 500 || code == 502 || code == 503 || code == 504;
                if (!temporary || attempt == maxAttempts - 1) throw apiError;
                lastError = apiError;
            } catch (java.io.IOException ex) {
                lastError = new Exception("Temporary connection problem: "
                        + (ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage()));
                if (attempt == maxAttempts - 1) throw lastError;
            } finally {
                if (connection != null) connection.disconnect();
            }

            long waitMs = 1800L * (attempt + 1);
            main.post(() -> append("NOVA AI: Temporary issue. Retrying automatically...\\n"));
            try {
                Thread.sleep(waitMs);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new Exception("AI retry was interrupted.");
            }
        }
        throw lastError == null ? new Exception("Groq request failed after automatic retries.") : lastError;
    }

    private void compactHistoryForNextTurn() {
        if (history.length() <= 9) return;
        JSONArray compact = new JSONArray();
        try {
            compact.put(history.getJSONObject(0));
            int kept = 0;
            for (int i = history.length() - 1; i >= 1 && kept < 8; i--) {
                JSONObject item = history.getJSONObject(i);
                String role = item.optString("role", "");
                if (("user".equals(role) || "assistant".equals(role))
                        && !item.has("tool_calls")) {
                    compact.put(item);
                    kept++;
                }
            }
            JSONArray ordered = new JSONArray();
            ordered.put(compact.getJSONObject(0));
            for (int i = compact.length() - 1; i >= 1; i--) ordered.put(compact.get(i));
            while (history.length() > 0) history.remove(history.length() - 1);
            for (int i = 0; i < ordered.length(); i++) history.put(ordered.get(i));
        } catch (Exception ignored) {
            // Preserve the current history if compaction cannot be completed.
        }
    }

    private JSONArray buildTools() throws Exception {
        if (meshyWorkspace != null) return buildMeshyTools();
        JSONArray tools = new JSONArray();
        tools.put(tool("inspect_scene", "List scene object IDs and names, and the selected object's transform.", "{\"type\":\"object\",\"properties\":{}}"));
        tools.put(tool("create_terrain", "Generate or replace the editable terrain patch.", "{\"type\":\"object\",\"properties\":{}}"));
        tools.put(tool("sculpt_terrain", "Sculpt terrain. Optional args: x defaults 0, z defaults -4, radius defaults 3, strength defaults 0.4, mode defaults raise. mode: raise, lower, smooth, flatten.", "{\"type\":\"object\",\"properties\":{\"x\":{\"type\":\"number\"},\"z\":{\"type\":\"number\"},\"radius\":{\"type\":\"number\"},\"strength\":{\"type\":\"number\"},\"mode\":{\"type\":\"string\",\"enum\":[\"raise\",\"lower\",\"smooth\",\"flatten\"]}}}"));
        tools.put(tool("create_lathe_mesh", "Generate a smooth rotational custom mesh from a compact radius/height profile. Excellent for detailed vehicle wheels, columns, barrels, handles, towers, bottles, limbs and mechanical parts. profile is [radius,height,...] with 2-32 pairs; segments 8-48. Coordinates are local; x/y/z place the part in the scene.", "{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\"},\"profile\":{\"type\":\"array\",\"items\":{\"type\":\"number\"},\"minItems\":4,\"maxItems\":64},\"segments\":{\"type\":\"integer\"},\"x\":{\"type\":\"number\"},\"y\":{\"type\":\"number\"},\"z\":{\"type\":\"number\"},\"r\":{\"type\":\"number\"},\"g\":{\"type\":\"number\"},\"b\":{\"type\":\"number\"}} ,\"required\":[\"profile\"]}"));
        tools.put(tool("create_ellipsoid_mesh", "Generate a smooth custom ellipsoid mesh with independent X/Y/Z radii. Use for anatomically proportioned heads, muscles, tires, body panels and organic forms. rings 8-32, segments 12-48. x/y/z place its center.", "{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\"},\"rx\":{\"type\":\"number\"},\"ry\":{\"type\":\"number\"},\"rz\":{\"type\":\"number\"},\"rings\":{\"type\":\"integer\"},\"segments\":{\"type\":\"integer\"},\"x\":{\"type\":\"number\"},\"y\":{\"type\":\"number\"},\"z\":{\"type\":\"number\"},\"r\":{\"type\":\"number\"},\"g\":{\"type\":\"number\"},\"b\":{\"type\":\"number\"}}}"));
        tools.put(tool("create_sweep_mesh", "Create a real smooth tapered tube mesh by sweeping a circular cross-section along a 3D path. Use for curved limbs, fingers, tails, horns, pipes, cables, handles, rails, branches and trim. path is flat xyz triplets in local coordinates (2-24 points); optional radii has one radius per point for tapering; segments 6-20. Caps close both ends. Position x/y/z moves the generated part in the scene.", "{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\"},\"path\":{\"type\":\"array\",\"items\":{\"type\":\"number\"},\"minItems\":6,\"maxItems\":72},\"radii\":{\"type\":\"array\",\"items\":{\"type\":\"number\"},\"minItems\":2,\"maxItems\":24},\"radius\":{\"type\":\"number\"},\"segments\":{\"type\":\"integer\"},\"caps\":{\"type\":\"boolean\"},\"x\":{\"type\":\"number\"},\"y\":{\"type\":\"number\"},\"z\":{\"type\":\"number\"},\"r\":{\"type\":\"number\"},\"g\":{\"type\":\"number\"},\"b\":{\"type\":\"number\"}},\"required\":[\"path\"]}"));
        tools.put(tool("create_mesh_part", "Create one real custom triangulated mesh part from flat xyz positions and triangle indices. Use 3-300 vertices and 1-600 triangles. Local coordinates must stay within -20..20. Build complex assets from multiple connected parts with sensible proportions and slight overlap at joints.", "{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\"},\"positions\":{\"type\":\"array\",\"items\":{\"type\":\"number\"},\"minItems\":9,\"maxItems\":900},\"triangles\":{\"type\":\"array\",\"items\":{\"type\":\"integer\"},\"minItems\":3,\"maxItems\":1800},\"x\":{\"type\":\"number\"},\"y\":{\"type\":\"number\"},\"z\":{\"type\":\"number\"},\"r\":{\"type\":\"number\"},\"g\":{\"type\":\"number\"},\"b\":{\"type\":\"number\"}},\"required\":[\"positions\",\"triangles\"]}"));
        tools.put(tool("create_sculpt_mesh", "Create and select an editable dense sphere or plane mesh for sculpting. Use density 32-80 on mobile. This creates actual geometry.", "{\"type\":\"object\",\"properties\":{\"shape\":{\"type\":\"string\",\"enum\":[\"sphere\",\"plane\"]},\"density\":{\"type\":\"integer\"}}}"));
        tools.put(tool("sculpt_selected_mesh", "Use the real BVH-based SculptMesh brush engine on the selected dense sculpt mesh. Brushes: draw, smooth, grab, inflate, pinch, flatten, crease, mask, clay, scrape, layer, unmask, blob, clay_strips, elastic_deform, snake_hook, thumb, pose, boundary, slide_relax, multiplane_scrape, fill, deepen, nudge, simplify. Supply either x/y/z for one dab or points as a flat xyz triplet list for a continuous stroke; the engine interpolates dabs every 25% of radius and processes them on its sculpt worker. Radius 0.02-1.0, strength 0-1, hardness 0-1. symmetryAxis is off/x/y/z; subtract reverses Draw, Clay and Inflate. For Grab use dragX/Y/Z for a one-point pull or a points path. Create a dense sphere or plane with create_sculpt_mesh first. Make multiple carefully planned strokes and refine with Smooth.", "{\"type\":\"object\",\"properties\":{\"brush\":{\"type\":\"string\",\"enum\":[\"draw\",\"smooth\",\"grab\",\"inflate\",\"pinch\",\"flatten\",\"crease\",\"mask\",\"clay\",\"scrape\",\"layer\",\"unmask\",\"blob\",\"clay_strips\",\"elastic_deform\",\"snake_hook\",\"thumb\",\"pose\",\"boundary\",\"slide_relax\",\"multiplane_scrape\",\"fill\",\"deepen\",\"nudge\",\"simplify\"]},\"x\":{\"type\":\"number\"},\"y\":{\"type\":\"number\"},\"z\":{\"type\":\"number\"},\"points\":{\"type\":\"array\",\"items\":{\"type\":\"number\"},\"minItems\":3,\"maxItems\":192},\"radius\":{\"type\":\"number\"},\"strength\":{\"type\":\"number\"},\"falloff\":{\"type\":\"number\"},\"hardness\":{\"type\":\"number\"},\"symmetryAxis\":{\"type\":\"string\",\"enum\":[\"off\",\"x\",\"y\",\"z\"]},\"symmetry\":{\"type\":\"boolean\"},\"subtract\":{\"type\":\"boolean\"},\"pressure\":{\"type\":\"number\"},\"dragX\":{\"type\":\"number\"},\"dragY\":{\"type\":\"number\"},\"dragZ\":{\"type\":\"number\"}}}"));
        tools.put(tool("extrude_face", "Extrude a real triangle face on the selected custom mesh along its normal. faceIndex is the triangle index (0-based); distance is the extrusion distance. This creates cap and side-wall triangles.", "{\"type\":\"object\",\"properties\":{\"faceIndex\":{\"type\":\"integer\"},\"distance\":{\"type\":\"number\"}},\"required\":[\"faceIndex\",\"distance\"]}"));
        tools.put(tool("inset_face", "Inset a real triangle face on the selected custom mesh. faceIndex is 0-based; amount is 0.01-0.90 as a proportional inset.", "{\"type\":\"object\",\"properties\":{\"faceIndex\":{\"type\":\"integer\"},\"amount\":{\"type\":\"number\"}},\"required\":[\"faceIndex\",\"amount\"]}"));
        tools.put(tool("bevel_edge", "Bevel/split a selected mesh edge using an offset midpoint. edgeIndex identifies an edge slot derived from triangle order (0-based); width controls offset.", "{\"type\":\"object\",\"properties\":{\"edgeIndex\":{\"type\":\"integer\"},\"width\":{\"type\":\"number\"}},\"required\":[\"edgeIndex\",\"width\"]}"));
        tools.put(tool("loop_cut", "Insert a real topology subdivision across all triangles in the selected mesh. This is a uniform triangle-mesh loop refinement, not a quad-only Blender edge-loop slide.", "{\"type\":\"object\",\"properties\":{}}"));
        tools.put(tool("subdivide_mesh", "Subdivide the selected mesh into smaller triangles using shared edge midpoints. levels is 1-3; use 1 on mobile unless more detail is needed.", "{\"type\":\"object\",\"properties\":{\"levels\":{\"type\":\"integer\"}}}"));
        tools.put(tool("merge_vertices", "Merge selected-mesh vertices within a distance tolerance and remove degenerate triangles.", "{\"type\":\"object\",\"properties\":{\"tolerance\":{\"type\":\"number\"}},\"required\":[\"tolerance\"]}"));
        tools.put(tool("knife_cut", "Perform a real topology split on one selected triangle by dividing it into four triangles at its edge midpoints. faceIndex is 0-based. This is a triangle subdivision cut, not an arbitrary freehand knife path.", "{\"type\":\"object\",\"properties\":{\"faceIndex\":{\"type\":\"integer\"}},\"required\":[\"faceIndex\"]}"));
        tools.put(tool("paint_foliage", "Paint trees or bushes around world X/Z. kind is tree or bush. Count is limited for mobile performance.", "{\"type\":\"object\",\"properties\":{\"x\":{\"type\":\"number\"},\"z\":{\"type\":\"number\"},\"radius\":{\"type\":\"number\"},\"count\":{\"type\":\"integer\"},\"kind\":{\"type\":\"string\",\"enum\":[\"tree\",\"bush\"]}},\"required\":[\"x\",\"z\",\"radius\",\"count\",\"kind\"]}"));
        tools.put(tool("clear_foliage", "Remove all generated foliage objects.", "{\"type\":\"object\",\"properties\":{}}"));
        tools.put(tool("select_object", "Select an existing scene object by its numeric ID from inspect_scene.", "{\"type\":\"object\",\"properties\":{\"id\":{\"type\":\"integer\"}},\"required\":[\"id\"]}"));
        tools.put(tool("move_selected", "Move the currently selected object by a world-space offset.", "{\"type\":\"object\",\"properties\":{\"dx\":{\"type\":\"number\"},\"dy\":{\"type\":\"number\"},\"dz\":{\"type\":\"number\"}},\"required\":[\"dx\",\"dy\",\"dz\"]}"));
        tools.put(tool("rotate_selected", "Rotate the selected object by radians around X, Y and Z.", "{\"type\":\"object\",\"properties\":{\"rx\":{\"type\":\"number\"},\"ry\":{\"type\":\"number\"},\"rz\":{\"type\":\"number\"}},\"required\":[\"rx\",\"ry\",\"rz\"]}"));
        tools.put(tool("scale_selected", "Scale the selected object by multipliers on X, Y and Z. Use values such as 1.2 to enlarge or 0.8 to shrink.", "{\"type\":\"object\",\"properties\":{\"sx\":{\"type\":\"number\"},\"sy\":{\"type\":\"number\"},\"sz\":{\"type\":\"number\"}},\"required\":[\"sx\",\"sy\",\"sz\"]}"));
        tools.put(tool("undo", "Undo the last supported scene change.", "{\"type\":\"object\",\"properties\":{}}"));
        tools.put(tool("redo", "Redo the last undone scene change.", "{\"type\":\"object\",\"properties\":{}}"));
        return tools;
    }


    private JSONArray buildMeshyTools() throws Exception {
        JSONArray t = new JSONArray();
        t.put(tool("inspect_meshy_scene","Inspect Meshy3D objects, active object, mesh counts, selection mode and selected component count.","{\"type\":\"object\",\"properties\":{}}"));
        t.put(tool("create_mesh_part","Create a real Meshy3D mesh object from local xyz vertices and triangle indices. Positions are flat xyz values; triangles are flat index triplets. Keep parts below 3000 vertices and 6000 triangles.","{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\"},\"positions\":{\"type\":\"array\",\"items\":{\"type\":\"number\"},\"minItems\":9,\"maxItems\":9000},\"triangles\":{\"type\":\"array\",\"items\":{\"type\":\"integer\"},\"minItems\":3,\"maxItems\":18000},\"x\":{\"type\":\"number\"},\"y\":{\"type\":\"number\"},\"z\":{\"type\":\"number\"},\"r\":{\"type\":\"number\"},\"g\":{\"type\":\"number\"},\"b\":{\"type\":\"number\"},\"metallic\":{\"type\":\"number\"},\"roughness\":{\"type\":\"number\"}},\"required\":[\"positions\",\"triangles\"]}"));
        t.put(tool("create_ellipsoid","Create a smooth real Meshy3D ellipsoid mesh object with independent radii.","{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\"},\"rx\":{\"type\":\"number\"},\"ry\":{\"type\":\"number\"},\"rz\":{\"type\":\"number\"},\"rings\":{\"type\":\"integer\"},\"segments\":{\"type\":\"integer\"},\"x\":{\"type\":\"number\"},\"y\":{\"type\":\"number\"},\"z\":{\"type\":\"number\"},\"r\":{\"type\":\"number\"},\"g\":{\"type\":\"number\"},\"b\":{\"type\":\"number\"}}}"));
        t.put(tool("rename_active_object","Rename the active Meshy3D object.","{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\"}},\"required\":[\"name\"]}"));
        t.put(tool("transform_active_object","Transform the active object. operation is move, rotate or scale; x/y/z are offsets, radians, or scale multipliers respectively.","{\"type\":\"object\",\"properties\":{\"operation\":{\"type\":\"string\",\"enum\":[\"move\",\"rotate\",\"scale\"]},\"x\":{\"type\":\"number\"},\"y\":{\"type\":\"number\"},\"z\":{\"type\":\"number\"}},\"required\":[\"operation\"]}"));
        t.put(tool("set_material","Set the active mesh base color and PBR metallic/roughness values. RGB values range from 0 to 1.","{\"type\":\"object\",\"properties\":{\"r\":{\"type\":\"number\"},\"g\":{\"type\":\"number\"},\"b\":{\"type\":\"number\"},\"metallic\":{\"type\":\"number\"},\"roughness\":{\"type\":\"number\"}}}"));
        t.put(tool("set_edit_mode","Enable or disable Meshy3D Edit Mode.","{\"type\":\"object\",\"properties\":{\"enabled\":{\"type\":\"boolean\"}},\"required\":[\"enabled\"]}"));
        t.put(tool("set_component_selection_mode","Set component selection mode to VERTEX, EDGE or FACE.","{\"type\":\"object\",\"properties\":{\"mode\":{\"type\":\"string\",\"enum\":[\"VERTEX\",\"EDGE\",\"FACE\"]}},\"required\":[\"mode\"]}"));
        t.put(tool("select_all_components","Select every component in the active mesh.","{\"type\":\"object\",\"properties\":{}}"));
        t.put(tool("extrude_selected","Extrude selected mesh components along their normals by distance.","{\"type\":\"object\",\"properties\":{\"distance\":{\"type\":\"number\"}},\"required\":[\"distance\"]}"));
        t.put(tool("inset_selected","Inset selected faces by a proportional amount from 0.01 to 0.9.","{\"type\":\"object\",\"properties\":{\"amount\":{\"type\":\"number\"}},\"required\":[\"amount\"]}"));
        t.put(tool("bevel_selected","Bevel selected edges by a proportional width from 0.001 to 0.49.","{\"type\":\"object\",\"properties\":{\"width\":{\"type\":\"number\"}},\"required\":[\"width\"]}"));
        t.put(tool("subdivide_selected","Subdivide the active mesh one to three levels.","{\"type\":\"object\",\"properties\":{\"levels\":{\"type\":\"integer\"}}}"));
        t.put(tool("smooth_selected","Smooth the active mesh using a bounded amount and iteration count.","{\"type\":\"object\",\"properties\":{\"amount\":{\"type\":\"number\"},\"iterations\":{\"type\":\"integer\"}}}"));
        t.put(tool("merge_selected","Merge selected vertices by distance.","{\"type\":\"object\",\"properties\":{\"tolerance\":{\"type\":\"number\"}},\"required\":[\"tolerance\"]}"));
        t.put(tool("add_modifier","Add a non-destructive Meshy3D modifier to the active mesh. Supported types: MIRROR, ARRAY, BEVEL, SOLIDIFY, SUBDIVIDE, CATMULL_CLARK, TRIANGULATE, PLANAR_DECIMATE, SMOOTH, WIREFRAME.","{\"type\":\"object\",\"properties\":{\"type\":{\"type\":\"string\",\"enum\":[\"MIRROR\",\"ARRAY\",\"BEVEL\",\"SOLIDIFY\",\"SUBDIVIDE\",\"CATMULL_CLARK\",\"TRIANGULATE\",\"PLANAR_DECIMATE\",\"SMOOTH\",\"WIREFRAME\"]}},\"required\":[\"type\"]}"));
        t.put(tool("configure_modifier","Configure an existing modifier by ID. Set enabled, levels, count, axis and value as appropriate.","{\"type\":\"object\",\"properties\":{\"modifierId\":{\"type\":\"string\"},\"enabled\":{\"type\":\"boolean\"},\"levels\":{\"type\":\"integer\"},\"count\":{\"type\":\"integer\"},\"axis\":{\"type\":\"integer\"},\"value\":{\"type\":\"number\"}},\"required\":[\"modifierId\"]}"));
        t.put(tool("apply_modifier_stack","Apply the active object's modifier stack through the zero-based modifier index.","{\"type\":\"object\",\"properties\":{\"index\":{\"type\":\"integer\"}},\"required\":[\"index\"]}"));
        t.put(tool("unwrap_uv","Generate a planar/conformal UV unwrap for the active mesh.","{\"type\":\"object\",\"properties\":{}}"));
        t.put(tool("add_geometry_node","Add a geometry node to the active mesh document's Geometry Nodes graph.","{\"type\":\"object\",\"properties\":{\"type\":{\"type\":\"string\"},\"x\":{\"type\":\"number\"},\"y\":{\"type\":\"number\"},\"z\":{\"type\":\"number\"},\"value\":{\"type\":\"number\"}},\"required\":[\"type\"]}"));
        t.put(tool("evaluate_geometry_nodes","Evaluate the active mesh document's Geometry Nodes graph.","{\"type\":\"object\",\"properties\":{}}"));
        t.put(tool("add_shader_node","Add a shader/material node to the active mesh document.","{\"type\":\"object\",\"properties\":{\"type\":{\"type\":\"string\"}},\"required\":[\"type\"]}"));
        t.put(tool("insert_animation_key","Insert an animation key into the active mesh document.","{\"type\":\"object\",\"properties\":{\"property\":{\"type\":\"string\"},\"frame\":{\"type\":\"integer\"},\"value\":{\"type\":\"number\"}},\"required\":[\"property\",\"frame\",\"value\"]}"));
        t.put(tool("create_texture","Create a paint texture for the active mesh, with dimensions limited for mobile.","{\"type\":\"object\",\"properties\":{\"width\":{\"type\":\"integer\"},\"height\":{\"type\":\"integer\"},\"color\":{\"type\":\"integer\"}}}"));
        t.put(tool("sculpt_selected_mesh","Apply a real Meshy3D sculpt brush stroke to the active mesh. Brush names: draw, smooth, grab, inflate, pinch, flatten, crease, mask, clay, scrape, layer, unmask, blob, clay_strips, elastic_deform, snake_hook, thumb, pose, boundary, slide_relax, multiplane_scrape, fill, deepen, nudge, simplify. points is a flat list of local xyz triplets; if omitted use x/y/z. radius 0.02-1, strength/falloff 0-1, symmetry off/x/y/z.","{\"type\":\"object\",\"properties\":{\"brush\":{\"type\":\"string\",\"enum\":[\"draw\",\"smooth\",\"grab\",\"inflate\",\"pinch\",\"flatten\",\"crease\",\"mask\",\"clay\",\"scrape\",\"layer\",\"unmask\",\"blob\",\"clay_strips\",\"elastic_deform\",\"snake_hook\",\"thumb\",\"pose\",\"boundary\",\"slide_relax\",\"multiplane_scrape\",\"fill\",\"deepen\",\"nudge\",\"simplify\"]},\"x\":{\"type\":\"number\"},\"y\":{\"type\":\"number\"},\"z\":{\"type\":\"number\"},\"points\":{\"type\":\"array\",\"items\":{\"type\":\"number\"},\"minItems\":3,\"maxItems\":192},\"radius\":{\"type\":\"number\"},\"strength\":{\"type\":\"number\"},\"falloff\":{\"type\":\"number\"},\"symmetry\":{\"type\":\"string\",\"enum\":[\"off\",\"x\",\"y\",\"z\"]},\"subtract\":{\"type\":\"boolean\"}}}"));
        t.put(tool("select_object_by_name","Select a Meshy3D object by its exact name from inspect_meshy_scene.","{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\"}},\"required\":[\"name\"]}"));
        t.put(tool("paint_texture","Paint a real brush dab onto the active mesh UV texture at pixel coordinates x/y. RGB values range 0-1.","{\"type\":\"object\",\"properties\":{\"x\":{\"type\":\"integer\"},\"y\":{\"type\":\"integer\"},\"r\":{\"type\":\"number\"},\"g\":{\"type\":\"number\"},\"b\":{\"type\":\"number\"},\"radius\":{\"type\":\"number\"},\"opacity\":{\"type\":\"number\"}}}"));
        t.put(tool("mark_uv_seams","Mark selected mesh edges as UV seams, or clear seam flags when clear is true.","{\"type\":\"object\",\"properties\":{\"clear\":{\"type\":\"boolean\"}}}"));
        t.put(tool("pin_uvs","Pin or unpin selected UV vertices.","{\"type\":\"object\",\"properties\":{\"pinned\":{\"type\":\"boolean\"}}}"));
        t.put(tool("pack_uv_islands","Pack UV islands into the 0-1 UV tile with the requested margin.","{\"type\":\"object\",\"properties\":{\"margin\":{\"type\":\"number\"}}}"));
        t.put(tool("mirror_uv","Mirror selected UV coordinates on U or V axis.","{\"type\":\"object\",\"properties\":{\"axis\":{\"type\":\"string\",\"enum\":[\"U\",\"V\"]}}}"));
        t.put(tool("weld_uv","Weld selected UV vertices within a tolerance.","{\"type\":\"object\",\"properties\":{\"tolerance\":{\"type\":\"number\"}}}"));
        t.put(tool("delete_selected_objects","Delete the selected Meshy3D objects.","{\"type\":\"object\",\"properties\":{}}"));
        t.put(tool("undo_meshy_edit","Undo the last Meshy3D AI or manual edit.","{\"type\":\"object\",\"properties\":{}}"));
        t.put(tool("redo_meshy_edit","Redo the last Meshy3D edit.","{\"type\":\"object\",\"properties\":{}}"));
        return t;
    }

    private interface UiCall<T> { T run(); }
    private <T> T runOnMainThread(UiCall<T> call) {
        if (Looper.myLooper() == Looper.getMainLooper()) return call.run();
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<T> value = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<RuntimeException> failure = new java.util.concurrent.atomic.AtomicReference<>();
        main.post(() -> { try { value.set(call.run()); } catch (RuntimeException ex) { failure.set(ex); } finally { latch.countDown(); } });
        try {
            if (!latch.await(15, java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("Meshy3D UI thread did not respond.");
        } catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IllegalStateException("Meshy3D operation interrupted."); }
        if (failure.get() != null) throw failure.get();
        return value.get();
    }

    private String executeMeshyToolOnUiThread(String name, JSONObject args) {
        Meshy3DViewport viewport = meshyWorkspace.getMeshyViewport();
        if (viewport == null) return "Meshy3D viewport is not open.";
        Meshy3DCore core = viewport.getDocumentCore();
        Meshy3DObjectScene scene = viewport.getObjectScene();
        try {
            switch (name) {
                case "inspect_meshy_scene": {
                    StringBuilder out = new StringBuilder("Meshy3D objects: ");
                    for (Meshy3DObjectScene.Object3D object : scene.getObjects()) {
                        out.append(object.name).append(" [").append(object.type).append("]");
                        if (object.mesh != null) out.append(" {").append(object.mesh.getVertexCount()).append(" verts, ").append(object.mesh.getFaceCount()).append(" faces}");
                        if (object.active) out.append(" (ACTIVE)");
                        out.append("; ");
                    }
                    out.append(" | Selection mode: ").append(viewport.getSelectionMode())
                       .append(" | Selected components: ").append(viewport.getSelectedElementCount())
                       .append(" | Edit mode: ").append(viewport.isMeshEditMode());
                    return out.toString();
                }
                case "create_mesh_part":
                case "create_ellipsoid": {
                    meshyWorkspace.checkpointMeshEdit();
                    if ("create_ellipsoid".equals(name)) {
                        float rx=Math.max(.05f,Math.min(10f,number(args,"rx",1)));
                        float ry=Math.max(.05f,Math.min(10f,number(args,"ry",1)));
                        float rz=Math.max(.05f,Math.min(10f,number(args,"rz",1)));
                        int rings=Math.max(8,Math.min(32,args.optInt("rings",16)));
                        int segments=Math.max(12,Math.min(48,args.optInt("segments",24)));
                        float[] p=new float[(rings+1)*segments*3]; int k=0;
                        for(int i=0;i<=rings;i++){double phi=Math.PI*i/rings;for(int j=0;j<segments;j++){double theta=2*Math.PI*j/segments;p[k++]=(float)(rx*Math.sin(phi)*Math.cos(theta));p[k++]=(float)(ry*Math.cos(phi));p[k++]=(float)(rz*Math.sin(phi)*Math.sin(theta));}}
                        int[] f=new int[rings*segments*6];k=0;
                        for(int i=0;i<rings;i++)for(int j=0;j<segments;j++){int n=(j+1)%segments,a0=i*segments+j,b0=i*segments+n,c0=(i+1)*segments+n,d0=(i+1)*segments+j;f[k++]=a0;f[k++]=b0;f[k++]=c0;f[k++]=a0;f[k++]=c0;f[k++]=d0;}
                        String id=viewport.addAIMeshObject(args.optString("name","AI Ellipsoid"),p,f,number(args,"x",0),number(args,"y",0),number(args,"z",0),meshColor(args),number(args,"metallic",0),number(args,"roughness",.65f));
                        return "Created Meshy3D ellipsoid object " + id + " with " + (p.length/3) + " vertices.";
                    }
                    JSONArray pa=args.optJSONArray("positions"),fa=args.optJSONArray("triangles");
                    if(pa==null||fa==null||pa.length()<9||pa.length()>9000||pa.length()%3!=0||fa.length()<3||fa.length()>18000||fa.length()%3!=0) return "Mesh arrays must contain 3-3000 vertices and 1-6000 triangles.";
                    float[] p=new float[pa.length()];int[] f=new int[fa.length()];
                    for(int i=0;i<p.length;i++){double q=pa.optDouble(i,Double.NaN);if(!Double.isFinite(q)||Math.abs(q)>20)return "Vertex coordinates must be finite and within -20..20.";p[i]=(float)q;}
                    for(int i=0;i<f.length;i++){f[i]=fa.optInt(i,-1);if(f[i]<0||f[i]>=p.length/3)return "Triangle index is outside the vertex array.";}
                    String id=viewport.addAIMeshObject(args.optString("name","AI Mesh"),p,f,number(args,"x",0),number(args,"y",0),number(args,"z",0),meshColor(args),number(args,"metallic",0),number(args,"roughness",.65f));
                    return "Created Meshy3D mesh object " + id + " with " + (p.length/3) + " vertices and " + (f.length/3) + " triangles.";
                }
                case "select_object_by_name": {
                    String wanted=args.optString("name","").trim();
                    if(wanted.isEmpty())return "Object name is required.";
                    for(Meshy3DObjectScene.Object3D object:scene.getObjects())if(object.name.equalsIgnoreCase(wanted)){
                        viewport.selectSceneObject(object.id);return "Selected Meshy3D object "+object.name+".";
                    }
                    return "No Meshy3D object named "+wanted+" was found.";
                }
                case "sculpt_selected_mesh": {
                    String brush=args.optString("brush","draw").toLowerCase(java.util.Locale.US);
                    String[] names={"draw","smooth","grab","inflate","pinch","flatten","crease","mask","clay","scrape","layer","unmask","blob","clay_strips","elastic_deform","snake_hook","thumb","pose","boundary","slide_relax","multiplane_scrape","fill","deepen","nudge","simplify"};
                    int brushId=-1;for(int i=0;i<names.length;i++)if(names[i].equals(brush)){brushId=i;break;}
                    if(brushId<0)return "Unknown Meshy3D sculpt brush.";
                    JSONArray path=args.optJSONArray("points");
                    float[] points;
                    if(path==null){points=new float[]{number(args,"x",0),number(args,"y",0),number(args,"z",0)};}
                    else{if(path.length()<3||path.length()>192||path.length()%3!=0)return "Sculpt points must contain 1-64 xyz triplets.";points=new float[path.length()];for(int i=0;i<points.length;i++)points[i]=(float)path.optDouble(i,Double.NaN);}
                    String sym=args.optString("symmetry","off").toLowerCase(java.util.Locale.US);
                    int symmetry="x".equals(sym)?1:"y".equals(sym)?2:"z".equals(sym)?3:0;
                    int count=viewport.applyAISculptStroke(brushId,points,Math.max(.02f,Math.min(1f,number(args,"radius",.18f))),Math.max(0,Math.min(1,number(args,"strength",.4f))),Math.max(0,Math.min(1,number(args,"falloff",.5f))),symmetry,args.optBoolean("subtract",false));
                    return "Applied "+brush+" sculpt stroke to Meshy3D mesh ("+count+" vertices).";
                }
                case "rename_active_object": {
                    String n=args.optString("name","").trim();if(n.isEmpty())return "Name cannot be empty.";
                    meshyWorkspace.checkpointMeshEdit();viewport.renameActiveObject(n);viewport.invalidate();return "Renamed active Meshy3D object to "+n+".";
                }
                case "transform_active_object": {
                    Meshy3DObjectScene.Object3D o=scene.getActiveObject();if(o==null)return "No active Meshy3D object.";
                    String op=args.optString("operation","move");float x=number(args,"x",0),y=number(args,"y",0),z=number(args,"z",0);
                    meshyWorkspace.checkpointMeshEdit();
                    if("move".equals(op))scene.translate(o.id,x,y,z);
                    else if("rotate".equals(op))scene.rotate(o.id,x,y,z);
                    else if("scale".equals(op))scene.scale(o.id,Math.max(.01f,x==0?1:x),Math.max(.01f,y==0?1:y),Math.max(.01f,z==0?1:z));
                    else return "Unsupported transform operation.";
                    viewport.invalidate();return "Applied "+op+" to "+o.name+".";
                }
                case "set_material": {
                    if(scene.getActiveObject()==null||scene.getActiveObject().mesh==null)return "Select a mesh object first.";
                    meshyWorkspace.checkpointMeshEdit();core.setMaterial(meshColor(args),number(args,"metallic",core.getMetallic()),number(args,"roughness",core.getRoughness()));viewport.invalidate();return "Updated active mesh base color and PBR material values.";
                }
                case "set_edit_mode": viewport.setMeshEditMode(args.optBoolean("enabled",true));return "Mesh Edit Mode "+(args.optBoolean("enabled",true)?"enabled":"disabled")+".";
                case "set_component_selection_mode": viewport.setSelectionMode(args.optString("mode","FACE"));return "Component selection mode set to "+viewport.getSelectionMode()+".";
                case "select_all_components": viewport.selectAllElements();return "Selected "+viewport.getSelectedElementCount()+" mesh components.";
                case "extrude_selected": if(viewport.getSelectedElementCount()==0)return "Select mesh components first.";meshyWorkspace.checkpointMeshEdit();viewport.extrudeSelected(number(args,"distance",.2f));return "Extruded selected mesh components.";
                case "inset_selected": if(viewport.getSelectedElementCount()==0)return "Select faces first.";meshyWorkspace.checkpointMeshEdit();viewport.insetSelected(Math.max(.01f,Math.min(.9f,number(args,"amount",.1f))));return "Inset selected mesh faces.";
                case "bevel_selected": if(viewport.getSelectedElementCount()==0)return "Select edges first.";meshyWorkspace.checkpointMeshEdit();viewport.bevelSelected(Math.max(.001f,Math.min(.49f,number(args,"width",.1f))));return "Beveled selected mesh edges.";
                case "subdivide_selected": meshyWorkspace.checkpointMeshEdit();int levels=Math.max(1,Math.min(3,args.optInt("levels",1)));for(int i=0;i<levels;i++)viewport.subdivideSelected();return "Subdivided active mesh "+levels+" level(s).";
                case "smooth_selected": meshyWorkspace.checkpointMeshEdit();viewport.smoothTopology(Math.max(0,Math.min(.8f,number(args,"amount",.25f))),Math.max(1,Math.min(8,args.optInt("iterations",2))));return "Smoothed active mesh topology.";
                case "merge_selected": if(viewport.getSelectedElementCount()==0)return "Select components first.";meshyWorkspace.checkpointMeshEdit();viewport.mergeSelected(Math.max(.0001f,Math.min(1f,number(args,"tolerance",.001f))));return "Merged selected vertices by distance.";
                case "add_modifier": {
                    Meshy3DObjectScene.Object3D active=scene.getActiveObject();if(active==null||active.mesh==null)return "Select a mesh object first.";
                    meshyWorkspace.checkpointMeshEdit();Meshy3DObjectScene.Modifier3D modifier=scene.addModifier(active.id,args.optString("type","BEVEL"));
                    viewport.invalidate();return "Added "+modifier.type+" modifier. Modifier ID: "+modifier.id+".";
                }
                case "configure_modifier": {
                    Meshy3DObjectScene.Object3D active=scene.getActiveObject();if(active==null)return "No active object.";
                    String id=args.optString("modifierId","");Meshy3DObjectScene.Modifier3D found=null;
                    for(Meshy3DObjectScene.Modifier3D m:scene.getModifiers(active.id))if(m.id.equals(id)){found=m;break;}
                    if(found==null)return "Modifier ID was not found on the active object.";
                    meshyWorkspace.checkpointMeshEdit();
                    scene.setModifierEnabled(active.id,id,args.optBoolean("enabled",found.enabled));
                    scene.setModifierParameters(active.id,id,Math.max(0,Math.min(2,args.optInt("axis",found.axis))),Math.max(1,Math.min(20,args.optInt("count",found.count))),Math.max(1,Math.min(3,args.optInt("levels",found.levels))),number(args,"value",found.value),found.offsetX,found.offsetY,found.offsetZ);
                    viewport.invalidate();return "Updated "+found.type+" modifier parameters.";
                }
                case "apply_modifier_stack": {
                    meshyWorkspace.checkpointMeshEdit();int index=Math.max(0,args.optInt("index",0));viewport.applyActiveObjectModifiersThrough(index);return "Applied modifier stack through index "+index+".";
                }
                case "unwrap_uv": meshyWorkspace.checkpointMeshEdit();viewport.unwrapMesh();return "Generated UV coordinates for the active mesh.";
                case "add_geometry_node": meshyWorkspace.checkpointMeshEdit();viewport.addGeometryNode(args.optString("type","Transform"),number(args,"x",0),number(args,"y",0),number(args,"z",0),number(args,"value",0));return "Added geometry node to Meshy3D document.";
                case "evaluate_geometry_nodes": meshyWorkspace.checkpointMeshEdit();viewport.evaluateGeometryNodes();return "Evaluated Geometry Nodes graph.";
                case "add_shader_node": meshyWorkspace.checkpointMeshEdit();viewport.addShaderNode(args.optString("type","Principled BSDF"));return "Added shader node to Meshy3D document.";
                case "insert_animation_key": meshyWorkspace.checkpointMeshEdit();viewport.insertAnimationKey(args.optString("property","location.x"),Math.max(1,args.optInt("frame",1)),number(args,"value",0));return "Inserted animation key.";
                case "create_texture": meshyWorkspace.checkpointMeshEdit();int width=Math.max(16,Math.min(2048,args.optInt("width",512))),height=Math.max(16,Math.min(2048,args.optInt("height",512)));viewport.createPaintTexture(width,height,args.optInt("color",0xFFFFFFFF));return "Created "+width+" x "+height+" texture.";
                case "paint_texture": {
                    if(scene.getActiveObject()==null||scene.getActiveObject().mesh==null)return "Select a mesh object first.";
                    meshyWorkspace.checkpointMeshEdit();
                    if(!core.hasTexture())core.createTexture(512,512,0xFFFFFFFF);
                    core.setBrush(meshColor(args),Math.max(1,Math.min(256,number(args,"radius",12))),Math.max(0,Math.min(1,number(args,"opacity",1))));
                    core.paintPixel(Math.max(0,Math.min(core.getTextureWidth()-1,args.optInt("x",256))),Math.max(0,Math.min(core.getTextureHeight()-1,args.optInt("y",256))));
                    viewport.invalidate();return "Painted a UV texture brush dab.";
                }
                case "mark_uv_seams": meshyWorkspace.checkpointMeshEdit();viewport.markSeams(args.optBoolean("clear",false));return "Updated selected UV seam flags.";
                case "pin_uvs": meshyWorkspace.checkpointMeshEdit();viewport.pinSelectedUvs(args.optBoolean("pinned",true));return "Updated selected UV pin flags.";
                case "pack_uv_islands": meshyWorkspace.checkpointMeshEdit();viewport.packUvIslands(Math.max(.001f,Math.min(.25f,number(args,"margin",.02f))));return "Packed UV islands.";
                case "mirror_uv": meshyWorkspace.checkpointMeshEdit();viewport.mirrorSelectedUv("V".equalsIgnoreCase(args.optString("axis","U"))?1:0);return "Mirrored selected UV coordinates.";
                case "weld_uv": meshyWorkspace.checkpointMeshEdit();viewport.weldSelectedUv(Math.max(.0001f,Math.min(.25f,number(args,"tolerance",.001f))));return "Welded selected UV coordinates.";
                case "delete_selected_objects": if(scene.getSelectedObjects().isEmpty())return "No Meshy3D objects selected.";meshyWorkspace.checkpointMeshEdit();int count=scene.getSelectedObjects().size();viewport.deleteSelectedObjects();return "Deleted "+count+" selected Meshy3D object(s).";
                case "undo_meshy_edit": return meshyWorkspace.undoMeshEdit()?"Undid the last Meshy3D edit.":"Nothing to undo.";
                case "redo_meshy_edit": return meshyWorkspace.redoMeshEdit()?"Redid the last Meshy3D edit.":"Nothing to redo.";
                default: return "Unknown Meshy3D tool; no operation performed.";
            }
        } catch (Exception ex) { return "Meshy3D tool failed: " + safeMessage(ex); }
    }

    private int meshColor(JSONObject args) {
        int r=Math.max(0,Math.min(255,Math.round(number(args,"r",.62f)*255)));
        int g=Math.max(0,Math.min(255,Math.round(number(args,"g",.68f)*255)));
        int b=Math.max(0,Math.min(255,Math.round(number(args,"b",.76f)*255)));
        return 0xFF000000|(r<<16)|(g<<8)|b;
    }

    private JSONObject tool(String name, String description, String schema) throws Exception {
        JSONObject fn = new JSONObject();
        fn.put("name", name); fn.put("description", description); fn.put("parameters", new JSONObject(schema));
        JSONObject item = new JSONObject(); item.put("type", "function"); item.put("function", fn);
        return item;
    }

    private String createLatheMesh(JSONObject a) {
        JSONArray profile = a.optJSONArray("profile");
        if (profile == null || profile.length() < 4 || profile.length() > 64 || profile.length() % 2 != 0)
            return "Lathe profile must contain 2-32 radius/height pairs.";
        int segments = Math.max(8, Math.min(48, a.optInt("segments", 24)));
        int rings = profile.length() / 2;
        float[] radii = new float[rings], heights = new float[rings];
        for (int i = 0; i < rings; i++) {
            radii[i] = (float) profile.optDouble(i * 2, -1);
            heights[i] = (float) profile.optDouble(i * 2 + 1, Float.NaN);
            if (!Float.isFinite(radii[i]) || !Float.isFinite(heights[i]) ||
                    radii[i] < 0.01f || radii[i] > 10f || Math.abs(heights[i]) > 10f)
                return "Lathe profile values are invalid. Radius must be 0.01-10 and height -10..10.";
        }
        int vertexCount = rings * segments + 2;
        float[] p = new float[vertexCount * 3];
        int cursor = 0;
        for (int ring = 0; ring < rings; ring++) {
            for (int j = 0; j < segments; j++) {
                double angle = Math.PI * 2.0 * j / segments;
                p[cursor++] = (float) (Math.cos(angle) * radii[ring]);
                p[cursor++] = heights[ring];
                p[cursor++] = (float) (Math.sin(angle) * radii[ring]);
            }
        }
        int bottomCenter = rings * segments, topCenter = bottomCenter + 1;
        p[bottomCenter * 3] = 0; p[bottomCenter * 3 + 1] = heights[0]; p[bottomCenter * 3 + 2] = 0;
        p[topCenter * 3] = 0; p[topCenter * 3 + 1] = heights[rings - 1]; p[topCenter * 3 + 2] = 0;
        int[] t = new int[((rings - 1) * segments * 6 + segments * 6)];
        cursor = 0;
        for (int ring = 0; ring < rings - 1; ring++) {
            for (int j = 0; j < segments; j++) {
                int next = (j + 1) % segments;
                int a0 = ring * segments + j, b0 = ring * segments + next;
                int c0 = (ring + 1) * segments + next, d0 = (ring + 1) * segments + j;
                // Profile is ordered bottom-to-top; reverse the side winding for outward normals.
                t[cursor++] = a0; t[cursor++] = c0; t[cursor++] = b0;
                t[cursor++] = a0; t[cursor++] = d0; t[cursor++] = c0;
            }
        }
        for (int j = 0; j < segments; j++) {
            int next = (j + 1) % segments;
            t[cursor++] = bottomCenter; t[cursor++] = j; t[cursor++] = next;
            int a0 = (rings - 1) * segments + j, b0 = (rings - 1) * segments + next;
            t[cursor++] = topCenter; t[cursor++] = b0; t[cursor++] = a0;
        }
        return uploadProceduralMesh(a, p, t, "Lathe mesh");
    }

    private String createSweepMesh(JSONObject a) {
        JSONArray path = a.optJSONArray("path");
        if (path == null || path.length() < 6 || path.length() > 72 || path.length() % 3 != 0)
            return "Sweep path must contain 2-24 xyz points.";
        int rings = path.length() / 3;
        float[] centers = new float[path.length()];
        for (int i = 0; i < path.length(); i++) {
            double value = path.optDouble(i, Double.NaN);
            if (!Double.isFinite(value) || Math.abs(value) > 20.0)
                return "Sweep path coordinates must be finite and within -20..20.";
            centers[i] = (float) value;
        }
        JSONArray radiusArray = a.optJSONArray("radii");
        if (radiusArray != null && radiusArray.length() != rings)
            return "Provide exactly one radius per path point, or omit radii.";
        float baseRadius = Math.max(0.02f, Math.min(3.0f, number(a, "radius", 0.12f)));
        float[] radii = new float[rings];
        for (int i = 0; i < rings; i++) {
            float radius = radiusArray == null ? baseRadius : (float) radiusArray.optDouble(i, Double.NaN);
            if (!Float.isFinite(radius) || radius < 0.01f || radius > 3.0f)
                return "Sweep radii must be between 0.01 and 3.0.";
            radii[i] = radius;
        }
        int sides = Math.max(6, Math.min(20, a.optInt("segments", 12)));
        boolean caps = a.optBoolean("caps", true);
        int vertexCount = rings * sides + (caps ? 2 : 0);
        float[] positions = new float[vertexCount * 3];
        float prevNx = 0, prevNy = 0, prevNz = 0;
        for (int i = 0; i < rings; i++) {
            int prev = Math.max(0, i - 1), next = Math.min(rings - 1, i + 1);
            float tx = centers[next * 3] - centers[prev * 3];
            float ty = centers[next * 3 + 1] - centers[prev * 3 + 1];
            float tz = centers[next * 3 + 2] - centers[prev * 3 + 2];
            float tl = (float) Math.sqrt(tx * tx + ty * ty + tz * tz);
            if (tl < 0.0001f) return "Sweep path contains repeated or overlapping points.";
            tx /= tl; ty /= tl; tz /= tl;
            float nx, ny, nz;
            if (i == 0) {
                float ax = Math.abs(ty) < 0.90f ? 0.0f : 1.0f;
                float ay = Math.abs(ty) < 0.90f ? 1.0f : 0.0f;
                nx = -tz * ay; ny = tz * ax; nz = tx * ay - ty * ax;
            } else {
                // Parallel-transport the previous ring frame into the new tangent plane.
                float projection = prevNx * tx + prevNy * ty + prevNz * tz;
                nx = prevNx - tx * projection;
                ny = prevNy - ty * projection;
                nz = prevNz - tz * projection;
            }
            float nl = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (nl < 0.0001f) {
                float ax = Math.abs(ty) < 0.90f ? 0.0f : 1.0f;
                float ay = Math.abs(ty) < 0.90f ? 1.0f : 0.0f;
                nx = -tz * ay; ny = tz * ax; nz = tx * ay - ty * ax;
                nl = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            }
            if (nl < 0.0001f) return "Could not construct a stable sweep frame for this path.";
            nx /= nl; ny /= nl; nz /= nl;
            prevNx = nx; prevNy = ny; prevNz = nz;
            float bx = ty * nz - tz * ny, by = tz * nx - tx * nz, bz = tx * ny - ty * nx;
            for (int j = 0; j < sides; j++) {
                double angle = Math.PI * 2.0 * j / sides;
                float ca = (float) Math.cos(angle), sa = (float) Math.sin(angle);
                int k = (i * sides + j) * 3;
                positions[k] = centers[i * 3] + radii[i] * (nx * ca + bx * sa);
                positions[k + 1] = centers[i * 3 + 1] + radii[i] * (ny * ca + by * sa);
                positions[k + 2] = centers[i * 3 + 2] + radii[i] * (nz * ca + bz * sa);
            }
        }
        int startCap = rings * sides, endCap = startCap + 1;
        if (caps) {
            System.arraycopy(centers, 0, positions, startCap * 3, 3);
            System.arraycopy(centers, (rings - 1) * 3, positions, endCap * 3, 3);
        }
        int[] triangles = new int[((rings - 1) * sides * 6) + (caps ? sides * 6 : 0)];
        int cursor = 0;
        for (int i = 0; i < rings - 1; i++) for (int j = 0; j < sides; j++) {
            int n = (j + 1) % sides;
            int a0 = i * sides + j, b0 = i * sides + n;
            int c0 = (i + 1) * sides + n, d0 = (i + 1) * sides + j;
            triangles[cursor++] = a0; triangles[cursor++] = b0; triangles[cursor++] = c0;
            triangles[cursor++] = a0; triangles[cursor++] = c0; triangles[cursor++] = d0;
        }
        if (caps) for (int j = 0; j < sides; j++) {
            int n = (j + 1) % sides;
            triangles[cursor++] = startCap; triangles[cursor++] = n; triangles[cursor++] = j;
            int a0 = (rings - 1) * sides + j, b0 = (rings - 1) * sides + n;
            triangles[cursor++] = endCap; triangles[cursor++] = a0; triangles[cursor++] = b0;
        }
        return uploadProceduralMesh(a, positions, triangles, "Swept tube mesh");
    }
    private String createEllipsoidMesh(JSONObject a) {
        float rx = Math.max(0.05f, Math.min(10f, number(a, "rx", 1f)));
        float ry = Math.max(0.05f, Math.min(10f, number(a, "ry", 1f)));
        float rz = Math.max(0.05f, Math.min(10f, number(a, "rz", 1f)));
        int rings = Math.max(8, Math.min(32, a.optInt("rings", 16)));
        int segments = Math.max(12, Math.min(48, a.optInt("segments", 24)));
        int vertexCount = (rings + 1) * segments;
        float[] p = new float[vertexCount * 3];
        int cursor = 0;
        for (int i = 0; i <= rings; i++) {
            double phi = Math.PI * i / rings;
            for (int j = 0; j < segments; j++) {
                double theta = Math.PI * 2.0 * j / segments;
                p[cursor++] = (float) (rx * Math.sin(phi) * Math.cos(theta));
                p[cursor++] = (float) (ry * Math.cos(phi));
                p[cursor++] = (float) (rz * Math.sin(phi) * Math.sin(theta));
            }
        }
        int[] t = new int[rings * segments * 6];
        cursor = 0;
        for (int i = 0; i < rings; i++) {
            for (int j = 0; j < segments; j++) {
                int next = (j + 1) % segments;
                int a0 = i * segments + j, b0 = i * segments + next;
                int c0 = (i + 1) * segments + next, d0 = (i + 1) * segments + j;
                t[cursor++] = a0; t[cursor++] = b0; t[cursor++] = c0;
                t[cursor++] = a0; t[cursor++] = c0; t[cursor++] = d0;
            }
        }
        return uploadProceduralMesh(a, p, t, "Ellipsoid mesh");
    }

    private String uploadProceduralMesh(JSONObject a, float[] positions, int[] triangles, String label) {
        String meshName = a.optString("name", "AI " + label);
        float x = number(a, "x", 0), y = number(a, "y", 0), z = number(a, "z", 0);
        float red = Math.max(0, Math.min(1, number(a, "r", 0.62f)));
        float green = Math.max(0, Math.min(1, number(a, "g", 0.68f)));
        float blue = Math.max(0, Math.min(1, number(a, "b", 0.76f)));
        int id = runOnRenderThread(() -> NativeBridge.nativeCreateMeshPart(
                positions, triangles, meshName, x, y, z, red, green, blue));
        return id > 0 ? label + " created: " + meshName + ", " + (positions.length / 3)
                + " vertices, " + (triangles.length / 3) + " triangles, ID " + id
                : label + " failed: native mesh creation or GPU upload was rejected.";
    }

    private String executeTool(String name, JSONObject a) {
        if (meshyWorkspace != null) {
            try { return runOnMainThread(() -> executeMeshyToolOnUiThread(name, a)); }
            catch (Exception ex) { return "Meshy3D tool error: " + safeMessage(ex); }
        }
        try {
            switch (name) {
                case "inspect_scene": {
                    String[] objects = engine.getSceneObjects();
                    StringBuilder out = new StringBuilder("Objects: ");
                    if (objects != null) for (int i=0;i<Math.min(objects.length,100);i++) out.append(objects[i]).append("; ");
                    float[] t = engine.getSelectedTransform();
                    if (t != null && t.length >= 9) out.append(" Selected transform position=(").append(t[0]).append(",").append(t[1]).append(",").append(t[2]).append("), rotation=(").append(t[3]).append(",").append(t[4]).append(",").append(t[5]).append("), scale=(").append(t[6]).append(",").append(t[7]).append(",").append(t[8]).append(").");
                    return out.toString();
                }
                case "create_terrain":
                    boolean terrain = runOnRenderThread(() -> NativeBridge.nativeCreateTerrain());
                    return terrain ? "Terrain created successfully." : "Terrain creation failed.";
                case "sculpt_terrain": {
                    int mode = a.optString("mode","raise").equals("lower") ? 1
                            : a.optString("mode","raise").equals("smooth") ? 2
                            : a.optString("mode","raise").equals("flatten") ? 3 : 0;
                    float x=number(a,"x",0), z=number(a,"z",-4), radius=number(a,"radius",3), strength=number(a,"strength",0.4f);
                    boolean sculpted = runOnRenderThread(() -> NativeBridge.nativeSculptTerrain(x,z,radius,strength,mode));
                    return sculpted ? "Terrain sculpted successfully." : "No terrain changed. Generate terrain first or adjust the brush.";
                }
                case "create_lathe_mesh": return createLatheMesh(a);
                case "create_ellipsoid_mesh": return createEllipsoidMesh(a);
                case "create_sweep_mesh": return createSweepMesh(a);
                case "create_mesh_part": {
                    JSONArray pv=a.optJSONArray("positions"), ti=a.optJSONArray("triangles");
                    if(pv==null || ti==null || pv.length()<9 || pv.length()>9000 || pv.length()%3!=0 || ti.length()<3 || ti.length()>18000 || ti.length()%3!=0)
                        return "Invalid mesh arrays. Provide 3-3000 vertices and triangle indices.";
                    float[] positions=new float[pv.length()]; int[] triangles=new int[ti.length()];
                    for(int i=0;i<pv.length();i++){ double v=pv.optDouble(i,Double.NaN); if(!Double.isFinite(v)||Math.abs(v)>20) return "Vertex coordinate outside safe range."; positions[i]=(float)v; }
                    int vc=positions.length/3;
                    for(int i=0;i<ti.length();i++){ int v=ti.optInt(i,-1); if(v<0||v>=vc) return "Triangle index outside vertex array."; triangles[i]=v; }
                    String meshPartName=a.optString("name","AI Mesh Part");
                    float x=number(a,"x",0), y=number(a,"y",0), z=number(a,"z",0);
                    float red=Math.max(0,Math.min(1,number(a,"r",0.62f))), green=Math.max(0,Math.min(1,number(a,"g",0.68f))), blue=Math.max(0,Math.min(1,number(a,"b",0.76f)));
                    int id=runOnRenderThread(() -> NativeBridge.nativeCreateMeshPart(positions,triangles,meshPartName,x,y,z,red,green,blue));
                    return id>0 ? "Created mesh part "+name+" with "+vc+" vertices and "+(triangles.length/3)+" triangles. ID "+id : "Mesh part rejected or GPU upload failed.";
                }
                case "create_sculpt_mesh": {
                    String shapeName = a.optString("shape", "sphere").toLowerCase(java.util.Locale.US);
                    int shape = shapeName.equals("plane") ? 1 : 0;
                    int density = Math.max(16, Math.min(80, a.optInt("density", 48)));
                    int id = runOnRenderThread(() -> NativeBridge.nativeCreateSculptMesh(shape, density));
                    return id > 0
                            ? "Created and selected sculptable " + shapeName + " mesh ID " + id + " with density " + density + ". Now sculpt it using sculpt_selected_mesh calls."
                            : "Could not create sculpt mesh. The engine may not be initialized.";
                }
                case "sculpt_selected_mesh": {
                    String brushName = a.optString("brush", "draw").toLowerCase(java.util.Locale.US);
                    int brush = brushName.equals("smooth") ? 1
                            : brushName.equals("grab") ? 2
                            : brushName.equals("inflate") ? 3
                            : brushName.equals("pinch") ? 4
                            : brushName.equals("flatten") ? 5
                            : brushName.equals("crease") ? 6
                            : brushName.equals("mask") ? 7
                            : brushName.equals("clay") ? 8
                            : brushName.equals("scrape") ? 9
                            : brushName.equals("layer") ? 10
                            : brushName.equals("unmask") ? 11
                            : brushName.equals("blob") ? 12
                            : brushName.equals("clay strips") || brushName.equals("clay_strips") ? 13
                            : brushName.equals("elastic deform") || brushName.equals("elastic_deform") ? 14
                            : brushName.equals("snake hook") || brushName.equals("snake_hook") ? 15
                            : brushName.equals("thumb") ? 16
                            : brushName.equals("pose") ? 17
                            : brushName.equals("boundary") ? 18
                            : brushName.equals("slide relax") || brushName.equals("slide_relax") ? 19
                            : brushName.equals("multiplane scrape") || brushName.equals("multiplane_scrape") ? 20
                            : brushName.equals("fill") ? 21
                            : brushName.equals("deepen") ? 22
                            : brushName.equals("nudge") ? 23
                            : brushName.equals("simplify") ? 24 : 0;
                    float radius = Math.max(0.02f, Math.min(1.0f, number(a, "radius", 0.22f)));
                    float strength = Math.max(0.0f, Math.min(1.0f, number(a, "strength", 0.28f)));
                    float hardness = Math.max(0.0f, Math.min(1.0f, number(a, "hardness", number(a, "falloff", 0.45f))));
                    String axisName = a.optString("symmetryAxis", a.optBoolean("symmetry", true) ? "x" : "off").toLowerCase(java.util.Locale.US);
                    int symmetryAxis = axisName.equals("x") ? 0 : axisName.equals("y") ? 1 : axisName.equals("z") ? 2 : -1;
                    boolean subtract = a.optBoolean("subtract", false);
                    float pressure = Math.max(0.0f, Math.min(1.0f, number(a, "pressure", 1.0f)));
                    float dx = number(a, "dragX", 0), dy = number(a, "dragY", 0), dz = number(a, "dragZ", 0);
                    JSONArray path = a.optJSONArray("points");
                    float[] points;
                    if (path != null) {
                        if (path.length() < 3 || path.length() > 192 || path.length() % 3 != 0)
                            return "Invalid sculpt path. Provide 1-64 xyz points as a flat list of coordinate triplets.";
                        points = new float[path.length()];
                        for (int i = 0; i < path.length(); i++) {
                            double value = path.optDouble(i, Double.NaN);
                            if (!Double.isFinite(value) || Math.abs(value) > 1000.0) return "Invalid sculpt path. Provide finite local xyz coordinates within -1000..1000.";
                            points[i] = (float) value;
                        }
                    } else {
                        points = new float[]{number(a, "x", 0), number(a, "y", 0), number(a, "z", 0)};
                    }
                    int result = NativeBridge.nativeAISculptStroke(points, radius, strength, brush,
                            hardness, symmetryAxis, subtract, pressure, dx, dy, dz);
                    if (result > 0) return "Queued " + result + " interpolated " + brushName + " sculpt dabs on the active SculptMesh. The background sculpt worker is processing the stroke.";
                    if (result == -1) return "No active sculpt mesh is selected. Call create_sculpt_mesh first, then sculpt the selected sphere or plane.";
                    if (result == -2) return "Engine is not initialized.";
                    if (result == -4) return "The selected mesh has no active SculptMesh CPU data. Create a dense sculpt mesh first.";
                    if (result == -6) return "This stroke is too long for one safe batch. Split it into shorter paths; dab spacing is preserved at 25% of brush radius.";
                    return "Sculpt stroke rejected. Check the path triplets and brush parameters.";
                }
                case "extrude_face": return runMeshModeling(0, a.optInt("faceIndex", 0), number(a, "distance", 0.5f), "Face extrusion");
                case "inset_face": return runMeshModeling(1, a.optInt("faceIndex", 0), number(a, "amount", 0.15f), "Face inset");
                case "bevel_edge": return runMeshModeling(2, a.optInt("edgeIndex", 0), number(a, "width", 0.08f), "Edge bevel");
                case "loop_cut": return runMeshModeling(3, 0, 0.0f, "Loop cut");
                case "subdivide_mesh": return runMeshModeling(4, Math.max(1, Math.min(3, a.optInt("levels", 1))), 0.0f, "Mesh subdivision");
                case "merge_vertices": return runMeshModeling(5, 0, number(a, "tolerance", 0.001f), "Vertex merge");
                case "knife_cut": return runMeshModeling(6, a.optInt("faceIndex", 0), 0.0f, "Knife topology cut");
                case "paint_foliage": {
                    int kind = a.optString("kind","tree").equals("bush") ? 1 : 0;
                    float x=number(a,"x",0), z=number(a,"z",-4), radius=number(a,"radius",6);
                    int count=Math.max(1,Math.min(40,a.optInt("count",10)));
                    int planted = runOnRenderThread(() -> NativeBridge.nativePaintFoliage(x,z,radius,count,kind));
                    return "Placed " + planted + " " + (kind==0?"trees":"bushes") + ".";
                }
                case "clear_foliage":
                    int removed = runOnRenderThread(() -> NativeBridge.nativeClearFoliage());
                    return "Removed " + removed + " foliage scene objects.";
                case "select_object": {
                    int id = a.optInt("id", 0);
                    if (id <= 0) return "Invalid scene object ID.";
                    runOnRenderThread(() -> { NativeBridge.nativeSelectObjectById(id); return true; });
                    return "Selected scene object ID " + id + ".";
                }
                case "move_selected": {
                    float[] t=engine.getSelectedTransform();
                    if(t==null || t.length<9) return "Could not read selected transform.";
                    t[0]+=number(a,"dx",0); t[1]+=number(a,"dy",0); t[2]+=number(a,"dz",0);
                    runOnRenderThread(() -> { NativeBridge.nativeSetSelectedTransform(t); return true; });
                    return "Selected object moved to (" + t[0] + ", " + t[1] + ", " + t[2] + ").";
                }
                case "rotate_selected": {
                    float[] t=engine.getSelectedTransform();
                    if(t==null || t.length<9) return "Could not read selected transform.";
                    t[3]+=number(a,"rx",0); t[4]+=number(a,"ry",0); t[5]+=number(a,"rz",0);
                    runOnRenderThread(() -> { NativeBridge.nativeSetSelectedTransform(t); return true; });
                    return "Selected object rotation updated (radians).";
                }
                case "scale_selected": {
                    float[] t=engine.getSelectedTransform();
                    if(t==null || t.length<9) return "Could not read selected transform.";
                    t[6]=Math.max(0.05f, Math.min(50f, t[6]*number(a,"sx",1)));
                    t[7]=Math.max(0.05f, Math.min(50f, t[7]*number(a,"sy",1)));
                    t[8]=Math.max(0.05f, Math.min(50f, t[8]*number(a,"sz",1)));
                    runOnRenderThread(() -> { NativeBridge.nativeSetSelectedTransform(t); return true; });
                    return "Selected object scale updated to (" + t[6] + ", " + t[7] + ", " + t[8] + ").";
                }
                case "undo":
                    boolean undone = runOnRenderThread(() -> NativeBridge.nativeUndo()); return undone ? "Undo completed." : "Nothing available to undo.";
                case "redo":
                    boolean redone = runOnRenderThread(() -> NativeBridge.nativeRedo()); return redone ? "Redo completed." : "Nothing available to redo.";
                default: return "Unknown tool. No action performed.";
            }
        } catch (Exception ex) { return "Tool error: " + safeMessage(ex); }
    }


    private String runMeshModeling(int operation, int elementIndex, float amount, String label) {
        if (elementIndex < 0 || elementIndex > 300000) return label + " failed: invalid element index.";
        int result = runOnRenderThread(() -> NativeBridge.nativeModelSelectedMesh(operation, elementIndex, amount));
        if (result == 1) return label + " completed on the selected mesh. Real mesh topology was updated and uploaded to the renderer.";
        if (result == -1) return label + " failed: select or create a custom mesh first.";
        if (result == -2) return label + " failed: engine is not initialized.";
        if (result == -3) return label + " rejected: mesh complexity limit reached. Reduce subdivision.";
        if (result == -4) return label + " failed: GPU mesh upload failed; the previous mesh is retained.";
        return label + " failed: the requested face/edge may not exist or the operation would produce invalid geometry.";
    }

    private interface RenderCall<T> { T run(); }

    private <T> T runOnRenderThread(RenderCall<T> call) {
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<T> result = new java.util.concurrent.atomic.AtomicReference<>();
        engine.queueEvent(() -> {
            try { result.set(call.run()); }
            finally { latch.countDown(); }
        });
        try {
            if (!latch.await(12, java.util.concurrent.TimeUnit.SECONDS))
                throw new IllegalStateException("Renderer did not respond in time.");
            return result.get();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Scene operation interrupted.");
        }
    }

    private float number(JSONObject o, String key, float fallback) { return (float)o.optDouble(key, fallback); }
    private String safeMessage(Exception e) { return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(); }

    private void append(String text) {
        if (transcript == null) return;
        transcript.append(text);
        scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
    }

    private int dp(float v) { return (int)(v * activity.getResources().getDisplayMetrics().density + 0.5f); }
}
