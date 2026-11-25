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
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
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
    // World environment values are mirrored to the native renderer.
    private final float[] worldEnvironment = {
            -0.45f, -0.80f, -0.35f,
            1.0f, 0.96f, 0.88f,
            3.0f, 1.0f, 1.0f, 0.45f,
            1.0f, 1.0f, 0.12f,
            1.0f, 0.55f, 0.75f, 0.002f,
            0.0f, 0.0f, 0.0f, 0.2f,
            0.70f, 0.76f, 0.82f,
            1.0f, 0.15f, 1.0f, 1.0f
    };

    private boolean viewportMaximized;
    private View toolsScrim;
    private LinearLayout toolsDrawer;
    private boolean toolsDrawerOpen;
    private boolean drawerPhysicsEnabled;
    private boolean selectedRigidBodyEnabled;
    private boolean terrainViewportSculpting;
    private boolean terrainViewportPainting;
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
        addDrawerItem("Import Heightmap PNG", "Replace terrain heights from a grayscale image", this::openTerrainHeightmapImport);
        addDrawerItem("Export Heightmap PNG", "Save the current terrain height data as a grayscale image", this::openTerrainHeightmapExport);
        addDrawerItem("Import 16-bit RAW Heightmap", "Import a 129 x 129 little-endian .r16 file", this::openTerrainHeightmap16Import);
        addDrawerItem("Import 16-bit PNG Heightmap", "Import a 129 x 129 16-bit grayscale PNG without reducing precision", this::openTerrainHeightmap16PngImport);
        addDrawerItem("Export 16-bit PNG Heightmap", "Export a 129 x 129 16-bit grayscale PNG", this::openTerrainHeightmap16PngExport);
        addDrawerItem("Export 16-bit RAW Heightmap", "Export full 16-bit terrain height precision", this::openTerrainHeightmap16Export);
        addDrawerItem("Manual Sculpting", "Open the landscape sculpt editor with brush browser, scene tools and viewport controls", this::showMeshSculptor);
        addDrawerItem("Foliage Painting", "Paint trees and bushes into the scene", this::showFoliagePainter);
        addDrawerItem("Import Blender Model (.glb)", "Import a Blender-exported glTF binary model", this::openGlbPicker);

        addDrawerSection("RENDERING");
        addDrawerItem("World Environment", "Directional light, sky light, atmosphere, clouds, height fog and post-processing", this::showWorldEnvironment);
        addDrawerItem("Global Illumination", "Indirect lighting controls", () -> showToolStatus("Global Illumination"));
        addDrawerItem("Reflections", "Environment and surface reflections", () -> showToolStatus("Reflections"));

        addDrawerSection("GAME SYSTEMS");
        addDrawerItem("Animation", "Keyframes and playback", () -> showToolStatus("Animation"));
        addDrawerItem("Selected Object Rigid Body", "Attach or remove a gravity body that collides with the sculpted landscape", this::showRigidBodySetup);
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

    private void applyWorldEnvironment() {
        if (engineSurface != null) {
            engineSurface.setWorldEnvironment(worldEnvironment);
        }
    }

    private void addEnvironmentToggle(LinearLayout panel, String title, int index) {
        CheckBox check = new CheckBox(this);
        check.setText(title);
        check.setTextColor(Color.WHITE);
        check.setChecked(worldEnvironment[index] >= 0.5f);
        panel.addView(check);
        check.setOnCheckedChangeListener((button, checked) -> {
            worldEnvironment[index] = checked ? 1.0f : 0.0f;
            applyWorldEnvironment();
        });
    }

    private void addEnvironmentSlider(LinearLayout panel, String title,
                                      float minimum, float maximum, float initial,
                                      java.util.function.Consumer<Float> changed) {
        TextView label = new TextView(this);
        label.setTextColor(0xFFE2E8F0);
        label.setTextSize(12);
        label.setText(title + ": " + String.format(java.util.Locale.US, "%.2f", initial));
        label.setPadding(0, dp(8), 0, dp(2));
        panel.addView(label);

        android.widget.SeekBar slider = new android.widget.SeekBar(this);
        slider.setMax(1000);
        slider.setProgress(Math.max(0, Math.min(1000,
                Math.round((initial - minimum) / Math.max(0.0001f, maximum - minimum) * 1000.0f))));
        panel.addView(slider);
        slider.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(android.widget.SeekBar bar, int progress, boolean fromUser) {
                float value = minimum + (maximum - minimum) * progress / 1000.0f;
                label.setText(title + ": " + String.format(java.util.Locale.US, "%.2f", value));
                if (fromUser) changed.accept(value);
            }
            @Override public void onStartTrackingTouch(android.widget.SeekBar bar) {}
            @Override public void onStopTrackingTouch(android.widget.SeekBar bar) {}
        });
    }

    private void showWorldEnvironment() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(18), dp(8), dp(18), dp(18));
        panel.setBackgroundColor(0xFF202630);
        scroll.addView(panel);

        TextView note = new TextView(this);
        note.setText("Mobile renderer environment controls. Changes update the viewport immediately.");
        note.setTextColor(0xFFB9C4D0);
        note.setTextSize(11);
        panel.addView(note);

        addDrawerSectionTo(panel, "DIRECTIONAL LIGHT");
        addEnvironmentToggle(panel, "Enable directional sun", 7);
        float azimuth = (float)Math.toDegrees(Math.atan2(-worldEnvironment[2], -worldEnvironment[0]));
        if (azimuth < 0) azimuth += 360.0f;
        float elevation = (float)Math.toDegrees(Math.asin(Math.max(-1.0f, Math.min(1.0f, -worldEnvironment[1]))));
        final float[] sunAngles = {azimuth, elevation};
        addEnvironmentSlider(panel, "Sun azimuth", 0.0f, 360.0f, azimuth, value -> {
            sunAngles[0] = value;
            updateEnvironmentSunDirection(sunAngles[0], sunAngles[1]);
        });
        addEnvironmentSlider(panel, "Sun elevation", -10.0f, 89.0f, elevation, value -> {
            sunAngles[1] = value;
            updateEnvironmentSunDirection(sunAngles[0], sunAngles[1]);
        });
        addEnvironmentSlider(panel, "Sun intensity", 0.0f, 10.0f, worldEnvironment[6], value -> {
            worldEnvironment[6] = value; applyWorldEnvironment();
        });
        addEnvironmentSlider(panel, "Sun red", 0.0f, 1.0f, worldEnvironment[3], value -> {
            worldEnvironment[3] = value; applyWorldEnvironment();
        });
        addEnvironmentSlider(panel, "Sun green", 0.0f, 1.0f, worldEnvironment[4], value -> {
            worldEnvironment[4] = value; applyWorldEnvironment();
        });
        addEnvironmentSlider(panel, "Sun blue", 0.0f, 1.0f, worldEnvironment[5], value -> {
            worldEnvironment[5] = value; applyWorldEnvironment();
        });

        addDrawerSectionTo(panel, "SKY LIGHT");
        addEnvironmentToggle(panel, "Enable sky light", 8);
        addEnvironmentSlider(panel, "Sky light intensity", 0.0f, 2.0f, worldEnvironment[9], value -> {
            worldEnvironment[9] = value; applyWorldEnvironment();
        });

        addDrawerSectionTo(panel, "SKY ATMOSPHERE");
        addEnvironmentToggle(panel, "Enable sky atmosphere", 10);
        addEnvironmentSlider(panel, "Rayleigh scattering", 0.0f, 3.0f, worldEnvironment[11], value -> {
            worldEnvironment[11] = value; applyWorldEnvironment();
        });
        addEnvironmentSlider(panel, "Mie scattering", 0.0f, 1.0f, worldEnvironment[12], value -> {
            worldEnvironment[12] = value; applyWorldEnvironment();
        });

        addDrawerSectionTo(panel, "CLOUD LAYER");
        addEnvironmentToggle(panel, "Enable procedural clouds", 13);
        addEnvironmentSlider(panel, "Cloud coverage", 0.0f, 1.0f, worldEnvironment[14], value -> {
            worldEnvironment[14] = value; applyWorldEnvironment();
        });
        addEnvironmentSlider(panel, "Cloud density", 0.0f, 1.0f, worldEnvironment[15], value -> {
            worldEnvironment[15] = value; applyWorldEnvironment();
        });
        addEnvironmentSlider(panel, "Cloud movement speed", 0.0f, 0.02f, worldEnvironment[16], value -> {
            worldEnvironment[16] = value; applyWorldEnvironment();
        });

        addDrawerSectionTo(panel, "EXPONENTIAL HEIGHT FOG");
        addEnvironmentToggle(panel, "Enable height fog", 17);
        addEnvironmentSlider(panel, "Fog density", 0.0f, 0.05f, worldEnvironment[18], value -> {
            worldEnvironment[18] = value; applyWorldEnvironment();
        });
        addEnvironmentSlider(panel, "Fog base height", -20.0f, 50.0f, worldEnvironment[19], value -> {
            worldEnvironment[19] = value; applyWorldEnvironment();
        });
        addEnvironmentSlider(panel, "Fog height falloff", 0.001f, 2.0f, worldEnvironment[20], value -> {
            worldEnvironment[20] = value; applyWorldEnvironment();
        });
        addEnvironmentSlider(panel, "Fog red", 0.0f, 1.0f, worldEnvironment[21], value -> {
            worldEnvironment[21] = value; applyWorldEnvironment();
        });
        addEnvironmentSlider(panel, "Fog green", 0.0f, 1.0f, worldEnvironment[22], value -> {
            worldEnvironment[22] = value; applyWorldEnvironment();
        });
        addEnvironmentSlider(panel, "Fog blue", 0.0f, 1.0f, worldEnvironment[23], value -> {
            worldEnvironment[23] = value; applyWorldEnvironment();
        });

        addDrawerSectionTo(panel, "POST PROCESSING");
        addEnvironmentToggle(panel, "Enable bloom", 24);
        addEnvironmentSlider(panel, "Bloom intensity", 0.0f, 1.5f, worldEnvironment[25], value -> {
            worldEnvironment[25] = value; applyWorldEnvironment();
        });
        addEnvironmentSlider(panel, "Color contrast", 0.0f, 2.0f, worldEnvironment[26], value -> {
            worldEnvironment[26] = value; applyWorldEnvironment();
        });
        addEnvironmentSlider(panel, "Color saturation", 0.0f, 2.0f, worldEnvironment[27], value -> {
            worldEnvironment[27] = value; applyWorldEnvironment();
        });

        applyWorldEnvironment();
        new AlertDialog.Builder(this).setTitle("WORLD ENVIRONMENT")
                .setView(scroll)
                .setPositiveButton("Done", (dialog, which) -> closeToolsDrawer())
                .setNegativeButton("Close", null)
                .show();
    }

    private void addDrawerSectionTo(LinearLayout panel, String title) {
        TextView section = new TextView(this);
        section.setText(title);
        section.setTextColor(0xFFFFB45C);
        section.setTextSize(11);
        section.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        section.setPadding(0, dp(14), 0, dp(4));
        panel.addView(section);
    }

    private void updateEnvironmentSunDirection(float azimuthDegrees, float elevationDegrees) {
        double azimuth = Math.toRadians(azimuthDegrees);
        double elevation = Math.toRadians(elevationDegrees);
        float horizontal = (float)Math.cos(elevation);
        worldEnvironment[0] = -horizontal * (float)Math.cos(azimuth);
        worldEnvironment[1] = -(float)Math.sin(elevation);
        worldEnvironment[2] = -horizontal * (float)Math.sin(azimuth);
        applyWorldEnvironment();
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
    private static final int REQUEST_IMPORT_HEIGHTMAP = 7413;
    private static final int REQUEST_EXPORT_HEIGHTMAP = 7414;
    private static final int REQUEST_IMPORT_HEIGHTMAP16 = 7415;
    private static final int REQUEST_IMPORT_HEIGHTMAP16_PNG = 7418;
    private static final int REQUEST_EXPORT_HEIGHTMAP16_PNG = 7419;
    private static final int REQUEST_IMPORT_WORLD_TILE_RAW16 = 7420;
    private int pendingWorldTileX = 1;
    private int pendingWorldTileZ = 0;
    private static final int REQUEST_EXPORT_HEIGHTMAP16 = 7416;
    private static final int REQUEST_IMPORT_TERRAIN_TEXTURE = 7417;
    private int pendingTerrainTextureLayer = 0;
    private int pendingTerrainTextureMapType = 0;

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

    private void openTerrainTexturePicker(int layer, int mapType) {
        pendingTerrainTextureLayer = Math.max(0, Math.min(3, layer));
        pendingTerrainTextureMapType = Math.max(0, Math.min(2, mapType));
        closeToolsDrawer();
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        startActivityForResult(intent, REQUEST_IMPORT_TERRAIN_TEXTURE);
    }

    private void openTerrainHeightmapImport() {
        closeToolsDrawer();
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        startActivityForResult(intent, REQUEST_IMPORT_HEIGHTMAP);
    }

    private void openTerrainHeightmap16Import() {
        closeToolsDrawer();
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        startActivityForResult(intent, REQUEST_IMPORT_HEIGHTMAP16);
    }

    private void openLandscapeWorldTilePicker(int tileX, int tileZ) {
        pendingWorldTileX = Math.max(-10000, Math.min(10000, tileX));
        pendingWorldTileZ = Math.max(-10000, Math.min(10000, tileZ));
        closeToolsDrawer();
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        startActivityForResult(intent, REQUEST_IMPORT_WORLD_TILE_RAW16);
    }

    private void openTerrainHeightmap16PngExport() {
        closeToolsDrawer();
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/png");
        intent.putExtra(Intent.EXTRA_TITLE, "Novaingine_Heightmap_129x129_16bit.png");
        startActivityForResult(intent, REQUEST_EXPORT_HEIGHTMAP16_PNG);
    }

    private void openTerrainHeightmap16PngImport() {
        closeToolsDrawer();
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/png");
        startActivityForResult(intent, REQUEST_IMPORT_HEIGHTMAP16_PNG);
    }

    private void openTerrainHeightmap16Export() {
        closeToolsDrawer();
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/octet-stream");
        intent.putExtra(Intent.EXTRA_TITLE, "Novaingine_Heightmap_129x129.r16");
        startActivityForResult(intent, REQUEST_EXPORT_HEIGHTMAP16);
    }

    private void openTerrainHeightmapExport() {
        closeToolsDrawer();
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/png");
        intent.putExtra(Intent.EXTRA_TITLE, "Novaingine_Heightmap.png");
        startActivityForResult(intent, REQUEST_EXPORT_HEIGHTMAP);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();

        if (requestCode == REQUEST_IMPORT_TERRAIN_TEXTURE) {
            new Thread(() -> {
                try (InputStream input = getContentResolver().openInputStream(uri);
                     java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream()) {
                    if (input == null) throw new IllegalStateException("Could not open texture image.");
                    byte[] buffer = new byte[8192]; int read;
                    while ((read = input.read(buffer)) != -1) {
                        if (bytes.size() + read > 64 * 1024 * 1024)
                            throw new IllegalArgumentException("Texture image exceeds 64 MB.");
                        bytes.write(buffer, 0, read);
                    }
                    byte[] encoded = bytes.toByteArray();
                    File assetFile = terrainTextureAssetFile(pendingTerrainTextureLayer, pendingTerrainTextureMapType);
                    if (assetFile != null) {
                        File parent = assetFile.getParentFile();
                        if (parent != null && !parent.exists() && !parent.mkdirs())
                            throw new IllegalStateException("Could not create project texture folder.");
                        try (java.io.FileOutputStream assetOutput = new java.io.FileOutputStream(assetFile)) {
                            assetOutput.write(encoded);
                        }
                    }
                    Bitmap decoded = BitmapFactory.decodeByteArray(encoded, 0, encoded.length);
                    if (decoded == null) throw new IllegalArgumentException("Unsupported image. Choose PNG, JPEG or WebP.");
                    int originalWidth = decoded.getWidth(), originalHeight = decoded.getHeight();
                    float scale = Math.min(1.0f, 1024.0f / Math.max(originalWidth, originalHeight));
                    int width = Math.max(1, Math.round(originalWidth * scale));
                    int height = Math.max(1, Math.round(originalHeight * scale));
                    Bitmap scaled = (width == originalWidth && height == originalHeight)
                            ? decoded : Bitmap.createScaledBitmap(decoded, width, height, true);
                    int[] pixels = new int[width * height];
                    scaled.getPixels(pixels, 0, width, 0, 0, width, height);
                    if (scaled != decoded) scaled.recycle();
                    decoded.recycle();
                    runOnUiThread(() -> {
                        if (engineSurface == null) return;
                        engineSurface.setTerrainTexture(pendingTerrainTextureLayer,
                                pendingTerrainTextureMapType, width, height, pixels, ok ->
                                android.widget.Toast.makeText(this, ok
                                        ? "Terrain texture imported and saved in project Assets/Terrain"
                                        : "Texture upload failed. Check image size and renderer state.",
                                        android.widget.Toast.LENGTH_LONG).show());
                    });
                } catch (Exception error) {
                    runOnUiThread(() -> android.widget.Toast.makeText(this,
                            "Texture import failed: " + error.getMessage(),
                            android.widget.Toast.LENGTH_LONG).show());
                }
            }, "Nova-Terrain-Texture-Import").start();
            return;
        }

        if (requestCode == REQUEST_IMPORT_WORLD_TILE_RAW16) {
            final int tileX = pendingWorldTileX, tileZ = pendingWorldTileZ;
            if (tileX == 0 && tileZ == 0) {
                android.widget.Toast.makeText(this, "Tile 0, 0 is the main landscape. Choose another tile coordinate.",
                        android.widget.Toast.LENGTH_LONG).show();
                return;
            }
            new Thread(() -> {
                try (InputStream input = getContentResolver().openInputStream(uri);
                     java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream()) {
                    if (input == null) throw new IllegalStateException("Could not open world tile heightmap.");
                    byte[] buffer = new byte[8192]; int read;
                    while ((read = input.read(buffer)) != -1) {
                        if (bytes.size() + read > 100000)
                            throw new IllegalArgumentException("World tile RAW16 file is too large.");
                        bytes.write(buffer, 0, read);
                    }
                    byte[] raw = bytes.toByteArray();
                    if (raw.length != 129 * 129 * 2)
                        throw new IllegalArgumentException("Expected 33,282 bytes: 129 x 129 unsigned 16-bit little-endian samples.");
                    float[] heights = new float[129 * 129];
                    for (int i = 0; i < heights.length; i++) {
                        int value = (raw[i * 2] & 0xFF) | ((raw[i * 2 + 1] & 0xFF) << 8);
                        heights[i] = -12.0f + (value / 65535.0f) * 36.0f;
                    }
                    File asset = landscapeWorldTileAssetFile(tileX, tileZ);
                    if (asset != null) {
                        File parent = asset.getParentFile();
                        if (parent != null && !parent.exists() && !parent.mkdirs())
                            throw new IllegalStateException("Could not create landscape tile asset folder.");
                        try (FileOutputStream output = new FileOutputStream(asset)) { output.write(raw); }
                    }
                    runOnUiThread(() -> engineSurface.createLandscapeWorldTile(tileX, tileZ, heights, ok ->
                            android.widget.Toast.makeText(this, ok
                                    ? "World tile " + tileX + ", " + tileZ + " imported and saved"
                                    : "Tile import failed. Check for duplicate coordinates and create the main landscape first.",
                                    android.widget.Toast.LENGTH_LONG).show()));
                } catch (Exception error) {
                    runOnUiThread(() -> android.widget.Toast.makeText(this,
                            "World tile import failed: " + error.getMessage(),
                            android.widget.Toast.LENGTH_LONG).show());
                }
            }, "Nova-World-Tile-Import").start();
            return;
        }

        if (requestCode == REQUEST_EXPORT_HEIGHTMAP16_PNG) {
            if (engineSurface == null) return;
            engineSurface.getTerrainHeights(heights -> {
                if (heights == null || heights.length != 129 * 129) {
                    android.widget.Toast.makeText(this, "Create a landscape before exporting.",
                            android.widget.Toast.LENGTH_LONG).show();
                    return;
                }
                new Thread(() -> {
                    try {
                        byte[] png = encodeTerrainHeightmap16Png(heights);
                        try (java.io.OutputStream output = getContentResolver().openOutputStream(uri)) {
                            if (output == null) throw new IllegalStateException("Could not write 16-bit PNG.");
                            output.write(png);
                        }
                        runOnUiThread(() -> android.widget.Toast.makeText(this,
                                "Exported 129 x 129 16-bit grayscale PNG.",
                                android.widget.Toast.LENGTH_LONG).show());
                    } catch (Exception error) {
                        runOnUiThread(() -> android.widget.Toast.makeText(this,
                                "16-bit PNG export failed: " + error.getMessage(),
                                android.widget.Toast.LENGTH_LONG).show());
                    }
                }, "Nova-Heightmap16-PNG-Export").start();
            });
            return;
        }

        if (requestCode == REQUEST_IMPORT_HEIGHTMAP16_PNG) {
            new Thread(() -> {
                try (InputStream input = getContentResolver().openInputStream(uri);
                     java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream()) {
                    if (input == null) throw new IllegalStateException("Could not open 16-bit PNG.");
                    byte[] buffer = new byte[8192]; int read;
                    while ((read = input.read(buffer)) != -1) {
                        if (bytes.size() + read > 16 * 1024 * 1024)
                            throw new IllegalArgumentException("PNG file exceeds 16 MB.");
                        bytes.write(buffer, 0, read);
                    }
                    byte[] png = bytes.toByteArray();
                    runOnUiThread(() -> {
                        if (engineSurface == null) return;
                        engineSurface.setTerrainHeightmapPng16(png, ok ->
                                android.widget.Toast.makeText(this, ok
                                        ? "16-bit grayscale PNG imported at full precision"
                                        : "Unsupported PNG. Use a 129 x 129, 16-bit grayscale, non-interlaced PNG.",
                                        android.widget.Toast.LENGTH_LONG).show());
                    });
                } catch (Exception error) {
                    runOnUiThread(() -> android.widget.Toast.makeText(this,
                            "16-bit PNG import failed: " + error.getMessage(),
                            android.widget.Toast.LENGTH_LONG).show());
                }
            }, "Nova-Heightmap16-PNG-Import").start();
            return;
        }

        if (requestCode == REQUEST_IMPORT_HEIGHTMAP16) {
            new Thread(() -> {
                try (InputStream input = getContentResolver().openInputStream(uri);
                     java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream()) {
                    if (input == null) throw new IllegalStateException("Could not open RAW16 file.");
                    byte[] buffer = new byte[8192]; int read;
                    while ((read = input.read(buffer)) != -1) {
                        if (bytes.size() + read > 100000) throw new IllegalArgumentException("Heightmap file is too large.");
                        bytes.write(buffer, 0, read);
                    }
                    byte[] raw = bytes.toByteArray();
                    if (raw.length != 129 * 129 * 2) throw new IllegalArgumentException(
                            "Expected exactly 33,282 bytes: 129 x 129 unsigned 16-bit little-endian samples.");
                    float[] heights = new float[129 * 129];
                    for (int i = 0; i < heights.length; i++) {
                        int value = (raw[i * 2] & 0xFF) | ((raw[i * 2 + 1] & 0xFF) << 8);
                        heights[i] = -12.0f + (value / 65535.0f) * 36.0f;
                    }
                    runOnUiThread(() -> engineSurface.setTerrainHeights(heights, ok ->
                            android.widget.Toast.makeText(this, ok ? "16-bit RAW heightmap imported" :
                                    "Create a landscape first", android.widget.Toast.LENGTH_LONG).show()));
                } catch (Exception error) {
                    runOnUiThread(() -> android.widget.Toast.makeText(this,
                            "RAW16 import failed: " + error.getMessage(), android.widget.Toast.LENGTH_LONG).show());
                }
            }, "Nova-Heightmap16-Import").start();
            return;
        }

        if (requestCode == REQUEST_EXPORT_HEIGHTMAP16) {
            if (engineSurface == null) return;
            engineSurface.getTerrainHeights(heights -> {
                if (heights == null || heights.length != 129 * 129) {
                    android.widget.Toast.makeText(this, "Create a landscape before exporting.",
                            android.widget.Toast.LENGTH_LONG).show(); return;
                }
                new Thread(() -> {
                    try (java.io.OutputStream output = getContentResolver().openOutputStream(uri)) {
                        if (output == null) throw new IllegalStateException("Could not write RAW16 file.");
                        byte[] raw = new byte[heights.length * 2];
                        for (int i = 0; i < heights.length; i++) {
                            int value = Math.max(0, Math.min(65535,
                                    Math.round(((heights[i] + 12.0f) / 36.0f) * 65535.0f)));
                            raw[i * 2] = (byte) (value & 0xFF);
                            raw[i * 2 + 1] = (byte) ((value >>> 8) & 0xFF);
                        }
                        output.write(raw);
                        runOnUiThread(() -> android.widget.Toast.makeText(this,
                                "Exported 129 x 129 unsigned 16-bit little-endian RAW heightmap",
                                android.widget.Toast.LENGTH_LONG).show());
                    } catch (Exception error) {
                        runOnUiThread(() -> android.widget.Toast.makeText(this,
                                "RAW16 export failed: " + error.getMessage(), android.widget.Toast.LENGTH_LONG).show());
                    }
                }, "Nova-Heightmap16-Export").start();
            });
            return;
        }

        if (requestCode == REQUEST_IMPORT_HEIGHTMAP) {
            new Thread(() -> {
                try (InputStream input = getContentResolver().openInputStream(uri)) {
                    if (input == null) throw new IllegalStateException("Could not open heightmap.");
                    Bitmap decoded = BitmapFactory.decodeStream(input);
                    if (decoded == null) throw new IllegalArgumentException("Unsupported image. Choose a PNG or other readable image.");
                    Bitmap scaled = Bitmap.createScaledBitmap(decoded, 129, 129, true);
                    int[] pixels = new int[129 * 129];
                    scaled.getPixels(pixels, 0, 129, 0, 0, 129, 129);
                    float[] heights = new float[pixels.length];
                    for (int i = 0; i < pixels.length; i++) {
                        int gray = (Color.red(pixels[i]) + Color.green(pixels[i]) + Color.blue(pixels[i])) / 3;
                        heights[i] = -12.0f + (gray / 255.0f) * 36.0f;
                    }
                    if (scaled != decoded) scaled.recycle();
                    decoded.recycle();
                    runOnUiThread(() -> {
                        if (engineSurface == null) return;
                        engineSurface.setTerrainHeights(heights, ok ->
                                android.widget.Toast.makeText(this,
                                        ok ? "Heightmap imported (129 x 129, 8-bit grayscale)" :
                                                "Create a landscape first, then import the heightmap.",
                                        android.widget.Toast.LENGTH_LONG).show());
                    });
                } catch (Exception error) {
                    runOnUiThread(() -> android.widget.Toast.makeText(this,
                            "Heightmap import failed: " + error.getMessage(),
                            android.widget.Toast.LENGTH_LONG).show());
                }
            }, "Nova-Heightmap-Import").start();
            return;
        }

        if (requestCode == REQUEST_EXPORT_HEIGHTMAP) {
            if (engineSurface == null) return;
            engineSurface.getTerrainHeights(heights -> {
                if (heights == null || heights.length != 129 * 129) {
                    android.widget.Toast.makeText(this, "Create a landscape before exporting.",
                            android.widget.Toast.LENGTH_LONG).show();
                    return;
                }
                Bitmap bitmap = Bitmap.createBitmap(129, 129, Bitmap.Config.ARGB_8888);
                int[] pixels = new int[heights.length];
                for (int i = 0; i < heights.length; i++) {
                    int gray = Math.max(0, Math.min(255,
                            Math.round(((heights[i] + 12.0f) / 36.0f) * 255.0f)));
                    pixels[i] = Color.rgb(gray, gray, gray);
                }
                bitmap.setPixels(pixels, 0, 129, 0, 0, 129, 129);
                new Thread(() -> {
                    try (java.io.OutputStream output = getContentResolver().openOutputStream(uri)) {
                        if (output == null || !bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                            throw new IllegalStateException("Could not write PNG.");
                        }
                        runOnUiThread(() -> android.widget.Toast.makeText(this,
                                "Heightmap exported as 129 x 129 8-bit grayscale PNG.",
                                android.widget.Toast.LENGTH_LONG).show());
                    } catch (Exception error) {
                        runOnUiThread(() -> android.widget.Toast.makeText(this,
                                "Heightmap export failed: " + error.getMessage(),
                                android.widget.Toast.LENGTH_LONG).show());
                    } finally {
                        bitmap.recycle();
                    }
                }, "Nova-Heightmap-Export").start();
            });
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
        content.setPadding(dp(18), dp(8), dp(18), dp(8));

        TextView info = new TextView(this);
        info.setText("LANDSCAPE • 129 x 129 connected heightfield\nCreate a continuous terrain surface, then shape it with height brushes. Brush positions use world X/Z coordinates.");
        info.setTextColor(Color.DKGRAY);
        info.setTextSize(12);
        content.addView(info);

        String[] labels = {"Brush center X", "Brush center Z", "Brush radius", "Brush strength"};
        EditText[] fields = new EditText[4];
        String[] defaults = {"0", "-4", "6.0", "0.65"};
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

        ScrollView landscapeScroll = new ScrollView(this);
        landscapeScroll.addView(content);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Landscape Tools")
                .setView(landscapeScroll)
                .setNegativeButton("Close", null)
                .create();

        TextView generate = new TextView(this);
        generate.setText("CREATE / REPLACE LANDSCAPE");
        generate.setTextColor(Color.WHITE);
        generate.setTextSize(12);
        generate.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        generate.setGravity(Gravity.CENTER);
        generate.setPadding(dp(12), dp(13), dp(12), dp(13));
        generate.setBackgroundColor(0xFF276A48);
        LinearLayout.LayoutParams actionParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(44));
        actionParams.topMargin = dp(8);
        content.addView(generate, actionParams);
        generate.setOnClickListener(v -> engineSurface.createTerrain(ok ->
                android.widget.Toast.makeText(this,
                        ok ? "129 x 129 landscape created" : "Landscape creation failed",
                        android.widget.Toast.LENGTH_SHORT).show()));

        String[] brushNames = {"Raise", "Lower", "Smooth", "Flatten", "Noise", "Ramp", "Erode"};
        Spinner terrainBrushPicker = new Spinner(this);
        ArrayAdapter<String> terrainBrushAdapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, brushNames);
        terrainBrushAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        terrainBrushPicker.setAdapter(terrainBrushAdapter);
        terrainBrushPicker.setSelection(0);
        content.addView(terrainBrushPicker, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(44)));

        TextView fingerSculpt = new TextView(this);
        fingerSculpt.setText(terrainViewportSculpting ? "STOP FINGER SCULPTING" : "START FINGER SCULPTING");
        fingerSculpt.setTextColor(Color.WHITE);
        fingerSculpt.setTextSize(12);
        fingerSculpt.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        fingerSculpt.setGravity(Gravity.CENTER);
        fingerSculpt.setPadding(dp(12), dp(12), dp(12), dp(12));
        fingerSculpt.setBackgroundColor(0xFF276A48);
        LinearLayout.LayoutParams fingerParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(44));
        fingerParams.topMargin = dp(8);
        content.addView(fingerSculpt, fingerParams);
        fingerSculpt.setOnClickListener(v -> {
            terrainViewportSculpting = !terrainViewportSculpting;
            float radius = terrainValue(fields[2], 6.0f);
            float strength = terrainValue(fields[3], 0.35f);
            int selectedBrush = terrainBrushPicker.getSelectedItemPosition();
            engineSurface.setTerrainSculptMode(
                    terrainViewportSculpting, selectedBrush, radius, strength);
            dialog.dismiss();
            android.widget.Toast.makeText(this,
                    terrainViewportSculpting
                            ? "Finger sculpting ON • drag directly on the terrain"
                            : "Finger sculpting OFF",
                    android.widget.Toast.LENGTH_LONG).show();
        });

        for (int mode = 0; mode < brushNames.length; mode++) {
            final int brushMode = mode;
            TextView action = new TextView(this);
            action.setText(brushNames[mode].toUpperCase(java.util.Locale.ROOT));
            action.setTextColor(Color.WHITE);
            action.setTextSize(12);
            action.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
            action.setGravity(Gravity.CENTER);
            action.setPadding(dp(12), dp(10), dp(12), dp(10));
            action.setBackgroundColor(0xFF354456);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(40));
            params.topMargin = dp(5);
            content.addView(action, params);
            action.setOnClickListener(v -> {
                terrainBrushPicker.setSelection(brushMode);
                if (terrainViewportSculpting) {
                    engineSurface.setTerrainSculptMode(true, brushMode,
                            terrainValue(fields[2], 6.0f),
                            terrainValue(fields[3], 0.35f));
                    dialog.dismiss();
                    android.widget.Toast.makeText(this,
                            brushNames[brushMode] + " brush selected • drag on terrain",
                            android.widget.Toast.LENGTH_SHORT).show();
                } else {
                    applyTerrainBrush(fields, brushMode);
                }
            });
        }

        TextView textureHeading = new TextView(this);
        textureHeading.setText("TEXTURE MAP ASSETS • ALBEDO / NORMAL / ROUGHNESS");
        textureHeading.setTextColor(0xFFB45F16);
        textureHeading.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        textureHeading.setPadding(0, dp(16), 0, dp(4));
        content.addView(textureHeading);
        TextView textureHelp = new TextView(this);
        textureHelp.setText("Import real image maps for each terrain layer. Albedo uses RGB colour, Normal expects a tangent-space normal map, and Roughness uses its red channel. Images are resized to a maximum 1024 px and uploaded to GPU memory.");
        textureHelp.setTextColor(Color.DKGRAY); textureHelp.setTextSize(11);
        content.addView(textureHelp);
        Spinner textureLayerPicker = new Spinner(this);
        String[] textureLayerNames = {"Grass", "Rock", "Sand", "Snow"};
        ArrayAdapter<String> textureLayerAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, textureLayerNames);
        textureLayerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        textureLayerPicker.setAdapter(textureLayerAdapter);
        content.addView(textureLayerPicker, new LinearLayout.LayoutParams(-1, dp(42)));
        Spinner textureMapPicker = new Spinner(this);
        String[] textureMapNames = {"Albedo / Base Color", "Normal", "Roughness"};
        ArrayAdapter<String> textureMapAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, textureMapNames);
        textureMapAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        textureMapPicker.setAdapter(textureMapAdapter);
        content.addView(textureMapPicker, new LinearLayout.LayoutParams(-1, dp(42)));
        TextView importTexture = new TextView(this);
        importTexture.setText("IMPORT SELECTED TEXTURE MAP");
        importTexture.setTextColor(Color.WHITE); importTexture.setGravity(Gravity.CENTER);
        importTexture.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        importTexture.setPadding(dp(8), dp(12), dp(8), dp(12));
        importTexture.setBackgroundColor(0xFF35566B);
        content.addView(importTexture, new LinearLayout.LayoutParams(-1, dp(44)));
        importTexture.setOnClickListener(v -> openTerrainTexturePicker(
                textureLayerPicker.getSelectedItemPosition(), textureMapPicker.getSelectedItemPosition()));

        TextView paintHeading = new TextView(this);
        paintHeading.setText("MATERIAL PAINTING");
        paintHeading.setTextColor(0xFFB45F16);
        paintHeading.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        paintHeading.setPadding(0, dp(16), 0, dp(4));
        content.addView(paintHeading);
        Spinner materialPicker = new Spinner(this);
        String[] materialNames = {"Grass", "Rock", "Sand", "Snow"};
        ArrayAdapter<String> materialAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, materialNames);
        materialAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        materialPicker.setAdapter(materialAdapter);
        content.addView(materialPicker, new LinearLayout.LayoutParams(-1, dp(44)));
        TextView paintToggle = new TextView(this);
        paintToggle.setText(terrainViewportPainting ? "STOP MATERIAL PAINTING" : "START MATERIAL PAINTING");
        paintToggle.setTextColor(Color.WHITE); paintToggle.setGravity(Gravity.CENTER);
        paintToggle.setPadding(dp(10), dp(12), dp(10), dp(12));
        paintToggle.setBackgroundColor(0xFF246A83);
        content.addView(paintToggle, new LinearLayout.LayoutParams(-1, dp(44)));
        paintToggle.setOnClickListener(v -> {
            terrainViewportPainting = !terrainViewportPainting;
            engineSurface.setTerrainPaintMode(terrainViewportPainting,
                    materialPicker.getSelectedItemPosition(),
                    terrainValue(fields[2], 6.0f), terrainValue(fields[3], 0.35f));
            terrainViewportSculpting = false;
            dialog.dismiss();
            android.widget.Toast.makeText(this,
                    terrainViewportPainting ? "Paint mode ON • drag on terrain" : "Paint mode OFF",
                    android.widget.Toast.LENGTH_LONG).show();
        });

        TextView erosionHeading = new TextView(this);
        erosionHeading.setText("EROSION SIMULATION");
        erosionHeading.setTextColor(0xFFB45F16);
        erosionHeading.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        erosionHeading.setPadding(0, dp(16), 0, dp(4));
        content.addView(erosionHeading);
        TextView hydraulic = new TextView(this);
        hydraulic.setText("RUN HYDRAULIC EROSION • 1200 DROPLETS");
        hydraulic.setTextColor(Color.WHITE); hydraulic.setGravity(Gravity.CENTER);
        hydraulic.setPadding(dp(8), dp(12), dp(8), dp(12));
        hydraulic.setBackgroundColor(0xFF35566B);
        content.addView(hydraulic, new LinearLayout.LayoutParams(-1, dp(42)));
        hydraulic.setOnClickListener(v -> engineSurface.runTerrainErosion(0, 1200,
                terrainValue(fields[3], 0.35f), ok ->
                android.widget.Toast.makeText(this, ok ? "Hydraulic erosion applied" :
                        "Create a landscape first or unlock the active layer",
                        android.widget.Toast.LENGTH_LONG).show()));
        TextView thermal = new TextView(this);
        thermal.setText("RUN THERMAL EROSION • 12 PASSES");
        thermal.setTextColor(Color.WHITE); thermal.setGravity(Gravity.CENTER);
        thermal.setPadding(dp(8), dp(12), dp(8), dp(12));
        thermal.setBackgroundColor(0xFF66512F);
        LinearLayout.LayoutParams thermalParams = new LinearLayout.LayoutParams(-1, dp(42));
        thermalParams.topMargin = dp(5);
        content.addView(thermal, thermalParams);
        thermal.setOnClickListener(v -> engineSurface.runTerrainErosion(1, 12,
                terrainValue(fields[3], 0.35f), ok ->
                android.widget.Toast.makeText(this, ok ? "Thermal erosion applied" :
                        "Create a landscape first or unlock the active layer",
                        android.widget.Toast.LENGTH_LONG).show()));

        TextView layerHeading = new TextView(this);
        layerHeading.setText("NON-DESTRUCTIVE EDIT LAYERS");
        layerHeading.setTextColor(0xFFB45F16);
        layerHeading.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        layerHeading.setPadding(0, dp(16), 0, dp(4));
        content.addView(layerHeading);
        TextView addLayer = new TextView(this);
        addLayer.setText("ADD EDIT LAYER");
        addLayer.setTextColor(Color.WHITE); addLayer.setGravity(Gravity.CENTER);
        addLayer.setPadding(dp(8), dp(12), dp(8), dp(12));
        addLayer.setBackgroundColor(0xFF3C6847);
        content.addView(addLayer, new LinearLayout.LayoutParams(-1, dp(42)));
        addLayer.setOnClickListener(v -> {
            EditText name = new EditText(this); name.setSingleLine(true);
            name.setHint("Layer name (e.g. Mountain sculpt)");
            new AlertDialog.Builder(this).setTitle("New Landscape Layer").setView(name)
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Add", (d, w) -> engineSurface.addLandscapeLayer(
                            name.getText().toString().trim(), index ->
                                    android.widget.Toast.makeText(this, index >= 0 ?
                                            "Edit layer added: " + (index + 1) :
                                            "Could not add layer. Create a landscape first.",
                                            android.widget.Toast.LENGTH_LONG).show()))
                    .show();
        });
        TextView manageLayers = new TextView(this);
        manageLayers.setText("SELECT / MANAGE EDIT LAYERS");
        manageLayers.setTextColor(Color.WHITE); manageLayers.setGravity(Gravity.CENTER);
        manageLayers.setPadding(dp(8), dp(12), dp(8), dp(12));
        manageLayers.setBackgroundColor(0xFF354456);
        LinearLayout.LayoutParams layerParams = new LinearLayout.LayoutParams(-1, dp(42));
        layerParams.topMargin = dp(5); content.addView(manageLayers, layerParams);
        manageLayers.setOnClickListener(v -> engineSurface.getLandscapeLayers(names -> {
            if (names == null || names.length == 0) {
                android.widget.Toast.makeText(this, "Create a landscape first", android.widget.Toast.LENGTH_SHORT).show();
                return;
            }
            new AlertDialog.Builder(this).setTitle("Landscape Edit Layers")
                    .setItems(names, (d, which) -> {
                        engineSurface.selectLandscapeLayer(which, ok -> {
                            if (!ok) return;
                            String[] actions = {"Toggle visibility", "Toggle lock", "Move layer up", "Move layer down", "Edit layer blend", "Delete layer"};
                            new AlertDialog.Builder(this).setTitle(names[which])
                                    .setItems(actions, (ad, action) -> engineSurface.getLandscapeLayerState(which, state -> {
                                        if (state == null || state.length < 4) return;
                                        if (action == 0) engineSurface.setLandscapeLayerState(which,
                                                state[0] < 0.5f, state[1] > 0.5f, state[2], state[3], result ->
                                                android.widget.Toast.makeText(this, result ? "Layer visibility updated" : "Layer update failed", android.widget.Toast.LENGTH_SHORT).show());
                                        else if (action == 1) engineSurface.setLandscapeLayerState(which,
                                                state[0] > 0.5f, state[1] < 0.5f, state[2], state[3], result ->
                                                android.widget.Toast.makeText(this, result ? "Layer lock updated" : "Layer update failed", android.widget.Toast.LENGTH_SHORT).show());
                                        else if (action == 2) engineSurface.moveLandscapeLayer(which, Math.max(0, which - 1), result ->
                                                android.widget.Toast.makeText(this, result ? "Layer moved up" : "Layer move failed", android.widget.Toast.LENGTH_SHORT).show());
                                        else if (action == 3) engineSurface.moveLandscapeLayer(which, Math.min(names.length - 1, which + 1), result ->
                                                android.widget.Toast.makeText(this, result ? "Layer moved down" : "Layer move failed", android.widget.Toast.LENGTH_SHORT).show());
                                        else if (action == 4) {
                                            LinearLayout blend = new LinearLayout(this);
                                            blend.setOrientation(LinearLayout.VERTICAL);
                                            blend.setPadding(dp(18), dp(8), dp(18), dp(8));
                                            TextView hLabel = new TextView(this); hLabel.setText("Height blend alpha"); hLabel.setTextColor(Color.DKGRAY);
                                            android.widget.SeekBar hAlpha = new android.widget.SeekBar(this); hAlpha.setMax(100); hAlpha.setProgress(Math.round(state[2] * 100));
                                            TextView pLabel = new TextView(this); pLabel.setText("Paint blend alpha"); pLabel.setTextColor(Color.DKGRAY);
                                            android.widget.SeekBar pAlpha = new android.widget.SeekBar(this); pAlpha.setMax(100); pAlpha.setProgress(Math.round(state[3] * 100));
                                            blend.addView(hLabel); blend.addView(hAlpha); blend.addView(pLabel); blend.addView(pAlpha);
                                            new AlertDialog.Builder(this).setTitle("Layer Blend • " + names[which]).setView(blend)
                                                    .setNegativeButton("Cancel", null)
                                                    .setPositiveButton("Apply", (bd, bw) -> engineSurface.setLandscapeLayerState(
                                                            which, state[0] > 0.5f, state[1] > 0.5f,
                                                            hAlpha.getProgress() / 100.0f, pAlpha.getProgress() / 100.0f,
                                                            result -> android.widget.Toast.makeText(this, result ? "Layer blend updated" : "Layer blend failed", android.widget.Toast.LENGTH_SHORT).show()))
                                                    .show();
                                        } else engineSurface.deleteLandscapeLayer(which, result ->
                                                android.widget.Toast.makeText(this, result ? "Layer deleted" : "The last remaining layer cannot be deleted", android.widget.Toast.LENGTH_SHORT).show());
                                    })).setNegativeButton("Close", null).show();
                        });
                    }).setNegativeButton("Close", null).show();
        }));

        TextView splineHeading = new TextView(this);
        splineHeading.setText("LANDSCAPE SPLINES • ROAD CORRIDORS");
        splineHeading.setTextColor(0xFFB45F16);
        splineHeading.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        splineHeading.setPadding(0, dp(16), 0, dp(4));
        content.addView(splineHeading);
        EditText[] road = new EditText[9];
        String[] roadLabels = {"Start X", "Start Z", "Start width", "Control X", "Control Z", "Control width", "End X", "End Z", "End width"};
        String[] roadDefaults = {"-20", "-4", "5", "0", "5", "5", "20", "-4", "5"};
        for (int i=0;i<road.length;i++) {
            TextView label = new TextView(this); label.setText(roadLabels[i]);
            label.setTextColor(Color.DKGRAY); label.setTextSize(11); content.addView(label);
            road[i] = new EditText(this); road[i].setSingleLine(true);
            road[i].setInputType(android.text.InputType.TYPE_CLASS_NUMBER |
                    android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL |
                    android.text.InputType.TYPE_NUMBER_FLAG_SIGNED);
            road[i].setText(roadDefaults[i]); content.addView(road[i]);
        }
        TextView addRoad = new TextView(this);
        addRoad.setText("CREATE CURVED ROAD SPLINE");
        addRoad.setTextColor(Color.WHITE); addRoad.setGravity(Gravity.CENTER);
        addRoad.setPadding(dp(8), dp(12), dp(8), dp(12));
        addRoad.setBackgroundColor(0xFF6B5733);
        content.addView(addRoad, new LinearLayout.LayoutParams(-1, dp(42)));
        addRoad.setOnClickListener(v -> {
            float[] points = {
                    terrainValue(road[0], -20), terrainValue(road[1], -4), terrainValue(road[2], 5),
                    terrainValue(road[3], 0), terrainValue(road[4], 5), terrainValue(road[5], 5),
                    terrainValue(road[6], 20), terrainValue(road[7], -4), terrainValue(road[8], 5)
            };
            engineSurface.addLandscapeSpline(points, ok ->
                    android.widget.Toast.makeText(this, ok ? "Curved road spline stored and terrain conformed" :
                            "Could not create road spline. Keep all control points inside one landscape tile.",
                            android.widget.Toast.LENGTH_LONG).show());
        });


        TextView caveHeading = new TextView(this);
        caveHeading.setText("CAVE / TUNNEL CREATION");
        caveHeading.setTextColor(0xFFB45F16);
        caveHeading.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        caveHeading.setPadding(0, dp(16), 0, dp(4));
        content.addView(caveHeading);
        EditText[] caveFields = new EditText[7];
        String[] caveLabels = {"Entry X", "Entry Y", "Entry Z", "Exit X", "Exit Y", "Exit Z", "Tunnel radius"};
        String[] caveDefaults = {"-14", "-2.5", "-4", "14", "-2.5", "-4", "2.0"};
        for (int i=0;i<caveFields.length;i++) {
            TextView label = new TextView(this); label.setText(caveLabels[i]);
            label.setTextColor(Color.DKGRAY); label.setTextSize(11); content.addView(label);
            caveFields[i] = new EditText(this); caveFields[i].setSingleLine(true);
            caveFields[i].setInputType(android.text.InputType.TYPE_CLASS_NUMBER |
                    android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL |
                    android.text.InputType.TYPE_NUMBER_FLAG_SIGNED);
            caveFields[i].setText(caveDefaults[i]); content.addView(caveFields[i]);
        }
        TextView createCave = new TextView(this);
        createCave.setText("CREATE CAVE TUNNEL + ENTRANCES");
        createCave.setTextColor(Color.WHITE); createCave.setGravity(Gravity.CENTER);
        createCave.setPadding(dp(8), dp(12), dp(8), dp(12)); createCave.setBackgroundColor(0xFF51413A);
        content.addView(createCave, new LinearLayout.LayoutParams(-1, dp(42)));
        createCave.setOnClickListener(v -> {
            float[] pathPoints = {
                    terrainValue(caveFields[0], -14), terrainValue(caveFields[1], -2.5f), terrainValue(caveFields[2], -4),
                    terrainValue(caveFields[3], 14), terrainValue(caveFields[4], -2.5f), terrainValue(caveFields[5], -4)
            };
            engineSurface.createLandscapeCave(pathPoints, terrainValue(caveFields[6], 2.0f), ok ->
                    android.widget.Toast.makeText(this, ok ? "Cave tunnel and surface entrances created" :
                            "Cave creation failed. Create a landscape first.", android.widget.Toast.LENGTH_LONG).show());
        });

        TextView holeHeading = new TextView(this);
        holeHeading.setText("TERRAIN VISIBILITY / HOLES");
        holeHeading.setTextColor(0xFFB45F16);
        holeHeading.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        holeHeading.setPadding(0, dp(16), 0, dp(4));
        content.addView(holeHeading);
        TextView cutHole = new TextView(this);
        cutHole.setText("CUT TERRAIN HOLES • TAP CELLS");
        cutHole.setTextColor(Color.WHITE); cutHole.setGravity(Gravity.CENTER);
        cutHole.setPadding(dp(8), dp(12), dp(8), dp(12)); cutHole.setBackgroundColor(0xFF7A3F3F);
        content.addView(cutHole, new LinearLayout.LayoutParams(-1, dp(42)));
        cutHole.setOnClickListener(v -> {
            engineSurface.setTerrainHoleMode(true, true);
            dialog.dismiss();
            android.widget.Toast.makeText(this, "Hole editing ON • tap terrain cells", android.widget.Toast.LENGTH_LONG).show();
        });
        TextView fillHole = new TextView(this);
        fillHole.setText("FILL TERRAIN HOLES • TAP CELLS");
        fillHole.setTextColor(Color.WHITE); fillHole.setGravity(Gravity.CENTER);
        fillHole.setPadding(dp(8), dp(12), dp(8), dp(12)); fillHole.setBackgroundColor(0xFF426B75);
        LinearLayout.LayoutParams fillParams = new LinearLayout.LayoutParams(-1, dp(42));
        fillParams.topMargin = dp(5); content.addView(fillHole, fillParams);
        fillHole.setOnClickListener(v -> {
            engineSurface.setTerrainHoleMode(true, false);
            dialog.dismiss();
            android.widget.Toast.makeText(this, "Hole filling ON • tap a hole cell", android.widget.Toast.LENGTH_LONG).show();
        });

        TextView worldTileHeading = new TextView(this);
        worldTileHeading.setText("LARGE WORLD • LANDSCAPE TILE STREAMING");
        worldTileHeading.setTextColor(0xFFB45F16);
        worldTileHeading.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        worldTileHeading.setPadding(0, dp(16), 0, dp(4));
        content.addView(worldTileHeading);
        TextView worldTileInfo = new TextView(this);
        worldTileInfo.setText("Each tile is 129 x 129 samples covering 64 x 64 world units. Adjacent tile borders are matched on import. Distant tiles stream out and return with distance-based LOD.");
        worldTileInfo.setTextColor(Color.DKGRAY); worldTileInfo.setTextSize(11);
        content.addView(worldTileInfo);
        EditText worldTileX = new EditText(this); worldTileX.setSingleLine(true);
        worldTileX.setHint("Tile X coordinate (0 is main tile)");
        worldTileX.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_SIGNED);
        worldTileX.setText("1"); content.addView(worldTileX);
        EditText worldTileZ = new EditText(this); worldTileZ.setSingleLine(true);
        worldTileZ.setHint("Tile Z coordinate");
        worldTileZ.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_SIGNED);
        worldTileZ.setText("0"); content.addView(worldTileZ);
        TextView importWorldTile = new TextView(this);
        importWorldTile.setText("IMPORT 16-BIT RAW WORLD TILE");
        importWorldTile.setTextColor(Color.WHITE); importWorldTile.setGravity(Gravity.CENTER);
        importWorldTile.setPadding(dp(8), dp(12), dp(8), dp(12));
        importWorldTile.setBackgroundColor(0xFF35566B);
        content.addView(importWorldTile, new LinearLayout.LayoutParams(-1, dp(44)));
        importWorldTile.setOnClickListener(v -> openLandscapeWorldTilePicker(
                Math.round(terrainValue(worldTileX, 1)), Math.round(terrainValue(worldTileZ, 0))));
        TextView worldTileCount = new TextView(this);
        worldTileCount.setText("REFRESH WORLD TILE COUNT");
        worldTileCount.setTextColor(Color.WHITE); worldTileCount.setGravity(Gravity.CENTER);
        worldTileCount.setPadding(dp(8), dp(10), dp(8), dp(10));
        worldTileCount.setBackgroundColor(0xFF46566A);
        content.addView(worldTileCount, new LinearLayout.LayoutParams(-1, dp(40)));
        worldTileCount.setOnClickListener(v -> engineSurface.getLandscapeWorldTileCount(count ->
                worldTileCount.setText("WORLD TILES: " + count)));
        TextView copyMainTile = new TextView(this);
        copyMainTile.setText("COPY MAIN LANDSCAPE TO THIS TILE");
        copyMainTile.setTextColor(Color.WHITE); copyMainTile.setGravity(Gravity.CENTER);
        copyMainTile.setPadding(dp(8), dp(10), dp(8), dp(10));
        copyMainTile.setBackgroundColor(0xFF3E684F);
        content.addView(copyMainTile, new LinearLayout.LayoutParams(-1, dp(40)));
        copyMainTile.setOnClickListener(v -> {
            int tx = Math.round(terrainValue(worldTileX, 1));
            int tz = Math.round(terrainValue(worldTileZ, 0));
            engineSurface.copyMainLandscapeTile(tx, tz, ok -> {
                if (ok) {
                    File asset = landscapeWorldTileAssetFile(tx, tz);
                    if (asset != null) {
                        File parent = asset.getParentFile();
                        if (parent != null && !parent.exists()) parent.mkdirs();
                        engineSurface.getTerrainHeights(heights -> {
                            if (heights == null) return;
                            new Thread(() -> {
                                try (java.io.DataOutputStream output = new java.io.DataOutputStream(
                                        new java.io.FileOutputStream(asset))) {
                                    for (float height : heights) {
                                        int sample = Math.max(0, Math.min(65535,
                                                Math.round(((height + 12.0f) / 36.0f) * 65535.0f)));
                                        output.writeByte(sample & 0xFF);
                                        output.writeByte((sample >>> 8) & 0xFF);
                                    }
                                } catch (Exception ignored) { }
                            }, "Nova-World-Tile-Copy-Asset").start();
                        });
                    }
                }
                android.widget.Toast.makeText(this, ok ? "Main landscape copied to tile " + tx + ", " + tz :
                        "Copy failed. Choose a new tile coordinate.", android.widget.Toast.LENGTH_LONG).show();
            });
        });
        TextView removeWorldTile = new TextView(this);
        removeWorldTile.setText("REMOVE WORLD TILE AT X / Z");
        removeWorldTile.setTextColor(Color.WHITE); removeWorldTile.setGravity(Gravity.CENTER);
        removeWorldTile.setPadding(dp(8), dp(10), dp(8), dp(10));
        removeWorldTile.setBackgroundColor(0xFF824848);
        content.addView(removeWorldTile, new LinearLayout.LayoutParams(-1, dp(40)));
        removeWorldTile.setOnClickListener(v -> {
            int tx = Math.round(terrainValue(worldTileX, 1));
            int tz = Math.round(terrainValue(worldTileZ, 0));
            engineSurface.removeLandscapeWorldTile(tx, tz, ok -> {
                if (ok) {
                    File asset = landscapeWorldTileAssetFile(tx, tz);
                    if (asset != null && asset.exists()) asset.delete();
                }
                android.widget.Toast.makeText(this, ok ? "World tile removed" :
                        "No removable world tile exists at those coordinates", android.widget.Toast.LENGTH_LONG).show();
            });
        });

        TextView componentHeading = new TextView(this);
        componentHeading.setText("LANDSCAPE COMPONENT MANAGEMENT");
        componentHeading.setTextColor(0xFFB45F16);
        componentHeading.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        componentHeading.setPadding(0, dp(16), 0, dp(4));
        content.addView(componentHeading);
        Spinner chunkPicker = new Spinner(this);
        String[] chunkSizes = {"8 quads", "16 quads", "32 quads", "64 quads", "128 quads"};
        ArrayAdapter<String> chunkAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, chunkSizes);
        chunkAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        chunkPicker.setAdapter(chunkAdapter); chunkPicker.setSelection(2);
        content.addView(chunkPicker, new LinearLayout.LayoutParams(-1, dp(44)));
        TextView applyChunks = new TextView(this);
        applyChunks.setText("REBUILD LANDSCAPE COMPONENT GRID");
        applyChunks.setTextColor(Color.WHITE); applyChunks.setGravity(Gravity.CENTER);
        applyChunks.setPadding(dp(8), dp(12), dp(8), dp(12)); applyChunks.setBackgroundColor(0xFF354456);
        content.addView(applyChunks, new LinearLayout.LayoutParams(-1, dp(42)));
        applyChunks.setOnClickListener(v -> {
            int[] sizes = {8,16,32,64,128};
            engineSurface.setLandscapeChunkSize(sizes[chunkPicker.getSelectedItemPosition()], ok -> {
                engineSurface.getLandscapeChunkCount(count ->
                        android.widget.Toast.makeText(this, ok ? "Landscape component grid: " + count + " components" :
                                "Component grid update failed", android.widget.Toast.LENGTH_LONG).show());
            });
        });
        dialog.show();
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
        float radius = terrainValue(fields[2], 6.0f);
        float strength = terrainValue(fields[3], 0.65f);
        engineSurface.sculptTerrain(x, z, radius, strength, mode, changed ->
                android.widget.Toast.makeText(this,
                        changed ? "Landscape updated" : "No landscape change. Create a landscape first.",
                        android.widget.Toast.LENGTH_SHORT).show());
    }

    private void openAIAssistant() {
        closeToolsDrawer();
        new AIEditorAssistant(this, engineSurface).show();
    }

    private void showRigidBodySetup() {
        closeToolsDrawer();
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(8), dp(16), dp(8));
        EditText mass = new EditText(this); mass.setSingleLine(true); mass.setHint("Mass (kg)"); mass.setText("1.0");
        EditText restitution = new EditText(this); restitution.setSingleLine(true); restitution.setHint("Bounciness (0 - 0.9)"); restitution.setText("0.12");
        EditText friction = new EditText(this); friction.setSingleLine(true); friction.setHint("Friction (0 - 2)"); friction.setText("0.7");
        content.addView(mass); content.addView(restitution); content.addView(friction);
        new AlertDialog.Builder(this).setTitle("Selected Object Physics")
                .setMessage("Select a mesh in the viewport first. This adds a basic dynamic body with gravity, terrain contact and static-platform contact.")
                .setView(content)
                .setNegativeButton("Cancel", null)
                .setNeutralButton(selectedRigidBodyEnabled ? "REMOVE BODY" : "ADD BODY", (d, w) -> {
                    if (selectedRigidBodyEnabled) {
                        engineSurface.setSelectedRigidBody(false, 1.0f, 0.12f, 0.7f, ok -> {
                            if (ok) selectedRigidBodyEnabled = false;
                            android.widget.Toast.makeText(this, ok ? "Rigid body removed" :
                                    "Select a non-landscape object first", android.widget.Toast.LENGTH_LONG).show();
                        });
                    } else {
                        engineSurface.setSelectedRigidBody(true,
                                terrainValue(mass, 1.0f), terrainValue(restitution, 0.12f),
                                terrainValue(friction, 0.7f), ok -> {
                                    if (ok) selectedRigidBodyEnabled = true;
                                    android.widget.Toast.makeText(this, ok ? "Rigid body attached" :
                                            "Select a non-landscape object first", android.widget.Toast.LENGTH_LONG).show();
                                });
                    }
                }).show();
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

    private static void writePngInt(java.io.OutputStream output, int value) throws java.io.IOException {
        output.write((value >>> 24) & 0xFF);
        output.write((value >>> 16) & 0xFF);
        output.write((value >>> 8) & 0xFF);
        output.write(value & 0xFF);
    }

    private static void writePngChunk(java.io.OutputStream output, String type, byte[] data)
            throws java.io.IOException {
        byte[] typeBytes = type.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        writePngInt(output, data.length);
        output.write(typeBytes);
        output.write(data);
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(typeBytes);
        crc.update(data);
        writePngInt(output, (int) crc.getValue());
    }

    private static byte[] encodeTerrainHeightmap16Png(float[] heights) throws java.io.IOException {
        if (heights == null || heights.length != 129 * 129)
            throw new IllegalArgumentException("Expected 129 x 129 height samples.");
        java.io.ByteArrayOutputStream png = new java.io.ByteArrayOutputStream();
        png.write(new byte[]{(byte)137, 80, 78, 71, 13, 10, 26, 10});
        java.io.ByteArrayOutputStream header = new java.io.ByteArrayOutputStream(13);
        writePngInt(header, 129); writePngInt(header, 129);
        header.write(16); header.write(0); header.write(0); header.write(0); header.write(0);
        writePngChunk(png, "IHDR", header.toByteArray());

        java.io.ByteArrayOutputStream raw = new java.io.ByteArrayOutputStream(129 * 129 * 2 + 129);
        for (int y = 0; y < 129; y++) {
            raw.write(0);
            for (int x = 0; x < 129; x++) {
                float h = heights[y * 129 + x];
                int sample = Math.max(0, Math.min(65535,
                        Math.round(((h + 12.0f) / 36.0f) * 65535.0f)));
                raw.write((sample >>> 8) & 0xFF);
                raw.write(sample & 0xFF);
            }
        }
        java.io.ByteArrayOutputStream compressed = new java.io.ByteArrayOutputStream();
        try (java.util.zip.DeflaterOutputStream deflater =
                     new java.util.zip.DeflaterOutputStream(compressed)) {
            raw.writeTo(deflater);
        }
        writePngChunk(png, "IDAT", compressed.toByteArray());
        writePngChunk(png, "IEND", new byte[0]);
        return png.toByteArray();
    }

    private File landscapeWorldTileAssetFile(int tileX, int tileZ) {
        if (activeProjectDirectory == null) return null;
        return new File(new File(activeProjectDirectory, "Assets/LandscapeTiles"),
                "Tile_" + tileX + "_" + tileZ + ".r16");
    }

    private void loadLandscapeWorldTiles() {
        if (activeProjectDirectory == null || engineSurface == null) return;
        File directory = new File(activeProjectDirectory, "Assets/LandscapeTiles");
        File[] files = directory.listFiles((dir, name) -> name.matches("Tile_-?\\d+_-?\\d+\\.r16"));
        if (files == null || files.length == 0) return;
        new Thread(() -> {
            for (File file : files) {
                try (InputStream input = new java.io.FileInputStream(file);
                     java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream()) {
                    byte[] buffer = new byte[8192]; int read;
                    while ((read = input.read(buffer)) != -1) {
                        if (bytes.size() + read > 100000) throw new IllegalArgumentException("Tile file too large.");
                        bytes.write(buffer, 0, read);
                    }
                    byte[] raw = bytes.toByteArray();
                    if (raw.length != 129 * 129 * 2) continue;
                    java.util.regex.Matcher matcher = java.util.regex.Pattern
                            .compile("Tile_(-?\\d+)_(-?\\d+)\\.r16").matcher(file.getName());
                    if (!matcher.matches()) continue;
                    int tileX = Integer.parseInt(matcher.group(1));
                    int tileZ = Integer.parseInt(matcher.group(2));
                    float[] heights = new float[129 * 129];
                    for (int i = 0; i < heights.length; i++) {
                        int value = (raw[i * 2] & 0xFF) | ((raw[i * 2 + 1] & 0xFF) << 8);
                        heights[i] = -12.0f + (value / 65535.0f) * 36.0f;
                    }
                    engineSurface.createLandscapeWorldTile(tileX, tileZ, heights, ok -> {
                        if (!ok) android.widget.Toast.makeText(this,
                                "Could not restore world tile " + tileX + ", " + tileZ,
                                android.widget.Toast.LENGTH_SHORT).show();
                    });
                } catch (Exception error) {
                    android.widget.Toast.makeText(this, "Tile restore failed: " + file.getName(),
                            android.widget.Toast.LENGTH_SHORT).show();
                }
            }
        }, "Nova-World-Tile-Restore").start();
    }

    private File terrainTextureAssetFile(int layer, int mapType) {
        if (activeProjectDirectory == null) return null;
        String[] layers = {"Grass", "Rock", "Sand", "Snow"};
        String[] maps = {"Albedo", "Normal", "Roughness"};
        File directory = new File(activeProjectDirectory, "Assets/Terrain");
        return new File(directory, layers[Math.max(0, Math.min(3, layer))] + "_" +
                maps[Math.max(0, Math.min(2, mapType))] + ".img");
    }

    private void loadTerrainTextureAssets() {
        if (activeProjectDirectory == null || engineSurface == null) return;
        new Thread(() -> {
            for (int layer = 0; layer < 4; layer++) {
                for (int mapType = 0; mapType < 3; mapType++) {
                    File file = terrainTextureAssetFile(layer, mapType);
                    if (file == null || !file.isFile()) continue;
                    Bitmap decoded = BitmapFactory.decodeFile(file.getAbsolutePath());
                    if (decoded == null) continue;
                    int originalWidth = decoded.getWidth(), originalHeight = decoded.getHeight();
                    float scale = Math.min(1.0f, 1024.0f / Math.max(originalWidth, originalHeight));
                    int width = Math.max(1, Math.round(originalWidth * scale));
                    int height = Math.max(1, Math.round(originalHeight * scale));
                    Bitmap scaled = (width == originalWidth && height == originalHeight)
                            ? decoded : Bitmap.createScaledBitmap(decoded, width, height, true);
                    int[] pixels = new int[width * height];
                    scaled.getPixels(pixels, 0, width, 0, 0, width, height);
                    if (scaled != decoded) scaled.recycle();
                    decoded.recycle();
                    final int textureLayer = layer, textureMap = mapType;
                    engineSurface.setTerrainTexture(textureLayer, textureMap, width, height, pixels, ok -> {
                        if (!ok) android.widget.Toast.makeText(this,
                                "Could not restore " + file.getName(), android.widget.Toast.LENGTH_SHORT).show();
                    });
                }
            }
        }, "Nova-Terrain-Texture-Restore").start();
    }

    private void enterProject(String message) {
        loadTerrainTextureAssets();
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
