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
import android.content.ClipData;
import android.provider.OpenableColumns;

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
    private final java.util.ArrayDeque<File> meshyUndo = new java.util.ArrayDeque<>();
    private final java.util.ArrayDeque<File> meshyRedo = new java.util.ArrayDeque<>();
    private final java.util.ArrayList<String> meshyEditHistory = new java.util.ArrayList<>();
    private String meshyLastOperation;
    private int meshyLastOperationCode = -1;
    private int meshyLastElementIndex;
    private float meshyLastAmount = 0.1f;
    private final java.util.ArrayList<String> meshyRepeatHistory = new java.util.ArrayList<>();
    private boolean meshyEditBusy;
    private int meshySnapshotSerial;
    private boolean meshyObjectModesLocked;
    private boolean meshyWindowFullscreen = true;
    private ControllerAgent controllerAgent;
    private View projectStartPage;
    private final Handler projectHandler = new Handler(Looper.getMainLooper());
    private File activeProjectDirectory;
    private File activeProjectScene;
    private File activeMeshyDocument;
    private File activeMeshyProjectDirectory;
    private final Runnable autoSaveTask = new Runnable() {
        @Override public void run() {
            boolean hasActiveWork = false;
            if (manualSculptWorkspace != null && activeMeshyDocument != null) {
                hasActiveWork = true;
                try {
                    writeMeshyDocumentFile(activeMeshyDocument, false);
                    if (statisticsText != null) statisticsText.setText("MESHY3D  |  AUTO-SAVED");
                } catch (Exception error) {
                    if (statisticsText != null) statisticsText.setText("MESHY3D  |  AUTO-SAVE RETRY");
                }
            }
            if (activeProjectScene != null && engineSurface != null) {
                hasActiveWork = true;
                engineSurface.saveSceneAsync(activeProjectScene.getAbsolutePath(), saved -> {
                    if (statisticsText != null && manualSculptWorkspace == null)
                        statisticsText.setText(saved ? "NOVAINGINE V2  |  AUTO-SAVED" : "NOVAINGINE V2  |  SAVE RETRY");
                    if (saved && activeProjectScene != null) {
                        File recovery = new File(activeProjectScene.getParentFile(), "Scene.recovery.nova");
                        engineSurface.saveSceneAsync(recovery.getAbsolutePath(), recovered -> {
                            if (!recovered && statisticsText != null)
                                statisticsText.setText("NOVAINGINE V2  |  RECOVERY SAVE RETRY");
                        });
                    }
                });
            }
            if (hasActiveWork) projectHandler.postDelayed(this, 5000L);
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
        restoreMeshyHistory();

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
    private static final int REQUEST_OPEN_NOVA = 7413;
    private static final int REQUEST_SAVE_NOVA = 7414;
    private static final int REQUEST_SAVE_COPY_NOVA = 7415;
    private static final int REQUEST_EXPORT_NOVA = 7416;
    private static final int REQUEST_SAVE_MESHY_RENDER = 7417;
    private static final int REQUEST_EXPORT_MESHY_DOCUMENT = 7418;
    private static final int REQUEST_IMPORT_MESHY_DOCUMENT = 7419;
    private static final int REQUEST_SAVE_MESHY_SYSTEM_INFO = 7420;
    private static final int REQUEST_IMPORT_MESHY_GLB = 7421;
    private static final int REQUEST_EXPORT_MESHY_OBJ = 7422;
    private static final int REQUEST_EXPORT_MESHY_STL = 7423;
    private static final int REQUEST_EXPORT_MESHY_GLB = 7424;

    private android.graphics.Bitmap pendingMeshyImage;
    private String pendingMeshyExportJson;
    private String pendingMeshySystemInfo;
    private String pendingMeshyGeometryExport;
    private byte[] pendingMeshyGlbExport;
    private boolean meshyStatusBarVisible = true;

    public void showMeshyApplicationMenu(View anchor) {
        android.widget.PopupMenu popup = new android.widget.PopupMenu(this, anchor);
        String[] entries = {"About Meshy3D", "Meshy3D Manual", "Release Notes",
                "Source Repository", "Creator Credits"};
        for (String entry : entries) popup.getMenu().add(entry);
        popup.setOnMenuItemClickListener(item -> {
            switch (item.getTitle().toString()) {
                case "About Meshy3D":
                case "Creator Credits":
                    showMeshyDocumentation("About Meshy3D");
                    break;
                case "Meshy3D Manual":
                    openMeshyHelpUrl("https://github.com/gospinkisali80-art/Novaingine_V2/blob/main/README.md");
                    break;
                case "Release Notes":
                    openMeshyHelpUrl("https://github.com/gospinkisali80-art/Novaingine_V2/releases");
                    break;
                case "Source Repository":
                    openMeshyHelpUrl("https://github.com/gospinkisali80-art/Novaingine_V2");
                    break;
            }
            return true;
        });
        popup.show();
    }

    public void showMeshyFileMenu(View anchor) {
        android.widget.PopupMenu popup = new android.widget.PopupMenu(this, anchor);
        String[] entries = {
                "New", "Open", "Open Recent", "Clear Recent Files List", "Revert", "Recover",
                "Save", "Save As", "Save Copy", "Save Incremental",
                "Link", "Append", "Data Previews", "Import", "Export", "Export All Collections",
                "External Data", "Clean Up", "Defaults", "Quit"
        };
        for (int i = 0; i < entries.length; i++) {
            android.view.MenuItem menuItem = popup.getMenu().add(0, i, i, entries[i]);
            if ("Link".equals(entries[i]) || "Append".equals(entries[i])) {
                menuItem.setEnabled(false);
            }
        }
        popup.setOnMenuItemClickListener(item -> {
            performMeshyFileAction(item.getTitle().toString());
            return true;
        });
        popup.show();
    }

    public void showMeshyRenderMenu(View anchor) {
        android.widget.PopupMenu popup = new android.widget.PopupMenu(this, anchor);
        String[] entries = {
                "Render Image", "Render Animation", "Render Sequencer Image",
                "Render Sequencer Animation", "Render Audio", "View Render",
                "View Animation", "Lock Interface"
        };
        for (String entry : entries) {
            android.view.MenuItem item = popup.getMenu().add(entry);
            if (!"Render Image".equals(entry) && !"View Render".equals(entry)) {
                item.setEnabled(false);
            }
        }
        popup.setOnMenuItemClickListener(item -> {
            String action = item.getTitle().toString();
            if ("Render Image".equals(action)) renderMeshyImage();
            else if ("View Render".equals(action)) viewLastMeshyRender();
            return true;
        });
        popup.show();
    }

    public void performMeshyFileAction(String action) {
        switch (action) {
            case "New":
                startNewMeshyProject();
                break;
            case "Open":
                new AlertDialog.Builder(this).setTitle("Open")
                        .setItems(new String[]{"Open Meshy3D project…", "Open recent Meshy3D project…"}, (d, w) -> {
                            if (w == 0) chooseMeshyProject();
                            else chooseRecentMeshyProject();
                        }).setNegativeButton("Cancel", null).show();
                break;
            case "Open Recent":
                chooseRecentMeshyProject();
                break;
            case "Clear Recent Files List":
                clearRecentMeshyDocuments();
                break;
            case "Revert":
                new AlertDialog.Builder(this).setTitle("Revert Meshy3D document?")
                        .setMessage("Reload the saved Meshy3D document. The Novaingine scene is not affected.")
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Revert", (d, w) -> {
                            if (activeMeshyDocument != null && activeMeshyDocument.isFile())
                                loadMeshyDocumentIntoWorkspace(activeMeshyDocument);
                            else android.widget.Toast.makeText(this, "No saved Meshy3D document.", android.widget.Toast.LENGTH_SHORT).show();
                        }).show();
                break;
            case "Recover":
                recoverMeshyDocument();
                break;
            case "Save":
                saveMeshyDocument();
                break;
            case "Save As":
                saveMeshyDocumentAs();
                break;
            case "Save Copy":
                saveMeshyDocumentCopy();
                break;
            case "Save Incremental":
                saveMeshyIncremental();
                break;
            case "Link":
            case "Append":
                android.widget.Toast.makeText(this,
                        "External .blend library linking and appending are not supported by the Meshy3D document format.",
                        android.widget.Toast.LENGTH_LONG).show();
                break;
            case "Import":
                showMeshyImportMenu();
                break;
            case "Data Previews":
                showSceneDataPreview();
                break;
            case "Export":
                showMeshyExportMenu();
                break;
            case "Export All Collections":
                exportMeshyDocumentToPicker();
                break;
            case "External Data":
                new AlertDialog.Builder(this).setTitle("External Data")
                        .setMessage("Meshy3D documents are stored separately as .meshy3d files in the Meshy3DProjects folder. External linked .blend data is not supported yet.")
                        .setPositiveButton("OK", null).show();
                break;
            case "Clean Up":
                cleanMeshyTemporaryFiles();
                break;
            case "Defaults":
                showMeshyDefaultsMenu();
                break;
            case "Quit":
                if (manualSculptWorkspace != null) {
                    confirmMeshySwitch("quitting Meshy3D", () -> {
                        activeMeshyDocument = null;
                        activeMeshyProjectDirectory = null;
                        finish();
                    });
                } else finish();
                break;
        }
    }

    private static final String[] MESHY_WORKSPACE_NAMES = {
            "Layout", "Modeling", "Sculpting", "UV Editing", "Texture Paint",
            "Shading", "Animation", "Rendering", "Compositing", "Geometry Nodes"
    };

    public void showMeshyWindowMenu(View anchor) {
        android.widget.PopupMenu popup = new android.widget.PopupMenu(this, anchor);
        String[] entries = {"New Window", "New Main Window", "Toggle Window Fullscreen",
                "Toggle System Console", "Workspaces", "Save Workspace", "Revert Workspace",
                "Next Workspace", "Previous Workspace", "Show Status Bar",
                "Save Screenshot", "Save Screenshot (Editor)"};
        for (String entry : entries) popup.getMenu().add(entry);
        popup.setOnMenuItemClickListener(item -> {
            performMeshyWindowAction(item.getTitle().toString());
            return true;
        });
        popup.show();
    }

    private void performMeshyWindowAction(String action) {
        switch (action) {
            case "New Window":
                launchMeshyWindow(true);
                break;
            case "New Main Window":
                launchMeshyWindow(false);
                break;
            case "Toggle Window Fullscreen":
                meshyWindowFullscreen = !meshyWindowFullscreen;
                applyMeshyWindowFullscreen();
                break;
            case "Toggle System Console":
                showMeshySystemConsole();
                break;
            case "Workspaces":
                if (manualSculptWorkspace != null) manualSculptWorkspace.showWorkspaceChooser();
                break;
            case "Save Workspace":
                saveMeshyWorkspace();
                break;
            case "Revert Workspace":
                revertMeshyWorkspace();
                break;
            case "Next Workspace":
                changeMeshyWorkspace(1);
                break;
            case "Previous Workspace":
                changeMeshyWorkspace(-1);
                break;
            case "Show Status Bar":
                meshyStatusBarVisible = !meshyStatusBarVisible;
                if (manualSculptWorkspace != null) manualSculptWorkspace.setStatusBarVisible(meshyStatusBarVisible);
                android.widget.Toast.makeText(this, meshyStatusBarVisible ? "Status Bar shown." : "Status Bar hidden.", android.widget.Toast.LENGTH_SHORT).show();
                break;
            case "Save Screenshot":
                captureMeshyScreenshot(false);
                break;
            case "Save Screenshot (Editor)":
                captureMeshyScreenshot(true);
                break;
        }
    }

    private void launchMeshyWindow(boolean separateDocument) {
        try {
            Intent intent = new Intent(this, MainActivity.class);
            if (separateDocument) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
            } else {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
            }
            startActivity(intent);
        } catch (Exception error) {
            new AlertDialog.Builder(this).setTitle("New Window")
                    .setMessage("Android could not open another Meshy3D activity: " + error.getMessage())
                    .setPositiveButton("OK", null).show();
        }
    }

    private void applyMeshyWindowFullscreen() {
        int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE;
        if (meshyWindowFullscreen) {
            flags |= View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        }
        getWindow().getDecorView().setSystemUiVisibility(flags);
        if (meshyWindowFullscreen) getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        android.widget.Toast.makeText(this, meshyWindowFullscreen
                ? "Meshy3D fullscreen enabled" : "System bars shown", android.widget.Toast.LENGTH_SHORT).show();
    }

    private void showMeshySystemConsole() {
        String project = activeProjectScene == null ? "No active project" : activeProjectScene.getAbsolutePath();
        String workspace = manualSculptWorkspace == null ? "Meshy3D workspace not open"
                : MESHY_WORKSPACE_NAMES[Math.max(0, Math.min(MESHY_WORKSPACE_NAMES.length - 1,
                        manualSculptWorkspace.getActiveWorkspaceIndex()))];
        String details = "MESHY3D SYSTEM CONSOLE\n"
                + "Application: Novaingine / Meshy3D\n"
                + "Workspace: " + workspace + "\n"
                + "Project: " + project + "\n"
                + "Device: " + android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL + "\n"
                + "Android: " + android.os.Build.VERSION.RELEASE + " (API " + android.os.Build.VERSION.SDK_INT + ")\n"
                + "This panel shows editor diagnostics. It is not a live Android logcat stream.";
        TextView output = new TextView(this);
        output.setText(details);
        output.setTextIsSelectable(true);
        output.setTextColor(0xFFE4EAF0);
        output.setTextSize(12);
        output.setPadding(dp(16), dp(14), dp(16), dp(14));
        output.setBackgroundColor(0xFF15191F);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(output);
        new AlertDialog.Builder(this).setTitle("System Console")
                .setView(scroll).setPositiveButton("Close", null).show();
    }

    private void saveMeshyWorkspace() {
        int index = manualSculptWorkspace == null ? 0 : manualSculptWorkspace.getActiveWorkspaceIndex();
        getSharedPreferences("meshy3d_editor", MODE_PRIVATE).edit()
                .putInt("saved_workspace_index", index)
                .putBoolean("saved_workspace_fullscreen", meshyWindowFullscreen).apply();
        android.widget.Toast.makeText(this, "Workspace layout saved.", android.widget.Toast.LENGTH_SHORT).show();
    }

    private void revertMeshyWorkspace() {
        android.content.SharedPreferences prefs = getSharedPreferences("meshy3d_editor", MODE_PRIVATE);
        int index = prefs.getInt("saved_workspace_index", 0);
        if (manualSculptWorkspace != null) manualSculptWorkspace.setActiveWorkspaceIndex(index);
        meshyWindowFullscreen = prefs.getBoolean("saved_workspace_fullscreen", true);
        applyMeshyWindowFullscreen();
        android.widget.Toast.makeText(this, "Saved workspace restored.", android.widget.Toast.LENGTH_SHORT).show();
    }

    private void changeMeshyWorkspace(int direction) {
        if (manualSculptWorkspace == null) {
            android.widget.Toast.makeText(this, "Open Meshy3D to switch workspaces.", android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        int count = MESHY_WORKSPACE_NAMES.length;
        int next = (manualSculptWorkspace.getActiveWorkspaceIndex() + direction + count) % count;
        manualSculptWorkspace.setActiveWorkspaceIndex(next);
    }

    public void showMeshyHelpMenu(View anchor) {
        android.widget.PopupMenu popup = new android.widget.PopupMenu(this, anchor);
        String[] topics = {
                "Meshy3D Manual", "Support", "User Communities", "Get Involved",
                "Release Notes", "Report a Bug", "Save System Info",
                "Welcome to Meshy3D", "Getting Started", "Viewport Guide", "Modeling Guide",
                "Sculpting Guide", "Materials & Textures Guide", "Lighting Guide",
                "Animation Guide", "AI Modeling Guide", "Keyboard Shortcuts",
                "Mouse & Navigation Controls", "Tool Reference", "Troubleshooting",
                "Performance & Optimization", "Project Recovery Guide", "What's New",
                "Send Feedback", "Check for Updates", "System Information", "About Meshy3D"
        };
        for (String topic : topics) popup.getMenu().add(topic);
        popup.setOnMenuItemClickListener(item -> {
            showMeshyDocumentation(item.getTitle().toString());
            return true;
        });
        popup.show();
    }

    private void openMeshyHelpUrl(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception error) {
            android.widget.Toast.makeText(this, "Could not open help link: " + error.getMessage(), android.widget.Toast.LENGTH_LONG).show();
        }
    }

    private String buildMeshySystemInfo() {
        String version = "unknown";
        try {
            android.content.pm.PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            version = info.versionName == null ? "unknown" : info.versionName;
        } catch (Exception ignored) {}
        android.util.DisplayMetrics display = getResources().getDisplayMetrics();
        android.app.ActivityManager.MemoryInfo memory = new android.app.ActivityManager.MemoryInfo();
        android.app.ActivityManager manager = (android.app.ActivityManager) getSystemService(ACTIVITY_SERVICE);
        if (manager != null) manager.getMemoryInfo(memory);
        String workspace = manualSculptWorkspace == null ? "Closed"
                : MESHY_WORKSPACE_NAMES[Math.max(0, Math.min(MESHY_WORKSPACE_NAMES.length - 1,
                        manualSculptWorkspace.getActiveWorkspaceIndex()))];
        return "MESHY3D SYSTEM INFORMATION\n"
                + "App: Novaingine / Meshy3D " + version + "\n"
                + "Package: " + getPackageName() + "\n"
                + "Device: " + android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL + "\n"
                + "Android: " + android.os.Build.VERSION.RELEASE + " (API " + android.os.Build.VERSION.SDK_INT + ")\n"
                + "ABI: " + android.os.Build.SUPPORTED_ABIS[0] + "\n"
                + "Display: " + display.widthPixels + " × " + display.heightPixels
                + " px @ " + display.densityDpi + " dpi\n"
                + "Available RAM: " + (memory.availMem / (1024L * 1024L)) + " MiB\n"
                + "Workspace: " + workspace + "\n"
                + "Active document: " + (activeMeshyDocument == null ? "None" : activeMeshyDocument.getName()) + "\n"
                + "Timestamp: " + new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", java.util.Locale.US).format(new java.util.Date());
    }

    private void saveMeshySystemInfo() {
        String info = buildMeshySystemInfo();
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_TITLE, "Meshy3D-System-Info.txt");
        pendingMeshySystemInfo = info;
        startActivityForResult(intent, REQUEST_SAVE_MESHY_SYSTEM_INFO);
    }

    private void sendMeshyFeedback() {
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_SUBJECT, "Meshy3D feedback");
        send.putExtra(Intent.EXTRA_TEXT, "Meshy3D feedback\n\n" + buildMeshySystemInfo()
                + "\n\nDescribe what happened, what you expected, and the steps to reproduce it.");
        try {
            startActivity(Intent.createChooser(send, "Send Meshy3D feedback"));
        } catch (Exception error) {
            android.widget.Toast.makeText(this, "No app is available to share feedback.", android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    private void showMeshyDocumentation(String topic) {
        switch (topic) {
            case "Meshy3D Manual":
                openMeshyHelpUrl("https://github.com/gospinkisali80-art/Novaingine_V2/blob/main/README.md");
                return;
            case "Support":
                openMeshyHelpUrl("https://github.com/gospinkisali80-art/Novaingine_V2/issues");
                return;
            case "User Communities":
                openMeshyHelpUrl("https://github.com/gospinkisali80-art/Novaingine_V2/discussions");
                return;
            case "Get Involved":
                openMeshyHelpUrl("https://github.com/gospinkisali80-art/Novaingine_V2");
                return;
            case "Release Notes":
            case "What's New":
            case "Check for Updates":
                openMeshyHelpUrl("https://github.com/gospinkisali80-art/Novaingine_V2/releases");
                return;
            case "Report a Bug":
                openMeshyHelpUrl("https://github.com/gospinkisali80-art/Novaingine_V2/issues/new");
                return;
            case "Save System Info":
            case "System Information":
                saveMeshySystemInfo();
                return;
            case "Send Feedback":
                sendMeshyFeedback();
                return;
            default:
                break;
        }
        String[] titles = {
                "Welcome to Meshy3D", "Getting Started", "Viewport Guide", "Modeling Guide",
                "Sculpting Guide", "Materials & Textures Guide", "Lighting Guide",
                "Animation Guide", "AI Modeling Guide", "Keyboard Shortcuts",
                "Mouse & Navigation Controls", "Tool Reference", "Troubleshooting",
                "Performance & Optimization", "Project Recovery Guide", "What's New",
                "Send Feedback", "Check for Updates", "System Information", "About Meshy3D"
        };
        String[] docs = {
                "Meshy3D is the 3D creation workspace inside Novaingine. It combines a real-time 3D viewport, object and mesh editing, sculpting, project management, and engine tools.\n\nThe editor is designed around a scene: objects are placed in 3D space, selected, transformed, modeled, shaded, and previewed. The top application bar contains File, Edit, Render, Window, and Help. Workspace tabs organize tasks such as Layout, Modeling, Sculpting, UV Editing, Texture Paint, Shading, Animation, Rendering, Compositing, and Geometry Nodes.\n\nUse File for project operations, Edit for editor history and mesh commands, Window for workspace/window controls, and Help for this documentation.",
                "1. Create a project from the Novaingine project start page.\n2. Give the project a name and create an empty scene.\n3. Use the viewport to inspect the scene and select an object.\n4. Use the transform controls and numeric fields to position, rotate, and scale.\n5. Open Modeling or Sculpting for geometry work.\n6. Save from File > Save or use the automatic project save.\n\nProjects use Novaingine's .nova scene format. Imported GLB assets can be brought into the scene through File > Import.",
                "The viewport is the main 3D viewing area. It displays the active scene from the editor camera. Drag to orbit/look around, use the navigation controls to move the view, and use pinch/zoom gestures where supported. The viewport header contains the mode selector plus View, Select, Add, and Object menus. The XYZ transform gizmo indicates the selected object's axes. Keep the viewport clear while editing; use the docks for tools and object information.",
                "Modeling changes mesh geometry. Select a mesh object, enter Edit Mode, then choose an operation from Edit or the modeling tool controls. Available mesh operations include Extrude, Inset, Bevel, Loop Cut, Subdivide, Merge by Distance, and Knife Split. Operation dialogs expose parameters such as element index and amount where supported. Use Undo/Redo if an operation produces an unwanted result. Some operations require a supported editable mesh and a valid selection.",
                "Sculpting is for shaping a mesh directly with brush strokes. Switch the mode selector to Sculpt Mode to show the brush dock and brush controls. Choose a brush, adjust radius, strength, falloff, pressure, symmetry, and additive/subtractive behavior where available, then drag over the mesh. Sculpt results depend on mesh density and the selected brush implementation. Use Undo to return to an earlier editor state.",
                "Materials describe how a surface responds to light; textures provide image or procedural surface detail. The Shading workspace is the place for material and shader workflows as they become available in the editor. Inspect roughness, metallic response, base color, and texture inputs when those controls are exposed. Imported geometry may carry material data only to the extent supported by the current importer.",
                "Lighting controls the illumination of the scene. The Rendering workspace is used to inspect the final look, while the viewport provides a real-time preview. Direction, intensity, color, and shadow quality affect readability and surface appearance. Keep lighting simple while modeling, then refine it for presentation. Not every offline-rendering feature of desktop 3D packages is currently implemented in Meshy3D.",
                "Animation uses time-based changes to object transforms. The Animation workspace provides the intended area for timeline and keyframe work. The current editor includes transform keyframe/timeline foundations; available playback and keyframe controls depend on the active build. Save the project after important animation edits.",
                "AI Modeling is intended to turn a text description into editable 3D scene content. Describe the object, its parts, proportions, and materials clearly. Review the generated result in the viewport and continue editing it with Meshy3D's modeling or sculpting tools. AI availability depends on the configured provider and network connection; generated output should be checked before use.",
                "Meshy3D supports touch-first interaction. The Edit menu exposes its commands and Menu Search can filter available editor commands. The Android build does not assume a physical keyboard, so a complete desktop-style shortcut map may not be available on every device. When a keyboard is connected, use the shortcut hints shown by individual controls if present.",
                "Touch the viewport and drag to look around or manipulate the selected tool, depending on the active mode. Use pinch gestures for zoom where enabled. Drag a visible XYZ gizmo axis to transform along that axis. Use the on-screen navigation controls for camera movement. If a gesture edits the mesh instead of moving the camera, check whether Sculpt Mode is active.",
                "Core editor commands include object selection, transform editing, scene import/export, and mesh operations. Modeling operations: Extrude adds geometry from selected elements; Inset creates an inner region; Bevel rounds or chamfers edges; Loop Cut inserts edge loops; Subdivide adds topology; Merge by Distance welds nearby vertices; Knife Split cuts geometry. Sculpt brushes include Draw, Smooth, Grab, Inflate, Pinch, Flatten, Crease, Mask, Clay, Scrape, Layer, Unmask, Blob, Clay Strips, Elastic Deform, Snake Hook, Thumb, Pose, Boundary, Slide Relax, Multiplane Scrape, Fill, Deepen, Nudge, and Simplify.",
                "If a menu does not open, close another dialog and tap the menu again. If an operation reports that no editable mesh is available, select a supported mesh object. If sculpting has no visible effect, confirm Sculpt Mode is active, a brush is selected, and the mesh supports sculpt deformation. If a project fails to load, try File > Recover. If the app closes unexpectedly, reopen the project and inspect the recovery file before overwriting it.",
                "Close unused workspaces and dialogs, reduce unnecessary scene complexity, and use simpler meshes while sculpting on mobile hardware. Large meshes and many high-resolution assets increase memory and rendering cost. Save regularly. If the viewport slows down, test with fewer objects and simpler materials before changing project content.",
                "Novaingine periodically saves the active project and writes a recovery scene alongside the project file. Use File > Recover to inspect and restore a recovery snapshot. Use File > Revert only when you intentionally want to reload the last saved scene and discard current unsaved changes. Keep a separate Save Copy before major edits.",
                "This section describes the current Meshy3D feature set included in the installed build. New commands may appear as the editor evolves. Check the project release notes or update information distributed with your Novaingine build for changes. A menu item is considered available only when its action is connected in the running app.",
                "Use Send Feedback to describe the screen, action, and result. Include the steps that led to the issue, what you expected, and what happened instead. Avoid sharing passwords, access tokens, or private project content. Feedback submission requires a configured feedback destination; if none is configured, use your project support channel.",
                "Check for Updates checks the app's configured update source when one is available. If no update service is configured in this build, the app will explain that automatic update checking is unavailable. Install updates only from the official Novaingine distribution channel.",
                "System Information shows the Android device model, Android version, and editor context. Use this information when reporting rendering or compatibility issues. It does not expose private credentials or account passwords.",
                "Meshy3D is the 3D editor workspace inside Novaingine. This page identifies the editor and its current build context. Creator credit: MESH. Project and feature availability can vary by Novaingine version."
        };
        int index = 0;
        for (int i = 0; i < titles.length; i++) if (titles[i].equals(topic)) { index = i; break; }
        TextView body = new TextView(this);
        body.setText(docs[index]);
        body.setTextSize(13);
        body.setTextColor(0xFFE2E7ED);
        body.setTextIsSelectable(true);
        body.setPadding(dp(18), dp(14), dp(18), dp(14));
        body.setBackgroundColor(0xFF202832);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(body);
        new AlertDialog.Builder(this).setTitle(titles[index])
                .setView(scroll).setPositiveButton("Close", null).show();
    }

    public void showMeshyEditMenu(View anchor) {
        android.widget.PopupMenu popup = new android.widget.PopupMenu(this, anchor);
        String[] entries = {
                "Undo", "Redo", "Undo History", "Repeat Last", "Repeat History",
                "Adjust Last Operation", "Menu Search", "Rename Active Item",
                "Batch Rename", "Lock Object Modes", "Preferences",
                "Extrude", "Inset", "Bevel", "Loop Cut", "Subdivide",
                "Merge by Distance", "Knife Split"
        };
        for (int i = 0; i < entries.length; i++) {
            android.view.MenuItem item = popup.getMenu().add(0, i, i, entries[i]);
            if ("Lock Object Modes".equals(entries[i])) item.setCheckable(true).setChecked(meshyObjectModesLocked);
            if ("Knife Split".equals(entries[i])) item.setEnabled(false);
        }
        popup.setOnMenuItemClickListener(item -> {
            performMeshyEditAction(item.getTitle().toString());
            return true;
        });
        popup.show();
    }

    private File nextMeshySnapshot(String label) {
        File dir = new File(getFilesDir(), "meshy3d_editor_history");
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, String.format(java.util.Locale.US, "%05d_%s.nova",
                ++meshySnapshotSerial, label.replaceAll("[^A-Za-z0-9_-]", "_")));
    }

    private void captureMeshyState(java.util.ArrayDeque<File> target, String label, Runnable done) {
        File snapshot = nextMeshySnapshot(label);
        engineSurface.saveSceneAsync(snapshot.getAbsolutePath(), ok -> {
            if (ok) {
                target.addLast(snapshot);
                while (target.size() > 30) {
                    File old = target.removeFirst();
                    if (old.exists()) old.delete();
                }
                persistMeshyHistory();
            }
            if (done != null) done.run();
        });
    }

    private void persistMeshyHistory() {
        android.content.SharedPreferences prefs = getSharedPreferences("meshy3d_editor", MODE_PRIVATE);
        if (!prefs.getBoolean("persist_history", false)) return;
        java.util.ArrayList<String> undoNames = new java.util.ArrayList<>();
        java.util.ArrayList<String> redoNames = new java.util.ArrayList<>();
        for (File f : meshyUndo) undoNames.add(f.getName());
        for (File f : meshyRedo) redoNames.add(f.getName());
        prefs.edit().putString("undo_files", android.text.TextUtils.join("\n", undoNames))
                .putString("redo_files", android.text.TextUtils.join("\n", redoNames))
                .putString("repeat_files", android.text.TextUtils.join("\n", meshyRepeatHistory))
                .putInt("snapshot_serial", meshySnapshotSerial).apply();
    }

    private void restoreMeshyHistory() {
        android.content.SharedPreferences prefs = getSharedPreferences("meshy3d_editor", MODE_PRIVATE);
        if (!prefs.getBoolean("persist_history", false)) return;
        File dir = new File(getFilesDir(), "meshy3d_editor_history");
        for (String name : prefs.getString("undo_files", "").split("\n"))
            if (!name.isEmpty()) { File f = new File(dir, name); if (f.isFile()) meshyUndo.addLast(f); }
        for (String name : prefs.getString("redo_files", "").split("\n"))
            if (!name.isEmpty()) { File f = new File(dir, name); if (f.isFile()) meshyRedo.addLast(f); }
        for (String line : prefs.getString("repeat_files", "").split("\n"))
            if (!line.isEmpty()) meshyRepeatHistory.add(line);
        meshySnapshotSerial = prefs.getInt("snapshot_serial", 0);
        meshyObjectModesLocked = prefs.getBoolean("lock_object_modes", false);
    }

    private void meshyUndo() {
        if (manualSculptWorkspace != null) {
            boolean changed = manualSculptWorkspace.undoMeshEdit();
            android.widget.Toast.makeText(this, changed ? "Meshy3D mesh edit undone."
                    : "Meshy3D mesh undo history is empty.", android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        if (meshyUndo.isEmpty()) {
            android.widget.Toast.makeText(this, "Meshy3D editor history is empty.", android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        File target = meshyUndo.removeLast();
        captureMeshyState(meshyRedo, "redo", () ->
                engineSurface.loadSceneAsync(target.getAbsolutePath(), ok -> {
                    if (!ok) meshyUndo.addLast(target);
                    else { persistMeshyHistory(); if (statisticsText != null) statisticsText.setText("MESHY3D  |  EDIT UNDO"); }
                }));
    }

    private void meshyRedo() {
        if (manualSculptWorkspace != null) {
            boolean changed = manualSculptWorkspace.redoMeshEdit();
            android.widget.Toast.makeText(this, changed ? "Meshy3D mesh edit redone."
                    : "Meshy3D mesh redo history is empty.", android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        if (meshyRedo.isEmpty()) {
            android.widget.Toast.makeText(this, "Meshy3D editor redo history is empty.", android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        File target = meshyRedo.removeLast();
        captureMeshyState(meshyUndo, "undo", () ->
                engineSurface.loadSceneAsync(target.getAbsolutePath(), ok -> {
                    if (!ok) meshyRedo.addLast(target);
                    else { persistMeshyHistory(); if (statisticsText != null) statisticsText.setText("MESHY3D  |  EDIT REDO"); }
                }));
    }

    private void showMeshyUndoHistory() {
        if (manualSculptWorkspace != null) {
            int count = manualSculptWorkspace.getMeshUndoCount();
            String[] entries = new String[count + 1];
            entries[0] = "Current State";
            for (int i = 1; i <= count; i++) entries[i] = "Undo to checkpoint " + i;
            new AlertDialog.Builder(this).setTitle("Meshy3D Undo History")
                    .setItems(entries, (dialog, which) -> {
                        if (which <= 0) return;
                        int restored = 0;
                        for (int step = 0; step < which; step++) {
                            if (!manualSculptWorkspace.undoMeshEdit()) break;
                            restored++;
                        }
                        android.widget.Toast.makeText(this, restored > 0
                                ? "Restored " + restored + " Meshy3D history step(s)."
                                : "No earlier Meshy3D checkpoint is available.",
                                android.widget.Toast.LENGTH_LONG).show();
                    })
                    .setNegativeButton("Close", null).show();
            return;
        }
        java.util.ArrayList<String> rows = new java.util.ArrayList<>();
        rows.add("Current state");
        for (int i = meshyUndo.size() - 1; i >= 0; i--) rows.add("Undo checkpoint " + (meshyUndo.size() - i));
        new AlertDialog.Builder(this).setTitle("Meshy3D Undo History")
                .setItems(rows.toArray(new String[0]), (d, which) -> {
                    if (which == 0) return;
                    File[] states = meshyUndo.toArray(new File[0]);
                    int index = states.length - which;
                    if (index >= 0 && index < states.length) {
                        File target = states[index];
                        captureMeshyState(meshyRedo, "redo", () ->
                                engineSurface.loadSceneAsync(target.getAbsolutePath(), ok -> {
                                    if (ok) while (meshyUndo.peekLast() != target && !meshyUndo.isEmpty()) meshyUndo.removeLast();
                                }));
                    }
                }).setNegativeButton("Close", null).show();
    }

    private void showMeshyMenuSearch() {
        final String[] commands = {"Undo", "Redo", "Undo History", "Repeat Last", "Repeat History",
                "Adjust Last Operation", "Rename Active Item", "Batch Rename",
                "Lock Object Modes", "Preferences", "Extrude", "Inset", "Bevel",
                "Loop Cut", "Subdivide", "Merge by Distance"};
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(16), dp(8), dp(16), 0);
        EditText query = new EditText(this);
        query.setSingleLine(true);
        query.setHint("Type to filter Edit commands");
        panel.addView(query);
        android.widget.ListView list = new android.widget.ListView(this);
        panel.addView(list, new LinearLayout.LayoutParams(-1, dp(300)));
        java.util.ArrayList<String> filtered = new java.util.ArrayList<>();
        android.widget.ArrayAdapter<String> adapter = new android.widget.ArrayAdapter<>(
                this, android.R.layout.simple_list_item_1, filtered);
        list.setAdapter(adapter);
        Runnable refresh = () -> {
            String q = query.getText().toString().trim().toLowerCase(java.util.Locale.ROOT);
            filtered.clear();
            for (String command : commands)
                if (q.isEmpty() || command.toLowerCase(java.util.Locale.ROOT).contains(q)) filtered.add(command);
            adapter.notifyDataSetChanged();
        };
        query.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int st, int count, int after) {}
            public void onTextChanged(CharSequence s, int st, int before, int count) { refresh.run(); }
            public void afterTextChanged(android.text.Editable e) {}
        });
        refresh.run();
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Menu Search")
                .setView(panel).setNegativeButton("Close", null).create();
        list.setOnItemClickListener((parent, view, position, id) -> {
            String selected = filtered.get(position);
            dialog.dismiss();
            performMeshyEditAction(selected);
        });
        dialog.show();
    }

    private void showMeshyPreferences() {
        LinearLayout panel = new LinearLayout(this);
        panel.setPadding(dp(18), dp(10), dp(18), 0);
        panel.setOrientation(LinearLayout.VERTICAL);
        CheckBox confirm = new CheckBox(this);
        confirm.setText("Confirm destructive mesh operations");
        confirm.setChecked(getSharedPreferences("meshy3d_editor", MODE_PRIVATE).getBoolean("confirm_ops", true));
        panel.addView(confirm);
        CheckBox history = new CheckBox(this);
        history.setText("Keep Meshy3D undo / redo history between sessions");
        history.setChecked(getSharedPreferences("meshy3d_editor", MODE_PRIVATE).getBoolean("persist_history", false));
        panel.addView(history);
        CheckBox lockModes = new CheckBox(this);
        lockModes.setText("Lock Object / Edit / Sculpt mode switching");
        lockModes.setChecked(getSharedPreferences("meshy3d_editor", MODE_PRIVATE).getBoolean("lock_object_modes", false));
        panel.addView(lockModes);
        new AlertDialog.Builder(this).setTitle("Meshy3D Preferences").setView(panel)
                .setPositiveButton("Save", (d, w) -> {
                    getSharedPreferences("meshy3d_editor", MODE_PRIVATE).edit()
                            .putBoolean("confirm_ops", confirm.isChecked())
                            .putBoolean("persist_history", history.isChecked())
                            .putBoolean("lock_object_modes", lockModes.isChecked()).apply();
                    meshyObjectModesLocked = lockModes.isChecked();
                    if (manualSculptWorkspace != null) manualSculptWorkspace.setObjectModesLocked(meshyObjectModesLocked);
                    if (history.isChecked()) persistMeshyHistory();
                    else getSharedPreferences("meshy3d_editor", MODE_PRIVATE).edit()
                            .remove("undo_files").remove("redo_files").remove("repeat_files").apply();
                })
                .setNegativeButton("Cancel", null).show();
    }

    private void renameActiveItem() {
        if (manualSculptWorkspace == null) {
            android.widget.Toast.makeText(this, "Open the Meshy3D workspace first.", android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        String currentName = manualSculptWorkspace.getActiveObjectName();
        if (currentName == null) {
            android.widget.Toast.makeText(this, "Select a Meshy3D object first.", android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        EditText name = new EditText(this);
        name.setSingleLine(true);
        name.setText(currentName);
        name.setHint("New active object name");
        new AlertDialog.Builder(this).setTitle("Rename Active Item").setView(name)
                .setNegativeButton("Cancel", null).setPositiveButton("Rename", (d, w) -> {
                    String value = name.getText().toString().trim();
                    if (value.isEmpty()) {
                        android.widget.Toast.makeText(this, "Object name cannot be empty.", android.widget.Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (manualSculptWorkspace.renameActiveMeshyObject(value)) {
                        recordMeshyHistory("Rename Active Item");
                    } else {
                        android.widget.Toast.makeText(this, "No active Meshy3D object could be renamed.", android.widget.Toast.LENGTH_SHORT).show();
                    }
                }).show();
    }

    private void showMeshyBatchRename() {
        if (manualSculptWorkspace == null) {
            android.widget.Toast.makeText(this, "Open the Meshy3D workspace first.", android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        int selectedCount = manualSculptWorkspace.getSelectedObjectCount();
        if (selectedCount == 0) {
            android.widget.Toast.makeText(this, "Select the Meshy3D objects to rename first.", android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        LinearLayout form = new LinearLayout(this);
        form.setPadding(dp(16), dp(8), dp(16), 0);
        form.setOrientation(LinearLayout.VERTICAL);
        EditText prefix = new EditText(this);
        prefix.setSingleLine(true);
        prefix.setHint("Name prefix");
        prefix.setText("Object");
        EditText start = new EditText(this);
        start.setSingleLine(true);
        start.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_SIGNED);
        start.setText("1");
        form.addView(prefix);
        form.addView(start);
        new AlertDialog.Builder(this).setTitle("Batch Rename " + selectedCount + " Meshy3D Objects").setView(form)
                .setNegativeButton("Cancel", null).setPositiveButton("Rename Selected", (d, w) -> {
                    String base = prefix.getText().toString().trim();
                    int first;
                    try { first = Integer.parseInt(start.getText().toString()); }
                    catch (Exception e) { first = 1; }
                    if (base.isEmpty()) base = "Object";
                    int renamed = manualSculptWorkspace.renameSelectedMeshyObjects(base, first);
                    if (renamed > 0) recordMeshyHistory("Batch Rename");
                    else android.widget.Toast.makeText(this, "No selected Meshy3D objects were renamed.", android.widget.Toast.LENGTH_SHORT).show();
                }).show();
    }

    private void renameBatchAt(String[] objects, int index, String prefix, int first) {
        if (index >= objects.length) { recordMeshyHistory("Batch Rename"); return; }
        String row = objects[index];
        int split = row == null ? -1 : row.indexOf('|');
        if (split < 1) { renameBatchAt(objects, index + 1, prefix, first); return; }
        try {
            int id = Integer.parseInt(row.substring(0, split));
            engineSurface.renameObject(id, prefix + String.format(java.util.Locale.US, "_%03d", first + index),
                    ok -> renameBatchAt(objects, index + 1, prefix, first));
        } catch (Exception e) { renameBatchAt(objects, index + 1, prefix, first); }
    }

    private void recordMeshyHistory(String action) {
        meshyEditHistory.add(action);
        if (meshyEditHistory.size() > 50) meshyEditHistory.remove(0);
        meshyLastOperation = action;
        persistMeshyHistory();
    }

    private void performMeshyEditAction(String action) {
        switch (action) {
            case "Undo": meshyUndo(); return;
            case "Redo": meshyRedo(); return;
            case "Undo History": showMeshyUndoHistory(); return;
            case "Repeat Last":
                if (meshyLastOperationCode < 0) {
                    android.widget.Toast.makeText(this, "No repeatable mesh operation is available yet.", android.widget.Toast.LENGTH_SHORT).show();
                } else runMeshyMeshOperation(meshyLastOperation, meshyLastOperationCode, meshyLastElementIndex, meshyLastAmount);
                return;
            case "Repeat History":
                if (meshyRepeatHistory.isEmpty()) {
                    android.widget.Toast.makeText(this, "No repeatable operations in Meshy3D history.", android.widget.Toast.LENGTH_SHORT).show();
                    return;
                }
                String[] repeats = meshyRepeatHistory.toArray(new String[0]);
                new AlertDialog.Builder(this).setTitle("Repeat History")
                        .setItems(repeats, (d, which) -> {
                            String[] parts = repeats[which].split("\\|", -1);
                            try { runMeshyMeshOperation(parts[0], Integer.parseInt(parts[1]),
                                    Integer.parseInt(parts[2]), Float.parseFloat(parts[3])); }
                            catch (Exception ignored) { android.widget.Toast.makeText(this, "Saved operation data is invalid.", android.widget.Toast.LENGTH_SHORT).show(); }
                        }).setNegativeButton("Close", null).show();
                return;
            case "Adjust Last Operation":
                if (meshyLastOperationCode < 0) {
                    android.widget.Toast.makeText(this, "There is no previous mesh operation to adjust.", android.widget.Toast.LENGTH_SHORT).show();
                } else showMeshyOperationDialog(meshyLastOperation, meshyLastOperationCode,
                        meshyLastElementIndex, meshyLastAmount, true);
                return;
            case "Menu Search": showMeshyMenuSearch(); return;
            case "Rename Active Item": renameActiveItem(); return;
            case "Batch Rename": showMeshyBatchRename(); return;
            case "Lock Object Modes":
                meshyObjectModesLocked = !meshyObjectModesLocked;
                if (manualSculptWorkspace != null) manualSculptWorkspace.setObjectModesLocked(meshyObjectModesLocked);
                getSharedPreferences("meshy3d_editor", MODE_PRIVATE).edit().putBoolean("lock_object_modes", meshyObjectModesLocked).apply();
                android.widget.Toast.makeText(this, meshyObjectModesLocked ? "Meshy3D mode switching is locked." : "Meshy3D mode switching is unlocked.", android.widget.Toast.LENGTH_SHORT).show();
                return;
            case "Preferences": showMeshyPreferences(); return;
            default: break;
        }
        final int operation;
        switch (action) {
            case "Extrude": operation = 0; break;
            case "Inset": operation = 1; break;
            case "Bevel": operation = 2; break;
            case "Loop Cut": operation = 3; break;
            case "Subdivide": operation = 4; break;
            case "Merge by Distance": operation = 5; break;
            case "Knife Split": operation = 6; break;
            default: return;
        }
        showMeshyOperationDialog(action, operation, 0, operation == 4 ? 1.0f : 0.1f, false);
    }

    private void showMeshyOperationDialog(String action, int operation, int initialElement,
                                         float initialAmount, boolean adjusting) {
        if (manualSculptWorkspace == null) {
            android.widget.Toast.makeText(this, "Open the Meshy3D workspace first.", android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        form.setPadding(pad, pad, pad, 0);
        TextView label = new TextView(this);
        String parameter = "Operation amount";
        if ("Extrude".equals(action)) parameter = "Extrusion distance";
        else if ("Inset".equals(action)) parameter = "Inset thickness";
        else if ("Bevel".equals(action)) parameter = "Bevel width";
        else if ("Loop Cut".equals(action)) parameter = "Loop position (0–1)";
        else if ("Merge by Distance".equals(action)) parameter = "Merge threshold";
        label.setText(parameter);
        form.addView(label);
        EditText amount = new EditText(this);
        amount.setSingleLine(true);
        amount.setInputType(android.text.InputType.TYPE_CLASS_NUMBER
                | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
                | android.text.InputType.TYPE_NUMBER_FLAG_SIGNED);
        amount.setHint(parameter);
        amount.setText(String.valueOf(initialAmount));
        form.addView(amount);
        new AlertDialog.Builder(this).setTitle((adjusting ? "Adjust Last Operation: " : "") + action)
                .setMessage("This operation edits the active Meshy3D mesh document.")
                .setView(form).setNegativeButton("Cancel", null)
                .setPositiveButton(adjusting ? "Apply Adjustment" : "Apply", (dialog, which) -> {
                    final float value;
                    try {
                        value = Float.parseFloat(amount.getText().toString().trim());
                        if (!Float.isFinite(value)) throw new NumberFormatException();
                    } catch (Exception error) {
                        android.widget.Toast.makeText(this, "Enter a valid operation amount.", android.widget.Toast.LENGTH_LONG).show();
                        return;
                    }
                    Runnable apply = () -> {
                        if (adjusting && !manualSculptWorkspace.undoMeshEdit()) {
                            android.widget.Toast.makeText(this, "The original Meshy3D operation checkpoint is unavailable.", android.widget.Toast.LENGTH_LONG).show();
                            return;
                        }
                        runMeshyMeshOperation(action, operation, 0, value);
                    };
                    boolean confirm = getSharedPreferences("meshy3d_editor", MODE_PRIVATE).getBoolean("confirm_ops", true);
                    if (confirm) new AlertDialog.Builder(this).setTitle("Confirm " + (adjusting ? "adjustment" : action))
                            .setMessage(adjusting
                                    ? "Restore the previous Meshy3D checkpoint, then apply the updated settings?"
                                    : "Apply this operation to the active Meshy3D mesh? An undo checkpoint will be created first.")
                            .setNegativeButton("Cancel", null).setPositiveButton("Apply", (d, w) -> apply.run()).show();
                    else apply.run();
                }).show();
    }

    private void runMeshyMeshOperation(String action, int operation, int elementIndex, float value) {
        if (manualSculptWorkspace == null) {
            android.widget.Toast.makeText(this, "Open the Meshy3D workspace first.", android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            boolean applied = manualSculptWorkspace.executeEditMenuMeshOperation(operation, value);
            if (!applied) {
                android.widget.Toast.makeText(this,
                        "Switch to Edit Mode and select mesh elements before running " + action + ".",
                        android.widget.Toast.LENGTH_LONG).show();
                return;
            }
            meshyLastOperation = action;
            meshyLastOperationCode = operation;
            meshyLastElementIndex = 0;
            meshyLastAmount = value;
            String record = action + "|" + operation + "|0|" + value;
            meshyRepeatHistory.add(record);
            if (meshyRepeatHistory.size() > 50) meshyRepeatHistory.remove(0);
            recordMeshyHistory(action);
        } catch (Exception error) {
            android.widget.Toast.makeText(this, action + " failed: " + error.getMessage(), android.widget.Toast.LENGTH_LONG).show();
        }
    }

    private void openNovaDocumentPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/octet-stream", "application/x-nova-project"});
        startActivityForResult(intent, REQUEST_OPEN_NOVA);
    }

    private void saveDocumentPicker(int requestCode, String filename) {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/octet-stream");
        intent.putExtra(Intent.EXTRA_TITLE, filename);
        startActivityForResult(intent, requestCode);
    }

    private void recoverAutosave() {
        File autosave = activeProjectScene == null ? null
                : new File(activeProjectScene.getParentFile(), "Scene.recovery.nova");
        if (autosave == null || !autosave.isFile()) {
            android.widget.Toast.makeText(this, "No recovery file is available yet.", android.widget.Toast.LENGTH_LONG).show();
            return;
        }
        engineSurface.loadSceneAsync(autosave.getAbsolutePath(), ok ->
                android.widget.Toast.makeText(this, ok ? "Recovered autosaved scene." : "Recovery file could not be opened.",
                        android.widget.Toast.LENGTH_LONG).show());
    }

    private void saveIncrementalScene() {
        File source = sceneFile();
        File directory = source.getParentFile();
        if (directory == null) directory = getFilesDir();
        int version = 1;
        File target;
        do {
            target = new File(directory, String.format(java.util.Locale.US, "Scene_%03d.nova", version++));
        } while (target.exists() && version < 10000);
        boolean saved = engineSurface.saveScene(target.getAbsolutePath());
        android.widget.Toast.makeText(this, saved ? "Saved version: " + target.getName() : "Incremental save failed.",
                android.widget.Toast.LENGTH_LONG).show();
    }

    private void showSceneDataPreview() {
        StringBuilder preview = new StringBuilder();
        preview.append("Document: ").append(activeMeshyDocument == null ? "Unsaved Meshy3D session"
                : activeMeshyDocument.getName()).append("\n");
        if (activeMeshyDocument != null && activeMeshyDocument.isFile()) {
            preview.append("File size: ").append(activeMeshyDocument.length()).append(" bytes\n")
                    .append("Last saved: ").append(new java.text.SimpleDateFormat(
                            "yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
                            .format(new java.util.Date(activeMeshyDocument.lastModified()))).append("\n");
        }
        preview.append("Format: Meshy3D document\n\n");
        if (manualSculptWorkspace != null) preview.append(manualSculptWorkspace.getDocumentDataPreview());
        else preview.append("The Meshy3D workspace is closed.");
        TextView content = new TextView(this);
        content.setText(preview.toString());
        content.setTextColor(0xFFE4EAF0);
        content.setTextSize(12);
        content.setTextIsSelectable(true);
        content.setPadding(dp(16), dp(12), dp(16), dp(12));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(content);
        new AlertDialog.Builder(this).setTitle("Meshy3D Data Preview")
                .setView(scroll).setPositiveButton("Close", null).show();
    }

    private void cleanMeshyTemporaryFiles() {
        File[] files = getCacheDir().listFiles();
        int removed = 0;
        if (files != null) for (File file : files) {
            String name = file.getName();
            if ((name.startsWith("nova_import_") || name.startsWith("meshy_export_"))
                    && file.isFile() && file.delete()) removed++;
        }
        android.widget.Toast.makeText(this, "Cleaned " + removed + " temporary import/export file(s).",
                android.widget.Toast.LENGTH_LONG).show();
    }

    private File copyUriToPrivateFile(Uri uri, String prefix, String suffix) throws Exception {
        File directory = new File(getFilesDir(), "ImportedProjects");
        if (!directory.exists() && !directory.mkdirs()) throw new IllegalStateException("Cannot create project storage.");
        File target = new File(directory, prefix + System.currentTimeMillis() + suffix);
        try (InputStream input = getContentResolver().openInputStream(uri);
             FileOutputStream output = new FileOutputStream(target)) {
            if (input == null) throw new IllegalStateException("Cannot read selected document.");
            byte[] buffer = new byte[8192];
            int count;
            long total = 0;
            while ((count = input.read(buffer)) != -1) {
                total += count;
                if (total > 300L * 1024L * 1024L) throw new IllegalArgumentException("Project file exceeds 300 MB.");
                output.write(buffer, 0, count);
            }
        }
        return target;
    }

    private void copyPrivateFileToUri(File source, Uri uri) throws Exception {
        try (java.io.OutputStream output = getContentResolver().openOutputStream(uri, "wt");
             java.io.InputStream input = new java.io.FileInputStream(source)) {
            if (output == null) throw new IllegalStateException("Cannot write selected document.");
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            output.flush();
        }
    }

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
        if ((requestCode == REQUEST_EXPORT_MESHY_OBJ || requestCode == REQUEST_EXPORT_MESHY_STL)
                && resultCode != RESULT_OK) pendingMeshyGeometryExport = null;
        if (requestCode == REQUEST_EXPORT_MESHY_GLB && resultCode != RESULT_OK) pendingMeshyGlbExport = null;
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (requestCode == REQUEST_SAVE_MESHY_SYSTEM_INFO) {
            String info = pendingMeshySystemInfo;
            pendingMeshySystemInfo = null;
            if (info == null) return;
            try (java.io.OutputStream output = getContentResolver().openOutputStream(uri)) {
                if (output == null) throw new IllegalStateException("Could not open the selected save location.");
                output.write(info.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                output.flush();
                android.widget.Toast.makeText(this, "Meshy3D system information saved.", android.widget.Toast.LENGTH_LONG).show();
            } catch (Exception error) {
                android.widget.Toast.makeText(this, "System information save failed: " + error.getMessage(), android.widget.Toast.LENGTH_LONG).show();
            }
            return;
        }
        if (requestCode == REQUEST_SAVE_MESHY_RENDER) {
            android.graphics.Bitmap bitmap = pendingMeshyImage;
            pendingMeshyImage = null;
            if (bitmap == null) return;
            try (java.io.OutputStream output = getContentResolver().openOutputStream(uri)) {
                if (output == null) throw new IllegalStateException("Could not open the selected save location.");
                if (!bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output)) {
                    throw new IllegalStateException("PNG encoding failed.");
                }
                output.flush();
                android.widget.Toast.makeText(this, "Meshy3D PNG saved.", android.widget.Toast.LENGTH_LONG).show();
            } catch (Exception error) {
                android.widget.Toast.makeText(this, "PNG save failed: " + error.getMessage(), android.widget.Toast.LENGTH_LONG).show();
            }
            return;
        }
        if (requestCode == REQUEST_EXPORT_MESHY_GLB) {
            byte[] glb = pendingMeshyGlbExport;
            pendingMeshyGlbExport = null;
            if (glb == null) return;
            new Thread(() -> {
                try (java.io.OutputStream output = getContentResolver().openOutputStream(uri)) {
                    if (output == null) throw new IllegalStateException("Could not open the selected GLB destination.");
                    output.write(glb);
                    output.flush();
                    runOnUiThread(() -> android.widget.Toast.makeText(this,
                            "GLB model exported. You can now import it into Novaingine.", android.widget.Toast.LENGTH_LONG).show());
                } catch (Exception error) {
                    runOnUiThread(() -> android.widget.Toast.makeText(this,
                            "GLB export failed: " + error.getMessage(), android.widget.Toast.LENGTH_LONG).show());
                }
            }, "Meshy3D-Write-GLB").start();
            return;
        }
        if (requestCode == REQUEST_EXPORT_MESHY_DOCUMENT) {
            String json = pendingMeshyExportJson;
            pendingMeshyExportJson = null;
            if (json == null) return;
            try (java.io.OutputStream output = getContentResolver().openOutputStream(uri)) {
                if (output == null) throw new IllegalStateException("Could not open the selected export location.");
                output.write(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                output.flush();
                android.widget.Toast.makeText(this, "Meshy3D document exported.", android.widget.Toast.LENGTH_LONG).show();
            } catch (Exception error) {
                android.widget.Toast.makeText(this, "Meshy3D export failed: " + error.getMessage(), android.widget.Toast.LENGTH_LONG).show();
            }
            return;
        }
        if (requestCode == REQUEST_IMPORT_MESHY_DOCUMENT) {
            new Thread(() -> {
                try (InputStream input = getContentResolver().openInputStream(uri)) {
                    if (input == null) throw new IllegalStateException("Could not open the selected document.");
                    java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
                    byte[] buffer = new byte[8192];
                    int count;
                    long total = 0;
                    while ((count = input.read(buffer)) != -1) {
                        total += count;
                        if (total > 64L * 1024L * 1024L) throw new IllegalArgumentException("Meshy3D document exceeds 64 MB.");
                        bytes.write(buffer, 0, count);
                    }
                    org.json.JSONObject document = new org.json.JSONObject(
                            new String(bytes.toByteArray(), java.nio.charset.StandardCharsets.UTF_8));
                    runOnUiThread(() -> {
                        if (manualSculptWorkspace == null) {
                            android.widget.Toast.makeText(this, "Open Meshy3D before importing a document.", android.widget.Toast.LENGTH_SHORT).show();
                            return;
                        }
                        manualSculptWorkspace.checkpointMeshEdit();
                        boolean ok = manualSculptWorkspace.importMeshyDocumentState(document);
                        android.widget.Toast.makeText(this, ok
                                ? "Meshy3D document state imported. Save to keep the change."
                                : "Import rejected: unsupported Meshy3D document format.",
                                android.widget.Toast.LENGTH_LONG).show();
                    });
                } catch (Exception error) {
                    runOnUiThread(() -> android.widget.Toast.makeText(this,
                            "Meshy3D import failed: " + error.getMessage(), android.widget.Toast.LENGTH_LONG).show());
                }
            }, "Meshy-Import-Document").start();
            return;
        }
        if (requestCode == REQUEST_EXPORT_MESHY_OBJ || requestCode == REQUEST_EXPORT_MESHY_STL) {
            String content = pendingMeshyGeometryExport;
            pendingMeshyGeometryExport = null;
            if (content == null) return;
            new Thread(() -> {
                try (java.io.OutputStream output = getContentResolver().openOutputStream(uri)) {
                    if (output == null) throw new IllegalStateException("Could not open the selected export destination.");
                    output.write(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    output.flush();
                    runOnUiThread(() -> android.widget.Toast.makeText(this,
                            requestCode == REQUEST_EXPORT_MESHY_OBJ ? "Wavefront OBJ exported." : "ASCII STL exported.",
                            android.widget.Toast.LENGTH_LONG).show());
                } catch (Exception error) {
                    runOnUiThread(() -> android.widget.Toast.makeText(this,
                            "Meshy3D geometry export failed: " + error.getMessage(), android.widget.Toast.LENGTH_LONG).show());
                }
            }, "Meshy3D-Geometry-Export").start();
            return;
        }
        if (requestCode == REQUEST_IMPORT_MESHY_GLB) {
            new Thread(() -> {
                try (InputStream input = getContentResolver().openInputStream(uri)) {
                    if (input == null) throw new IllegalStateException("Could not open the selected GLB file.");
                    java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
                    byte[] buffer = new byte[8192];
                    int read;
                    long total = 0;
                    while ((read = input.read(buffer)) != -1) {
                        total += read;
                        if (total > 100L * 1024L * 1024L)
                            throw new IllegalArgumentException("GLB model exceeds the 100 MB mobile import limit.");
                        bytes.write(buffer, 0, read);
                    }
                    java.util.List<Meshy3DGlbImporter.ImportedMesh> imported =
                            Meshy3DGlbImporter.parse(bytes.toByteArray());
                    runOnUiThread(() -> {
                        if (manualSculptWorkspace == null) {
                            android.widget.Toast.makeText(this, "Meshy3D was closed before the import completed.",
                                    android.widget.Toast.LENGTH_LONG).show();
                            return;
                        }
                        try {
                            int count = manualSculptWorkspace.importGlbIntoMeshy3D(imported);
                            android.widget.Toast.makeText(this,
                                    "Imported " + count + " editable mesh object(s) into Meshy3D.",
                                    android.widget.Toast.LENGTH_LONG).show();
                        } catch (Exception error) {
                            android.widget.Toast.makeText(this, "Meshy3D GLB import failed: " + error.getMessage(),
                                    android.widget.Toast.LENGTH_LONG).show();
                        }
                    });
                } catch (Exception error) {
                    runOnUiThread(() -> android.widget.Toast.makeText(this,
                            "GLB import failed: " + error.getMessage(), android.widget.Toast.LENGTH_LONG).show());
                }
            }, "Meshy3D-GLB-Import").start();
            return;
        }
        if (requestCode == REQUEST_OPEN_NOVA) {
            new Thread(() -> {
                try {
                    File project = copyUriToPrivateFile(uri, "opened_", ".nova");
                    runOnUiThread(() -> {
                        activeProjectDirectory = project.getParentFile();
                        activeProjectScene = project;
                        engineSurface.loadSceneAsync(project.getAbsolutePath(), ok -> {
                            if (ok) enterProject("Opened " + project.getName());
                            else android.widget.Toast.makeText(this, "Could not open this .nova project.", android.widget.Toast.LENGTH_LONG).show();
                        });
                    });
                } catch (Exception error) {
                    runOnUiThread(() -> android.widget.Toast.makeText(this, "Open failed: " + error.getMessage(), android.widget.Toast.LENGTH_LONG).show());
                }
            }, "Meshy-Open-Project").start();
            return;
        }
        if (requestCode == REQUEST_SAVE_NOVA || requestCode == REQUEST_SAVE_COPY_NOVA || requestCode == REQUEST_EXPORT_NOVA) {
            File temp = new File(getCacheDir(), "meshy_export_" + System.currentTimeMillis() + ".nova");
            boolean saved = engineSurface.saveScene(temp.getAbsolutePath());
            if (!saved) {
                android.widget.Toast.makeText(this, "Could not serialize the current scene.", android.widget.Toast.LENGTH_LONG).show();
                return;
            }
            new Thread(() -> {
                try {
                    copyPrivateFileToUri(temp, uri);
                    runOnUiThread(() -> {
                        if (requestCode == REQUEST_SAVE_NOVA) {
                            File local = new File(new File(getFilesDir(), "ImportedProjects"), "SavedAs_" + System.currentTimeMillis() + ".nova");
                            try {
                                java.nio.file.Files.copy(temp.toPath(), local.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                                activeProjectDirectory = local.getParentFile();
                                activeProjectScene = local;
                            } catch (Exception ignored) {}
                        }
                        android.widget.Toast.makeText(this, requestCode == REQUEST_SAVE_COPY_NOVA
                                ? "Project copy exported." : requestCode == REQUEST_EXPORT_NOVA
                                ? "Project exported." : "Project saved to selected location.",
                                android.widget.Toast.LENGTH_LONG).show();
                    });
                } catch (Exception error) {
                    runOnUiThread(() -> android.widget.Toast.makeText(this, "Save failed: " + error.getMessage(), android.widget.Toast.LENGTH_LONG).show());
                } finally {
                    temp.delete();
                }
            }, "Meshy-Save-Project").start();
            return;
        }
        if (requestCode != REQUEST_IMPORT_GLB) return;
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

        if (engineSurface != null) engineSurface.setVisibility(View.GONE);
        manualSculptWorkspace = new ManualSculptWorkspace(
                this, engineSurface, this::closeManualSculpting);
        manualSculptWorkspace.setObjectModesLocked(meshyObjectModesLocked);
        manualSculptWorkspace.setActiveWorkspaceIndex(getSharedPreferences("meshy3d_editor", MODE_PRIVATE)
                .getInt("saved_workspace_index", 0));
        rootLayout.addView(manualSculptWorkspace, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        projectHandler.removeCallbacks(autoSaveTask);
        projectHandler.postDelayed(autoSaveTask, 5000L);
    }

    private void closeManualSculpting() {
        if (manualSculptWorkspace == null) return;
        rootLayout.removeView(manualSculptWorkspace);
        manualSculptWorkspace = null;
        if (engineSurface != null) engineSurface.setVisibility(View.VISIBLE);
        if (editorToolbar != null) editorToolbar.setVisibility(View.VISIBLE);
        if (viewportInput != null) viewportInput.setVisibility(View.VISIBLE);
        if (statisticsText != null) statisticsText.setVisibility(View.VISIBLE);
        if (centeredStatisticsText != null) centeredStatisticsText.setVisibility(centeredStatisticsVisible ? View.VISIBLE : View.GONE);
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

    private void startNewMeshyProject() {
        if (manualSculptWorkspace != null) {
            confirmMeshySwitch("creating a new Meshy3D project", () -> {
                activeMeshyDocument = null;
                activeMeshyProjectDirectory = null;
                createNewMeshyProject();
            });
        } else {
            activeMeshyDocument = null;
            activeMeshyProjectDirectory = null;
            createNewMeshyProject();
        }
    }

    private void confirmMeshySwitch(String action, Runnable proceed) {
        if (manualSculptWorkspace == null) {
            if (proceed != null) proceed.run();
            return;
        }
        AlertDialog.Builder dialog = new AlertDialog.Builder(this)
                .setTitle("Save current Meshy3D document?")
                .setMessage("Save the current Meshy3D work before " + action + "?")
                .setNegativeButton("Discard", (d, w) -> {
                    closeManualSculpting();
                    if (proceed != null) proceed.run();
                })
                .setNeutralButton("Cancel", null);
        if (activeMeshyDocument != null) {
            dialog.setPositiveButton("Save", (d, w) -> {
                if (saveMeshyDocument()) {
                    closeManualSculpting();
                    if (proceed != null) proceed.run();
                }
            });
        } else {
            dialog.setMessage("This session has no saved Meshy3D document. Discard it and continue?");
        }
        dialog.show();
    }

    private void createNewMeshyProject() {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("My Meshy3D Project");
        new AlertDialog.Builder(this).setTitle("New Meshy3D Project")
                .setMessage("Create a separate Meshy3D document. This will not change your Novaingine scene.")
                .setView(input).setPositiveButton("CREATE", (d, w) -> {
                    String name = input.getText().toString().trim()
                            .replaceAll("[^A-Za-z0-9 _-]", "");
                    if (name.length() > 40) name = name.substring(0, 40).trim();
                    if (name.isEmpty()) name = "Untitled Meshy3D";
                    File root = new File(getFilesDir(), "Meshy3DProjects");
                    if (!root.exists() && !root.mkdirs()) {
                        android.widget.Toast.makeText(this, "Cannot create Meshy3D storage.", android.widget.Toast.LENGTH_LONG).show();
                        return;
                    }
                    File dir = new File(root, name);
                    if (dir.exists()) {
                        android.widget.Toast.makeText(this, "That Meshy3D project already exists.", android.widget.Toast.LENGTH_LONG).show();
                        return;
                    }
                    if (!dir.mkdirs()) {
                        android.widget.Toast.makeText(this, "Could not create Meshy3D project folder.", android.widget.Toast.LENGTH_LONG).show();
                        return;
                    }
                    activeMeshyProjectDirectory = dir;
                    activeMeshyDocument = new File(dir, name + ".meshy3d");
                    String document = "{\n  \"format\": \"Meshy3D\",\n  \"version\": 1,\n  \"objects\": [{\"name\": \"Cube\", \"type\": \"MESH_CUBE\", \"location\": [0,0,0], \"rotation\": [0,0,0], \"scale\": [1,1,1]}]\n}\n";
                    try (java.io.FileOutputStream out = new java.io.FileOutputStream(activeMeshyDocument)) {
                        out.write(document.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                        out.flush();
                        if (engineSurface != null) engineSurface.setVisibility(View.GONE);
                        if (editorToolbar != null) editorToolbar.setVisibility(View.GONE);
                        if (viewportInput != null) viewportInput.setVisibility(View.GONE);
                        if (statisticsText != null) statisticsText.setVisibility(View.GONE);
                        if (centeredStatisticsText != null) centeredStatisticsText.setVisibility(View.GONE);
                        if (editorPanels != null) editorPanels.setVisibility(View.GONE);
                        openManualSculpting();
                         File startupTemplate = new File(getFilesDir(), "meshy3d_startup_template.json");
                         if (startupTemplate.isFile()) loadMeshyDocumentIntoWorkspace(startupTemplate);
                         saveMeshyDocument();
                        android.widget.Toast.makeText(this, "Created independent Meshy3D project: " + name, android.widget.Toast.LENGTH_SHORT).show();
                    } catch (Exception e) {
                        android.widget.Toast.makeText(this, "Could not create Meshy3D document: " + e.getMessage(), android.widget.Toast.LENGTH_LONG).show();
                    }
                }).setNegativeButton("CANCEL", null).show();
    }

    private void chooseMeshyProject() {
        File root = new File(getFilesDir(), "Meshy3DProjects");
        File[] dirs = root.listFiles();
        java.util.ArrayList<File> docs = new java.util.ArrayList<>();
        if (dirs != null) for (File dir : dirs) if (dir.isDirectory()) {
            File[] files = dir.listFiles((d, name) -> name.endsWith(".meshy3d"));
            if (files != null) for (File file : files) docs.add(file);
        }
        if (docs.isEmpty()) {
            new AlertDialog.Builder(this).setTitle("Open Meshy3D")
                    .setMessage("No Meshy3D documents found. Use File > New to create one.")
                    .setPositiveButton("OK", null).show();
            return;
        }
        java.util.Collections.sort(docs, (a,b) -> Long.compare(b.lastModified(), a.lastModified()));
        String[] names = new String[docs.size()];
        for (int i=0;i<docs.size();i++) names[i]=docs.get(i).getParentFile().getName()+" / "+docs.get(i).getName();
        new AlertDialog.Builder(this).setTitle("Open Meshy3D Document")
                .setItems(names, (dialog, which) -> openMeshyDocument(docs.get(which)))
                .setNegativeButton("Cancel", null).show();
    }

    private void recordRecentMeshyDocument(File file) {
        if (file == null || !file.isFile()) return;
        android.content.SharedPreferences prefs = getSharedPreferences("meshy3d_editor", MODE_PRIVATE);
        java.util.LinkedHashSet<String> recent = new java.util.LinkedHashSet<>(
                prefs.getStringSet("recent_meshy_documents", new java.util.LinkedHashSet<>()));
        String path = file.getAbsolutePath();
        recent.remove(path);
        java.util.LinkedHashSet<String> ordered = new java.util.LinkedHashSet<>();
        ordered.add(path);
        for (String item : recent) { if (ordered.size() >= 12) break; ordered.add(item); }
        prefs.edit().putStringSet("recent_meshy_documents", ordered).apply();
    }

    private void chooseRecentMeshyProject() {
        android.content.SharedPreferences prefs = getSharedPreferences("meshy3d_editor", MODE_PRIVATE);
        java.util.ArrayList<File> files = new java.util.ArrayList<>();
        java.util.Set<String> saved = prefs.getStringSet("recent_meshy_documents", java.util.Collections.emptySet());
        if (saved != null) for (String path : saved) {
            if (path == null) continue;
            File file = new File(path);
            if (file.isFile() && file.getName().endsWith(".meshy3d")) files.add(file);
        }
        if (files.isEmpty()) { chooseMeshyProject(); return; }
        java.util.Collections.sort(files, (a,b) -> Long.compare(b.lastModified(), a.lastModified()));
        String[] labels = new String[files.size()];
        for (int i=0;i<labels.length;i++) labels[i]=files.get(i).getParentFile().getName()+" / "+files.get(i).getName();
        new AlertDialog.Builder(this).setTitle("Open Recent Meshy3D").setItems(labels,
                (d,w) -> openMeshyDocument(files.get(w)))
                .setNeutralButton("Clear Recent", (d,w) -> {
                    prefs.edit().remove("recent_meshy_documents").apply();
                    android.widget.Toast.makeText(this,"Recent Meshy3D list cleared.",android.widget.Toast.LENGTH_SHORT).show();
                }).setNegativeButton("Cancel",null).show();
    }

    private void openMeshyDocument(File document) {
        if (document == null || !document.isFile()) {
            android.widget.Toast.makeText(this,"The selected Meshy3D document is unavailable.",android.widget.Toast.LENGTH_LONG).show();
            return;
        }
        if (manualSculptWorkspace != null) {
            confirmMeshySwitch("opening another Meshy3D project", () -> openMeshyDocumentNow(document));
        } else openMeshyDocumentNow(document);
    }

    private void openMeshyDocumentNow(File document) {
        if (document == null || !document.isFile()) {
            android.widget.Toast.makeText(this,"The selected Meshy3D document is unavailable.",android.widget.Toast.LENGTH_LONG).show();
            return;
        }
        activeMeshyDocument=document;
        activeMeshyProjectDirectory=document.getParentFile();
        if(engineSurface!=null)engineSurface.setVisibility(View.GONE);
        if(editorToolbar!=null)editorToolbar.setVisibility(View.GONE);
        if(viewportInput!=null)viewportInput.setVisibility(View.GONE);
        if(statisticsText!=null)statisticsText.setVisibility(View.GONE);
        if(centeredStatisticsText!=null)centeredStatisticsText.setVisibility(View.GONE);
        if(editorPanels!=null)editorPanels.setVisibility(View.GONE);
        openManualSculpting();
        loadMeshyDocumentIntoWorkspace(document);
        recordRecentMeshyDocument(document);
        android.widget.Toast.makeText(this,"Opened Meshy3D: "+document.getName(),android.widget.Toast.LENGTH_SHORT).show();
    }

    private org.json.JSONObject buildMeshyDocumentSnapshot() throws Exception {
        if(manualSculptWorkspace==null)throw new IllegalStateException("Meshy3D workspace is not open.");
        org.json.JSONObject document=manualSculptWorkspace.exportMeshyDocumentState();
        document.put("projectName",activeMeshyProjectDirectory==null
                ?(activeMeshyDocument==null?"Untitled Meshy3D":activeMeshyDocument.getName())
                :activeMeshyProjectDirectory.getName());
        document.put("savedAt",System.currentTimeMillis());
        return document;
    }

    private void copyMeshyFile(File from,File to) throws Exception {
        File parent=to.getParentFile();
        if(parent!=null&&!parent.exists()&&!parent.mkdirs())throw new IllegalStateException("Cannot create destination folder.");
        try(java.io.FileInputStream input=new java.io.FileInputStream(from);
            FileOutputStream output=new FileOutputStream(to)){
            byte[] buffer=new byte[8192];int count;
            while((count=input.read(buffer))!=-1)output.write(buffer,0,count);
            output.flush();output.getFD().sync();
        }
    }

    private void writeMeshyDocumentFile(File target,boolean keepBackup) throws Exception {
        if(target==null)throw new IllegalArgumentException("No destination file selected.");
        File parent=target.getParentFile();
        if(parent!=null&&!parent.exists()&&!parent.mkdirs())throw new IllegalStateException("Cannot create destination folder.");
        if(keepBackup&&target.isFile())copyMeshyFile(target,new File(parent,target.getName()+".backup"));
        File temporary=new File(parent,target.getName()+".tmp");
        org.json.JSONObject document=buildMeshyDocumentSnapshot();
        try(FileOutputStream output=new FileOutputStream(temporary)){
            output.write((document.toString(2)+"\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            output.flush();output.getFD().sync();
        }
        if(target.exists()&&!target.delete()){
            temporary.delete();throw new IllegalStateException("Cannot replace existing document.");
        }
        if(!temporary.renameTo(target)){
            try{copyMeshyFile(temporary,target);}finally{temporary.delete();}
        }
    }

    private void saveMeshyDocumentAs() {
        if(manualSculptWorkspace==null){
            android.widget.Toast.makeText(this,"Open Meshy3D before using Save As.",android.widget.Toast.LENGTH_SHORT).show();return;
        }
        EditText input=new EditText(this);input.setSingleLine(true);input.setHint("Project name");
        if(activeMeshyProjectDirectory!=null)input.setText(activeMeshyProjectDirectory.getName()+" Copy");
        new AlertDialog.Builder(this).setTitle("Save Meshy3D As")
                .setMessage("Create a separate project folder and save the current editor state.")
                .setView(input).setNegativeButton("Cancel",null)
                .setPositiveButton("Save",(d,w)->{
                    String name=input.getText().toString().trim().replaceAll("[^A-Za-z0-9 _-]","");
                    if(name.length()>40)name=name.substring(0,40).trim();
                    if(name.isEmpty()){android.widget.Toast.makeText(this,"Enter a project name.",android.widget.Toast.LENGTH_SHORT).show();return;}
                    File root=new File(getFilesDir(),"Meshy3DProjects");
                    File folder=new File(root,name);
                    if(folder.exists()){android.widget.Toast.makeText(this,"A project with that name already exists.",android.widget.Toast.LENGTH_LONG).show();return;}
                    if(!root.exists()&&!root.mkdirs()){android.widget.Toast.makeText(this,"Cannot create project storage.",android.widget.Toast.LENGTH_LONG).show();return;}
                    if(!folder.mkdirs()){android.widget.Toast.makeText(this,"Could not create project folder.",android.widget.Toast.LENGTH_LONG).show();return;}
                    File target=new File(folder,name+".meshy3d");
                    File previousDirectory=activeMeshyProjectDirectory;
                    File previousDocument=activeMeshyDocument;
                    try{
                        activeMeshyProjectDirectory=folder;activeMeshyDocument=target;
                        writeMeshyDocumentFile(target,false);
                        recordRecentMeshyDocument(target);
                        android.widget.Toast.makeText(this,"Saved new Meshy3D document: "+target.getName(),android.widget.Toast.LENGTH_LONG).show();
                    }catch(Exception error){
                        activeMeshyProjectDirectory=previousDirectory;activeMeshyDocument=previousDocument;
                        target.delete();folder.delete();
                        android.widget.Toast.makeText(this,"Save As failed: "+error.getMessage(),android.widget.Toast.LENGTH_LONG).show();
                    }
                }).show();
    }

    private void saveMeshyDocumentCopy() {
        if(activeMeshyDocument==null||manualSculptWorkspace==null){
            android.widget.Toast.makeText(this,"Create or open a Meshy3D project first.",android.widget.Toast.LENGTH_SHORT).show();return;
        }
        String base=activeMeshyDocument.getName().replaceFirst("\\.meshy3d$","");
        File target=new File(activeMeshyDocument.getParentFile(),base+"_Copy.meshy3d");
        int suffix=1;
        while(target.exists())target=new File(activeMeshyDocument.getParentFile(),base+"_Copy_"+
                String.format(java.util.Locale.US,"%03d",suffix++)+".meshy3d");
        try{
            writeMeshyDocumentFile(target,false);
            android.widget.Toast.makeText(this,"Current editor state copied to "+target.getName(),android.widget.Toast.LENGTH_LONG).show();
        }catch(Exception error){
            android.widget.Toast.makeText(this,"Save Copy failed: "+error.getMessage(),android.widget.Toast.LENGTH_LONG).show();
        }
    }

    private void recoverMeshyDocument() {
        if(activeMeshyDocument==null||manualSculptWorkspace==null){
            android.widget.Toast.makeText(this,"Open a Meshy3D project before recovering it.",android.widget.Toast.LENGTH_SHORT).show();return;
        }
        File backup=new File(activeMeshyDocument.getParentFile(),activeMeshyDocument.getName()+".backup");
        if(!backup.isFile()){
            android.widget.Toast.makeText(this,"No previous-save backup exists yet.",android.widget.Toast.LENGTH_LONG).show();return;
        }
        new AlertDialog.Builder(this).setTitle("Recover Previous Save?")
                .setMessage("The current document will be preserved before the previous saved version is restored.")
                .setNegativeButton("Cancel",null).setPositiveButton("Recover",(d,w)->{
                    try{
                        copyMeshyFile(activeMeshyDocument,new File(activeMeshyDocument.getParentFile(),activeMeshyDocument.getName()+".before-recovery"));
                        copyMeshyFile(backup,activeMeshyDocument);
                        loadMeshyDocumentIntoWorkspace(activeMeshyDocument);
                        android.widget.Toast.makeText(this,"Previous Meshy3D save recovered.",android.widget.Toast.LENGTH_LONG).show();
                    }catch(Exception error){
                        android.widget.Toast.makeText(this,"Recovery failed: "+error.getMessage(),android.widget.Toast.LENGTH_LONG).show();
                    }
                }).show();
    }

    private void clearRecentMeshyDocuments() {
        getSharedPreferences("meshy3d_editor", MODE_PRIVATE)
                .edit().remove("recent_meshy_documents").apply();
        android.widget.Toast.makeText(this, "Recent Meshy3D file list cleared.",
                android.widget.Toast.LENGTH_SHORT).show();
    }

    private void showMeshyDefaultsMenu() {
        new AlertDialog.Builder(this).setTitle("Meshy3D Defaults")
                .setItems(new String[]{"Save Startup File", "Load Factory Settings"}, (dialog, which) -> {
                    if (which == 0) saveMeshyStartupTemplate();
                    else restoreMeshyDefaults();
                }).setNegativeButton("Cancel", null).show();
    }

    private void saveMeshyStartupTemplate() {
        if (manualSculptWorkspace == null) {
            android.widget.Toast.makeText(this, "Open Meshy3D and configure the startup scene first.",
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        File template = new File(getFilesDir(), "meshy3d_startup_template.json");
        try {
            org.json.JSONObject document = buildMeshyDocumentSnapshot();
            try (FileOutputStream output = new FileOutputStream(template)) {
                output.write((document.toString(2) + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
                output.flush();
                output.getFD().sync();
            }
            android.widget.Toast.makeText(this,
                    "Current Meshy3D scene and workspace saved as the New-project startup template.",
                    android.widget.Toast.LENGTH_LONG).show();
        } catch (Exception error) {
            android.widget.Toast.makeText(this, "Startup template save failed: " + error.getMessage(),
                    android.widget.Toast.LENGTH_LONG).show();
        }
    }

    private void restoreMeshyDefaults() {
        new AlertDialog.Builder(this).setTitle("Restore Meshy3D Defaults?")
                .setMessage("Reset editor preferences, workspace selection, fullscreen and mode locking. Project objects and mesh data will be preserved.")
                .setNegativeButton("Cancel",null).setPositiveButton("Restore",(d,w)->{
                    getSharedPreferences("meshy3d_editor",MODE_PRIVATE).edit()
                            .remove("saved_workspace_index").remove("saved_workspace_fullscreen")
                            .remove("lock_object_modes").apply();
                    meshyObjectModesLocked=false;meshyStatusBarVisible=true;
                    if(manualSculptWorkspace!=null){
                        manualSculptWorkspace.setObjectModesLocked(false);
                        manualSculptWorkspace.setActiveWorkspaceIndex(0);
                        manualSculptWorkspace.setStatusBarVisible(true);
                    }
                    File startupTemplate = new File(getFilesDir(), "meshy3d_startup_template.json");
                    if (startupTemplate.isFile()) startupTemplate.delete();
                    meshyWindowFullscreen=true;applyMeshyWindowFullscreen();
                    android.widget.Toast.makeText(this,"Factory editor defaults restored; project data was preserved.",android.widget.Toast.LENGTH_LONG).show();
                }).show();
    }

    private void showMeshyExportMenu() {
        if (manualSculptWorkspace == null) {
            android.widget.Toast.makeText(this, "Open Meshy3D before exporting mesh geometry.",
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this).setTitle("Export Meshy3D")
                .setItems(new String[]{"Meshy3D document (.meshy3d)", "glTF Binary model (.glb)",
                        "Wavefront OBJ (.obj)", "STL mesh (.stl)"},
                        (dialog, which) -> {
                            if (which == 0) exportMeshyDocumentToPicker();
                            else if (which == 1) startMeshyGlbExport();
                            else startMeshyGeometryExport(which == 3);
                        }).setNegativeButton("Cancel", null).show();
    }

    private void startMeshyGlbExport() {
        if (manualSculptWorkspace == null) {
            android.widget.Toast.makeText(this, "Open Meshy3D before exporting a GLB model.",
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        final java.util.List<Meshy3DGeometryExporter.ObjectSnapshot> snapshot;
        try {
            snapshot = manualSculptWorkspace.snapshotGeometryForExport();
        } catch (Exception error) {
            android.widget.Toast.makeText(this, "Could not prepare GLB export: " + error.getMessage(),
                    android.widget.Toast.LENGTH_LONG).show();
            return;
        }
        android.widget.Toast.makeText(this, "Preparing GLB model…", android.widget.Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                byte[] glb = Meshy3DGlbExporter.export(snapshot);
                runOnUiThread(() -> {
                    pendingMeshyGlbExport = glb;
                    Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.setType("model/gltf-binary");
                    intent.putExtra(Intent.EXTRA_TITLE, "Meshy3D_Export.glb");
                    startActivityForResult(intent, REQUEST_EXPORT_MESHY_GLB);
                });
            } catch (Exception error) {
                runOnUiThread(() -> android.widget.Toast.makeText(this,
                        "GLB export failed: " + error.getMessage(), android.widget.Toast.LENGTH_LONG).show());
            }
        }, "Meshy3D-GLB-Export").start();
    }

    private void startMeshyGeometryExport(boolean stl) {
        if (manualSculptWorkspace == null) {
            android.widget.Toast.makeText(this, "Open Meshy3D before exporting geometry.",
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        final java.util.List<Meshy3DGeometryExporter.ObjectSnapshot> snapshot;
        try {
            snapshot = manualSculptWorkspace.snapshotGeometryForExport();
        } catch (Exception error) {
            android.widget.Toast.makeText(this, "Could not prepare geometry export: " + error.getMessage(),
                    android.widget.Toast.LENGTH_LONG).show();
            return;
        }
        android.widget.Toast.makeText(this, stl ? "Preparing STL export…" : "Preparing OBJ export…",
                android.widget.Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                String content = stl ? Meshy3DGeometryExporter.exportAsciiStl(snapshot)
                        : Meshy3DGeometryExporter.exportObj(snapshot);
                runOnUiThread(() -> {
                    pendingMeshyGeometryExport = content;
                    Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.setType("text/plain");
                    intent.putExtra(Intent.EXTRA_TITLE, stl ? "Meshy3D_Export.stl" : "Meshy3D_Export.obj");
                    startActivityForResult(intent, stl ? REQUEST_EXPORT_MESHY_STL : REQUEST_EXPORT_MESHY_OBJ);
                });
            } catch (Exception error) {
                runOnUiThread(() -> android.widget.Toast.makeText(this,
                        "Meshy3D geometry export failed: " + error.getMessage(), android.widget.Toast.LENGTH_LONG).show());
            }
        }, "Meshy3D-Prepare-Export").start();
    }

    private void exportMeshyDocumentToPicker() {
        try{
            pendingMeshyExportJson=buildMeshyDocumentSnapshot().toString(2)+"\n";
            Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT);intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("application/json");
            intent.putExtra(Intent.EXTRA_TITLE,activeMeshyDocument==null?"Meshy3D_Export.meshy3d":activeMeshyDocument.getName());
            startActivityForResult(intent,REQUEST_EXPORT_MESHY_DOCUMENT);
        }catch(Exception error){
            pendingMeshyExportJson=null;
            android.widget.Toast.makeText(this,"Could not prepare Meshy3D export: "+error.getMessage(),android.widget.Toast.LENGTH_LONG).show();
        }
    }

    private void showMeshyImportMenu() {
        if (manualSculptWorkspace == null) {
            android.widget.Toast.makeText(this, "Open Meshy3D before importing assets.", android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this).setTitle("Import into Meshy3D")
                .setItems(new String[]{"Meshy3D document (.meshy3d)", "glTF Binary model (.glb)"}, (dialog, which) -> {
                    if (which == 0) importMeshyDocumentFromPicker();
                    else importMeshyGlbFromPicker();
                }).setNegativeButton("Cancel", null).show();
    }

    private void importMeshyDocumentFromPicker() {
        if(manualSculptWorkspace==null){
            android.widget.Toast.makeText(this,"Open Meshy3D before importing a document.",android.widget.Toast.LENGTH_SHORT).show();return;
        }
        Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT);intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");intent.putExtra(Intent.EXTRA_MIME_TYPES,new String[]{"application/json","application/octet-stream"});
        startActivityForResult(intent,REQUEST_IMPORT_MESHY_DOCUMENT);
    }

    private void importMeshyGlbFromPicker() {
        if (manualSculptWorkspace == null) {
            android.widget.Toast.makeText(this, "Open Meshy3D before importing a GLB model.", android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"model/gltf-binary", "application/octet-stream"});
        startActivityForResult(intent, REQUEST_IMPORT_MESHY_GLB);
    }

    private void saveMeshyIncremental() {
        if(activeMeshyDocument==null||manualSculptWorkspace==null){saveMeshyDocument();return;}
        String base=activeMeshyDocument.getName().replaceFirst("\\.meshy3d$","");
        int version=1;File target;
        do{target=new File(activeMeshyDocument.getParentFile(),base+"_"+
                String.format(java.util.Locale.US,"%03d",version++)+".meshy3d");}
        while(target.exists()&&version<100000);
        try{
            writeMeshyDocumentFile(target,false);
            android.widget.Toast.makeText(this,"Current Meshy3D state saved as "+target.getName(),android.widget.Toast.LENGTH_LONG).show();
        }catch(Exception error){
            android.widget.Toast.makeText(this,"Incremental save failed: "+error.getMessage(),android.widget.Toast.LENGTH_LONG).show();
        }
    }

    private boolean saveMeshyDocument() {
        if(activeMeshyDocument==null || manualSculptWorkspace==null){
            android.widget.Toast.makeText(this,"Create or open a Meshy3D project first.",android.widget.Toast.LENGTH_SHORT).show();
            return false;
        }
        try{
            writeMeshyDocumentFile(activeMeshyDocument,true);
            recordRecentMeshyDocument(activeMeshyDocument);
            android.widget.Toast.makeText(this,"Meshy3D mesh and workspace saved; previous save backed up.",android.widget.Toast.LENGTH_SHORT).show();
            return true;
        }catch(Exception error){
            android.widget.Toast.makeText(this,"Meshy3D save failed: "+error.getMessage(),android.widget.Toast.LENGTH_LONG).show();
            return false;
        }
    }

    private File lastMeshyRenderFile() {
        File directory = activeMeshyProjectDirectory;
        if (directory == null) directory = new File(getFilesDir(), "Meshy3DProjects/RenderOutput");
        if (!directory.exists() && !directory.mkdirs()) throw new IllegalStateException("Cannot create render output folder.");
        return new File(directory, "LastRender.png");
    }

    private void renderMeshyImage() {
        if (manualSculptWorkspace == null) {
            android.widget.Toast.makeText(this, "Open a Meshy3D workspace before rendering.", android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            int sourceWidth = Math.max(1, manualSculptWorkspace.getWidth());
            int sourceHeight = Math.max(1, manualSculptWorkspace.getHeight());
            float scale = Math.min(2.0f, Math.min(1920.0f / sourceWidth, 1080.0f / sourceHeight));
            int width = Math.max(320, Math.round(sourceWidth * scale));
            int height = Math.max(240, Math.round(sourceHeight * scale));
            android.graphics.Bitmap bitmap = manualSculptWorkspace.renderFinalImage(width, height);
            if (bitmap == null) throw new IllegalStateException("The renderer returned no image.");
            File target = lastMeshyRenderFile();
            try (FileOutputStream output = new FileOutputStream(target)) {
                if (!bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output)) {
                    throw new IllegalStateException("PNG encoding failed.");
                }
                output.flush();
                output.getFD().sync();
            }
            showMeshyImagePreview(bitmap, "Meshy3D Render Result");
        } catch (Exception error) {
            android.widget.Toast.makeText(this, "Render failed: " + error.getMessage(), android.widget.Toast.LENGTH_LONG).show();
        }
    }

    private void viewLastMeshyRender() {
        try {
            File file = lastMeshyRenderFile();
            if (!file.isFile()) {
                android.widget.Toast.makeText(this, "No render exists yet. Choose Render > Render Image first.", android.widget.Toast.LENGTH_LONG).show();
                return;
            }
            android.graphics.Bitmap bitmap = android.graphics.BitmapFactory.decodeFile(file.getAbsolutePath());
            if (bitmap == null) throw new IllegalStateException("The last render could not be decoded.");
            showMeshyImagePreview(bitmap, "Last Meshy3D Render");
        } catch (Exception error) {
            android.widget.Toast.makeText(this, "Could not open render: " + error.getMessage(), android.widget.Toast.LENGTH_LONG).show();
        }
    }

    private void showMeshyImagePreview(android.graphics.Bitmap bitmap, String title) {
        if (bitmap == null) return;
        android.widget.ImageView image = new android.widget.ImageView(this);
        image.setImageBitmap(bitmap);
        image.setAdjustViewBounds(true);
        image.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(image, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        new AlertDialog.Builder(this).setTitle(title).setView(scroll)
                .setNeutralButton("Save PNG", (dialog, which) -> startMeshyImageSave(bitmap, "Meshy3D_" +
                        (title.toLowerCase(java.util.Locale.ROOT).contains("screenshot") ? "Screenshot" : "Render") + ".png"))
                .setPositiveButton("Close", null).show();
    }

    private void startMeshyImageSave(android.graphics.Bitmap bitmap, String filename) {
        pendingMeshyImage = bitmap;
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/png");
        intent.putExtra(Intent.EXTRA_TITLE, filename);
        startActivityForResult(intent, REQUEST_SAVE_MESHY_RENDER);
    }

    private void captureMeshyScreenshot(boolean editorOnly) {
        View target = editorOnly ? manualSculptWorkspace : rootLayout;
        if (target == null || target.getWidth() <= 0 || target.getHeight() <= 0) {
            android.widget.Toast.makeText(this, "The requested window or editor is not available for capture.", android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(
                    target.getWidth(), target.getHeight(), android.graphics.Bitmap.Config.ARGB_8888);
            target.draw(new android.graphics.Canvas(bitmap));
            showMeshyImagePreview(bitmap, editorOnly ? "Meshy3D Editor Screenshot" : "Meshy3D Window Screenshot");
        } catch (Exception error) {
            android.widget.Toast.makeText(this, "Screenshot failed: " + error.getMessage(), android.widget.Toast.LENGTH_LONG).show();
        }
    }

    private void loadMeshyDocumentIntoWorkspace(File documentFile) {
        if (documentFile == null || !documentFile.isFile() || manualSculptWorkspace == null) return;
        try {
            byte[] bytes;
            try (java.io.FileInputStream in = new java.io.FileInputStream(documentFile);
                 java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
                bytes = out.toByteArray();
            }
            org.json.JSONObject document = new org.json.JSONObject(
                    new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
            if (!manualSculptWorkspace.importMeshyDocumentState(document)) {
                android.widget.Toast.makeText(this,
                        "This document uses an unsupported or incomplete mesh format. The current mesh was not replaced.",
                        android.widget.Toast.LENGTH_LONG).show();
            }
        } catch (Exception error) {
            android.widget.Toast.makeText(this, "Could not load Meshy3D document: " + error.getMessage(),
                    android.widget.Toast.LENGTH_LONG).show();
        }
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
        if (manualSculptWorkspace != null && activeMeshyDocument != null) {
            try { writeMeshyDocumentFile(activeMeshyDocument, false); }
            catch (Exception error) { android.util.Log.e("Meshy3D", "Pause autosave failed", error); }
        }
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
        if (manualSculptWorkspace != null && activeMeshyDocument != null) {
            try { writeMeshyDocumentFile(activeMeshyDocument, false); }
            catch (Exception error) { android.util.Log.e("Meshy3D", "Destroy autosave failed", error); }
        }
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
