package com.novaingine.v2;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
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

/** Groq-backed editor agent. The model proposes tool calls; this class validates and executes them locally. */
public final class AIEditorAssistant {
    private static final String PREFS = "nova_ai_settings";
    private static final String KEY = "groq_api_key";
    private static final String MODEL = "openai/gpt-oss-20b";
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
            system.put("content", "You are NOVA AI, a professional real-time 3D world-building agent inside Novaingine, inspired by the workflow and quality expectations of Unreal Editor. You MUST build requested creations by executing scene tools, not merely describe them. Think like a level artist and modular environment artist. For a house, create a coherent complete structure: raised foundation, floor slab, separate walls, roof sections, door, window frames and glass, trim, steps and tasteful details. Use multiple accurately positioned and scaled primitives; avoid a pile of random cubes. Keep parts aligned, intersecting slightly where needed to avoid gaps, use consistent dimensions, complementary colors and sensible materials. For roads, bridges, towers and other assets, decompose into logical structural parts and details. Inspect the scene first when placement matters, build around existing objects without destroying them, and explain the layout. Coordinates use X horizontal, Y up, Z depth; default arena center is near X=0, Z=-4. Use sensible human-scale proportions (door about 1.0 wide x 2.1 high; storey about 3 units). Place requested assets beside the arena if asked, not on top of it. Use many tool calls in a single response when needed, but stay under the operation limit. After building, inspect the scene and report what was actually created. Use create_primitive repeatedly for compound assets; use select_object before transform tools. Rotate values are radians. Never claim an action succeeded unless its tool reports success. Preserve existing scene content unless the user asks to remove it. Keep mobile performance in mind.");
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
        title.setText("NOVA AI  •  GROQ");
        title.setTextColor(0xFFFFB45C);
        title.setTextSize(16);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        top.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        Button keyButton = new Button(activity);
        keyButton.setText("API KEY");
        keyButton.setOnClickListener(v -> showKeyDialog());
        top.addView(keyButton);
        root.addView(top);

        TextView model = new TextView(activity);
        model.setText("Model: " + MODEL + "  |  Tool-enabled scene assistant");
        model.setTextColor(0xFF9EADBE);
        model.setTextSize(10);
        root.addView(model);

