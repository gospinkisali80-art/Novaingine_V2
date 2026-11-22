package com.novaingine.v2;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.pm.ActivityInfo;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.ScrollView;

public final class MainActivity extends Activity
        implements EditorToolbar.ViewportActions {

    private FrameLayout rootLayout;

    private EngineSurface engineSurface;
    private ViewportInput viewportInput;
    private EditorToolbar editorToolbar;
    private CameraControls cameraControls;

    private TextView statisticsText;

    private boolean viewportMaximized;
    private View toolsScrim;
    private LinearLayout toolsDrawer;
    private boolean toolsDrawerOpen;
    private boolean drawerPhysicsEnabled;

    private int normalToolbarHeight;
    private int normalInputHeight;

    @Override
    protected void onCreate(Bundle savedInstanceState) {

        super.onCreate(savedInstanceState);

        getWindow().setFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN
        );

        getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        );

        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        );

        setRequestedOrientation(
                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        );

        normalToolbarHeight = dp(48);
        normalInputHeight = dp(190);

        createViewport();
        createToolbar();
        createTouchControls();
        createStatistics();
        createEditorPanels();

        setContentView(rootLayout);

        cameraControls = new CameraControls(engineSurface);
    }

    private void createViewport() {

        rootLayout = new FrameLayout(this);

        rootLayout.setBackgroundColor(Color.BLACK);

        engineSurface = new EngineSurface(this);

        FrameLayout.LayoutParams viewportParams =
                new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                );

        rootLayout.addView(
                engineSurface,
                viewportParams
        );
    }

    private void createToolbar() {

        editorToolbar = new EditorToolbar(
                this,
                engineSurface,
                this
        );

        FrameLayout.LayoutParams toolbarParams =
                new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        normalToolbarHeight,
                        Gravity.TOP
                );

        rootLayout.addView(
                editorToolbar,
                toolbarParams
        );
    }

    private void createTouchControls() {

        viewportInput = new ViewportInput(
                this,
                engineSurface
        );

        FrameLayout.LayoutParams inputParams =
                new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        normalInputHeight,
                        Gravity.BOTTOM
                );

        rootLayout.addView(
                viewportInput,
                inputParams
        );
    }

    private void createStatistics() {

        statisticsText = new TextView(this);

        statisticsText.setTextColor(Color.WHITE);
        statisticsText.setTextSize(11);
        statisticsText.setPadding(
                dp(8),
                dp(5),
                dp(8),
                dp(5)
        );

        statisticsText.setBackgroundColor(
                0x99000000
        );

        statisticsText.setText(
                "NOVAINGINE V2  |  VIEWPORT"
        );

        FrameLayout.LayoutParams statsParams =
                new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        Gravity.TOP | Gravity.END
                );

        statsParams.setMargins(
                0,
                dp(55),
                dp(8),
                0
        );

        rootLayout.addView(
                statisticsText,
                statsParams
        );
    }


    private void createEditorPanels() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.HORIZONTAL);
        panel.setPadding(dp(5), dp(4), dp(5), dp(4));
        panel.setBackgroundColor(0xDD202020);

        TextView outliner = new TextView(this);
        outliner.setText("WORLD OUTLINER");
        outliner.setTextColor(Color.WHITE);
        outliner.setTextSize(10);
        outliner.setGravity(Gravity.CENTER);
        outliner.setPadding(dp(10), dp(8), dp(10), dp(8));
        outliner.setBackgroundColor(0xFF3A3A3A);
        outliner.setOnClickListener(view -> showWorldOutliner());

        TextView details = new TextView(this);
        details.setText("DETAILS");
        details.setTextColor(Color.WHITE);
        details.setTextSize(10);
        details.setGravity(Gravity.CENTER);
        details.setPadding(dp(10), dp(8), dp(10), dp(8));
        details.setBackgroundColor(0xFF3A3A3A);
        details.setOnClickListener(view -> showDetailsInspector());

        TextView undo = new TextView(this);
        undo.setText("UNDO");
        undo.setTextColor(Color.WHITE);
        undo.setTextSize(10);
        undo.setGravity(Gravity.CENTER);
        undo.setPadding(dp(10), dp(8), dp(10), dp(8));
        undo.setBackgroundColor(0xFF3A3A3A);
        undo.setOnClickListener(view -> engineSurface.undoScene());

        TextView redo = new TextView(this);
        redo.setText("REDO");
        redo.setTextColor(Color.WHITE);
        redo.setTextSize(10);
        redo.setGravity(Gravity.CENTER);
        redo.setPadding(dp(10), dp(8), dp(10), dp(8));
        redo.setBackgroundColor(0xFF3A3A3A);
        redo.setOnClickListener(view -> engineSurface.redoScene());

        LinearLayout.LayoutParams itemParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        itemParams.setMargins(dp(3), 0, dp(3), 0);
        panel.addView(outliner, itemParams);
        panel.addView(details, itemParams);
        panel.addView(undo, itemParams);
        panel.addView(redo, itemParams);

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.END
        );
        params.setMargins(0, dp(55), dp(8), 0);
        rootLayout.addView(panel, params);
    }


    @Override
    public void onOpenToolsDrawer() {
        if (toolsDrawerOpen) {
            closeToolsDrawer();
        } else {
            openToolsDrawer();
        }
    }

    private void openToolsDrawer() {
        if (toolsDrawerOpen || rootLayout == null) return;
        toolsDrawerOpen = true;

        toolsScrim = new View(this);
        toolsScrim.setBackgroundColor(0x99000000);
        toolsScrim.setAlpha(0.0f);
        toolsScrim.setOnClickListener(view -> closeToolsDrawer());
        rootLayout.addView(toolsScrim, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(0xFF171A20);

        toolsDrawer = new LinearLayout(this);
        toolsDrawer.setOrientation(LinearLayout.VERTICAL);
        toolsDrawer.setPadding(dp(16), dp(18), dp(16), dp(24));
        toolsDrawer.setBackground(new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0xFF242A33, 0xFF15191F}
        ));
        scroll.addView(toolsDrawer);

        addDrawerHeader("NOVAINGINE TOOLS", "EDITOR CONTROL CENTER");
        addDrawerSection("SCENE");
        addDrawerItem("World Outliner", "Browse and select scene objects", this::showWorldOutliner);
        addDrawerItem("Details Inspector", "Edit position, rotation and scale", this::showDetailsInspector);
        addDrawerItem("Save Scene", "Save the current .nova scene", this::saveCurrentScene);
        addDrawerItem("Load Scene", "Load the saved .nova scene", this::loadCurrentScene);
        addDrawerItem("Undo", "Undo the latest supported scene edit", () -> engineSurface.undoScene());
        addDrawerItem("Redo", "Redo the last undone scene edit", () -> engineSurface.redoScene());

        addDrawerSection("WORLD BUILDING");
        addDrawerItem("Terrain Sculpting", "Generate terrain and raise, lower, smooth or flatten it", this::showTerrainSculptor);
        addDrawerItem("Foliage Painting", "Paint trees and bushes into the scene", this::showFoliagePainter);
        addDrawerItem("Asset Importing", "Import 3D models and textures", () -> showToolStatus("Asset Importing"));

        addDrawerSection("RENDERING");
        addDrawerItem("Global Illumination", "Indirect lighting controls", () -> showToolStatus("Global Illumination"));
        addDrawerItem("Reflections", "Environment and surface reflections", () -> showToolStatus("Reflections"));

        addDrawerSection("GAME SYSTEMS");
        addDrawerItem("Animation", "Keyframes and playback", () -> showToolStatus("Animation"));
        addDrawerItem("Audio", "Sound sources and audio controls", () -> showToolStatus("Audio"));
        addDrawerItem("AI Assistant", "Groq-powered scene-building assistant", this::openAIAssistant);
        addDrawerItem("Visual Scripting", "Node-based gameplay logic", () -> showToolStatus("Visual Scripting"));

        addDrawerSection("EDITOR");
        addDrawerItem("Physics: " + (drawerPhysicsEnabled ? "ON" : "OFF"), "Toggle collision and gravity", () -> {
            drawerPhysicsEnabled = !drawerPhysicsEnabled;
            engineSurface.setPhysicsEnabled(drawerPhysicsEnabled);
            closeToolsDrawer();
        });
        addDrawerItem("Reset Scene", "Restore the default arena", () -> {
            engineSurface.resetScene();
            closeToolsDrawer();
        });
        addDrawerItem("Close Menu", "Return to the viewport", this::closeToolsDrawer);

        FrameLayout.LayoutParams drawerParams = new FrameLayout.LayoutParams(
                Math.min(dp(340), (int)(getResources().getDisplayMetrics().widthPixels * 0.86f)),
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.END
        );
        rootLayout.addView(scroll, drawerParams);
        scroll.setTranslationX(drawerParams.width);
        toolsDrawer = (LinearLayout) scroll.getChildAt(0);

        toolsScrim.animate().alpha(1.0f).setDuration(180).start();
        scroll.animate().translationX(0).setDuration(220).start();
    }

    private void closeToolsDrawer() {
        if (!toolsDrawerOpen) return;
        toolsDrawerOpen = false;
        View drawer = toolsDrawer == null ? null : (View) toolsDrawer.getParent();
        if (drawer == null) {
            if (toolsScrim != null) rootLayout.removeView(toolsScrim);
            toolsScrim = null;
            toolsDrawer = null;
            return;
        }
        final View panel = drawer;
        panel.animate().translationX(panel.getWidth()).setDuration(180)
                .withEndAction(() -> rootLayout.removeView(panel)).start();
        if (toolsScrim != null) {
            View scrim = toolsScrim;
            scrim.animate().alpha(0.0f).setDuration(160)
                    .withEndAction(() -> rootLayout.removeView(scrim)).start();
        }
        toolsScrim = null;
        toolsDrawer = null;
    }

    private void addDrawerHeader(String title, String subtitle) {
        TextView heading = new TextView(this);
        heading.setText(title);
        heading.setTextColor(0xFFFFA52B);
        heading.setTextSize(18);
        heading.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        heading.setPadding(0, 0, 0, dp(4));
        toolsDrawer.addView(heading);

        TextView sub = new TextView(this);
        sub.setText(subtitle);
        sub.setTextColor(0xFF9BA7B5);
        sub.setTextSize(10);
        sub.setPadding(0, 0, 0, dp(16));
        toolsDrawer.addView(sub);
    }

    private void addDrawerSection(String name) {
        TextView section = new TextView(this);
        section.setText(name);
        section.setTextColor(0xFFFFB45C);
        section.setTextSize(10);
        section.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        section.setPadding(0, dp(15), 0, dp(6));
        toolsDrawer.addView(section);
    }

    private void addDrawerItem(String title, String subtitle, Runnable action) {
        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setPadding(dp(11), dp(9), dp(11), dp(9));
        GradientDrawable background = new GradientDrawable();
        background.setColor(0xFF303844);
        background.setCornerRadius(dp(7));
        item.setBackground(background);

        TextView name = new TextView(this);
        name.setText(title);
        name.setTextColor(Color.WHITE);
        name.setTextSize(13);
        name.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        item.addView(name);

        TextView description = new TextView(this);
        description.setText(subtitle);
        description.setTextColor(0xFFB3BDC9);
        description.setTextSize(10);
        item.addView(description);

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        params.setMargins(0, dp(3), 0, dp(3));
        toolsDrawer.addView(item, params);
        item.setOnClickListener(view -> action.run());
    }

    private void showFoliagePainter() {
        closeToolsDrawer();

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(18), dp(8), dp(18), dp(4));

        TextView info = new TextView(this);
        info.setText("Paint a bounded group of low-poly trees or bushes around a world X/Z position. Each tree uses a trunk and canopy. Foliage is part of the scene and supports Undo.");
        info.setTextColor(Color.DKGRAY);
        info.setTextSize(12);
        content.addView(info);

        String[] labels = {"Brush center X", "Brush center Z", "Brush radius", "Plant count"};
        String[] defaults = {"0", "-4", "8", "18"};
        EditText[] fields = new EditText[4];
        for (int i = 0; i < fields.length; i++) {
            TextView label = new TextView(this);
            label.setText(labels[i]);
            label.setTextColor(Color.DKGRAY);
            label.setTextSize(11);
            content.addView(label);

            EditText field = new EditText(this);
            field.setSingleLine(true);
            field.setInputType(android.text.InputType.TYPE_CLASS_NUMBER
                    | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
                    | android.text.InputType.TYPE_NUMBER_FLAG_SIGNED);
            field.setText(defaults[i]);
            content.addView(field);
            fields[i] = field;
        }

        new AlertDialog.Builder(this)
                .setTitle("Foliage Painter")
                .setView(content)
                .setPositiveButton("Paint Trees", (dialog, which) ->
                        applyFoliageBrush(fields, 0))
                .setNeutralButton("Paint Bushes", (dialog, which) ->
                        applyFoliageBrush(fields, 1))
                .setNegativeButton("Clear Foliage", (dialog, which) -> {
                    int removed = engineSurface.clearFoliage();
                    android.widget.Toast.makeText(this,
                            removed > 0 ? "Removed " + removed + " foliage objects" : "No foliage to clear",
                            android.widget.Toast.LENGTH_SHORT).show();
                })
                .show();
    }

    private float foliageValue(EditText field, float fallback) {
        try {
            return Float.parseFloat(field.getText().toString().trim());
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private void applyFoliageBrush(EditText[] fields, int kind) {
        float x = foliageValue(fields[0], 0.0f);
        float z = foliageValue(fields[1], -4.0f);
        float radius = foliageValue(fields[2], 8.0f);
        int count = Math.round(foliageValue(fields[3], 18.0f));
        int planted = engineSurface.paintFoliage(x, z, radius, count, kind);
        android.widget.Toast.makeText(this,
                planted > 0 ? "Painted " + planted + (kind == 0 ? " trees" : " bushes")
                        : "No foliage placed (mobile scene limit reached)",
                android.widget.Toast.LENGTH_SHORT).show();
    }

    private void showTerrainSculptor() {
        closeToolsDrawer();

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(18), dp(8), dp(18), dp(4));

        TextView info = new TextView(this);
        info.setText("Create an editable 11 x 11 terrain patch, then sculpt around a world X/Z position. The patch is centered near the arena.");
        info.setTextColor(Color.DKGRAY);
        info.setTextSize(12);
        content.addView(info);

        String[] labels = {"Brush center X", "Brush center Z", "Brush radius", "Brush strength"};
        EditText[] fields = new EditText[4];
        String[] defaults = {"0", "-4", "3.0", "0.45"};
        for (int i = 0; i < fields.length; i++) {
            TextView label = new TextView(this);
            label.setText(labels[i]);
            label.setTextColor(Color.DKGRAY);
            label.setTextSize(11);
            content.addView(label);

            EditText field = new EditText(this);
            field.setSingleLine(true);
            field.setInputType(android.text.InputType.TYPE_CLASS_NUMBER
                    | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
                    | android.text.InputType.TYPE_NUMBER_FLAG_SIGNED);
            field.setText(defaults[i]);
            content.addView(field);
            fields[i] = field;
        }

        new AlertDialog.Builder(this)
                .setTitle("Terrain Sculpting")
                .setView(content)
                .setNeutralButton("Generate", (dialog, which) -> {
                    boolean ok = engineSurface.createTerrain();
                    android.widget.Toast.makeText(this,
                            ok ? "Terrain generated" : "Terrain generation failed",
                            android.widget.Toast.LENGTH_SHORT).show();
                })
                .setPositiveButton("Raise", (dialog, which) ->
                        applyTerrainBrush(fields, 0))
                .setNegativeButton("More Tools", (dialog, which) ->
                        showTerrainModes(fields))
                .show();
    }

    private float terrainValue(EditText field, float fallback) {
        try {
            return Float.parseFloat(field.getText().toString().trim());
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private void applyTerrainBrush(EditText[] fields, int mode) {
        float x = terrainValue(fields[0], 0.0f);
        float z = terrainValue(fields[1], -4.0f);
        float radius = terrainValue(fields[2], 3.0f);
        float strength = terrainValue(fields[3], 0.45f);
        boolean changed = engineSurface.sculptTerrain(x, z, radius, strength, mode);
        android.widget.Toast.makeText(this,
                changed ? "Terrain sculpted" : "No terrain found or no change",
                android.widget.Toast.LENGTH_SHORT).show();
    }

    private void showTerrainModes(EditText[] fields) {
        new AlertDialog.Builder(this)
                .setTitle("Terrain Brush")
                .setItems(new String[]{"Lower", "Smooth", "Flatten"},
                        (dialog, which) -> applyTerrainBrush(fields, which + 1))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void openAIAssistant() {\n        closeToolsDrawer();\n        new AIEditorAssistant(this, engineSurface).show();\n    }\n\n    private void showToolStatus(String toolName) {
        closeToolsDrawer();
        new AlertDialog.Builder(this)
                .setTitle(toolName)
                .setMessage("This system is listed in the Tools drawer, but its engine implementation is not complete yet. It will be added and build-tested in a separate stage.")
                .setPositiveButton("OK", null)
                .show();
    }

    private java.io.File sceneFile() {
        return new java.io.File(getFilesDir(), "NovaingineScene.nova");
    }

    private void saveCurrentScene() {
        boolean saved = engineSurface.saveScene(sceneFile().getAbsolutePath());
        closeToolsDrawer();
        android.widget.Toast.makeText(this,
                saved ? "Scene saved" : "Scene save failed",
                android.widget.Toast.LENGTH_SHORT).show();
    }

    private void loadCurrentScene() {
        boolean loaded = engineSurface.loadScene(sceneFile().getAbsolutePath());
        closeToolsDrawer();
        android.widget.Toast.makeText(this,
                loaded ? "Scene loaded" : "No saved scene found / load failed",
                android.widget.Toast.LENGTH_SHORT).show();
    }

    private void showWorldOutliner() {
        String[] objects = engineSurface.getSceneObjects();
        if (objects == null || objects.length == 0) {
            new AlertDialog.Builder(this)
                    .setTitle("World Outliner")
                    .setMessage("The scene contains no objects.")
                    .setPositiveButton("Close", null)
                    .show();
            return;
        }

        String[] labels = new String[objects.length];
        int[] ids = new int[objects.length];
        for (int i = 0; i < objects.length; i++) {
            String row = objects[i] == null ? "" : objects[i];
            int split = row.indexOf('|');
            try {
                ids[i] = Integer.parseInt(split < 0 ? row : row.substring(0, split));
            } catch (NumberFormatException ignored) {
                ids[i] = 0;
            }
            labels[i] = split < 0 ? row : row.substring(split + 1);
        }

        new AlertDialog.Builder(this)
                .setTitle("World Outliner  •  " + objects.length + " objects")
                .setItems(labels, (dialog, which) -> {
                    if (ids[which] != 0) {
                        engineSurface.selectObjectById(ids[which]);
                    }
                })
                .setNegativeButton("Close", null)
                .show();
    }

    private void showDetailsInspector() {
        float[] values = engineSurface.getSelectedTransform();
        if (values == null || values.length < 9) return;

        String[] names = {
                "Position X", "Position Y", "Position Z",
                "Rotation X", "Rotation Y", "Rotation Z",
                "Scale X", "Scale Y", "Scale Z"
        };

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(18), dp(8), dp(18), dp(4));

        EditText[] fields = new EditText[9];
        for (int i = 0; i < fields.length; i++) {
            TextView label = new TextView(this);
            label.setText(names[i]);
            label.setTextColor(Color.DKGRAY);
            label.setTextSize(11);
            content.addView(label);

            EditText field = new EditText(this);
            field.setSingleLine(true);
            field.setInputType(android.text.InputType.TYPE_CLASS_NUMBER
                    | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
                    | android.text.InputType.TYPE_NUMBER_FLAG_SIGNED);
            field.setText(String.format(java.util.Locale.US, "%.3f", values[i]));
            content.addView(field);
            fields[i] = field;
        }

        new AlertDialog.Builder(this)
                .setTitle("Details Inspector")
                .setView(content)
                .setPositiveButton("Apply", (dialog, which) -> {
                    float[] updated = values.clone();
                    for (int i = 0; i < fields.length; i++) {
                        try {
                            updated[i] = Float.parseFloat(
                                    fields[i].getText().toString().trim()
                            );
                        } catch (NumberFormatException ignored) {
                            updated[i] = values[i];
                        }
                    }
                    engineSurface.setSelectedTransform(updated);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    @Override
    public void onToggleMaximize() {

        viewportMaximized = !viewportMaximized;

        if (viewportMaximized) {

            editorToolbar.setVisibility(View.GONE);
            viewportInput.setVisibility(View.GONE);
            statisticsText.setVisibility(View.GONE);

        } else {

            editorToolbar.setVisibility(View.VISIBLE);
            viewportInput.setVisibility(View.VISIBLE);
            statisticsText.setVisibility(View.VISIBLE);
        }
    }

    private int dp(float value) {

        return (int) (
                value * getResources()
                        .getDisplayMetrics()
                        .density + 0.5f
        );
    }

    @Override
    protected void onResume() {

        super.onResume();

        if (engineSurface != null) {
            engineSurface.onResume();
        }
    }

    @Override
    protected void onPause() {

        if (engineSurface != null) {
            engineSurface.onPause();
        }

        super.onPause();
    }

    @Override
    protected void onDestroy() {

        cameraControls = null;
        viewportInput = null;
        editorToolbar = null;
        engineSurface = null;
        rootLayout = null;

        super.onDestroy();
    }
}
