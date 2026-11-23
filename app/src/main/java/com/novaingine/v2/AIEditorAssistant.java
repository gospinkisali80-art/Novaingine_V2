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
    private static final String MODEL_ID = "openai/gpt-oss-20b";
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
            system.put("content", "You are NOVA AI, the Groq-powered modeling, sculpting and scene assistant inside Novaingine. Build recognizable assets from several purpose-built connected mesh parts with create_mesh_part, not only a deformed sphere. Use 3-8 parts, deliberate silhouettes and clean triangulated topology. Use sculpt tools for organic refinement. Blender desktop bridge remains available for high-detail modeling. You can directly sculpt custom meshes through Novaingine's built-in eight-brush mesh sculptor. Keep the Blender-to-GLB integration available; never remove or replace it. For AI-created sculpted assets, use create_sculpt_mesh to create a dense sphere or plane, then call sculpt_selected_mesh repeatedly with Draw, Smooth, Grab, Inflate, Pinch, Flatten, Crease, Mask, Clay, Scrape, Layer or Unmask to shape the actual selected mesh. Brush centers are LOCAL mesh coordinates; X is left/right, Y is up, Z is depth. A sphere starts around radius 1.0; a plane spans about -1 to +1 in X/Z. Use multiple varied brush passes and smoothing to build recognizable forms; do not merely describe steps. Keep each operation moderate for Android performance. Grab uses dragX/dragY/dragZ to pull geometry. Mask protects vertices from later sculpt passes. If a selected sculptable mesh already exists, sculpt it rather than creating another unless requested. You may also use the existing terrain and scene tools. Never claim success unless a tool confirms it. Preserve existing scene content unless asked to remove it. Be honest about current sculpting limits and do not claim Blender or Unreal parity.");
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
        transcript.setText("Novaingine AI is ready.\n\nTry: “Sculpt a rocky creature with a broad body, head, legs and smooth details” or “Sculpt a rocky hill on a dense plane.” Blender GLB import remains supported.\n\nSet your Groq API key using API KEY to connect the model.");
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
        prompt.setHint("Tell Groq what mesh to sculpt...");
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


    private String getModel() {
        return MODEL_ID;
    }

    private void updateModelInfo() {
        if (modelInfo != null) {
            modelInfo.setText("Provider: Groq  |  Model: OpenAI GPT-OSS 20B (" + MODEL_ID + ")  |  AI scene assistant");
        }
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
        compactHistoryForNextTurn();
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
            body.put("model", getModel());
            body.put("messages", history);
            body.put("tools", tools);
            body.put("tool_choice", "auto");
            body.put("temperature", 0.6);
            body.put("max_completion_tokens", 2048);
            body.put("reasoning_effort", "medium");
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
        int code;
        String responseText;
        String retryAfter;
        try {
            try (OutputStream out = connection.getOutputStream()) { out.write(data); }
            code = connection.getResponseCode();
            retryAfter = connection.getHeaderField("Retry-After");
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
        } finally {
            connection.disconnect();
        }

        JSONObject json;
        try {
            json = new JSONObject(responseText);
        } catch (Exception parseError) {
            if (code >= 200 && code < 300) throw new Exception("Groq returned an unreadable response.");
            throw new Exception("Groq HTTP " + code + ": " + responseText);
        }

        if (code >= 200 && code < 300) return json;
        String message = json.optJSONObject("error") == null ? json.toString()
                : json.getJSONObject("error").optString("message", json.toString());

        if (code == 429) {
            String wait = (retryAfter == null || retryAfter.trim().isEmpty())
                    ? "" : " Groq says to retry after " + retryAfter + " seconds.";
            throw new Exception("Groq HTTP 429: GPT-OSS 20B is rate-limited for this API key."
                    + wait + " I stopped automatic retries so Novaingine will not keep restarting. "
                    + "Your prompt is preserved; wait for the limit to reset, then press SEND again. "
                    + message);
        }
        throw new Exception("Groq HTTP " + code + ": " + message);
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
        tools.put(tool("create_mesh_part", "Create one real custom triangulated mesh part from flat xyz positions and triangle indices. Use 3-300 vertices and 1-600 triangles. Local coordinates must stay within -20..20. Build complex assets from multiple connected parts with sensible proportions and slight overlap at joints.", "{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\"},\"positions\":{\"type\":\"array\",\"items\":{\"type\":\"number\"},\"minItems\":9,\"maxItems\":900},\"triangles\":{\"type\":\"array\",\"items\":{\"type\":\"integer\"},\"minItems\":3,\"maxItems\":1800},\"x\":{\"type\":\"number\"},\"y\":{\"type\":\"number\"},\"z\":{\"type\":\"number\"},\"r\":{\"type\":\"number\"},\"g\":{\"type\":\"number\"},\"b\":{\"type\":\"number\"}},\"required\":[\"positions\",\"triangles\"]}"));\n        tools.put(tool("create_sculpt_mesh", "Create and select an editable dense sphere or plane mesh for sculpting. Use density 32-80 on mobile. This creates actual geometry.", "{\"type\":\"object\",\"properties\":{\"shape\":{\"type\":\"string\",\"enum\":[\"sphere\",\"plane\"]},\"density\":{\"type\":\"integer\"}}}"));
        tools.put(tool("sculpt_selected_mesh", "Apply one real brush pass to the selected custom mesh. brush: draw, smooth, grab, inflate, pinch, flatten, crease or mask. x/y/z are local mesh coordinates. radius 0.01-3; strength 0.001-0.5; falloff 0-1; symmetry mirrors across X. Grab uses dragX/dragY/dragZ. Call repeatedly with different centers and brushes to build a shape.", "{\"type\":\"object\",\"properties\":{\"brush\":{\"type\":\"string\",\"enum\":[\"draw\",\"smooth\",\"grab\",\"inflate\",\"pinch\",\"flatten\",\"crease\",\"mask\"]},\"x\":{\"type\":\"number\"},\"y\":{\"type\":\"number\"},\"z\":{\"type\":\"number\"},\"radius\":{\"type\":\"number\"},\"strength\":{\"type\":\"number\"},\"falloff\":{\"type\":\"number\"},\"symmetry\":{\"type\":\"boolean\"},\"dragX\":{\"type\":\"number\"},\"dragY\":{\"type\":\"number\"},\"dragZ\":{\"type\":\"number\"}}}"));
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
                    if(pv==null || ti==null || pv.length()<9 || pv.length()>900 || pv.length()%3!=0 || ti.length()<3 || ti.length()>1800 || ti.length()%3!=0)
                        return "Invalid mesh arrays. Provide 3-300 vertices and triangle indices.";
                    float[] positions=new float[pv.length()]; int[] triangles=new int[ti.length()];
                    for(int i=0;i<pv.length();i++){ double v=pv.optDouble(i,Double.NaN); if(!Double.isFinite(v)||Math.abs(v)>20) return "Vertex coordinate outside safe range."; positions[i]=(float)v; }
                    int vc=positions.length/3;
                    for(int i=0;i<ti.length();i++){ int v=ti.optInt(i,-1); if(v<0||v>=vc) return "Triangle index outside vertex array."; triangles[i]=v; }
                    String name=a.optString("name","AI Mesh Part");
                    float x=number(a,"x",0), y=number(a,"y",0), z=number(a,"z",0);
                    float red=Math.max(0,Math.min(1,number(a,"r",0.62f))), green=Math.max(0,Math.min(1,number(a,"g",0.68f))), blue=Math.max(0,Math.min(1,number(a,"b",0.76f)));
                    int id=runOnRenderThread(() -> NativeBridge.nativeCreateMeshPart(positions,triangles,name,x,y,z,red,green,blue));
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