        scroll = new ScrollView(activity);
        scroll.setFillViewport(true);
        transcript = new TextView(activity);
        transcript.setTextColor(Color.WHITE);
        transcript.setTextSize(13);
        transcript.setPadding(dp(10), dp(10), dp(10), dp(10));
        transcript.setText("Novaingine AI is ready.\n\nTry: “Create a large platform beside the central pedestal” or “Plant 12 trees around the arena.”\n\nSet your Groq API key using API KEY to connect the model.");
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
        prompt.setHint("Tell the editor what to build...");
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
        if (getKey().isEmpty()) showKeyDialog();
    }

    private void showKeyDialog() {
        EditText field = new EditText(activity);
        field.setSingleLine(true);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        field.setHint("gsk_...");
        field.setText(getKey());
        new AlertDialog.Builder(activity).setTitle("Connect Groq AI")
                .setMessage("Enter your Groq API key. It is stored only in this app's private preferences and is not written to GitHub. Do not paste it into this chat.")
                .setView(field)
                .setPositiveButton("SAVE KEY", (d, w) -> {
                    String value = field.getText().toString().trim();
                    activity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE)
                            .edit().putString(KEY, value).apply();
                    append("\n[Groq key saved on this device.]\n");
                })
                .setNegativeButton("CANCEL", null).show();
    }

    private String getKey() {
        return activity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE).getString(KEY, "").trim();
    }

    private void sendPrompt() {
        if (busy) return;
        String text = prompt.getText().toString().trim();
        if (text.isEmpty()) return;
        if (getKey().isEmpty()) { showKeyDialog(); return; }
        prompt.setText("");
        append("\nYOU: " + text + "\n");
        busy = true;
        append("NOVA AI: Thinking and checking available scene tools...\n");
        worker.execute(() -> {
            try {
                JSONObject user = new JSONObject();
                user.put("role", "user");
                user.put("content", text);
                history.put(user);
                String answer = runAgent();
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

    private String runAgent() throws Exception {
        JSONArray tools = buildTools();
        int operationCount = 0;
        for (int step = 0; step < 12; step++) {
            JSONObject body = new JSONObject();
            body.put("model", MODEL);
            body.put("messages", history);
            body.put("tools", tools);
            body.put("tool_choice", "auto");
            body.put("temperature", 0.2);
            body.put("max_completion_tokens", 4096);
            body.put("reasoning_effort", "low");
            body.put("include_reasoning", false);
            body.put("parallel_tool_calls", false);
            JSONObject response = postGroq(body);
            JSONObject message = response.getJSONArray("choices").getJSONObject(0).getJSONObject("message");
            JSONArray calls = message.optJSONArray("tool_calls");
            if (calls == null || calls.length() == 0) {
                String content = message.optString("content", "").trim();
                JSONObject assistant = new JSONObject();
                assistant.put("role", "assistant");
                assistant.put("content", content);
                history.put(assistant);
                if (content.isEmpty()) {
                    String lastRequest = history.optJSONObject(history.length() - 1) == null ? ""
                            : history.optJSONObject(history.length() - 1).optString("content", "");
                    if (looksLikeHouseRequest(lastRequest)) {
                        String built = buildModernHouse(8.0f, -4.0f, 1.0f);
                        return "Groq returned an empty completion, so the local construction fallback ran. " + built;
                    }
                    return "Groq returned no text or scene-tool calls. No scene changes were made. Please retry.";
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

    private JSONObject postGroq(JSONObject body) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(
                "https://api.groq.com/openai/v1/chat/completions").openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(20000);
        connection.setReadTimeout(60000);
        connection.setDoOutput(true);
        connection.setRequestProperty("Authorization", "Bearer " + getKey());
        connection.setRequestProperty("Content-Type", "application/json");
        byte[] data = body.toString().getBytes(StandardCharsets.UTF_8);
        try (OutputStream out = connection.getOutputStream()) { out.write(data); }
        int code = connection.getResponseCode();
        java.io.InputStream stream = code >= 200 && code < 300
                ? connection.getInputStream() : connection.getErrorStream();
        StringBuilder result = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) result.append(line);
        }
        connection.disconnect();
        JSONObject json = new JSONObject(result.toString());
        if (code < 200 || code >= 300) {
            String message = json.optJSONObject("error") == null ? json.toString()
                    : json.getJSONObject("error").optString("message", json.toString());
            throw new Exception("Groq HTTP " + code + ": " + message);
        }
        return json;
    }

    private JSONArray buildTools() throws Exception {
        JSONArray tools = new JSONArray();
        tools.put(tool("build_house", "Construct a detailed two-storey modern house from real native scene objects. Use for house, home, villa or cottage requests. Optional center x, z and scale.", "{\"type\":\"object\",\"properties\":{\"x\":{\"type\":\"number\"},\"z\":{\"type\":\"number\"},\"scale\":{\"type\":\"number\"}}}"));
        tools.put(tool("create_primitive", "Create a 3D object in the current scene. primitive: cube, sphere, cylinder, plane, ramp or wedge.", "{\"type\":\"object\",\"properties\":{\"primitive\":{\"type\":\"string\",\"enum\":[\"cube\",\"sphere\",\"cylinder\",\"plane\",\"ramp\",\"wedge\"]},\"name\":{\"type\":\"string\"},\"x\":{\"type\":\"number\"},\"y\":{\"type\":\"number\"},\"z\":{\"type\":\"number\"},\"sx\":{\"type\":\"number\"},\"sy\":{\"type\":\"number\"},\"sz\":{\"type\":\"number\"},\"r\":{\"type\":\"number\"},\"g\":{\"type\":\"number\"},\"b\":{\"type\":\"number\"}},\"required\":[\"primitive\",\"name\",\"x\",\"y\",\"z\",\"sx\",\"sy\",\"sz\"]}"));
        tools.put(tool("inspect_scene", "List scene object IDs and names, and the selected object's transform.", "{\"type\":\"object\",\"properties\":{}}"));
        tools.put(tool("create_terrain", "Generate or replace the editable terrain patch.", "{\"type\":\"object\",\"properties\":{}}"));
        tools.put(tool("sculpt_terrain", "Sculpt terrain. mode is raise, lower, smooth or flatten.", "{\"type\":\"object\",\"properties\":{\"x\":{\"type\":\"number\"},\"z\":{\"type\":\"number\"},\"radius\":{\"type\":\"number\"},\"strength\":{\"type\":\"number\"},\"mode\":{\"type\":\"string\",\"enum\":[\"raise\",\"lower\",\"smooth\",\"flatten\"]}},\"required\":[\"x\",\"z\",\"radius\",\"strength\",\"mode\"]}"));
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
                case "build_house":
                    return buildModernHouse(number(a,"x",8.0f), number(a,"z",-4.0f), number(a,"scale",1.0f));
                case "create_primitive": {
                    String primitive = a.optString("primitive", "cube");
                    int type = primitive.equals("cylinder") ? 1 : primitive.equals("ramp") ? 2
                            : primitive.equals("wedge") ? 3 : primitive.equals("sphere") ? 4
                            : primitive.equals("plane") ? 5 : 0;
                    String objectName = a.optString("name", "AI " + primitive);
                    float x = number(a,"x",0), y = number(a,"y",0.5f), z = number(a,"z",-4);
                    float sx = number(a,"sx",1), sy = number(a,"sy",1), sz = number(a,"sz",1);
                    float red = clamp(number(a,"r",0.9f)), green = clamp(number(a,"g",0.65f)), blue = clamp(number(a,"b",0.12f));
                    int id = runOnRenderThread(() -> NativeBridge.nativeAddPrimitive(type, objectName, x,y,z,sx,sy,sz,red,green,blue));
                    return id > 0 ? "Created " + objectName + " with scene object ID " + id + "." : "Object creation failed.";
                }
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


    private boolean looksLikeHouseRequest(String text) {
        if (text == null) return false;
        String s = text.toLowerCase(java.util.Locale.ROOT);
        return s.contains("house") || s.contains("home") || s.contains("villa") || s.contains("cottage");
    }

    /** Creates a complete modular house using native scene objects, not a text-only model response. */
    private String buildModernHouse(float cx, float cz, float requestedScale) {
        final float k = Math.max(0.7f, Math.min(1.6f, requestedScale));
        // Each row: x, y, z, width, height, depth, red, green, blue.
        final float[][] p = {
            {0,.18f,0,6.8f,.36f,5.8f,.25f,.27f,.30f},
            {0,.43f,0,6.3f,.16f,5.3f,.72f,.70f,.64f},
            {0,3.02f,0,6.1f,.18f,5.1f,.30f,.32f,.36f},
            {-2.92f,1.72f,0,.28f,2.55f,5,.88f,.84f,.75f},
            {2.92f,1.72f,0,.28f,2.55f,5,.88f,.84f,.75f},
            {0,1.72f,-2.36f,5.55f,2.55f,.28f,.88f,.84f,.75f},
            {-1.85f,1.72f,2.36f,1.85f,2.55f,.28f,.88f,.84f,.75f},
            {1.85f,1.72f,2.36f,1.85f,2.55f,.28f,.88f,.84f,.75f},
            {0,2.82f,2.36f,1.85f,.34f,.28f,.88f,.84f,.75f},
            {-2.82f,4.28f,0,.28f,2.35f,4.95f,.90f,.88f,.81f},
            {2.82f,4.28f,0,.28f,2.35f,4.95f,.90f,.88f,.81f},
            {0,4.28f,-2.34f,5.45f,2.35f,.28f,.90f,.88f,.81f},
            {-1.78f,4.28f,2.34f,1.8f,2.35f,.28f,.90f,.88f,.81f},
            {1.78f,4.28f,2.34f,1.8f,2.35f,.28f,.90f,.88f,.81f},
            {0,5.55f,0,6.8f,.22f,5.9f,.12f,.15f,.19f},
            {-1.62f,5.72f,0,3.55f,.20f,6.0f,.16f,.19f,.23f},
            {1.62f,5.72f,0,3.55f,.20f,6.0f,.16f,.19f,.23f},
            {0,1.48f,2.57f,1.02f,2.08f,.12f,.20f,.12f,.08f},
            {-2.0f,1.75f,2.57f,1.15f,1.25f,.08f,.20f,.48f,.66f},
            {2.0f,1.75f,2.57f,1.15f,1.25f,.08f,.20f,.48f,.66f},
            {-1.72f,4.30f,2.54f,1.20f,1.35f,.08f,.20f,.48f,.66f},
            {1.72f,4.30f,2.54f,1.20f,1.35f,.08f,.20f,.48f,.66f},
            {0,3.18f,3.05f,3.0f,.16f,1.15f,.28f,.30f,.33f},
            {-1.42f,3.70f,3.56f,.10f,.95f,.10f,.20f,.23f,.26f},
            {1.42f,3.70f,3.56f,.10f,.95f,.10f,.20f,.23f,.26f},
            {0,4.16f,3.56f,2.95f,.10f,.10f,.20f,.23f,.26f},
            {0,.55f,3.15f,1.65f,.18f,.65f,.60f,.59f,.54f},
            {0,.40f,3.58f,1.95f,.14f,.55f,.60f,.59f,.54f},
            {-1.42f,1.55f,2.92f,.16f,2.25f,.16f,.80f,.78f,.70f},
            {1.42f,1.55f,2.92f,.16f,2.25f,.16f,.80f,.78f,.70f}
        };
        final String[] names = {
            "House Foundation","House Ground Floor","House Upper Floor",
            "House Lower Left Wall","House Lower Right Wall","House Lower Rear Wall",
            "House Lower Front Left","House Lower Front Right","House Front Lintel",
            "House Upper Left Wall","House Upper Right Wall","House Upper Rear Wall",
            "House Upper Front Left","House Upper Front Right","House Roof Cap",
            "House Roof Wing Left","House Roof Wing Right","House Front Door",
            "House Ground Window Left","House Ground Window Right","House Upper Window Left",
            "House Upper Window Right","House Balcony Slab","House Balcony Rail Left",
            "House Balcony Rail Right","House Balcony Top Rail","House Front Step",
            "House Porch Step","House Porch Column Left","House Porch Column Right"
        };
        int count = runOnRenderThread(() -> {
            int made = 0;
            for (int i=0; i<p.length; i++) {
                float[] a = p[i];
                int id = NativeBridge.nativeAddPrimitive(0, names[i],
                    cx+a[0]*k, a[1]*k, cz+a[2]*k,
                    a[3]*k, a[4]*k, a[5]*k, a[6], a[7], a[8]);
                if (id > 0) made++;
            }
            return made;
        });
        return count == p.length
            ? "Created a two-storey modern house with " + count + " real scene parts: foundation, floors, walls, roof, door, glass windows, balcony, steps and columns."
            : "House creation incomplete: " + count + " of " + p.length + " parts were created.";
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
    private float clamp(float n) { return Math.max(0f, Math.min(1f, n)); }
    private String safeMessage(Exception e) { return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(); }

    private void append(String text) {
        if (transcript == null) return;
        transcript.append(text);
        scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
    }

    private int dp(float v) { return (int)(v * activity.getResources().getDisplayMetrics().density + 0.5f); }
}
