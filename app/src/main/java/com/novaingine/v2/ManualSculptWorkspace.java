package com.novaingine.v2;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.widget.FrameLayout;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;

/**
 * Full-screen portrait sculpt workspace. All sculpt strokes execute in the
 * native engine on the GL thread; no network AI provider is involved.
 */
public final class ManualSculptWorkspace extends FrameLayout {
    private static final String[] BRUSHES = {
            "Draw", "Smooth", "Grab", "Inflate", "Pinch", "Flatten",
            "Crease", "Mask", "Clay", "Scrape", "Layer", "Unmask",
            "Blob", "Clay Strips", "Elastic Deform", "Snake Hook", "Thumb",
            "Pose", "Boundary", "Slide Relax", "Multiplane Scrape", "Fill",
            "Deepen", "Nudge", "Simplify"
    };
    private static final String[] BRUSH_DESCRIPTIONS = {
            "Adds or removes volume along the surface.", "Softens bumps and evens the mesh.",
            "Drags a region without adding volume.", "Pushes the surface outward along its normals.",
            "Pulls nearby vertices toward the stroke center.", "Flattens raised areas toward a plane.",
            "Carves a narrow groove into the surface.", "Protects a region from sculpting.",
            "Builds up volume in clay-like strokes.", "Scrapes high points toward a flatter surface.",
            "Builds a controlled layer of surface volume.", "Removes mask protection from the surface.",
            "Adds rounded volume beneath the brush.", "Builds raised, elongated strips like layered clay.",
            "Stretches the surface with an elastic response.", "Pulls geometry outward to form long extensions.",
            "Pushes and smears surface geometry along the stroke.", "Bends and shifts a broad region of the mesh.",
            "Shapes open mesh borders and edge regions.", "Relaxes uneven topology while sliding vertices.",
            "Scrapes using a multi-directional flattening plane.", "Raises low areas and fills shallow depressions.",
            "Carves a stronger groove into the surface.", "Moves vertices along the stroke direction.",
            "Smooths and simplifies local surface detail."
    };
    private static final int BG = 0xFF15191F;
    private static final int PANEL = 0xF0202731;
    private static final int ACCENT = 0xFFFFA52B;

    private final Activity activity;
    private final EngineSurface engine;
    private final Runnable onExit;
    private final TextView status;
    private final TextView[] brushButtons = new TextView[BRUSHES.length];
    private LinearLayout brushControls;
    private int gizmoAxis = -1;
    private float gizmoCenterX;
    private float gizmoCenterY;
    private final float[] gizmoEndX = new float[3];
    private final float[] gizmoEndY = new float[3];
    private final boolean[] gizmoEndVisible = new boolean[3];
    private boolean gizmoVisible;
    private int selectedBrush;
    private float radius = 0.18f;
    private float strength = 0.40f;
    private float falloff = 0.5f;
    private float pressure = 1.0f;
    private int symmetryAxis = 0;
    private boolean subtract;
    private boolean sculptEnabled = true;
    private boolean orbitMode = false;
    private TextView orbitButton;
    private EditText[] positionInputs = new EditText[3];
    private int transformComponent = 0;
    private int activeTransformMode = -1;
    private float[] transformDragState;
    private float uiScale = 1.0f;
    private View brushDock;
    private View rightDock;
    private View leftResizeHandle;
    private View rightResizeHandle;
    private LinearLayout bottomDock;
    private final ArrayList<float[]> transformKeyframes = new ArrayList<>();
    private final Handler timelineHandler = new Handler(Looper.getMainLooper());
    private boolean timelinePlaying;
    private long timelineStartMs;
    private final Runnable timelineTick = this::advanceTimeline;

