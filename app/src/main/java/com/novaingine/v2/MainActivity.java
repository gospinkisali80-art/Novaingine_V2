package com.novaingine.v2;

import com.google.firebase.auth.FirebaseAuth;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.pm.ActivityInfo;
import android.content.Intent;
import android.net.Uri;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.ScrollView;
import android.widget.HorizontalScrollView;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;

public final class MainActivity extends Activity
        implements EditorToolbar.ViewportActions {

    private FrameLayout rootLayout;

    private EngineSurface engineSurface;
    private ViewportInput viewportInput;
    private EditorToolbar editorToolbar;
    private CameraControls cameraControls;

    private TextView statisticsText;
    private TextView centeredStatisticsText;
    private boolean centeredStatisticsVisible = true;
    private LinearLayout editorPanels;
    private TextView playStopButton;
    private boolean playMode;

    private boolean viewportMaximized;
    private View toolsScrim;
    private LinearLayout toolsDrawer;
    private boolean toolsDrawerOpen;
    private boolean drawerPhysicsEnabled;
    private boolean drawerWorldSpace = true;
    private boolean drawerSnapEnabled = true;
    private float drawerGridSnap = 10.0f;
    private float drawerRotationSnap = 10.0f;
    private float drawerScaleSnap = 0.25f;
    private float drawerCameraSpeed = 3.07f;
    private ManualSculptWorkspace manualSculptWorkspace;
    private ControllerAgent controllerAgent;
    private View projectStartPage;
    private final Handler projectHandler = new Handler(Looper.getMainLooper());
    private File activeProjectDirectory;
    private File activeProjectScene;
    private final Runnable autoSaveTask = new Runnable() {
        @Override public void run() {
            if (activeProjectScene == null || engineSurface == null) return;
            engineSurface.saveSceneAsync(activeProjectScene.getAbsolutePath(), saved -> {
                if (statisticsText != null) statisticsText.setText(saved
                        ? "NOVAINGINE V2  |  AUTO-SAVED" : "NOVAINGINE V2  |  SAVE RETRY");
            });
            projectHandler.postDelayed(this, 5000L);
        }
    };

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
        showProjectBrowser();
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
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        Gravity.BOTTOM
                );

        rootLayout.addView(
                viewportInput,
                inputParams
        );
        // Keep the existing MOVE/LOOK controls available for viewport navigation.
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

        statisticsText.setOnClickListener(view -> showControllerPairing());
        statisticsText.setOnLongClickListener(view -> { showControllerPairing(); return true; });

        FrameLayout.LayoutParams statsParams =
                new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        Gravity.TOP | Gravity.START
                );

        statsParams.setMargins(
                dp(8),
                dp(55),
                0,
                0
        );

        rootLayout.addView(
                statisticsText,
                statsParams
        );

        centeredStatisticsText = new TextView(this);
        centeredStatisticsText.setTextColor(Color.WHITE);
        centeredStatisticsText.setTextSize(11);
        centeredStatisticsText.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        centeredStatisticsText.setPadding(dp(10), dp(6), dp(10), dp(6));
        centeredStatisticsText.setBackground(new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0xDD172433, 0xDD26394B}
        ));
        centeredStatisticsText.setText("NOVAINGINE V2  |  VIEWPORT");
        centeredStatisticsText.setGravity(Gravity.CENTER);
        centeredStatisticsText.setOnClickListener(view -> showControllerPairing());
        FrameLayout.LayoutParams centeredParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL
        );
        centeredParams.setMargins(dp(8), dp(55), dp(8), 0);
        rootLayout.addView(centeredStatisticsText, centeredParams);
    }

    private void updateViewportStatus(String status) {
        if (statisticsText != null) statisticsText.setText(status);
        if (centeredStatisticsText != null) centeredStatisticsText.setText(status);
    }

    private void createEditorPanels() {
        editorPanels = new LinearLayout(this);
        editorPanels.setOrientation(LinearLayout.HORIZONTAL);
        editorPanels.setPadding(dp(5), dp(4), dp(5), dp(4));
        editorPanels.setBackgroundColor(0xDD202020);

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
        editorPanels.addView(outliner, itemParams);
        editorPanels.addView(details, itemParams);
        editorPanels.addView(undo, itemParams);
        editorPanels.addView(redo, itemParams);

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.END
        );
        params.setMargins(0, dp(55), dp(8), 0);
        rootLayout.addView(editorPanels, params);
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

        addDrawerSection("VIEWPORT CONTROLS");
        addDrawerItem("Camera View", "Perspective, top, bottom, front, back, left or right", this::showDrawerCameraViews);
        addDrawerItem("Rendering Mode", "Lit, unlit, wireframe, normals, roughness and metallic", this::showDrawerRenderModes);
        addDrawerItem("Show / Hide", "Grid, shadows, sky, characters and statistics", this::showDrawerVisibility);
        addDrawerItem("Centered Viewport Label", "Show or hide the NOVAINGINE V2 status badge at the top center", () -> {
            centeredStatisticsVisible = !centeredStatisticsVisible;
            if (centeredStatisticsText != null) {
                centeredStatisticsText.setVisibility(centeredStatisticsVisible ? View.VISIBLE : View.GONE);
            }
            closeToolsDrawer();
        });
        addDrawerItem("Transform Tool", "Select, move, rotate or scale", this::showDrawerTransformModes);
        addDrawerItem("Coordinate Space: " + (drawerWorldSpace ? "WORLD" : "LOCAL"), "Switch transform orientation", () -> {
            drawerWorldSpace = !drawerWorldSpace;
            engineSurface.setWorldSpace(drawerWorldSpace);
            closeToolsDrawer();
        });
        addDrawerItem("Snapping: " + (drawerSnapEnabled ? "ON" : "OFF"), "Toggle snapping; configure grid, rotation and scale increments", this::showDrawerSnapSettings);
        addDrawerItem("Camera Speed", "Adjust viewport navigation speed", this::showDrawerCameraSpeed);
        addDrawerSection("SCENE");
        addDrawerItem("World Outliner", "Browse and select scene objects", this::showWorldOutliner);
        addDrawerItem("Details Inspector", "Edit position, rotation and scale", this::showDetailsInspector);
        addDrawerItem("Save Scene", "Save the current .nova scene", this::saveCurrentScene);
        addDrawerItem("Load Scene", "Load the saved .nova scene", this::loadCurrentScene);
        addDrawerItem("Undo", "Undo the latest supported scene edit", () -> engineSurface.undoScene());
        addDrawerItem("Redo", "Redo the last undone scene edit", () -> engineSurface.redoScene());

        addDrawerSection("WORLD BUILDING");
        addDrawerItem("Terrain Sculpting", "Sculpt the generated terrain surface", this::showTerrainSculptor);
        addDrawerItem("Meshy3D Editor", "Open the docked 3D workspace with viewport, Outliner, object properties, tools and timeline", this::showMeshSculptor);
        addDrawerItem("Foliage Painting", "Paint trees and bushes into the scene", this::showFoliagePainter);
        addDrawerItem("Import Blender Model (.glb)", "Import a Blender-exported glTF binary model", this::openGlbPicker);

        addDrawerSection("RENDERING");
        addDrawerItem("Global Illumination", "Indirect lighting controls", () -> showToolStatus("Global Illumination"));
        addDrawerItem("Reflections", "Environment and surface reflections", () -> showToolStatus("Reflections"));

        addDrawerSection("GAME SYSTEMS");
        addDrawerItem("Animation", "Keyframes and playback", () -> showToolStatus("Animation"));
        addDrawerItem("Audio", "Sound sources and audio controls", () -> showToolStatus("Audio"));
        addDrawerItem("AI Assistant", "Groq-hosted Qwen cloud scene-building assistant", this::openAIAssistant);
        addDrawerItem("Visual Scripting", "Create, connect, edit and run gameplay nodes", this::showVisualScriptingEditor);

        addDrawerSection("PROJECT");
        addDrawerItem("Project Home", "Save and return to the project browser", this::returnToProjectBrowser);

        addDrawerSection("ACCOUNT");
        addDrawerItem("Sign Out", "Sign out of your Novaingine account", () -> {
            closeToolsDrawer();
            FirebaseAuth.getInstance().signOut();
            Intent login = new Intent(this, AuthActivity.class);
            login.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(login);
            finish();
        });

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


    private void showDrawerCameraViews() {
        final String[] names = {"Perspective", "Top", "Bottom", "Left", "Right", "Front", "Back"};
        new android.app.AlertDialog.Builder(this)
                .setTitle("Camera View")
                .setItems(names, (dialog, which) -> {
                    engineSurface.setCameraView(which);
                    closeToolsDrawer();
                }).setNegativeButton("Cancel", null).show();
    }

    private void showDrawerRenderModes() {
        final String[] names = {"Lit", "Unlit", "Wireframe", "Detail Lighting",
                "Lighting Only", "Normals", "Roughness", "Metallic"};
        new android.app.AlertDialog.Builder(this)
                .setTitle("Rendering Mode")
                .setItems(names, (dialog, which) -> {
                    engineSurface.setViewMode(which);
                    closeToolsDrawer();
                }).setNegativeButton("Cancel", null).show();
    }

    private void showDrawerVisibility() {
        final String[] names = {"Sculpt", "Show All", "Hide Grid", "Hide Shadows",
                "Hide Sky", "Hide Characters", "Show Statistics"};
        final int[] flags = {63, 62, 61, 59, 55, 16};
        new android.app.AlertDialog.Builder(this)
                .setTitle("Show / Hide")
                .setItems(names, (dialog, which) -> {
                    if (which == 0) {
                        // Route every editor Sculpt entry point to the current
                        // ManualSculptWorkspace. The legacy nativeEnterSculptMode()
                        // clears the scene and bypasses the brush touch workspace.
                        closeToolsDrawer();
                        openManualSculpting();
                        return;
                    }
                    engineSurface.setShowFlags(flags[which - 1]);
                    closeToolsDrawer();
                }).setNegativeButton("Cancel", null).show();
    }

    private void showDrawerTransformModes() {
        final String[] names = {"Select", "Move", "Rotate", "Scale"};
        new android.app.AlertDialog.Builder(this)
                .setTitle("Transform Tool")
                .setItems(names, (dialog, which) -> {
                    engineSurface.setTransformMode(which);
                    closeToolsDrawer();
                }).setNegativeButton("Cancel", null).show();
    }

    private void showDrawerSnapSettings() {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(18), dp(8), dp(18), dp(4));
        CheckBox enabled = new CheckBox(this);
        enabled.setText("Enable snapping");
        enabled.setChecked(drawerSnapEnabled);
        content.addView(enabled);
        final String[] gridNames = {"1", "5", "10", "25", "50", "100"};
        final float[] gridValues = {1, 5, 10, 25, 50, 100};
        final String[] rotationNames = {"1°", "5°", "10°", "15°", "30°", "45°", "90°"};
        final float[] rotationValues = {1, 5, 10, 15, 30, 45, 90};
        final String[] scaleNames = {"0.1", "0.25", "0.5", "1.0"};
        final float[] scaleValues = {0.1f, 0.25f, 0.5f, 1.0f};
        Spinner grid = new Spinner(this);
        grid.setAdapter(new ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, gridNames));
        grid.setSelection(indexOfFloat(gridValues, drawerGridSnap));
        content.addView(drawerLabel("Grid increment"));
        content.addView(grid);
        Spinner rotation = new Spinner(this);
        rotation.setAdapter(new ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, rotationNames));
        rotation.setSelection(indexOfFloat(rotationValues, drawerRotationSnap));
        content.addView(drawerLabel("Rotation increment"));
        content.addView(rotation);
        Spinner scale = new Spinner(this);
        scale.setAdapter(new ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, scaleNames));
        scale.setSelection(indexOfFloat(scaleValues, drawerScaleSnap));
        content.addView(drawerLabel("Scale increment"));
        content.addView(scale);
        new android.app.AlertDialog.Builder(this)
                .setTitle("Snapping Settings")
                .setView(content)
                .setPositiveButton("Apply", (dialog, which) -> {
                    drawerSnapEnabled = enabled.isChecked();
                    drawerGridSnap = gridValues[grid.getSelectedItemPosition()];
                    drawerRotationSnap = rotationValues[rotation.getSelectedItemPosition()];
                    drawerScaleSnap = scaleValues[scale.getSelectedItemPosition()];
                    engineSurface.setSnap(drawerSnapEnabled, drawerGridSnap,
                            drawerRotationSnap, drawerScaleSnap);
                    closeToolsDrawer();
                }).setNegativeButton("Cancel", null).show();
    }

    private int indexOfFloat(float[] values, float target) {
        for (int i = 0; i < values.length; i++) if (Math.abs(values[i] - target) < 0.001f) return i;
        return 0;
    }

    private TextView drawerLabel(String value) {
        TextView label = new TextView(this);
        label.setText(value);
        label.setTextColor(Color.WHITE);
        label.setTextSize(12);
        label.setPadding(0, dp(8), 0, dp(2));
        return label;
    }

    private void showDrawerCameraSpeed() {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(12), dp(20), dp(8));
        TextView value = drawerLabel(String.format(java.util.Locale.US, "Speed: %.1f", drawerCameraSpeed));
        content.addView(value);
        android.widget.SeekBar speed = new android.widget.SeekBar(this);
        speed.setMax(100);
        speed.setProgress(Math.max(0, Math.min(100, Math.round((drawerCameraSpeed - 0.1f) / 9.9f * 100))));
        content.addView(speed);
        speed.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(android.widget.SeekBar bar, int progress, boolean fromUser) {
                drawerCameraSpeed = 0.1f + (progress / 100.0f) * 9.9f;
                value.setText(String.format(java.util.Locale.US, "Speed: %.1f", drawerCameraSpeed));
                if (fromUser) engineSurface.setCameraSpeed(drawerCameraSpeed);
            }
            @Override public void onStartTrackingTouch(android.widget.SeekBar bar) {}
            @Override public void onStopTrackingTouch(android.widget.SeekBar bar) {}
        });
        new android.app.AlertDialog.Builder(this)
                .setTitle("Camera Speed")
                .setView(content)
                .setPositiveButton("Done", (dialog, which) -> closeToolsDrawer())
                .show();
    }

    private static final int REQUEST_IMPORT_GLB = 7412;

    public void openSculptAssetPicker() {
        openGlbPicker();
    }

    private void openGlbPicker() {
        closeToolsDrawer();
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"model/gltf-binary", "application/octet-stream"});
        startActivityForResult(intent, REQUEST_IMPORT_GLB);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_IMPORT_GLB || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        new Thread(() -> {
            File modelFile = new File(getCacheDir(), "nova_import_" + System.currentTimeMillis() + ".glb");
            try (InputStream input = getContentResolver().openInputStream(uri);
                 FileOutputStream output = new FileOutputStream(modelFile)) {
                if (input == null) throw new IllegalStateException("Could not open selected file.");
                byte[] buffer = new byte[8192];
                int read;
                long total = 0;
                while ((read = input.read(buffer)) != -1) {
                    total += read;
                    if (total > 100L * 1024L * 1024L) throw new IllegalArgumentException("Model is larger than 100 MB.");
                    output.write(buffer, 0, read);
                }
                runOnUiThread(() -> {
                    android.widget.Toast.makeText(this, "Importing Blender model…", android.widget.Toast.LENGTH_SHORT).show();
                    engineSurface.importGlb(modelFile.getAbsolutePath(), count -> {
                        String message = count > 0
                                ? "Imported " + count + " mesh part(s) from Blender."
                                : "Model import failed. Check that this is a valid .glb with triangle meshes.";
                        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_LONG).show();
                        if (count <= 0) modelFile.delete();
                    });
                });
            } catch (Exception error) {
                modelFile.delete();
                runOnUiThread(() -> android.widget.Toast.makeText(this,
                        "Model import error: " + error.getMessage(), android.widget.Toast.LENGTH_LONG).show());
            }
        }, "Nova-GLB-Copy").start();
    }

    private void showVisualScriptingEditor() {
        closeToolsDrawer();
        VisualScriptEditorView graph = new VisualScriptEditorView(this);
        LinearLayout content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(8), dp(8), dp(8), dp(8));
        TextView help = new TextView(this); help.setText("Drag nodes to arrange the graph. Add nodes, then tap AUTO CONNECT to wire them in order. Branch connects to the next two nodes (True / False). Edit the selected node before running."); help.setTextColor(0xFF344050); help.setTextSize(11); content.addView(help);
        HorizontalScrollView tools = new HorizontalScrollView(this); tools.setHorizontalScrollBarEnabled(false);
        LinearLayout buttons = new LinearLayout(this); buttons.setOrientation(LinearLayout.HORIZONTAL);
        tools.addView(buttons); content.addView(tools);
        java.util.function.BiConsumer<String, Runnable> addButton = (label, action) -> {
            TextView button = new TextView(this); button.setText(label); button.setTextColor(Color.WHITE); button.setTextSize(10); button.setPadding(dp(10), dp(9), dp(10), dp(9)); button.setBackgroundColor(0xFF354354);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT); p.setMargins(dp(2), dp(4), dp(2), dp(4)); buttons.addView(button, p); button.setOnClickListener(v -> action.run());
        };
        addButton.accept("+ BEGIN", () -> graph.addNode(VisualScriptEditorView.BEGIN_PLAY));
        addButton.accept("+ TICK", () -> graph.addNode(VisualScriptEditorView.TICK));
        addButton.accept("+ MOVE", () -> graph.addNode(VisualScriptEditorView.MOVE));
        addButton.accept("+ ROTATE", () -> graph.addNode(VisualScriptEditorView.ROTATE));
        addButton.accept("+ SCALE", () -> graph.addNode(VisualScriptEditorView.SCALE));
        addButton.accept("+ VISIBILITY", () -> graph.addNode(VisualScriptEditorView.VISIBILITY));
        addButton.accept("+ BRANCH", () -> graph.addNode(VisualScriptEditorView.BRANCH));
        addButton.accept("+ END", () -> graph.addNode(VisualScriptEditorView.END));
        addButton.accept("SAVE JSON", () -> {
            try {
                File file = new File(getFilesDir(), "NovaingineVisualScript.json");
                try (FileOutputStream output = new FileOutputStream(file)) {
                    output.write(graph.toJson().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }
                android.widget.Toast.makeText(this, "Graph saved: " + file.getAbsolutePath(), android.widget.Toast.LENGTH_LONG).show();
            } catch (Exception error) {
                android.widget.Toast.makeText(this, "Graph save failed: " + error.getMessage(), android.widget.Toast.LENGTH_LONG).show();
            }
        });
        addButton.accept("LOAD JSON", () -> {
            try {
                File file = new File(getFilesDir(), "NovaingineVisualScript.json");
                if (!file.exists()) {
                    android.widget.Toast.makeText(this, "No saved visual graph yet", android.widget.Toast.LENGTH_SHORT).show();
                    return;
                }
                byte[] bytes = new byte[(int)Math.min(file.length(), 256 * 1024)];
                try (java.io.FileInputStream input = new java.io.FileInputStream(file)) {
                    int count = input.read(bytes);
                    String json = new String(bytes, 0, Math.max(0, count), java.nio.charset.StandardCharsets.UTF_8);
                    android.widget.Toast.makeText(this, graph.loadJson(json) ? "Graph loaded" : "Invalid graph JSON", android.widget.Toast.LENGTH_SHORT).show();
                }
            } catch (Exception error) {
                android.widget.Toast.makeText(this, "Graph load failed: " + error.getMessage(), android.widget.Toast.LENGTH_LONG).show();
            }
        });
        HorizontalScrollView graphScroll = new HorizontalScrollView(this);
        graphScroll.setFillViewport(false);
        ScrollView vertical = new ScrollView(this);
        vertical.addView(graph, new ScrollView.LayoutParams(dp(760), dp(570)));
        graphScroll.addView(vertical);
        LinearLayout.LayoutParams graphParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(570));
        content.addView(graphScroll, graphParams);
        LinearLayout actions = new LinearLayout(this); actions.setOrientation(LinearLayout.HORIZONTAL);
        TextView connect = new TextView(this); connect.setText("AUTO CONNECT"); connect.setTextColor(Color.WHITE); connect.setPadding(dp(10), dp(10), dp(10), dp(10)); connect.setBackgroundColor(0xFF3A725F);
        TextView edit = new TextView(this); edit.setText("EDIT SELECTED"); edit.setTextColor(Color.WHITE); edit.setPadding(dp(10), dp(10), dp(10), dp(10)); edit.setBackgroundColor(0xFF405A79);
        TextView run = new TextView(this); run.setText("▶ RUN GRAPH"); run.setTextColor(Color.WHITE); run.setPadding(dp(10), dp(10), dp(10), dp(10)); run.setBackgroundColor(0xFFB66C21);
        TextView stop = new TextView(this); stop.setText("STOP"); stop.setTextColor(Color.WHITE); stop.setPadding(dp(10), dp(10), dp(10), dp(10)); stop.setBackgroundColor(0xFF8A3C3C);
        actions.addView(connect); actions.addView(edit); actions.addView(run); actions.addView(stop); content.addView(actions);
        connect.setOnClickListener(v -> graph.autoConnect());
        edit.setOnClickListener(v -> editVisualNode(graph));
        run.setOnClickListener(v -> engineSurface.setVisualScript(graph.buildNodeData(), graph.buildNodeValues(), true, ok -> android.widget.Toast.makeText(this, ok ? "Visual graph sent to engine. Begin Play runs once; Tick runs every frame." : "Graph could not be compiled. Check node links and node count.", android.widget.Toast.LENGTH_LONG).show()));
        stop.setOnClickListener(v -> engineSurface.setVisualScript(null, null, false, ok -> android.widget.Toast.makeText(this, "Visual script stopped.", android.widget.Toast.LENGTH_SHORT).show()));
        new AlertDialog.Builder(this).setTitle("NOVA BLUEPRINT GRAPH").setView(content).setNegativeButton("Close", null).show();
    }

    private void editVisualNode(VisualScriptEditorView graph) {
        VisualScriptEditorView.Node node = graph.getSelectedNode();
        if (node == null) return;
        LinearLayout form = new LinearLayout(this); form.setOrientation(LinearLayout.VERTICAL); form.setPadding(dp(16), dp(8), dp(16), dp(4));
        String[] labels = {"Target object ID (0 = selected)", "Vector X", "Vector Y", "Vector Z", "Branch value", "Branch compare value", "Condition: 1 = true, 0 = false"};
        String[] defaults = {String.valueOf(node.objectId), String.valueOf(node.vx), String.valueOf(node.vy), String.valueOf(node.vz), String.valueOf(node.value), String.valueOf(node.compareValue), node.condition ? "1" : "0"};
        EditText[] fields = new EditText[7];
        for (int i = 0; i < labels.length; i++) { TextView label = new TextView(this); label.setText(labels[i]); label.setTextColor(Color.DKGRAY); form.addView(label); EditText field = new EditText(this); field.setSingleLine(true); field.setText(defaults[i]); field.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL | android.text.InputType.TYPE_NUMBER_FLAG_SIGNED); form.addView(field); fields[i] = field; }
        new AlertDialog.Builder(this).setTitle("Edit Node " + node.id).setView(form).setPositiveButton("Apply", (d, w) -> {
            try { node.objectId = Math.max(0, Integer.parseInt(fields[0].getText().toString())); } catch (Exception ignored) {}
            try { node.vx = Float.parseFloat(fields[1].getText().toString()); } catch (Exception ignored) {}
            try { node.vy = Float.parseFloat(fields[2].getText().toString()); } catch (Exception ignored) {}
            try { node.vz = Float.parseFloat(fields[3].getText().toString()); } catch (Exception ignored) {}
            try { node.value = Float.parseFloat(fields[4].getText().toString()); } catch (Exception ignored) {}
            try { node.compareValue = Float.parseFloat(fields[5].getText().toString()); } catch (Exception ignored) {}
            try { node.condition = Float.parseFloat(fields[6].getText().toString()) >= 0.5f; } catch (Exception ignored) {}
            graph.invalidate();
        }).setNegativeButton("Cancel", null).show();
    }
    private void showMeshSculptor() {
        closeToolsDrawer();
        openManualSculpting();
    }

    private void openManualSculpting() {
        if (manualSculptWorkspace != null) return;
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        if (editorToolbar != null) editorToolbar.setVisibility(View.GONE);
        if (viewportInput != null) viewportInput.setVisibility(View.GONE);
        if (statisticsText != null) statisticsText.setVisibility(View.GONE);
        if (editorPanels != null) editorPanels.setVisibility(View.GONE);

        engineSurface.setManualSculptWorkspace(true);
        manualSculptWorkspace = new ManualSculptWorkspace(
                this, engineSurface, this::closeManualSculpting);
        rootLayout.addView(manualSculptWorkspace, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
    }

    private void closeManualSculpting() {
        if (manualSculptWorkspace == null) return;
        rootLayout.removeView(manualSculptWorkspace);
        manualSculptWorkspace = null;
        engineSurface.setSculptOptions(false, 0, 0.18f, 0.12f, 0.5f, 0, false, 1.0f);
        engineSurface.setManualSculptWorkspace(false);
        if (editorToolbar != null) editorToolbar.setVisibility(View.VISIBLE);
        if (viewportInput != null) viewportInput.setVisibility(View.VISIBLE);
        if (statisticsText != null) statisticsText.setVisibility(View.VISIBLE);
        if (editorPanels != null) editorPanels.setVisibility(View.VISIBLE);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
    }

    @Override
    public void onBackPressed() {
        if (manualSculptWorkspace != null) {
            closeManualSculpting();
            return;
        }
        super.onBackPressed();
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

    private void openAIAssistant() {
        closeToolsDrawer();
        new AIEditorAssistant(this, engineSurface).show();
    }

    private void showToolStatus(String toolName) {
        closeToolsDrawer();
        new AlertDialog.Builder(this)
                .setTitle(toolName)
                .setMessage("This system is listed in the Tools drawer, but its engine implementation is not complete yet. It will be added and build-tested in a separate stage.")
                .setPositiveButton("OK", null)
                .show();
    }


    private File projectsDirectory() {
        File dir = new File(getFilesDir(), "NovaProjects");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    private void showProjectBrowser() {
        if (projectStartPage != null) rootLayout.removeView(projectStartPage);
        FrameLayout page = new FrameLayout(this);
        page.setBackgroundColor(0xFF0B111B);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(22), dp(18), dp(22), dp(12));
        panel.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF1B3045, 0xFF0B111B, 0xFF111B27}));
        page.addView(panel, new FrameLayout.LayoutParams(-1, -1));

        TextView logo = new TextView(this);
        logo.setText("N O V A I N G I N E   V 2");
        logo.setTextColor(0xFF72BEFF);
        logo.setTextSize(13);
        logo.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        panel.addView(logo);

        TextView heading = new TextView(this);
        heading.setText("What are we creating today?");
        heading.setTextColor(Color.WHITE);
        heading.setTextSize(24);
        heading.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        heading.setPadding(0, dp(7), 0, dp(2));
        panel.addView(heading);

        TextView sub = new TextView(this);
        sub.setText("Start fresh, open a project, or continue building.");
        sub.setTextColor(0xFF9EADBF);
        sub.setTextSize(12);
        panel.addView(sub);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setPadding(0, dp(16), 0, dp(12));
        panel.addView(actions);
        addProjectCard(actions, "＋  NEW PROJECT", "Clean empty level", 0xFF24527A, this::createNewProject);
        addProjectCard(actions, "▣  OPEN PROJECT", "Browse saved projects", 0xFF303E50, this::chooseProject);

        TextView recent = new TextView(this);
        recent.setText("RECENT PROJECTS");
        recent.setTextColor(0xFF75BFFF);
        recent.setTextSize(11);
        recent.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        recent.setPadding(0, dp(5), 0, dp(7));
        panel.addView(recent);

        ScrollView scroll = new ScrollView(this);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list);
        panel.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));
        File[] dirs = projectsDirectory().listFiles();
        if (dirs != null) {
            java.util.Arrays.sort(dirs, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
            for (File dir : dirs) {
                File scene = new File(dir, "Scene.nova");
                if (!dir.isDirectory() || !scene.isFile()) continue;
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.VERTICAL);
                row.setPadding(dp(10), dp(8), dp(10), dp(8));
                row.setBackgroundColor(0xFF1D2936);
                TextView name = new TextView(this);
                name.setText("▰  " + dir.getName());
                name.setTextColor(Color.WHITE);
                name.setTextSize(13);
                name.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
                row.addView(name);
                TextView date = new TextView(this);
                date.setText("Saved project  ·  " + android.text.format.DateFormat.format("MMM d, HH:mm", dir.lastModified()));
                date.setTextColor(0xFF9EADBF);
                date.setTextSize(10);
                row.addView(date);
                LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, -2);
                rp.setMargins(0, 0, 0, dp(5));
                list.addView(row, rp);
                row.setOnClickListener(v -> openProject(dir, scene));
            }
        }
        if (list.getChildCount() == 0) {
            TextView empty = new TextView(this);
            empty.setText("No projects yet. Create your first project.");
            empty.setTextColor(0xFF8797AA);
            empty.setTextSize(12);
            empty.setPadding(dp(8), dp(12), dp(8), dp(12));
            list.addView(empty);
        }

        TextView example = new TextView(this);
        example.setText("OPEN ARENA EXAMPLE   →");
        example.setTextColor(0xFFFFC16B);
        example.setTextSize(12);
        example.setPadding(dp(12), dp(11), dp(12), dp(9));
        example.setBackgroundColor(0xFF202A36);
        panel.addView(example);
        example.setOnClickListener(v -> openArenaExample());

        TextView footer = new TextView(this);
        footer.setText("AUTO-SAVE ENABLED  ·  PROJECTS STORED ON THIS DEVICE");
        footer.setTextColor(0xFF8E9EAF);
        footer.setTextSize(9);
        footer.setPadding(0, dp(9), 0, 0);
        panel.addView(footer);
        projectStartPage = page;
        rootLayout.addView(page, new FrameLayout.LayoutParams(-1, -1));
    }

    private void addProjectCard(LinearLayout parent, String title, String detail,
                                int color, Runnable action) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(11), dp(10), dp(11), dp(10));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(dp(8));
        card.setBackground(bg);
        TextView name = new TextView(this);
        name.setText(title);
        name.setTextColor(Color.WHITE);
        name.setTextSize(12);
        name.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        card.addView(name);
        TextView detailView = new TextView(this);
        detailView.setText(detail);
        detailView.setTextColor(0xFFD0D9E3);
        detailView.setTextSize(10);
        detailView.setPadding(0, dp(4), 0, 0);
        card.addView(detailView);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -2, 1f);
        p.setMargins(0, 0, dp(8), 0);
        parent.addView(card, p);
        card.setOnClickListener(v -> action.run());
    }

    private void createNewProject() {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("My New World");
        new AlertDialog.Builder(this).setTitle("New Project").setMessage("Choose a project name")
                .setView(input).setPositiveButton("CREATE", (d, w) -> {
                    String name = input.getText().toString().trim().replaceAll("[^A-Za-z0-9 _-]", "");
                    if (name.length() > 40) name = name.substring(0, 40).trim();
                    if (name.isEmpty()) name = "Untitled Project";
                    File dir = new File(projectsDirectory(), name);
                    if (dir.exists()) {
                        android.widget.Toast.makeText(this, "That project already exists.", android.widget.Toast.LENGTH_LONG).show();
                        return;
                    }
                    if (!dir.mkdirs()) {
                        android.widget.Toast.makeText(this, "Could not create project folder.", android.widget.Toast.LENGTH_LONG).show();
                        return;
                    }
                    activeProjectDirectory = dir;
                    activeProjectScene = new File(dir, "Scene.nova");
                    final String projectName = name;
                    engineSurface.createEmptyScene(activeProjectScene.getAbsolutePath(), ok -> {
                        if (ok) enterProject("Created " + projectName);
                        else android.widget.Toast.makeText(this, "Could not initialize project.", android.widget.Toast.LENGTH_LONG).show();
                    });
                }).setNegativeButton("CANCEL", null).show();
    }

    private void chooseProject() {
        File[] dirs = projectsDirectory().listFiles();
        java.util.ArrayList<File> valid = new java.util.ArrayList<>();
        if (dirs != null) for (File dir : dirs)
            if (dir.isDirectory() && new File(dir, "Scene.nova").isFile()) valid.add(dir);
        if (valid.isEmpty()) {
            new AlertDialog.Builder(this).setMessage("No saved projects found.").setPositiveButton("OK", null).show();
            return;
        }
        String[] names = new String[valid.size()];
        for (int i = 0; i < valid.size(); i++) names[i] = valid.get(i).getName();
        new AlertDialog.Builder(this).setTitle("Open Project").setItems(names, (d, which) -> {
            File dir = valid.get(which);
            openProject(dir, new File(dir, "Scene.nova"));
        }).setNegativeButton("CANCEL", null).show();
    }

    private void openProject(File dir, File scene) {
        activeProjectDirectory = dir;
        activeProjectScene = scene;
        engineSurface.loadSceneAsync(scene.getAbsolutePath(), ok -> {
            if (ok) enterProject("Opened " + dir.getName());
            else android.widget.Toast.makeText(this, "Project load failed.", android.widget.Toast.LENGTH_LONG).show();
        });
    }

    private void openArenaExample() {
        File dir = new File(projectsDirectory(), "Arena Example");
        if (!dir.exists()) dir.mkdirs();
        activeProjectDirectory = dir;
        activeProjectScene = new File(dir, "Scene.nova");
        engineSurface.resetScene();
        projectHandler.postDelayed(() -> engineSurface.saveSceneAsync(
                activeProjectScene.getAbsolutePath(), ok -> {
                    if (ok) enterProject("Arena example opened");
                    else android.widget.Toast.makeText(this, "Could not save arena example.", android.widget.Toast.LENGTH_LONG).show();
                }), 700L);
    }

    private void enterProject(String message) {
        if (projectStartPage != null) rootLayout.removeView(projectStartPage);
        projectStartPage = null;
        projectHandler.removeCallbacks(autoSaveTask);
        projectHandler.postDelayed(autoSaveTask, 5000L);
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show();
    }

    private void returnToProjectBrowser() {
        closeToolsDrawer();
        if (activeProjectScene == null) {
            showProjectBrowser();
            return;
        }
        engineSurface.saveSceneAsync(activeProjectScene.getAbsolutePath(), saved -> {
            projectHandler.removeCallbacks(autoSaveTask);
            showProjectBrowser();
        });
    }

    private java.io.File sceneFile() {
        return activeProjectScene != null ? activeProjectScene : new java.io.File(getFilesDir(), "NovaingineScene.nova");
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
    public void onTogglePlay() {
        playMode = !playMode;
        if (playMode) {
            closeToolsDrawer();
            engineSurface.setEnginePaused(false);
            engineSurface.setPhysicsEnabled(true);
            editorToolbar.setVisibility(View.GONE);
            if (editorPanels != null) editorPanels.setVisibility(View.GONE);
            updateViewportStatus("PLAY MODE  •  LIVE");
            statisticsText.setVisibility(View.VISIBLE);
            if (viewportInput != null) viewportInput.setVisibility(View.VISIBLE);
            if (playStopButton == null) {
                playStopButton = new TextView(this);
                playStopButton.setText("■  STOP");
                playStopButton.setTextColor(Color.WHITE);
                playStopButton.setTextSize(12);
                playStopButton.setTypeface(null, android.graphics.Typeface.BOLD);
                playStopButton.setGravity(Gravity.CENTER);
                playStopButton.setPadding(dp(14), dp(9), dp(14), dp(9));
                playStopButton.setBackground(new GradientDrawable(
                        GradientDrawable.Orientation.LEFT_RIGHT,
                        new int[]{0xFFE34B4B, 0xFF9E2525}));
                FrameLayout.LayoutParams stopParams = new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        Gravity.TOP | Gravity.END);
                stopParams.setMargins(0, dp(8), dp(10), 0);
                rootLayout.addView(playStopButton, stopParams);
                playStopButton.setOnClickListener(v -> onTogglePlay());
            }
            playStopButton.setVisibility(View.VISIBLE);
        } else {
            engineSurface.setEnginePaused(false);
            engineSurface.setPhysicsEnabled(false);
            editorToolbar.setVisibility(View.VISIBLE);
            if (editorPanels != null) editorPanels.setVisibility(View.VISIBLE);
            if (viewportInput != null) viewportInput.setVisibility(View.VISIBLE);
            updateViewportStatus("NOVAINGINE V2  |  VIEWPORT");
            if (playStopButton != null) playStopButton.setVisibility(View.GONE);
        }
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
            startControllerAgent();
        }
    }

    private void startControllerAgent() {
        if (!ControllerAgent.isConfigured(this) || engineSurface == null) return;
        if (controllerAgent != null) controllerAgent.close();
        controllerAgent = new ControllerAgent(this, engineSurface, status -> {
            updateViewportStatus(status);
        });
        controllerAgent.start();
    }

    private void showControllerPairing() {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(20), dp(12), dp(20), dp(4));
        EditText address = new EditText(this);
        address.setSingleLine(true);
        address.setHint("Controller URL");
        address.setText("http://127.0.0.1:8001");
        EditText secret = new EditText(this);
        secret.setSingleLine(true);
        secret.setHint("Private DEVICE_TOKEN from Termux .env");
        secret.setInputType(android.text.InputType.TYPE_CLASS_TEXT |
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        TextView help = new TextView(this);
        help.setText("For same-phone Termux testing use 127.0.0.1:8001. The token is encrypted with Android Keystore and is never written into the APK.");
        help.setTextColor(0xFFAAAAAA);
        help.setTextSize(12);
        form.addView(address);
        form.addView(secret);
        form.addView(help);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("NOVAINGINE AI LINK")
                .setView(form)
                .setPositiveButton("CONNECT", null)
                .setNeutralButton("DISCONNECT", (d, which) -> {
                    ControllerAgent.clearConfiguration(this);
                    if (controllerAgent != null) {
                        controllerAgent.close();
                        controllerAgent = null;
                    }
                    updateViewportStatus("NOVAINGINE V2  |  AI LINK OFF");
                })
                .setNegativeButton("CANCEL", null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try {
                ControllerAgent.saveConfiguration(this, address.getText().toString(), secret.getText().toString());
                startControllerAgent();
                dialog.dismiss();
            } catch (Exception error) {
                new AlertDialog.Builder(this).setMessage(error.getMessage()).setPositiveButton("OK", null).show();
            }
        }));
        dialog.show();
    }

    @Override
    protected void onPause() {

        projectHandler.removeCallbacks(autoSaveTask);
        if (activeProjectScene != null && engineSurface != null) engineSurface.saveScene(activeProjectScene.getAbsolutePath());
        if (controllerAgent != null) {
            controllerAgent.close();
            controllerAgent = null;
        }
        if (engineSurface != null) {
            engineSurface.onPause();
        }

        super.onPause();
    }

    @Override
    protected void onDestroy() {

        projectHandler.removeCallbacks(autoSaveTask);
        if (controllerAgent != null) {
            controllerAgent.close();
            controllerAgent = null;
        }
        cameraControls = null;
        manualSculptWorkspace = null;
        viewportInput = null;
        editorToolbar = null;
        engineSurface = null;
        rootLayout = null;

        super.onDestroy();
    }
}
