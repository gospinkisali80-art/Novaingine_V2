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
    private static final String MODEL_ID = "openai/gpt-oss-120b";
    private static final String GROQ_CHAT_URL = "https://api.groq.com/openai/v1/chat/completions";
    private static final String PREFS_NAME = "nova_groq_settings";
    private static final String PREF_API_KEY = "groq_api_key";
    private TextView modelInfo;
    private final Activity activity;
    private final EngineSurface engine;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final JSONArray history = new JSONArray();
    private TextView transcript;
    private EditText prompt;
    private ScrollView scroll;
    private AlertDialog dialog;
    private boolean busy;

    public AIEditorAssistant(Activity activity, EngineSurface engine) {
        this.activity = activity;
        this.engine = engine;
        JSONObject system = new JSONObject();
        try {
            system.put("role", "system");
            system.put("content", "You are NOVA AI, powered by Groq, the modeling and scene-building assistant inside Novaingine. When asked to build an object, character, vehicle, building, prop or environment, CREATE REAL VISIBLE 3D GEOMETRY IN THE CURRENT SCENE, not just explain how. Use create_mesh_part for actual triangulated mesh geometry, then refine selected custom meshes with native topology tools. Build recognizable assets with deliberate silhouettes, correct proportions, varied cross-sections, and connected surfaces; use about 4-8 purpose-built meshes for a substantial asset when appropriate. Each mesh part supports up to 3000 vertices and 6000 triangles, local coordinates -20..20. Use extrude_face, inset_face, bevel_edge, loop_cut, subdivide_mesh, merge_vertices, and knife_cut on the selected mesh to perform real topology edits. Use faceIndex or edgeIndex starting at 0 unless scene inspection supplies a better index. Subdivision is capped at 3 levels and Android mesh complexity is capped. Do not claim a tool operation succeeded unless its result confirms success. Use coherent surfaces and sensible placement; do not make every object from identical boxes. After creating parts, inspect the scene and report exactly what was created. For organic assets, create a dense sculpt mesh and apply multiple sculpt_selected_mesh passes with varied brushes to shape actual geometry. Brush centers are LOCAL mesh coordinates; X is left/right, Y is up, Z is depth. A sphere starts around radius 1.0; a plane spans about -1 to +1 in X/Z. Grab uses dragX/dragY/dragZ. Mask protects vertices; Unmask clears protection. Use smoothing and additional passes to refine the silhouette. If editing an existing object, inspect and select it first. Preserve all existing scene content unless the user explicitly asks to remove it. Keep Blender desktop bridge and GLB import available. Use existing terrain and scene tools when relevant. Keep operations moderate for Android performance. Never claim a model exists unless creation tools confirm success. If a geometry tool fails, explain the failure. Be honest about current sculpting limits and do not claim Blender or Unreal parity.");
            history.put(system);
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
            modelInfo.setText("Provider: Groq GPT-OSS 120B  |  " + (configured ? "API key saved on this device" : "API key required"));
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
        chatSystem.put("content", "You are Novaingine's helpful local AI assistant. Answer clearly and briefly. Do not claim to change the 3D scene; use scene tools only for explicit modeling requests.");
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
        HttpURLConnection connection = (HttpURLConnection) new URL(GROQ_CHAT_URL).openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(180000);
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestProperty("Authorization", "Bearer " + apiKey);
        byte[] data = body.toString().getBytes(StandardCharsets.UTF_8);
        int code;
        String responseText;
        try {
            try (OutputStream out = connection.getOutputStream()) { out.write(data); }
            code = connection.getResponseCode();
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
            responseText = result.toString();
        } catch (java.net.ConnectException ex) {
            throw new Exception("Could not connect to Groq. Check your internet connection and try again.");
        } finally {
            connection.disconnect();
        }
        JSONObject json;
        try {
            json = new JSONObject(responseText);
        } catch (Exception parseError) {
            throw new Exception("Groq returned an unreadable response (HTTP " + code + ").");
        }
        if (code >= 200 && code < 300) return json;
        String message = json.optJSONObject("error") == null ? json.toString()
                : json.getJSONObject("error").optString("message", json.toString());
        throw new Exception("Groq API HTTP " + code + ": " + message);
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
        JSONArray tools = new JSONArray();
        tools.put(tool("inspect_scene", "List scene object IDs and names, and the selected object's transform.", "{\"type\":\"object\",\"properties\":{}}"));
        tools.put(tool("create_terrain", "Generate or replace the editable terrain patch.", "{\"type\":\"object\",\"properties\":{}}"));
        tools.put(tool("sculpt_terrain", "Sculpt terrain. Optional args: x defaults 0, z defaults -4, radius defaults 3, strength defaults 0.4, mode defaults raise. mode: raise, lower, smooth, flatten.", "{\"type\":\"object\",\"properties\":{\"x\":{\"type\":\"number\"},\"z\":{\"type\":\"number\"},\"radius\":{\"type\":\"number\"},\"strength\":{\"type\":\"number\"},\"mode\":{\"type\":\"string\",\"enum\":[\"raise\",\"lower\",\"smooth\",\"flatten\"]}}}"));
        tools.put(tool("create_mesh_part", "Create one real custom triangulated mesh part from flat xyz positions and triangle indices. Use 3-300 vertices and 1-600 triangles. Local coordinates must stay within -20..20. Build complex assets from multiple connected parts with sensible proportions and slight overlap at joints.", "{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\"},\"positions\":{\"type\":\"array\",\"items\":{\"type\":\"number\"},\"minItems\":9,\"maxItems\":900},\"triangles\":{\"type\":\"array\",\"items\":{\"type\":\"integer\"},\"minItems\":3,\"maxItems\":1800},\"x\":{\"type\":\"number\"},\"y\":{\"type\":\"number\"},\"z\":{\"type\":\"number\"},\"r\":{\"type\":\"number\"},\"g\":{\"type\":\"number\"},\"b\":{\"type\":\"number\"}},\"required\":[\"positions\",\"triangles\"]}"));
        tools.put(tool("create_sculpt_mesh", "Create and select an editable dense sphere or plane mesh for sculpting. Use density 32-80 on mobile. This creates actual geometry.", "{\"type\":\"object\",\"properties\":{\"shape\":{\"type\":\"string\",\"enum\":[\"sphere\",\"plane\"]},\"density\":{\"type\":\"integer\"}}}"));
        tools.put(tool("sculpt_selected_mesh", "Apply one real brush pass to the selected custom mesh. brush: draw, smooth, grab, inflate, pinch, flatten, crease, mask, clay, scrape, layer or unmask. x/y/z are local mesh coordinates. radius 0.01-3; strength 0.001-0.5; falloff 0-1; symmetry mirrors across X. Grab uses dragX/dragY/dragZ. Call repeatedly with different centers and brushes to build a shape.", "{\"type\":\"object\",\"properties\":{\"brush\":{\"type\":\"string\",\"enum\":[\"draw\",\"smooth\",\"grab\",\"inflate\",\"pinch\",\"flatten\",\"crease\",\"mask\",\"clay\",\"scrape\",\"layer\",\"unmask\"]},\"x\":{\"type\":\"number\"},\"y\":{\"type\":\"number\"},\"z\":{\"type\":\"number\"},\"radius\":{\"type\":\"number\"},\"strength\":{\"type\":\"number\"},\"falloff\":{\"type\":\"number\"},\"symmetry\":{\"type\":\"boolean\"},\"dragX\":{\"type\":\"number\"},\"dragY\":{\"type\":\"number\"},\"dragZ\":{\"type\":\"number\"}}}"));
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

    private JSONObject tool(String name, String description, String schema) throws Exception {
        JSONObject fn = new JSONObject();
        fn.put("name", name); fn.put("description", description); fn.put("parameters", new JSONObject(schema));
        JSONObject item = new JSONObject(); item.put("type", "function"); item.put("function", fn);
        return item;
    }

    private String executeTool(String name, JSONObject a) {
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
                            : brushName.equals("mask") ? 7 : brushName.equals("clay") ? 8 : brushName.equals("scrape") ? 9 : brushName.equals("layer") ? 10 : brushName.equals("unmask") ? 11 : 0;
                    float x = number(a, "x", 0), y = number(a, "y", 0), z = number(a, "z", 0);
                    float radius = Math.max(0.01f, Math.min(3.0f, number(a, "radius", 0.35f)));
                    float strength = Math.max(0.001f, Math.min(0.5f, number(a, "strength", 0.12f)));
                    float falloff = Math.max(0.0f, Math.min(1.0f, number(a, "falloff", 0.5f)));
                    boolean symmetry = a.optBoolean("symmetry", true);
                    float dx = number(a, "dragX", 0), dy = number(a, "dragY", 0), dz = number(a, "dragZ", 0);
                    int result = runOnRenderThread(() -> NativeBridge.nativeAISculptSelectedMesh(
                            x, y, z, radius, strength, brush, falloff, symmetry, dx, dy, dz));
                    if (result == 1) return "Applied " + brushName + " brush to selected mesh successfully.";
                    if (result == -1) return "No selected sculptable mesh. Call create_sculpt_mesh first or select an imported custom mesh.";
                    if (result == -2) return "Engine is not initialized.";
                    if (result == -3) return "Mesh upload failed.";
                    return "Brush made no geometry change at that local coordinate. Try another center, a larger radius or a different brush.";
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