    public ManualSculptWorkspace(Activity activity, EngineSurface engine, Runnable onExit) {
        super(activity);
        this.activity = activity;
        this.engine = engine;
        this.onExit = onExit;
        float density = getResources().getDisplayMetrics().density;
        float availableHeightDp = getResources().getDisplayMetrics().heightPixels / density;
        uiScale = Math.max(0.55f, Math.min(1.0f, availableHeightDp / 260.0f));
        setBackgroundColor(Color.TRANSPARENT);

        SculptTouchSurface touch = new SculptTouchSurface();

        // Reference-matched editor: fixed top bar, left brush workspace, open viewport,
        // right tool rail and bottom shortcut shelf. The GL surface remains visible below.
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.TRANSPARENT);
        FrameLayout.LayoutParams rootParams = new FrameLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT);
        addView(root, rootParams);

        LinearLayout top = new LinearLayout(activity);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(9), dp(3), dp(9), dp(3));
        top.setBackgroundColor(0xF21B1B1B);
        root.addView(top, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(43)));

        TextView home = button("⌂");
        top.addView(home, new LinearLayout.LayoutParams(dp(42), LayoutParams.MATCH_PARENT));
        home.setOnClickListener(v -> this.onExit.run());
        TextView save = button("▣");
        top.addView(save, new LinearLayout.LayoutParams(dp(42), LayoutParams.MATCH_PARENT));
        save.setOnClickListener(v -> {
            File folder = activity.getExternalFilesDir(null);
            if (folder == null) { Toast.makeText(activity, "Save folder unavailable.", Toast.LENGTH_LONG).show(); return; }
            File file = new File(folder, "Novaingine_Sculpt_Scene.nova");
            engine.saveSceneAsync(file.getAbsolutePath(), ok -> Toast.makeText(activity,
                    ok ? "Scene saved: " + file.getAbsolutePath() : "Scene save failed.",
                    Toast.LENGTH_LONG).show());
        });
        TextView importButton = button("⇧");
        top.addView(importButton, new LinearLayout.LayoutParams(dp(42), LayoutParams.MATCH_PARENT));
        importButton.setOnClickListener(v -> {
            if (activity instanceof MainActivity) ((MainActivity) activity).openSculptAssetPicker();
            else Toast.makeText(activity, "Import picker unavailable.", Toast.LENGTH_SHORT).show();
        });
        TextView refine = button("REFINE");
        top.addView(refine, new LinearLayout.LayoutParams(dp(58), LayoutParams.MATCH_PARENT));
        refine.setOnClickListener(v -> {
            new android.app.AlertDialog.Builder(activity)
                    .setTitle("Refine sculpt topology")
                    .setMessage("Split each triangle into four and interpolate the existing mask. This increases mesh density without changing the overall silhouette. Mobile limits apply.")
                    .setNegativeButton("CANCEL", null)
                    .setPositiveButton("REFINE", (dialog, which) -> engine.refineSculptMesh(result -> {
                        String message = result == 1 ? "Sculpt mesh refined."
                                : result == -2 ? "Finish the current stroke, then try again."
                                : result == -3 ? "Mesh is already at the mobile topology limit."
                                : result == -1 ? "Enter Sculpt Mode and select its sculpt mesh first."
                                : "Refinement failed (" + result + ").";
                        Toast.makeText(activity, message, Toast.LENGTH_LONG).show();
                        status.setText(message);
                    })).show();
        });
        TextView project = label("NOVAINGINE  •  SCULPT WORKSPACE", 11, Color.WHITE, true);
        project.setGravity(Gravity.CENTER);
        top.addView(project, new LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1));
        TextView undo = button("↶");
        top.addView(undo, new LinearLayout.LayoutParams(dp(42), LayoutParams.MATCH_PARENT));
        undo.setOnClickListener(v -> engine.undoScene());
        TextView redo = button("↷");
        top.addView(redo, new LinearLayout.LayoutParams(dp(42), LayoutParams.MATCH_PARENT));
        redo.setOnClickListener(v -> engine.redoScene());
        TextView help = button("?");
        top.addView(help, new LinearLayout.LayoutParams(dp(36), LayoutParams.MATCH_PARENT));
        help.setOnClickListener(v -> new android.app.AlertDialog.Builder(activity)
                .setTitle("Sculpt workspace")
                .setMessage("Choose a brush, adjust its controls, then drag on the model. Use two fingers to orbit/pan/zoom. Select a transform mode to edit the selected mesh.")
                .setPositiveButton("OK", null).show());
        TextView leftDockToggle = button("◧");
        top.addView(leftDockToggle, new LinearLayout.LayoutParams(dp(38), LayoutParams.MATCH_PARENT));
        leftDockToggle.setOnClickListener(v -> {
            if (brushDock != null) {
                boolean show = brushDock.getVisibility() != View.VISIBLE;
                brushDock.setVisibility(show ? View.VISIBLE : View.GONE);
                if (leftResizeHandle != null) leftResizeHandle.setVisibility(show ? View.VISIBLE : View.GONE);
            }
        });
        TextView bottomDockToggle = button("▤");
        top.addView(bottomDockToggle, new LinearLayout.LayoutParams(dp(38), LayoutParams.MATCH_PARENT));
        bottomDockToggle.setOnClickListener(v -> toggleBottomDock());
        TextView rightDockToggle = button("◨");
        top.addView(rightDockToggle, new LinearLayout.LayoutParams(dp(38), LayoutParams.MATCH_PARENT));
        rightDockToggle.setOnClickListener(v -> {
            if (rightDock != null) {
                boolean show = rightDock.getVisibility() != View.VISIBLE;
                rightDock.setVisibility(show ? View.VISIBLE : View.GONE);
                if (rightResizeHandle != null) rightResizeHandle.setVisibility(show ? View.VISIBLE : View.GONE);
            }
        });

        LinearLayout workArea = new LinearLayout(activity);
        workArea.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(workArea, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1));

        // Left quick-control rail (matching the reference's narrow strip).
        LinearLayout leftRail = new LinearLayout(activity);
        leftRail.setOrientation(LinearLayout.VERTICAL);
        leftRail.setGravity(Gravity.CENTER_HORIZONTAL);
        leftRail.setPadding(dp(3), dp(5), dp(3), dp(5));
        leftRail.setBackgroundColor(0xE9161719);
        workArea.addView(leftRail, new LinearLayout.LayoutParams(dp(42), LayoutParams.MATCH_PARENT));
        TextView activeBrush = button("●");
        leftRail.addView(activeBrush, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(38)));
        activeBrush.setOnClickListener(v -> showBrushPicker());
        TextView symmetry = button("X");
        leftRail.addView(symmetry, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(38)));
        symmetry.setOnClickListener(v -> {
            symmetryAxis = symmetryAxis == 0 ? 1 : symmetryAxis == 1 ? 2 : symmetryAxis == 2 ? -1 : 0;
            symmetry.setText(symmetryAxis < 0 ? "Ø" : symmetryAxis == 0 ? "X" : symmetryAxis == 1 ? "Y" : "Z");
            symmetry.setTextColor(symmetryAxis < 0 ? Color.WHITE : ACCENT);
            applyOptions();
        });
        TextView subtractQuick = button("±");
        leftRail.addView(subtractQuick, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(38)));
        subtractQuick.setOnClickListener(v -> {
            subtract = !subtract;
            subtractQuick.setText(subtract ? "−" : "+");
            subtractQuick.setTextColor(subtract ? ACCENT : Color.WHITE);
            applyOptions();
        });
        TextView smoothQuick = button("S");
        leftRail.addView(smoothQuick, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(38)));
        smoothQuick.setOnClickListener(v -> selectBrush(1));
        TextView maskQuick = button("M");
        leftRail.addView(maskQuick, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(38)));
        maskQuick.setOnClickListener(v -> selectBrush(7));
        TextView undoQuick = button("↶");
        leftRail.addView(undoQuick, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(38)));
        undoQuick.setOnClickListener(v -> engine.undoScene());
        TextView redoQuick = button("↷");
        leftRail.addView(redoQuick, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(38)));
        redoQuick.setOnClickListener(v -> engine.redoScene());

        // Brush browser panel: categories and a three-column, scrollable brush grid.
        LinearLayout browser = new LinearLayout(activity);
        browser.setOrientation(LinearLayout.VERTICAL);
        browser.setPadding(dp(7), dp(6), dp(7), dp(5));
        browser.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xF52B2B2B, 0xF51C1C1C}));
        LinearLayout.LayoutParams browserParams = new LinearLayout.LayoutParams(dp(292), LayoutParams.MATCH_PARENT);
        browserParams.setMargins(dp(4), dp(4), dp(4), dp(4));
        workArea.addView(browser, browserParams);
        brushDock = browser;
        leftResizeHandle = createResizeHandle(true, browser);
        workArea.addView(leftResizeHandle, new LinearLayout.LayoutParams(dp(6), LayoutParams.MATCH_PARENT));

        LinearLayout browserHeader = new LinearLayout(activity);
        browserHeader.setGravity(Gravity.CENTER_VERTICAL);
        browser.addView(browserHeader, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(38)));
        TextView browserTitle = label("Brushes", 17, Color.WHITE, true);
        browserTitle.setGravity(Gravity.CENTER);
        browserHeader.addView(browserTitle, new LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1));
        TextView filter = button("☷");
        browserHeader.addView(filter, new LinearLayout.LayoutParams(dp(38), LayoutParams.MATCH_PARENT));
        filter.setOnClickListener(v -> showBrushPicker());
        TextView more = button("⋯");
        browserHeader.addView(more, new LinearLayout.LayoutParams(dp(34), LayoutParams.MATCH_PARENT));
        more.setOnClickListener(v -> new android.app.AlertDialog.Builder(activity)
                .setTitle("Brush options")
                .setItems(new String[]{"All brushes", "Recent", "Favorites", "Brush settings"}, (d, which) -> {
                    if (which == 3) showBrushSettings();
                }).show());

        TextView featured = label("  SELECTED  •  " + BRUSHES[selectedBrush].toUpperCase(), 10, ACCENT, true);
        featured.setGravity(Gravity.CENTER_VERTICAL);
        browser.addView(featured, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(27)));

        HorizontalScrollView categoriesScroll = new HorizontalScrollView(activity);
        categoriesScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout categories = new LinearLayout(activity);
        categories.setGravity(Gravity.CENTER_VERTICAL);
        categoriesScroll.addView(categories);
        browser.addView(categoriesScroll, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(34)));
        String[] categoryNames = {"Recent", "Favorites", "All", "Clay", "Move", "Crease", "Mask", "Flatten", "Special"};
        for (String category : categoryNames) {
            TextView cat = button(category);
            categories.addView(cat, new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, dp(30)));
            cat.setOnClickListener(v -> {
                // Categories are quick filters; all brushes remain available in All.
                String key = category.equals("Move") ? "Grab" : category.equals("Crease") ? "Crease" : category;
                if (category.equals("All") || category.equals("Recent") || category.equals("Favorites")) {
                    for (TextView b : brushButtons) if (b != null) b.setVisibility(View.VISIBLE);
                } else {
                    for (int i = 0; i < brushButtons.length; i++) {
                        String n = BRUSHES[i].toLowerCase(java.util.Locale.US);
                        boolean show = category.equals("Clay") ? n.contains("clay") || n.equals("blob")
                                : category.equals("Mask") ? n.contains("mask")
                                : category.equals("Flatten") ? n.contains("flatten") || n.contains("scrape")
                                : category.equals("Special") ? i >= 12
                                : n.contains(key.toLowerCase(java.util.Locale.US));
                        brushButtons[i].setVisibility(show ? View.VISIBLE : View.GONE);
                    }
                }
            });
        }

        ScrollView brushScroll = new ScrollView(activity);
        brushScroll.setFillViewport(false);
        brushScroll.setVerticalScrollBarEnabled(true);
        LinearLayout brushGrid = new LinearLayout(activity);
        brushGrid.setOrientation(LinearLayout.VERTICAL);
        brushScroll.addView(brushGrid);
        browser.addView(brushScroll, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1));

        for (int row = 0; row < (BRUSHES.length + 2) / 3; row++) {
            LinearLayout line = new LinearLayout(activity);
            line.setOrientation(LinearLayout.HORIZONTAL);
            brushGrid.addView(line, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(88)));
            for (int col = 0; col < 3; col++) {
                final int index = row * 3 + col;
                if (index >= BRUSHES.length) break;
                LinearLayout tile = new LinearLayout(activity);
                tile.setOrientation(LinearLayout.VERTICAL);
                tile.setGravity(Gravity.CENTER);
                tile.setPadding(dp(2), dp(4), dp(2), dp(3));
                tile.setBackground(background(index == selectedBrush ? 0xFF51402B : 0xFF292929));
                TextView icon = label("◉", 22, index == selectedBrush ? ACCENT : 0xFFD7D7D7, true);
                icon.setGravity(Gravity.CENTER);
                tile.addView(icon, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(40)));
                TextView name = label(BRUSHES[index], 9, Color.WHITE, true);
                name.setGravity(Gravity.CENTER);
                tile.addView(name, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(30)));
                LinearLayout.LayoutParams tileParams = new LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1);
                tileParams.setMargins(dp(2), dp(2), dp(2), dp(2));
                line.addView(tile, tileParams);
                brushButtons[index] = name;
                tile.setOnClickListener(v -> {
                    selectBrush(index);
                    featured.setText("  SELECTED  •  " + BRUSHES[index].toUpperCase());
                    for (int i = 0; i < brushButtons.length; i++) {
                        if (brushButtons[i] != null) {
                            brushButtons[i].setTextColor(i == index ? ACCENT : Color.WHITE);
                            View parent = (View) brushButtons[i].getParent();
                            if (parent != null) parent.setBackground(background(i == index ? 0xFF51402B : 0xFF292929));
                        }
                    }
                });
            }
        }

        status = label("Drag on the model to sculpt.", 9, 0xFFB8C4D1, false);
        status.setGravity(Gravity.CENTER_VERTICAL);
        browser.addView(status, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(23)));

        brushControls = new LinearLayout(activity);
        brushControls.setOrientation(LinearLayout.VERTICAL);
        brushControls.setPadding(dp(2), 0, dp(2), 0);
        browser.addView(brushControls, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(112)));
        addSlider(brushControls, "SIZE", 1, 300, 7, value -> {
            radius = 0.02f + ((value - 1) / 299.0f) * 7.98f; applyOptions();
        });
        addSlider(brushControls, "STRENGTH", 0, 100, 40, value -> {
            strength = value / 100.0f; applyOptions();
        });
        addSlider(brushControls, "HARDNESS", 0, 100, 50, value -> {
            falloff = value / 100.0f; applyOptions();
        });
        addSlider(brushControls, "PRESSURE", 5, 100, 100, value -> {
            pressure = value / 100.0f; applyOptions();
        });

        // The center remains an unobstructed 3D viewport.
        // Sculpt input is confined to the viewport gap so it cannot intercept editor controls.
        View viewportSpacer = touch;
        viewportSpacer.setBackgroundColor(Color.TRANSPARENT);
        workArea.addView(viewportSpacer, new LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1));

        // Right-side modelling and scene rail, like the reference.
        LinearLayout rightRail = new LinearLayout(activity);
        rightRail.setOrientation(LinearLayout.VERTICAL);
        rightRail.setGravity(Gravity.CENTER_HORIZONTAL);
        rightRail.setPadding(dp(3), dp(5), dp(3), dp(5));
        rightRail.setBackgroundColor(0xE9161719);
        rightResizeHandle = createResizeHandle(false, rightRail);
        workArea.addView(rightResizeHandle, new LinearLayout.LayoutParams(dp(6), LayoutParams.MATCH_PARENT));
        workArea.addView(rightRail, new LinearLayout.LayoutParams(dp(43), LayoutParams.MATCH_PARENT));
        rightDock = rightRail;
        TextView scene = button("▤");
        rightRail.addView(scene, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(40)));
        scene.setOnClickListener(v -> showSceneObjects());
        TextView layers = button("▱");
        rightRail.addView(layers, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(40)));
        layers.setOnClickListener(v -> showSceneObjects());
        TextView shapes = button("◇");
        rightRail.addView(shapes, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(40)));
        shapes.setOnClickListener(v -> showShapes());
        TextView transform = button("XYZ");
        rightRail.addView(transform, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(40)));
        transform.setOnClickListener(v -> showTransformEditor(touch));
        TextView orbit = button("◎");
        rightRail.addView(orbit, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(40)));
        orbit.setOnClickListener(v -> {
            orbitMode = !orbitMode;
            sculptEnabled = !orbitMode;
            applyOptions();
            status.setText(orbitMode ? "Orbit mode: drag to rotate the selected object." : "Sculpt mode active.");
        });
        TextView symmetryRight = button("Y");
        rightRail.addView(symmetryRight, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(40)));
        symmetryRight.setOnClickListener(v -> {
            symmetryAxis = symmetryAxis == 1 ? -1 : 1;
            symmetryRight.setText(symmetryAxis == 1 ? "Y" : "Ø");
            applyOptions();
        });
        TextView export = button("⇧");
        rightRail.addView(export, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(40)));
        export.setOnClickListener(v -> exportMesh());

        bottomDock = new LinearLayout(activity);
        bottomDock.setOrientation(LinearLayout.VERTICAL);
        bottomDock.setBackgroundColor(0xF01B2027);
        bottomDock.setVisibility(View.GONE);
        root.addView(bottomDock, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0));
        View dockGrip = new View(activity);
        dockGrip.setBackgroundColor(ACCENT);
        bottomDock.addView(dockGrip, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(6)));
        final float[] dockY = new float[1];
        final int[] dockHeight = new int[1];
        dockGrip.setOnTouchListener((v, event) -> {
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) bottomDock.getLayoutParams();
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) { dockY[0] = event.getRawY(); dockHeight[0] = lp.height; return true; }
            if (event.getActionMasked() == MotionEvent.ACTION_MOVE) { lp.height = Math.max(dp(90), Math.min(dp(320), dockHeight[0] + Math.round(dockY[0] - event.getRawY()))); bottomDock.setLayoutParams(lp); return true; }
            return event.getActionMasked() == MotionEvent.ACTION_UP;
        });
        LinearLayout dockTabs = new LinearLayout(activity);
        dockTabs.setGravity(Gravity.CENTER_VERTICAL);
        bottomDock.addView(dockTabs, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(34)));
        FrameLayout dockBody = new FrameLayout(activity);
        bottomDock.addView(dockBody, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1));
        String[] dockNames = {"Console", "Objects", "Properties", "Timeline", "Assets"};
        for (String dockName : dockNames) {
            TextView tab = button(dockName);
            dockTabs.addView(tab, new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT));
            tab.setOnClickListener(v -> showDockPanel(dockName, dockBody, touch));
        }
        showDockPanel("Console", dockBody, touch);

        // Bottom brush shelf: persistent shortcuts, not a close/drawer control.
        HorizontalScrollView shelfScroll = new HorizontalScrollView(activity);
        shelfScroll.setHorizontalScrollBarEnabled(false);
        shelfScroll.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xF52A2A2A, 0xF51B1B1B}));
        LinearLayout shelf = new LinearLayout(activity);
        shelf.setGravity(Gravity.CENTER_VERTICAL);
        shelf.setPadding(dp(7), dp(3), dp(7), dp(3));
        shelfScroll.addView(shelf);
        root.addView(shelfScroll, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(52)));
        int[] favorites = {1, 8, 0, 3, 4, 5, 6, 9, 10, 12, 13, 14, 15, 7, 11};
        for (int brush : favorites) {
            final int index = brush;
            TextView shortcut = button(BRUSHES[index].equals("Clay Strips") ? "▧" : "●");
            shortcut.setTextSize(16);
            LinearLayout.LayoutParams shortcutParams = new LinearLayout.LayoutParams(dp(42), LayoutParams.MATCH_PARENT);
            shortcutParams.setMargins(dp(2), 0, dp(2), 0);
            shelf.addView(shortcut, shortcutParams);
            shortcut.setOnClickListener(v -> {
                selectBrush(index);
                featured.setText("  SELECTED  •  " + BRUSHES[index].toUpperCase());
                for (int i = 0; i < brushButtons.length; i++) {
                    if (brushButtons[i] != null) {
                        brushButtons[i].setTextColor(i == index ? ACCENT : Color.WHITE);
                        View parent = (View) brushButtons[i].getParent();
                        if (parent != null) parent.setBackground(background(i == index ? 0xFF51402B : 0xFF292929));
                    }
                }
            });
        }
        TextView shapeShelf = button("SHAPES");
        shelf.addView(shapeShelf, new LinearLayout.LayoutParams(dp(76), LayoutParams.MATCH_PARENT));
        shapeShelf.setOnClickListener(v -> showShapes());
        TextView moveShelf = button("MOVE");
        shelf.addView(moveShelf, new LinearLayout.LayoutParams(dp(64), LayoutParams.MATCH_PARENT));
        moveShelf.setOnClickListener(v -> setTransformMode(0, touch));
        TextView rotateShelf = button("ROT");
        shelf.addView(rotateShelf, new LinearLayout.LayoutParams(dp(54), LayoutParams.MATCH_PARENT));
        rotateShelf.setOnClickListener(v -> setTransformMode(1, touch));
        TextView scaleShelf = button("SCALE");
        shelf.addView(scaleShelf, new LinearLayout.LayoutParams(dp(64), LayoutParams.MATCH_PARENT));
        scaleShelf.setOnClickListener(v -> setTransformMode(2, touch));

        updateBrushButtons();
        applyOptions();
    }

    private View createResizeHandle(boolean leftPanel, View panel) {
        View handle = new View(activity);
        handle.setBackgroundColor(0xFF596675);
        handle.setOnTouchListener((view, event) -> {
            LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) panel.getLayoutParams();
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                view.setTag(new float[]{event.getRawX(), params.width});
                return true;
            }
            if (event.getActionMasked() == MotionEvent.ACTION_MOVE) {
                float[] start = (float[]) view.getTag();
                if (start == null) return true;
                float delta = event.getRawX() - start[0];
                float next = start[1] + (leftPanel ? delta : -delta);
                params.width = (int) Math.max(dp(leftPanel ? 190 : 43), Math.min(dp(leftPanel ? 430 : 220), next));
                panel.setLayoutParams(params);
                return true;
            }
            return event.getActionMasked() == MotionEvent.ACTION_UP
                    || event.getActionMasked() == MotionEvent.ACTION_CANCEL;
        });
        return handle;
    }

    private void showDockPanel(String name, FrameLayout host, View touch) {
        host.removeAllViews();
        LinearLayout panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(10), dp(5), dp(10), dp(5));
        ScrollView scroll = new ScrollView(activity);
        scroll.addView(panel);
        host.addView(scroll, new FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        if ("Console".equals(name)) {
            panel.addView(label("NOVAINGINE CONSOLE", 11, ACCENT, true));
            String[] objects = engine.getSceneObjects();
            float[] transform = engine.getSelectedTransform();
            panel.addView(label("Renderer FPS: " + engine.getFps(), 10, Color.WHITE, false));
            panel.addView(label("Scene objects: " + (objects == null ? 0 : objects.length), 10, Color.WHITE, false));
            panel.addView(label("Selected object: " + (transform == null ? "None" : "Available"), 10, Color.WHITE, false));
            TextView refresh = button("REFRESH");
            panel.addView(refresh);
            refresh.setOnClickListener(v -> showDockPanel("Console", host, touch));
        } else if ("Objects".equals(name)) {
            panel.addView(label("SCENE HIERARCHY", 11, ACCENT, true));
            String[] objects = engine.getSceneObjects();
            if (objects == null || objects.length == 0) {
                panel.addView(label("No objects in the scene. Use SHAPES to create one.", 10, Color.WHITE, false));
            } else {
                for (String row : objects) {
                    String value = row == null ? "" : row;
                    int split = value.indexOf('|');
                    int id = 0;
                    try { id = Integer.parseInt(split < 0 ? value : value.substring(0, split)); } catch (NumberFormatException ignored) {}
                    String objectName = split < 0 ? value : value.substring(split + 1);
                    final int objectId = id;
                    TextView item = button(objectName);
                    panel.addView(item, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(36)));
                    item.setOnClickListener(v -> {
                        if (objectId != 0) {
                            engine.selectObjectById(objectId);
                            status.setText("Selected: " + objectName);
                        }
                    });
                }
            }
            TextView refresh = button("REFRESH HIERARCHY");
            panel.addView(refresh);
            refresh.setOnClickListener(v -> showDockPanel("Objects", host, touch));
        } else if ("Properties".equals(name)) {
            panel.addView(label("TRANSFORM PROPERTIES", 11, ACCENT, true));
            float[] values = engine.getSelectedTransform();
            if (values == null || values.length < 9) {
                panel.addView(label("Select an object in the viewport or Objects tab.", 10, Color.WHITE, false));
            } else {
                String[] names = {"Position", "Rotation", "Scale"};
                for (int c = 0; c < 3; c++) {
                    panel.addView(label(names[c] + "   X " + values[c*3] + "   Y " + values[c*3+1] + "   Z " + values[c*3+2], 10, Color.WHITE, false));
                }
                TextView edit = button("EDIT XYZ");
                panel.addView(edit);
                edit.setOnClickListener(v -> showTransformEditor(touch));
            }
        } else if ("Timeline".equals(name)) {
            panel.addView(label("TRANSFORM TIMELINE", 11, ACCENT, true));
            TextView info = label("Keyframes: " + transformKeyframes.size() + "  •  1.2 seconds between keys", 10, Color.WHITE, false);
            panel.addView(info);
            TextView addKey = button("ADD KEYFRAME FROM SELECTED OBJECT");
            panel.addView(addKey);
            addKey.setOnClickListener(v -> {
                float[] transform = engine.getSelectedTransform();
                if (transform == null || transform.length < 9) {
                    Toast.makeText(activity, "Select an object before recording a keyframe.", Toast.LENGTH_SHORT).show();
                    return;
                }
                transformKeyframes.add(transform.clone());
                info.setText("Keyframes: " + transformKeyframes.size() + "  •  1.2 seconds between keys");
                status.setText("Timeline keyframe " + transformKeyframes.size() + " recorded.");
            });
            TextView play = button("PLAY TIMELINE");
            panel.addView(play);
            play.setOnClickListener(v -> startTimelinePlayback());
            TextView stop = button("STOP");
            panel.addView(stop);
            stop.setOnClickListener(v -> stopTimelinePlayback());
            TextView clear = button("CLEAR KEYFRAMES");
            panel.addView(clear);
            clear.setOnClickListener(v -> {
                stopTimelinePlayback();
                transformKeyframes.clear();
                info.setText("Keyframes: 0  •  1.2 seconds between keys");
                status.setText("Timeline cleared.");
            });
            panel.addView(label("Keyframes are held in memory for this sculpt session; they are not yet saved into project files.", 9, 0xFFFFC56B, false));
        } else if ("Assets".equals(name)) {
            panel.addView(label("PROJECT ASSETS", 11, ACCENT, true));
            panel.addView(label("Import a glTF binary model (.glb) or export the selected sculpt mesh as OBJ.", 10, Color.WHITE, false));
            TextView importAsset = button("IMPORT .GLB");
            panel.addView(importAsset);
            importAsset.setOnClickListener(v -> {
                if (activity instanceof MainActivity) ((MainActivity) activity).openSculptAssetPicker();
            });
            TextView exportAsset = button("EXPORT SELECTED OBJ");
            panel.addView(exportAsset);
            exportAsset.setOnClickListener(v -> exportMesh());
        }
    }

    private void startTimelinePlayback() {
        if (transformKeyframes.size() < 2) {
            Toast.makeText(activity, "Record at least two keyframes first.", Toast.LENGTH_SHORT).show();
            return;
        }
        timelineHandler.removeCallbacks(timelineTick);
        timelinePlaying = true;
        timelineStartMs = SystemClock.uptimeMillis();
        timelineHandler.post(timelineTick);
        status.setText("Timeline playback started.");
    }

    private void stopTimelinePlayback() {
        timelinePlaying = false;
        timelineHandler.removeCallbacks(timelineTick);
        status.setText("Timeline playback stopped.");
    }

    private void advanceTimeline() {
        if (!timelinePlaying || transformKeyframes.size() < 2) return;
        long elapsed = SystemClock.uptimeMillis() - timelineStartMs;
        final long segmentDuration = 1200L;
        int segment = (int) (elapsed / segmentDuration);
        if (segment >= transformKeyframes.size() - 1) {
            engine.setSelectedTransform(transformKeyframes.get(transformKeyframes.size() - 1));
            stopTimelinePlayback();
            return;
        }
        float t = (elapsed % segmentDuration) / (float) segmentDuration;
        float[] from = transformKeyframes.get(segment);
        float[] to = transformKeyframes.get(segment + 1);
        float[] current = new float[9];
        for (int i = 0; i < current.length; i++) current[i] = from[i] + (to[i] - from[i]) * t;
        engine.setSelectedTransform(current);
        timelineHandler.postDelayed(timelineTick, 16L);
    }

    @Override protected void onDetachedFromWindow() {
        stopTimelinePlayback();
        timelineHandler.removeCallbacksAndMessages(null);
        super.onDetachedFromWindow();
    }

    private void toggleBottomDock() {
        if (bottomDock == null) return;
        boolean show = bottomDock.getVisibility() != View.VISIBLE;
        bottomDock.setVisibility(show ? View.VISIBLE : View.GONE);
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) bottomDock.getLayoutParams();
        lp.height = show ? dp(150) : 0;
        bottomDock.setLayoutParams(lp);
    }

    private void selectBrush(int index) {
        selectedBrush = Math.max(0, Math.min(BRUSHES.length - 1, index));
        activeTransformMode = -1;
        orbitMode = false;
        sculptEnabled = true;
        applyOptions();
        updateBrushButtons();
        status.setText(BRUSH_DESCRIPTIONS[selectedBrush]);
    }

    private void showBrushPicker() {
        new android.app.AlertDialog.Builder(activity).setTitle("Brushes")
                .setItems(BRUSHES, (dialog, which) -> selectBrush(which)).show();
    }

    private void showBrushSettings() {
        new android.app.AlertDialog.Builder(activity).setTitle("Brush settings")
                .setMessage("Size, strength, hardness and pressure are available in the Brushes panel.")
                .setPositiveButton("OK", null).show();
    }

    private void showShapes() {
        String[] names = {"Sphere", "Plane", "Cube", "Cylinder", "Cone", "Torus", "Capsule", "Wedge", "Round Sphere"};
        int[] densities = {64, 128, 32, 48, 48, 48, 48, 32, 64};
        new android.app.AlertDialog.Builder(activity).setTitle("Shapes")
                .setItems(names, (dialog, which) -> createMesh(which, densities[which])).show();
    }

    private void setTransformMode(int mode, View touch) {
        engine.setTransformMode(mode);
        activeTransformMode = mode;
        transformComponent = mode;
        sculptEnabled = false;
        orbitMode = false;
        transformDragState = engine.getSelectedTransform();
        applyOptions();
        touch.invalidate();
        status.setText(mode == 0 ? "Move: drag an XYZ gizmo axis or set coordinates."
                : mode == 1 ? "Rotate selected mesh." : "Scale selected mesh.");
    }

    private void showTransformEditor(View touch) {
        float[] values = engine.getSelectedTransform();
        if (values == null || values.length < 9) {
            Toast.makeText(activity, "Select an object first.", Toast.LENGTH_SHORT).show();
            return;
        }
        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(12), dp(6), dp(12), dp(6));
        EditText[][] fields = new EditText[3][3];
        String[] names = {"POSITION", "ROTATION", "SCALE"};
        for (int component = 0; component < 3; component++) {
            TextView heading = label(names[component], 11, ACCENT, true);
            content.addView(heading);
            LinearLayout row = new LinearLayout(activity);
            content.addView(row);
            for (int axis = 0; axis < 3; axis++) {
                EditText input = new EditText(activity);
                input.setSingleLine(true);
                input.setTextSize(11);
                input.setHint(axis == 0 ? "X" : axis == 1 ? "Y" : "Z");
                input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER
                        | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
                        | android.text.InputType.TYPE_NUMBER_FLAG_SIGNED);
                input.setText(String.valueOf(values[component * 3 + axis]));
                row.addView(input, new LinearLayout.LayoutParams(0, dp(40), 1));
                fields[component][axis] = input;
            }
        }
        new android.app.AlertDialog.Builder(activity).setTitle("XYZ Transform")
                .setView(content).setPositiveButton("APPLY", (dialog, which) -> {
                    float[] next = values.clone();
                    try {
                        for (int c = 0; c < 3; c++) for (int a = 0; a < 3; a++)
                            next[c * 3 + a] = Float.parseFloat(fields[c][a].getText().toString());
                        engine.setSelectedTransform(next);
                        positionInputs = fields[transformComponent];
                        touch.invalidate();
                        status.setText("Position, rotation and scale updated.");
                    } catch (NumberFormatException error) {
                        status.setText("Invalid XYZ value.");
                    }
                }).setNegativeButton("CANCEL", null).show();
    }

    private void showSceneObjects() {
        String[] objects = engine.getSceneObjects();
        if (objects == null || objects.length == 0) {
            new android.app.AlertDialog.Builder(activity).setTitle("Scene").setMessage("No objects in the scene.")
                    .setPositiveButton("OK", null).show();
            return;
        }
        String[] labels = new String[objects.length];
        int[] ids = new int[objects.length];
        for (int i = 0; i < objects.length; i++) {
            String row = objects[i] == null ? "" : objects[i];
            int split = row.indexOf('|');
            try { ids[i] = Integer.parseInt(split < 0 ? row : row.substring(0, split)); }
            catch (NumberFormatException ignored) { ids[i] = 0; }
            labels[i] = split < 0 ? row : row.substring(split + 1);
        }
        new android.app.AlertDialog.Builder(activity).setTitle("Scene objects")
                .setItems(labels, (dialog, which) -> { if (ids[which] != 0) engine.selectObjectById(ids[which]); })
                .setNegativeButton("CLOSE", null).show();
    }

    private void createMesh(int shape, int density) {
        engine.createSculptMesh(shape, density, id -> {
            if (id > 0) {
                String[] names = {"sphere", "plane", "cube", "cylinder", "cone",
                        "torus", "capsule", "wedge", "rounded sphere"};
                status.setText("Sculpt " + names[Math.max(0, Math.min(8, shape))]
                        + " created and selected. Drag to sculpt.");
                float[] transform = engine.getSelectedTransform();
                if (transform != null && transform.length >= 9) {
                    for (int axis = 0; axis < 3; axis++) {
                        if (positionInputs[axis] != null) positionInputs[axis].setText(String.valueOf(transform[axis + transformComponent * 3]));
                    }
                }
            } else {
                status.setText("Could not create mesh. Try again.");
            }
        });
    }

    private void exportMesh() {
        File folder = activity.getExternalFilesDir(null);
        if (folder == null) {
            Toast.makeText(activity, "Export folder unavailable", Toast.LENGTH_LONG).show();
            return;
        }
        File file = new File(folder, "Novaingine_Sculpt.obj");
        engine.exportSelectedObj(file.getAbsolutePath(), ok ->
                Toast.makeText(activity, ok ? "OBJ exported to app storage" :
                        "Select a sculptable mesh first", Toast.LENGTH_LONG).show());
    }

    private interface SliderChange { void onChanged(int value); }

    private void addSlider(LinearLayout parent, String name, int min, int max,
                           int initial, SliderChange change) {
        LinearLayout row = new LinearLayout(activity);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = label(name, 9, 0xFFD3DCE5, true);
        row.addView(title, new LinearLayout.LayoutParams(dp(66), dp(28)));
        SeekBar seek = new SeekBar(activity);
        seek.setMax(max - min);
        seek.setProgress(initial - min);
        row.addView(seek, new LinearLayout.LayoutParams(0, dp(30), 1));
        parent.addView(row, new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, dp(31)));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                change.onChanged(progress + min);
            }
            @Override public void onStartTrackingTouch(SeekBar bar) {}
            @Override public void onStopTrackingTouch(SeekBar bar) {}
        });
    }

    private void applyOptions() {
        engine.setSculptOptions(sculptEnabled, selectedBrush, radius, strength,
                falloff, symmetryAxis, subtract, pressure);
    }

    private void updateBrushButtons() {
        for (int i = 0; i < brushButtons.length; i++) {
            if (brushButtons[i] == null) continue;
            brushButtons[i].setText(BRUSHES[i] + (i == selectedBrush ? "  • SELECTED" : ""));
            brushButtons[i].setTextColor(i == selectedBrush ? ACCENT : Color.WHITE);
        }
    }

    private TextView button(String text) {
        TextView view = label(text, 10, Color.WHITE, true);
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(9), dp(6), dp(9), dp(6));
        view.setBackground(background(0xFF303B48));
        view.setClickable(true);
        view.setFocusable(true);
        return view;
    }

    private TextView label(String text, float size, int color, boolean bold) {
        TextView view = new TextView(activity);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private GradientDrawable background(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(6));
        d.setStroke(dp(1), 0xFF465464);
        return d;
    }

    private LinearLayout.LayoutParams weighted(int height, float weight) {
        return new LinearLayout.LayoutParams(0,
                height == 0 ? LayoutParams.MATCH_PARENT : dp(height), weight);
    }

    private int dp(float value) {
        return (int)(value * getResources().getDisplayMetrics().density * uiScale + 0.5f);
    }

    private final class SculptTouchSurface extends View {
        private float lastX;
        private float lastY;
        private float lastDistance;

        SculptTouchSurface() {
            super(activity);
            setBackgroundColor(Color.TRANSPARENT);
            setClickable(true);
            setFocusable(true);
        }

        private float distanceToSegment(float px, float py, float ax, float ay, float bx, float by) {
            float vx = bx - ax, vy = by - ay;
            float length2 = vx * vx + vy * vy;
            float t = length2 == 0 ? 0 : ((px - ax) * vx + (py - ay) * vy) / length2;
            t = Math.max(0, Math.min(1, t));
            float dx = px - (ax + t * vx), dy = py - (ay + t * vy);
            return (float)Math.sqrt(dx * dx + dy * dy);
        }

        private void updateGizmoCenter() {
            float[] projected = engine.projectSelectedGizmo();
            if (projected == null || projected.length < 16 || projected[3] <= 0.0001f
                    || projected[2] < -1.0f || projected[2] > 1.0f
                    || Math.abs(projected[0]) > 1.15f || Math.abs(projected[1]) > 1.15f) {
                gizmoVisible = false;
                return;
            }
            gizmoCenterX = (projected[0] + 1.0f) * 0.5f * getWidth();
            gizmoCenterY = (1.0f - projected[1]) * 0.5f * getHeight();
            gizmoVisible = true;
            for (int axis=0;axis<3;axis++) {
                int offset=(axis+1)*4;
                gizmoEndVisible[axis]=projected[offset+3]>0.0001f
                        && projected[offset+2]>=-1.0f && projected[offset+2]<=1.0f;
                gizmoEndX[axis]=(projected[offset]+1.0f)*0.5f*getWidth();
                gizmoEndY[axis]=(1.0f-projected[offset+1])*0.5f*getHeight();
            }
        }

        private int hitGizmoAxis(float x, float y) {
            updateGizmoCenter();
            if (!gizmoVisible) return -1;
            float cx = gizmoCenterX, cy = gizmoCenterY;
            for (int axis = 0; axis < 3; axis++) {
                if (gizmoEndVisible[axis] && distanceToSegment(x, y, cx, cy,
                        gizmoEndX[axis], gizmoEndY[axis]) < dp(18)) return axis;
            }
            return -1;
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (activeTransformMode != 0) return;
            updateGizmoCenter();
            if (!gizmoVisible) {
                postInvalidateDelayed(33);
                return;
            }
            float cx = gizmoCenterX, cy = gizmoCenterY;
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            paint.setStrokeWidth(dp(4));
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStyle(Paint.Style.STROKE);
            int[] colors = {0xFFFF5555, 0xFF55DD77, 0xFF5599FF};
            for (int axis = 0; axis < 3; axis++) {
                if (!gizmoEndVisible[axis]) continue;
                float ex=gizmoEndX[axis], ey=gizmoEndY[axis];
                paint.setColor(colors[axis]);
                paint.setStrokeWidth(gizmoAxis == axis ? dp(7) : dp(4));
                canvas.drawLine(cx, cy, ex, ey, paint);
                float angle = (float)Math.atan2(ey-cy, ex-cx);
                float head = dp(13);
                Path arrow = new Path();
                arrow.moveTo(ex, ey);
                arrow.lineTo(ex-head*(float)Math.cos(angle-0.55f), ey-head*(float)Math.sin(angle-0.55f));
                arrow.moveTo(ex, ey);
                arrow.lineTo(ex-head*(float)Math.cos(angle+0.55f), ey-head*(float)Math.sin(angle+0.55f));
                canvas.drawPath(arrow, paint);
                paint.setStyle(Paint.Style.FILL);
                canvas.drawText(axis == 0 ? "X" : axis == 1 ? "Y" : "Z", ex+dp(5), ey-dp(5), paint);
                paint.setStyle(Paint.Style.STROKE);
            }
            postInvalidateDelayed(33);
        }

        private float distance(MotionEvent e) {
            if (e.getPointerCount() < 2) return 0;
            float dx = e.getX(0) - e.getX(1);
            float dy = e.getY(0) - e.getY(1);
            return (float)Math.sqrt(dx * dx + dy * dy);
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            int action = event.getActionMasked();
            if (event.getPointerCount() == 1 && activeTransformMode == 0) {
                if (action == MotionEvent.ACTION_DOWN) {
                    gizmoAxis = hitGizmoAxis(event.getX(), event.getY());
                    if (gizmoAxis >= 0) {
                        transformDragState = engine.getSelectedTransform();
                        engine.beginTransformEdit();
                        lastX = event.getX(); lastY = event.getY();
                        return true;
                    }
                } else if (gizmoAxis >= 0 && action == MotionEvent.ACTION_MOVE) {
                    float dx = event.getX() - lastX, dy = event.getY() - lastY;
                    if (transformDragState != null && transformDragState.length >= 9) {
                        float axisX=gizmoEndX[gizmoAxis]-gizmoCenterX;
                        float axisY=gizmoEndY[gizmoAxis]-gizmoCenterY;
                        float axisLengthSquared=axisX*axisX+axisY*axisY;
                        if(axisLengthSquared>dp(8)*dp(8)) {
                            // The projected world-axis endpoint is exactly one world unit away.
                            // Project the finger delta onto that visible axis; screen Y inversion
                            // is already accounted for by the projected endpoint.
                            float delta=(dx*axisX+dy*axisY)/axisLengthSquared;
                            transformDragState[gizmoAxis] += delta;
                        }
                        engine.updateSelectedTransform(transformDragState);
                        if (positionInputs[gizmoAxis] != null) positionInputs[gizmoAxis].setText(String.valueOf(transformDragState[gizmoAxis]));
                    }
                    lastX = event.getX(); lastY = event.getY();
                    invalidate();
                    return true;
                } else if ((action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) && gizmoAxis >= 0) {
                    gizmoAxis = -1; invalidate(); return true;
                }
            }
            engine.handleSculptTouchFromView(event, this);
            if (event.getPointerCount() >= 2) {
                float cx = (event.getX(0) + event.getX(1)) * 0.5f;
                float cy = (event.getY(0) + event.getY(1)) * 0.5f;
                float dist = distance(event);
                if (action == MotionEvent.ACTION_POINTER_DOWN) {
                    lastDistance = dist;
                    lastX = cx;
                    lastY = cy;
                } else if (action == MotionEvent.ACTION_MOVE) {
                    if (lastDistance > 0 && dist > 0) {
                        float scale = dist / lastDistance;
                        engine.queueEvent(() -> NativeBridge.nativeZoom(scale));
                    }
                    float px = cx - lastX;
                    float py = cy - lastY;
                    if (Math.abs(px) > 0.5f || Math.abs(py) > 0.5f) {
                        engine.queueEvent(() -> NativeBridge.nativePan(px, py));
                    }
                    lastDistance = dist;
                    lastX = cx;
                    lastY = cy;
                }
            } else if (action == MotionEvent.ACTION_DOWN) {
                if (activeTransformMode >= 0) engine.beginTransformEdit();
                lastX = event.getX();
                lastY = event.getY();
            } else if (action == MotionEvent.ACTION_MOVE && !sculptEnabled) {
                float dx = event.getX() - lastX;
                float dy = event.getY() - lastY;
                if (activeTransformMode >= 0) {
                    float[] transform = transformDragState;
                    if (transform != null && transform.length >= 9) {
                        if (activeTransformMode == 0) {
                            // Move in the viewport's screen-aligned XY plane.
                            transform[0] += dx * 0.008f;
                            transform[1] -= dy * 0.008f;
                        } else if (activeTransformMode == 1) {
                            transform[3] += dy * 0.45f;
                            transform[4] += dx * 0.45f;
                        } else {
                            float factor = (float)Math.exp(-dy * 0.008f);
                            transform[6] = Math.max(0.01f, Math.min(100.0f, transform[6] * factor));
                            transform[7] = Math.max(0.01f, Math.min(100.0f, transform[7] * factor));
                            transform[8] = Math.max(0.01f, Math.min(100.0f, transform[8] * factor));
                        }
                        transformDragState = transform;
                        engine.updateSelectedTransform(transform);
                        for (int axis = 0; axis < 3; axis++) {
                            if (positionInputs[axis] != null) positionInputs[axis].setText(String.valueOf(
                                    transform[axis + transformComponent * 3]));
                        }
                    }
                } else if (orbitMode) {
                    engine.queueEvent(() -> NativeBridge.nativeOrbitSelected(dx, dy));
                } else {
                    engine.queueEvent(() -> NativeBridge.nativeTouch(dx, dy));
                }
                lastX = event.getX();
                lastY = event.getY();
            }
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL
                    || action == MotionEvent.ACTION_POINTER_UP) lastDistance = 0;
            return true;
        }
    }
}
