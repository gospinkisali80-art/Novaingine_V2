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
import android.view.KeyEvent;
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
    private final EngineSurface engineSurface;
    private Meshy3DViewport meshyViewport;
    private int selectedSculptBrush;
    private float sculptRadiusUi=0.18f, sculptStrengthUi=0.40f, sculptFalloffUi=0.5f, sculptPressureUi=1.0f;
    private int sculptSymmetryUi;
    private int sculptStrokeMethodUi;
    private boolean sculptSubtractUi;
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
    private boolean sculptEnabled = false;
    private boolean objectModesLocked;
    private String activeEditorMode = "Object Mode";
    private final StringBuilder modelingNumericEntry = new StringBuilder();
    private int modelingNumericAxis = -1;
    private boolean modelingNumericPending;
    private TextView modeButton;
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
    private static final String[] WORKSPACE_NAMES = {
            "Layout", "Modeling", "Sculpting", "UV Editing", "Texture Paint",
            "Shading", "Animation", "Rendering", "Compositing", "Geometry Nodes"
    };
    private final ArrayList<TextView> workspaceTabs = new ArrayList<>();
    private final java.util.LinkedHashMap<String,TextView> outlinerObjectRows = new java.util.LinkedHashMap<>();
    private final java.util.LinkedHashMap<String,TextView> outlinerExpandRows = new java.util.LinkedHashMap<>();
    private final java.util.LinkedHashMap<String,String> outlinerParentIds = new java.util.LinkedHashMap<>();
    private final java.util.LinkedHashMap<String,String> outlinerObjectNames = new java.util.LinkedHashMap<>();
    private final java.util.HashSet<String> collapsedOutlinerObjectIds = new java.util.HashSet<>();
    private int activeWorkspaceIndex;
    private int workspaceTransitionToken;
    private boolean viewportFocusMode;
    private boolean workspacePanelsOpen;
    private Meshy3DViewportChrome viewportChrome;
    private Meshy3DPhoneControls phoneControls;
    private FrameLayout workspaceLayoutLayer;
    private LinearLayout workspaceToolbar;
    private LinearLayout workspaceLeftDock;
    private LinearLayout workspaceRightDock;
    private LinearLayout workspaceBottomDock;
    private TextView leftDockTab,rightDockTab;
    private final java.util.ArrayDeque<org.json.JSONObject> meshUndoStack = new java.util.ArrayDeque<>();
    private final java.util.ArrayDeque<org.json.JSONObject> meshRedoStack = new java.util.ArrayDeque<>();
    private final Handler timelineHandler = new Handler(Looper.getMainLooper());
    private boolean timelinePlaying;
    private boolean timelineReverse;
    private long timelineStartMs;
    private int currentTimelineFrame=1;
    private int renderOutputWidth=1280;
    private int renderOutputHeight=720;
    private int renderResolutionPercent=100;
    private Runnable timelineTick;

    public ManualSculptWorkspace(Activity activity, EngineSurface engine, Runnable onExit) {
        super(activity);
        this.activity = activity;
        this.engineSurface = engine;
        this.onExit = onExit;
        float density = getResources().getDisplayMetrics().density;
        float availableHeightDp = getResources().getDisplayMetrics().heightPixels / density;
        uiScale = Math.max(0.55f, Math.min(1.0f, availableHeightDp / 260.0f));
        setBackgroundColor(Color.TRANSPARENT);
        setClickable(false);
        setFocusable(true);
        setFocusableInTouchMode(true);
        requestFocus();

        // Meshy3D owns this viewport and its starter cube; it is not the Novaingine GL scene.
        // A normal tap in the centre of the viewport must select/sculpt only; it must never hide the editor.
        // Viewport focus is controlled explicitly from the workspace UI, not by an empty-space tap.
        meshyViewport = new Meshy3DViewport(activity, null);
        meshyViewport.setObjectSelectionListener(()->{
            if(activeWorkspaceIndex==1)refreshModelingOutliner();
        });
        meshyViewport.setObjectTransformStartListener(this::checkpointMeshEdit);
        meshyViewport.setSculptStrokeStartListener(this::checkpointMeshEdit);
        // The viewport must begin below the 86dp Blender-style title/menu/workspace header.
        // Previously it was centered behind that opaque header, hiding the default cube on phones.
        FrameLayout.LayoutParams viewportParams = new FrameLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT);
        viewportParams.topMargin = dp(86);
        addView(meshyViewport, viewportParams);

        // Meshy3D is being rebuilt from the Blender reference one section at a time.
        // This pass contains ONLY the complete top header; the native viewport remains below.
        LinearLayout topStack = new LinearLayout(activity);
        topStack.setOrientation(LinearLayout.VERTICAL);
        topStack.setBackgroundColor(0xFF202020);
        FrameLayout.LayoutParams stackParams = new FrameLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.TOP);
        addView(topStack, stackParams);

        // Window title row.
        LinearLayout titleRow = new LinearLayout(activity);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        titleRow.setPadding(dp(7), 0, dp(9), 0);
        titleRow.setBackgroundColor(0xFF171717);
        topStack.addView(titleRow, new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, dp(25)));
        TextView appMark = new TextView(activity);
        appMark.setText("◉");
        appMark.setTextColor(0xFFFF8A32);
        appMark.setTextSize(15);
        appMark.setGravity(Gravity.CENTER);
        appMark.setContentDescription("Meshy3D application menu");
        appMark.setOnClickListener(view -> {
            if (activity instanceof MainActivity) {
                ((MainActivity) activity).showMeshyApplicationMenu(view);
            }
        });
        titleRow.addView(appMark, new LinearLayout.LayoutParams(dp(23), LayoutParams.MATCH_PARENT));
        TextView windowTitle = new TextView(activity);
        windowTitle.setText("(Unsaved) - Meshy3D");
        windowTitle.setTextColor(0xFFE1E1E1);
        windowTitle.setTextSize(11);
        windowTitle.setGravity(Gravity.CENTER_VERTICAL);
        titleRow.addView(windowTitle, new LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f));
        TextView windowVersion = new TextView(activity);
        windowVersion.setText("Meshy3D Editor");
        windowVersion.setTextColor(0xFF929292);
        windowVersion.setTextSize(10);
        windowVersion.setGravity(Gravity.CENTER_VERTICAL);
        titleRow.addView(windowVersion);
        TextView undoHeader = new TextView(activity);
        undoHeader.setText("↶");
        undoHeader.setTextColor(0xFFE5EAF0);
        undoHeader.setTextSize(18);
        undoHeader.setGravity(Gravity.CENTER);
        undoHeader.setContentDescription("Undo Meshy3D edit");
        undoHeader.setOnClickListener(v -> {
            if (!undoMeshEdit()) Toast.makeText(activity, "Nothing to undo", Toast.LENGTH_SHORT).show();
        });
        titleRow.addView(undoHeader, new LinearLayout.LayoutParams(dp(30), LayoutParams.MATCH_PARENT));
        TextView redoHeader = new TextView(activity);
        redoHeader.setText("↷");
        redoHeader.setTextColor(0xFFE5EAF0);
        redoHeader.setTextSize(18);
        redoHeader.setGravity(Gravity.CENTER);
        redoHeader.setContentDescription("Redo Meshy3D edit");
        redoHeader.setOnClickListener(v -> {
            if (!redoMeshEdit()) Toast.makeText(activity, "Nothing to redo", Toast.LENGTH_SHORT).show();
        });
        titleRow.addView(redoHeader, new LinearLayout.LayoutParams(dp(30), LayoutParams.MATCH_PARENT));
        TextView aiHeader = new TextView(activity);
        aiHeader.setText("✦ AI");
        aiHeader.setTextColor(0xFFFFB45C);
        aiHeader.setTextSize(10);
        aiHeader.setGravity(Gravity.CENTER);
        aiHeader.setContentDescription("Open Nova AI for Meshy3D");
        aiHeader.setOnClickListener(v -> {
            if (engineSurface == null) {
                Toast.makeText(activity, "AI connection is unavailable.", Toast.LENGTH_SHORT).show();
                return;
            }
            new AIEditorAssistant(activity, engineSurface, this).show();
        });
        titleRow.addView(aiHeader, new LinearLayout.LayoutParams(dp(42), LayoutParams.MATCH_PARENT));
        TextView topMore = new TextView(activity);
        topMore.setText("⋮");
        topMore.setTextColor(0xFFE5EAF0);
        topMore.setTextSize(18);
        topMore.setGravity(Gravity.CENTER);
        topMore.setOnClickListener(v -> showWorkspaceChooser());
        titleRow.addView(topMore, new LinearLayout.LayoutParams(dp(24), LayoutParams.MATCH_PARENT));

        // Blender-style application menus and workspace tabs.
        LinearLayout menuWorkspaceRow = new LinearLayout(activity);
        menuWorkspaceRow.setGravity(Gravity.CENTER_VERTICAL);
        menuWorkspaceRow.setBackgroundColor(0xFF242424);
        topStack.addView(menuWorkspaceRow, new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, dp(31)));

        LinearLayout menus = new LinearLayout(activity);
        menus.setGravity(Gravity.CENTER_VERTICAL);
        menuWorkspaceRow.addView(menus, new LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT));
        String[] menuNames = {"File", "Edit", "Render", "Window", "Help"};
        for (String menuName : menuNames) {
            TextView menu = new TextView(activity);
            menu.setText(menuName);
            menu.setTextColor(0xFFE0E0E0);
            menu.setTextSize(11);
            menu.setGravity(Gravity.CENTER);
            menu.setPadding(dp(8), 0, dp(8), 0);
            menu.setOnClickListener(view -> {
                if (activity instanceof MainActivity) {
                    MainActivity host = (MainActivity) activity;
                    if ("File".equals(menuName)) host.showMeshyFileMenu(view);
                    else if ("Edit".equals(menuName)) host.showMeshyEditMenu(view);
                    else if ("Render".equals(menuName)) host.showMeshyRenderMenu(view);
                    else if ("Window".equals(menuName)) host.showMeshyWindowMenu(view);
                    else if ("Help".equals(menuName)) host.showMeshyHelpMenu(view);
                }
            });
            menus.addView(menu, new LinearLayout.LayoutParams(
                    LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT));
        }

        HorizontalScrollView workspaceScroll = new HorizontalScrollView(activity);
        workspaceScroll.setHorizontalScrollBarEnabled(false);
        workspaceScroll.setFillViewport(false);
        menuWorkspaceRow.addView(workspaceScroll, new LinearLayout.LayoutParams(
                0, LayoutParams.MATCH_PARENT, 1f));
        LinearLayout workspaces = new LinearLayout(activity);
        workspaces.setGravity(Gravity.CENTER_VERTICAL);
        workspaceScroll.addView(workspaces, new HorizontalScrollView.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT));
        for (int i = 0; i < WORKSPACE_NAMES.length; i++) {
            final int workspaceIndex = i;
            TextView tab = new TextView(activity);
            tab.setText(WORKSPACE_NAMES[i]);
            tab.setTextColor(0xFFB7B7B7);
            tab.setTextSize(10);
            tab.setGravity(Gravity.CENTER);
            tab.setPadding(dp(9), 0, dp(9), 0);
            tab.setOnClickListener(v -> setActiveWorkspaceIndex(workspaceIndex));
            workspaceTabs.add(tab);
            workspaces.addView(tab, new LinearLayout.LayoutParams(
                    LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT));
        }
        setActiveWorkspaceIndex(0);

        // 3D Viewport editor header, matching the controls visible beneath Blender's top bar.
        HorizontalScrollView viewportHeaderScroll = new HorizontalScrollView(activity);
        viewportHeaderScroll.setHorizontalScrollBarEnabled(false);
        viewportHeaderScroll.setFillViewport(true);
        viewportHeaderScroll.setBackgroundColor(0xFF303030);
        topStack.addView(viewportHeaderScroll, new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, dp(30)));
        LinearLayout viewportHeader = new LinearLayout(activity);
        viewportHeader.setGravity(Gravity.CENTER_VERTICAL);
        viewportHeader.setPadding(dp(7), 0, dp(8), 0);
        viewportHeaderScroll.addView(viewportHeader, new HorizontalScrollView.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        String[] leftHeaderItems = {"▣", "Object Mode", "View", "Select", "Add", "Object"};
        for (int i = 0; i < leftHeaderItems.length; i++) {
            TextView item = new TextView(activity);
            item.setText(leftHeaderItems[i]);
            item.setTextColor(0xFFE0E0E0);
            item.setTextSize(i == 0 ? 13 : 10);
            item.setGravity(Gravity.CENTER);
            item.setPadding(dp(6), 0, dp(6), 0);
            if (i == 1) {
                modeButton = item;
                item.setOnClickListener(v -> showEditorModeMenu());
            } else if (i == 2) {
                item.setOnClickListener(v -> showViewportViewMenu());
            } else if (i == 3) {
                item.setOnClickListener(v -> showViewportSelectMenu());
            } else if (i == 4) {
                item.setOnClickListener(v -> showPrimitiveDialog());
            } else if (i == 5) {
                item.setOnClickListener(v -> showViewportObjectMenu());
            }
            viewportHeader.addView(item, new LinearLayout.LayoutParams(
                    LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT));
        }
        View headerSpacer = new View(activity);
        viewportHeader.addView(headerSpacer, new LinearLayout.LayoutParams(0, 1, 1f));
        String[] rightHeaderItems = {"↔  Global", "◇", "⌖", "▦", "◉", "⌄"};
        for (int ri=0;ri<rightHeaderItems.length;ri++) {
            final int headerAction=ri;
            TextView item = new TextView(activity);
            item.setText(rightHeaderItems[ri]);
            item.setTextColor(0xFFD0D0D0);
            item.setTextSize(10);
            item.setGravity(Gravity.CENTER);
            item.setPadding(dp(7), 0, dp(7), 0);
            item.setOnClickListener(v -> {
                switch(headerAction) {
                    case 0: showTransformOrientationDialog(); break;
                    case 1: showPivotPointDialog(); break;
                    case 2: showSceneCursorDialog(); break;
                    case 3: showGridSnapDialog(); break;
                    case 4:
                        meshyViewport.toggleFloorGrid();
                        Toast.makeText(activity, "Floor grid "+(meshyViewport.isFloorGridVisible()?"shown":"hidden"),
                                Toast.LENGTH_SHORT).show();
                        break;
                    default: toggleWorkspacePanels();
                }
            });
            viewportHeader.addView(item, new LinearLayout.LayoutParams(
                    LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT));
        }

        status = new TextView(activity);
        status.setText("Meshy3D  |  " + WORKSPACE_NAMES[activeWorkspaceIndex]);
        updateSculptViewport();
        status.setTextColor(0xFFB8C4D1);
        status.setTextSize(9);
        workspaceLayoutLayer = new FrameLayout(activity);
        FrameLayout.LayoutParams layerParams = new FrameLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT);
        layerParams.topMargin = dp(86);
        addView(workspaceLayoutLayer, layerParams);
        renderWorkspaceLayout(0);

        // Shared phone-first viewport chrome. The virtual keyboard/mouse is opened from its keyboard key.
        phoneControls = new Meshy3DPhoneControls(activity, meshyViewport,
                new Meshy3DPhoneControls.Actions() {
                    @Override public void undo() {
                        if (!undoMeshEdit()) Toast.makeText(activity, "Nothing to undo", Toast.LENGTH_SHORT).show();
                    }
                    @Override public void redo() {
                        if (!redoMeshEdit()) Toast.makeText(activity, "Nothing to redo", Toast.LENGTH_SHORT).show();
                    }
                    @Override public void toggleMode() { toggleMeshyModeFromPhone(); }
                    @Override public void shortcut(String key) {
                        int code;
                        int meta=0;
                        switch(key){
                            case "1":code=KeyEvent.KEYCODE_1;break;
                            case "2":code=KeyEvent.KEYCODE_2;break;
                            case "3":code=KeyEvent.KEYCODE_3;break;
                            case "A":code=KeyEvent.KEYCODE_A;break;
                            case "Alt+A":code=KeyEvent.KEYCODE_A;meta=KeyEvent.META_ALT_ON;break;
                            case "G":code=KeyEvent.KEYCODE_G;break;
                            case "R":code=KeyEvent.KEYCODE_R;break;
                            case "S":code=KeyEvent.KEYCODE_S;break;
                            case "X":code=KeyEvent.KEYCODE_X;break;
                            case "Y":code=KeyEvent.KEYCODE_Y;break;
                            case "Z":code=KeyEvent.KEYCODE_Z;break;
                            case "Tab":code=KeyEvent.KEYCODE_TAB;break;
                            case "Esc":code=KeyEvent.KEYCODE_ESCAPE;break;
                            default:return;
                        }
                        long now=SystemClock.uptimeMillis();
                        handleModelingShortcut(new KeyEvent(now,now,KeyEvent.ACTION_DOWN,code,0,meta));
                        requestFocus();
                    }
                    @Override public void numericTransform(float amount,int axis) {
                        checkpointMeshEdit();
                        meshyViewport.applyNumericTransform(axis,amount);
                        status.setText("Modeling | Numeric transform applied: "+amount);
                        requestFocus();
                    }
                });
        phoneControls.setVisibility(View.GONE);
        viewportChrome = new Meshy3DViewportChrome(activity, meshyViewport,
                new Meshy3DViewportChrome.Actions() {
                    @Override public void select() {
                        meshyViewport.setMeasureMode(false);
                        if (activeWorkspaceIndex == 2) setActiveWorkspaceIndex(0);
                        meshyViewport.setObjectSelectionEnabled(true);
                        meshyViewport.setObjectBoxSelectEnabled(false);
                        meshyViewport.setObjectTransformTool(-1);
                        status.setText(WORKSPACE_NAMES[activeWorkspaceIndex]+" | Select tool active");
                    }
                    @Override public void cursor() { showSceneCursorDialog(); }
                    @Override public void transform(int mode) {
                        if(activeWorkspaceIndex==2)setActiveWorkspaceIndex(0);
                        if(meshyViewport.isMeshEditMode()) {
                            activeEditorMode="Object Mode";
                            meshyViewport.setMeshEditMode(false);
                            if(modeButton!=null)modeButton.setText(activeEditorMode);
                        }
                        meshyViewport.setObjectSelectionEnabled(true);
                        meshyViewport.setObjectBoxSelectEnabled(false);
                        meshyViewport.setObjectTransformTool(mode);
                        status.setText("Meshy3D | "+(mode==0?"Move":mode==1?"Rotate":"Scale")+" gizmo active");
                    }
                    @Override public void manualScale() {
                        Meshy3DObjectScene.Object3D active=meshyViewport.getObjectScene().getActiveObject();
                        if(active==null){Toast.makeText(activity,"Select an object first.",Toast.LENGTH_SHORT).show();return;}
                        if(activeWorkspaceIndex==2)setActiveWorkspaceIndex(0);
                        meshyViewport.setObjectSelectionEnabled(true);
                        meshyViewport.setObjectTransformTool(-1);
                        showObjectTransformDialog("Scale");
                    }
                    @Override public void sculpt() { setActiveWorkspaceIndex(2); }
                    @Override public void measure() {
                        meshyViewport.setMeasureMode(true);
                        meshyViewport.setObjectTransformTool(-1);
                        status.setText("Meshy3D | Measure tool: tap two points on the viewport");
                    }
                    @Override public void add() { showPrimitiveDialog(); }
                    @Override public void undo() { if(!undoMeshEdit())Toast.makeText(activity,"Nothing to undo",Toast.LENGTH_SHORT).show(); }
                    @Override public void redo() { if(!redoMeshEdit())Toast.makeText(activity,"Nothing to redo",Toast.LENGTH_SHORT).show(); }
                    @Override public void toggleMode() { toggleMeshyModeFromPhone(); }
                    @Override public void keyboard() {
                        if(phoneControls.getVisibility()!=View.VISIBLE) {
                            phoneControls.setVisibility(View.VISIBLE);
                            phoneControls.setExpanded(true);
                        } else if(phoneControls.isExpanded()) {
                            phoneControls.setExpanded(false);
                            phoneControls.setVisibility(View.GONE);
                        } else {
                            phoneControls.setExpanded(true);
                        }
                    }
                    @Override public void more() { toggleWorkspacePanels(); }
                    @Override public void settings() { showWorkspaceChooser(); }
                    @Override public void snap() { showGridSnapDialog(); }
                    @Override public void escape() {
                        meshyViewport.setObjectTransformTool(-1);
                        meshyViewport.setMeasureMode(false);
                        if(phoneControls.isExpanded())phoneControls.setExpanded(false);
                        phoneControls.setVisibility(View.GONE);
                        status.setText("Meshy3D | Tool cancelled");
                    }
                }, uiScale);
        FrameLayout.LayoutParams chromeParams = new FrameLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT);
        chromeParams.topMargin = dp(86);
        addView(viewportChrome, chromeParams);
        FrameLayout.LayoutParams phoneControlsParams = new FrameLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.END);
        phoneControlsParams.setMargins(0, 0, dp(8), dp(42));
        phoneControls.setElevation(dp(16));
        addView(phoneControls, phoneControlsParams);
    }

    private void toggleMeshyModeFromPhone() {
        if (activeWorkspaceIndex == 2) {
            setActiveWorkspaceIndex(0);
            activeEditorMode = "Object Mode";
            sculptEnabled = false;
            if (modeButton != null) modeButton.setText(activeEditorMode);
            updateSculptViewport();
            status.setText("Meshy3D | Object Mode");
            return;
        }
        checkpointMeshEdit();
        boolean edit = !meshyViewport.isMeshEditMode();
        meshyViewport.setMeshEditMode(edit);
        meshyViewport.setObjectSelectionEnabled(!edit);
        activeEditorMode = edit ? "Edit Mode" : "Object Mode";
        if (modeButton != null) modeButton.setText(activeEditorMode);
        if (status != null) status.setText("Meshy3D | " + activeEditorMode + " (phone controls)");
    }

    public void setActiveWorkspaceIndex(int index) {
        if (index < 0 || index >= WORKSPACE_NAMES.length) return;
        int previousIndex = activeWorkspaceIndex;
        viewportFocusMode = false;
        boolean shouldSlide = workspaceLayoutLayer != null && previousIndex != index;
        activeWorkspaceIndex = index;
        if (index == 2) {
            sculptEnabled = true;
            activeEditorMode = "Sculpt Mode";
            if (modeButton != null) modeButton.setText(activeEditorMode);
        } else if (previousIndex == 2) {
            sculptEnabled = false;
            activeEditorMode = "Object Mode";
            if (modeButton != null) modeButton.setText(activeEditorMode);
        }
        updateSculptViewport();
        for (int i = 0; i < workspaceTabs.size(); i++) {
            TextView tab = workspaceTabs.get(i);
            boolean selected = i == index;
            tab.setTextColor(selected ? Color.WHITE : 0xFFB7B7B7);
            if (selected) {
                GradientDrawable background = new GradientDrawable();
                background.setColor(0xFF3A3A3A);
                background.setCornerRadius(dp(2));
                tab.setBackground(background);
            } else {
                tab.setBackgroundColor(Color.TRANSPARENT);
            }
        }
        if (status != null) status.setText("Meshy3D  |  " + WORKSPACE_NAMES[index] + " Workspace");
        if (!shouldSlide) {
            renderWorkspaceLayout(index);
            return;
        }

        // Slide the previous workspace out, rebuild its replacement, then slide it in.
        final int transition = ++workspaceTransitionToken;
        final float direction = index > previousIndex ? 1.0f : -1.0f;
        workspaceLayoutLayer.animate().cancel();
        workspaceLayoutLayer.animate()
                .translationX(direction * dp(54))
                .alpha(0.0f)
                .setDuration(145)
                .withEndAction(() -> {
                    if (transition != workspaceTransitionToken || workspaceLayoutLayer == null) return;
                    renderWorkspaceLayout(index);
                    workspaceLayoutLayer.setTranslationX(-direction * dp(54));
                    workspaceLayoutLayer.animate()
                            .translationX(0.0f)
                            .alpha(1.0f)
                            .setDuration(185)
                            .start();
                })
                .start();
    }

    private TextView dockHeading(String text) {
        TextView view = new TextView(activity);
        view.setText(text.toUpperCase());
        view.setTextColor(0xFFFFB45A);
        view.setTextSize(10);
        view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        view.setPadding(dp(9), dp(8), dp(7), dp(6));
        return view;
    }

    private TextView dockItem(String text, boolean selected) {
        TextView view = new TextView(activity);
        view.setText(text);
        view.setTextColor(selected ? Color.WHITE : 0xFFD0D6DE);
        view.setTextSize(10);
        view.setPadding(dp(9), dp(7), dp(7), dp(7));
        if (selected) {
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(0xFF394654);
            bg.setCornerRadius(dp(3));
            view.setBackground(bg);
        }
        view.setOnClickListener(v -> {
            if (activeWorkspaceIndex == 2) handleSculptPanelItem(text);
            else handleWorkspaceItem(text);
        });
        return view;
    }

    private void updateSculptViewport() {
        if (meshyViewport != null) meshyViewport.setSculptSettings(
                sculptEnabled, selectedSculptBrush, sculptRadiusUi, sculptStrengthUi,
                sculptFalloffUi, sculptSymmetryUi, sculptSubtractUi, sculptPressureUi);
        if(meshyViewport!=null)meshyViewport.setSculptStrokeMethod(sculptStrokeMethodUi);
    }

    private void handleSculptPanelItem(String item) {
        for (int i=0;i<BRUSHES.length;i++) {
            if (BRUSHES[i].equalsIgnoreCase(item)) {
                selectedSculptBrush=i; updateSculptViewport();
                if(status!=null) status.setText("Sculpting | "+BRUSHES[i]+" — "+BRUSH_DESCRIPTIONS[i]);
                return;
            }
        }
        if ("Radius".equals(item)) showSculptSlider("Brush Radius",0.02f,0.75f,sculptRadiusUi,v->sculptRadiusUi=v);
        else if ("Strength".equals(item)) showSculptSlider("Brush Strength",0.01f,1f,sculptStrengthUi,v->sculptStrengthUi=v);
        else if ("Falloff".equals(item)) showSculptSlider("Brush Falloff",0f,1f,sculptFalloffUi,v->sculptFalloffUi=v);
        else if ("Pressure".equals(item)) showSculptSlider("Pressure Sensitivity",0.05f,1f,sculptPressureUi,v->sculptPressureUi=v);
        else if ("Symmetry".equals(item)) {
            String[] axes={"Off","X","Y","Z"};
            new android.app.AlertDialog.Builder(activity).setTitle("Sculpt Symmetry")
                    .setSingleChoiceItems(axes,sculptSymmetryUi,(d,w)->{sculptSymmetryUi=w;updateSculptViewport();d.dismiss();})
                    .setNegativeButton("Cancel",null).show();
        } else if ("Add / Subtract".equals(item)) {
            sculptSubtractUi=!sculptSubtractUi; updateSculptViewport();
            Toast.makeText(activity,sculptSubtractUi?"Subtract mode":"Add mode",Toast.LENGTH_SHORT).show();
        } else if ("Brush Preview".equals(item)) {
            Toast.makeText(activity,"Active brush: "+BRUSHES[selectedSculptBrush],Toast.LENGTH_SHORT).show();
        } else if ("Stroke Method".equals(item)) {
            String[] methods={"Dots","Drag Dot","Space"};
            new android.app.AlertDialog.Builder(activity).setTitle("Stroke Method")
                    .setSingleChoiceItems(methods,sculptStrokeMethodUi,(d,w)->{
                        sculptStrokeMethodUi=w;updateSculptViewport();
                        status.setText("Sculpting | Stroke method: "+methods[w]);d.dismiss();
                    }).setNegativeButton("Cancel",null).show();
        } else if ("Dyntopo / Remesh options".equals(item)) {
            new android.app.AlertDialog.Builder(activity).setTitle("Mesh Detail")
                    .setItems(new String[]{"Uniform Subdivide","Smooth Detail","Information"},(d,w)->{
                        if(w==0){checkpointMeshEdit();meshyViewport.subdivideSelected();status.setText("Sculpting | Uniform mesh subdivision applied");}
                        else if(w==1){showSculptSlider("Detail smoothing",0.05f,.8f,.25f,value->{checkpointMeshEdit();meshyViewport.smoothMesh(value,2);});}
                        else new android.app.AlertDialog.Builder(activity).setTitle("Mesh Detail")
                                .setMessage("Current mesh: "+meshyViewport.getVertexCount()+" vertices, "+meshyViewport.getFaceCount()+" faces. Adaptive Dyntopo and voxel remeshing are not implemented yet.")
                                .setPositiveButton("OK",null).show();
                    }).show();
        }
    }

    private void handleWorkspaceItem(String item) {
        if (meshyViewport == null) return;
        if ("Cube".equalsIgnoreCase(item)) {
            for(Meshy3DObjectScene.Object3D object:meshyViewport.getObjectScene().getObjects()) {
                if(object.name.equalsIgnoreCase(item)){meshyViewport.selectSceneObject(object.id);
                    status.setText(WORKSPACE_NAMES[activeWorkspaceIndex]+" | Active object: "+object.name);return;}
            }
        }
        if ("Selected Mesh".equalsIgnoreCase(item) || "Active Tool".equalsIgnoreCase(item)) {
            Meshy3DObjectScene.Object3D active=meshyViewport.getObjectScene().getActiveObject();
            status.setText(WORKSPACE_NAMES[activeWorkspaceIndex] + " | Active object: "
                    +(active==null?"None":active.name));
            return;
        }
        switch (activeWorkspaceIndex) {
            case 0:
                if ("Select Box".equals(item) || "Select".equals(item)) {
                    meshyViewport.setObjectSelectionEnabled(true);
                    meshyViewport.setObjectBoxSelectEnabled(true);
                    meshyViewport.setObjectTransformTool(-1);
                    status.setText("Layout | Box selection active");
                } else if ("Move".equals(item) || "Rotate".equals(item) || "Scale".equals(item)) {
                    meshyViewport.setObjectSelectionEnabled(true);
                    meshyViewport.setObjectBoxSelectEnabled(false);
                    meshyViewport.setObjectTransformTool("Move".equals(item)?0:"Rotate".equals(item)?1:2);
                    status.setText("Layout | "+item+" gizmo active — drag an axis handle");
                } else if ("Camera View".equals(item)) {
                    try { meshyViewport.toggleActiveCameraView(); }
                    catch (RuntimeException error) { status.setText("Layout | "+error.getMessage()); }
                } else if ("Hide / Show Objects".equals(item)) {
                    checkpointMeshEdit(); meshyViewport.toggleMeshVisible();
                    status.setText("Layout | Active object visibility toggled");
                } else if ("Frame Selected".equals(item)) {
                    meshyViewport.frameSelectedObjects();
                    status.setText("Layout | View framed on selected objects");
                } else if ("Frame All".equals(item)) {
                    meshyViewport.frameAllObjects();
                    status.setText("Layout | View framed on all visible objects");
                } else if ("Show Grid".equals(item) || "Grid".equals(item)) {
                    checkpointMeshEdit(); meshyViewport.toggleFloorGrid();
                } else if ("Move".equals(item) || "Rotate".equals(item) || "Scale".equals(item)
                        || "Transform".equals(item)) showTransformDialog(item.equals("Transform") ? "Move" : item);
                else if ("Hide Object".equals(item)) meshyViewport.setMeshVisible(false);
                else showWorkspaceActionInfo(item);
                break;
            case 1:
                if ("Set 3D Cursor".equals(item)) showSceneCursorDialog();
                else if ("Snap to Grid".equals(item)) showGridSnapDialog();
                else if ("Snap to 3D Cursor".equals(item)) {
                    checkpointMeshEdit();meshyViewport.snapSelectedObjectsToCursor();refreshModelingOutliner();
                    status.setText("Modeling | Selected object origins snapped to the 3D cursor");
                } else if ("Snap to Active Geometry".equals(item)) {
                    showObjectGeometrySnapDialog();
                } else if ("Frame Selected".equals(item)) {
                    meshyViewport.frameSelectedObjects();status.setText("Modeling | View framed on selected objects");
                } else if ("Frame All".equals(item)) {
                    meshyViewport.frameAllObjects();status.setText("Modeling | View framed on all visible objects");
                } else if ("Object Properties".equals(item)) showObjectPropertiesDialog();
                else if ("Constraints".equals(item)) showObjectConstraintsDialog();
                else if ("Modifiers".equals(item)) showObjectModifiersDialog();
                else if ("Custom Properties".equals(item)) showObjectCustomPropertiesDialog();
                else if ("Viewport Display".equals(item)) showObjectViewportDisplayDialog();
                else if ("Object Transform".equals(item) || "Numeric Transform".equals(item)) showObjectTransformTypeDialog();
                else if ("Dimensions".equals(item)) {
                    Meshy3DObjectScene.Object3D active=meshyViewport.getObjectScene().getActiveObject();
                    if(active!=null){float[] size=meshyViewport.getObjectScene().dimensions(active.id);
                        status.setText("Modeling | World dimensions X "+fmt(size[0])+"  Y "+fmt(size[1])+"  Z "+fmt(size[2]));}
                } else if ("Camera Settings".equals(item)) showCameraSettingsDialog();
                else if ("Light Settings".equals(item)) showLightSettingsDialog();
                else if ("Visibility".equals(item)) showObjectVisibilitySettingsDialog();
                else if ("Selectable".equals(item)) {
                    Meshy3DObjectScene.Object3D active=meshyViewport.getObjectScene().getActiveObject();
                    if(active!=null){meshyViewport.setActiveObjectSelectable(!active.selectable);refreshModelingOutliner();
                        status.setText("Modeling | "+active.name+" selectable: "+!active.selectable);}
                } else if ("Render Visibility".equals(item)) {
                    Meshy3DObjectScene.Object3D active=meshyViewport.getObjectScene().getActiveObject();
                    if(active!=null){meshyViewport.setActiveObjectRenderVisible(!active.renderVisible);
                        status.setText("Modeling | "+active.name+" render visibility: "+!active.renderVisible);}
                } else if ("Active Camera".equals(item)) {
                    Meshy3DObjectScene.Object3D camera=meshyViewport.getObjectScene().getActiveCamera();
                    status.setText("Modeling | Active camera: "+(camera==null?"None":camera.name));
                } else if ("Add Menu".equals(item) || "Add Primitive".equals(item)) showAddMenuDialog();
                else if ("Add → Mesh".equals(item)) showAddMeshMenuDialog();
                else if ("Add Camera".equals(item)) {
                    checkpointMeshEdit();meshyViewport.addCameraObject();meshyViewport.setMeshEditMode(false);
                    refreshModelingOutliner();status.setText("Modeling | Camera object added");
                } else if ("Add Light".equals(item)) {
                    checkpointMeshEdit();meshyViewport.addLightObject();meshyViewport.setMeshEditMode(false);
                    meshyViewport.setActiveLightSettings("POINT",1000f,45f,0xFFFFFFFF);
                    refreshModelingOutliner();showLightSettingsDialog();
                } else if ("Add Empty".equals(item)) {
                    checkpointMeshEdit();meshyViewport.addEmptyObject();meshyViewport.setMeshEditMode(false);
                    refreshModelingOutliner();status.setText("Modeling | Empty object added");
                } else if ("Add Cube".equals(item)) {
                    checkpointMeshEdit();meshyViewport.addCubeObject();
                    activeEditorMode="Object Mode";if(modeButton!=null)modeButton.setText(activeEditorMode);
                    meshyViewport.setMeshEditMode(false);meshyViewport.setObjectSelectionEnabled(true);
                    refreshModelingOutliner();
                    status.setText("Modeling | New independent Cube object added");
                } else if ("Duplicate".equals(item) || "Duplicate Linked".equals(item)) {
                    checkpointMeshEdit();meshyViewport.duplicateActiveObject("Duplicate Linked".equals(item));
                    activeEditorMode="Object Mode";if(modeButton!=null)modeButton.setText(activeEditorMode);
                    meshyViewport.setMeshEditMode(false);meshyViewport.setObjectSelectionEnabled(true);
                    refreshModelingOutliner();status.setText("Modeling | "+item+" created");
                } else if ("Delete Object".equals(item)) {
                    checkpointMeshEdit();meshyViewport.deleteActiveObject();
                    refreshModelingOutliner();status.setText("Modeling | Active object deleted");
                } else if ("Delete Selected Objects".equals(item)) {
                    checkpointMeshEdit();meshyViewport.deleteSelectedObjects();
                    refreshModelingOutliner();status.setText("Modeling | Selected objects deleted");
                } else if ("Delete by Type".equals(item)) showDeleteObjectsByTypeDialog();
                else if ("Select All Objects".equals(item)) {
                    meshyViewport.selectAllSceneObjects();refreshModelingOutliner();status.setText("Modeling | All scene objects selected");
                } else if ("Deselect Objects".equals(item)) {
                    meshyViewport.deselectAllSceneObjects();refreshModelingOutliner();status.setText("Modeling | Object selection cleared");
                } else if ("Invert Selection".equals(item)) {
                    meshyViewport.invertSceneSelection();refreshModelingOutliner();status.setText("Modeling | Object selection inverted");
                } else if ("Rename Object".equals(item)) showObjectRenameDialog();
                else if ("Select Object".equals(item)) showObjectPickerDialog(false);
                else if ("Select By Type".equals(item)) showObjectTypeDialog();
                else if ("Select Hierarchy".equals(item)) showHierarchySelectionDialog();
                else if ("Select by Name".equals(item)) showObjectPatternDialog();
                else if ("Select Active Camera".equals(item)) {
                    meshyViewport.selectActiveCameraObject(false);refreshModelingOutliner();
                    status.setText("Modeling | Active camera selected");
                } else if ("Select Linked Data".equals(item)) {
                    meshyViewport.selectLinkedDataObjects(false);refreshModelingOutliner();
                    status.setText("Modeling | Objects sharing the active mesh datablock selected");
                } else if ("Hide Selected".equals(item)) {
                    meshyViewport.hideSelectedObjects();refreshModelingOutliner();status.setText("Modeling | Selected objects hidden");
                } else if ("Hide Unselected".equals(item)) {
                    meshyViewport.hideUnselectedObjects();refreshModelingOutliner();status.setText("Modeling | Unselected objects hidden");
                } else if ("Show All Objects".equals(item)) {
                    meshyViewport.showAllObjects();refreshModelingOutliner();status.setText("Modeling | All objects visible");
                } else if ("Invert Hidden".equals(item)) {
                    meshyViewport.invertHiddenObjects();refreshModelingOutliner();status.setText("Modeling | Object visibility inverted");
                } else if ("Toggle Local View".equals(item)) {
                    meshyViewport.toggleLocalView();status.setText("Modeling | Local View "+(meshyViewport.isLocalViewActive()?"enabled":"disabled"));
                } else if ("Lock Selection".equals(item)) {
                    meshyViewport.setSelectedObjectsSelectable(false);refreshModelingOutliner();status.setText("Modeling | Selected objects locked from selection");
                } else if ("Unlock Selection".equals(item)) {
                    meshyViewport.setSelectedObjectsSelectable(true);refreshModelingOutliner();status.setText("Modeling | Selected objects selectable");
                } else if ("Disable in Render".equals(item)) {
                    meshyViewport.setSelectedObjectsRenderVisible(false);refreshModelingOutliner();status.setText("Modeling | Selected objects disabled for render");
                } else if ("Enable in Render".equals(item)) {
                    meshyViewport.setSelectedObjectsRenderVisible(true);refreshModelingOutliner();status.setText("Modeling | Selected objects enabled for render");
                } else if ("Join Selected Meshes".equals(item)) {
                    try{checkpointMeshEdit();meshyViewport.joinSelectedObjects();refreshModelingOutliner();
                        status.setText("Modeling | Selected mesh objects joined into active object");}
                    catch(Exception error){showObjectOperationError(error);}
                } else if ("Make Single User".equals(item)) {
                    checkpointMeshEdit();meshyViewport.makeActiveObjectSingleUser();refreshModelingOutliner();
                    status.setText("Modeling | Active object mesh data made single-user");
                } else if ("Link Mesh Data".equals(item)) {
                    try{checkpointMeshEdit();meshyViewport.linkSelectedObjectsToActiveMeshData();
                        refreshModelingOutliner();status.setText("Modeling | Selected objects now share the active mesh datablock");}
                    catch(Exception error){showObjectOperationError(error);}
                } else if ("Set Active Camera".equals(item)) {
                    try{meshyViewport.makeActiveCamera();status.setText("Modeling | Active camera updated");}
                    catch(Exception error){showObjectOperationError(error);}
                } else if ("View Camera".equals(item)) {
                    try{meshyViewport.toggleActiveCameraView();
                        status.setText("Modeling | Camera view "+(meshyViewport.isCameraViewActive()?"enabled":"disabled"));}
                    catch(Exception error){showObjectOperationError(error);}
                } else if ("Camera Settings".equals(item)) showCameraSettingsDialog();
                else if ("Light Settings".equals(item)) showLightSettingsDialog();
                else if ("Hide / Show Object".equals(item)) {
                    Meshy3DObjectScene.Object3D active=meshyViewport.getObjectScene().getActiveObject();
                    if(active!=null){meshyViewport.setActiveObjectVisible(!active.visible);status.setText("Modeling | "+active.name+(active.visible?" hidden":" visible"));}
                } else if ("Apply Location".equals(item)) {
                    checkpointMeshEdit();meshyViewport.applyActiveObjectLocation();
                    status.setText("Modeling | Active object location applied");
                } else if ("Apply Rotation & Scale".equals(item)) {
                    checkpointMeshEdit();meshyViewport.applyActiveObjectRotationAndScale();
                    status.setText("Modeling | Active object rotation and scale applied");
                } else if ("Apply All Transforms".equals(item)) {
                    checkpointMeshEdit();meshyViewport.applyActiveObjectRotationAndScale();meshyViewport.applyActiveObjectLocation();
                    status.setText("Modeling | All active object transforms applied");
                } else if ("Clear Location".equals(item) || "Clear Rotation".equals(item) || "Clear Scale".equals(item)) {
                    checkpointMeshEdit();meshyViewport.clearActiveObjectTransform("Clear Location".equals(item)?0:"Clear Rotation".equals(item)?1:2);
                    status.setText("Modeling | "+item+" completed");
                } else if ("Set Origin to Geometry".equals(item) || "Set Origin to 3D Cursor".equals(item)) {
                    checkpointMeshEdit();
                    if("Set Origin to Geometry".equals(item))meshyViewport.setActiveObjectOriginToGeometry();
                    else meshyViewport.setActiveObjectOriginToCursor();
                    status.setText("Modeling | "+item+" completed");
                } else if ("Parent To".equals(item)) showObjectPickerDialog(true);
                else if ("Align Objects".equals(item)) showAlignObjectsDialog();
                else if ("Mirror Objects".equals(item)) showMirrorObjectsDialog();
                else if ("Parent Selected to Active".equals(item)) {
                    try{meshyViewport.parentSelectedToActive();refreshModelingOutliner();status.setText("Modeling | Selected objects parented to active object");}
                    catch(Exception error){showObjectOperationError(error);}
                } else if ("Clear Parent".equals(item)) {
                    meshyViewport.clearSelectedParents();refreshModelingOutliner();status.setText("Modeling | Selected parent relationships cleared");
                } else if ("Add Collection".equals(item)) showCreateCollectionDialog();
                else if ("Manage Collections".equals(item)) showCollectionManagerPicker();
                else if ("Move To Collection".equals(item)) showCollectionPickerDialog(false);
                else if ("Link To Collection".equals(item)) showCollectionPickerDialog(true);
                else if ("Object Statistics".equals(item)) {
                    Meshy3DObjectScene scene=meshyViewport.getObjectScene();
                    status.setText("Scene | "+scene.getObjectCount()+" objects | "+scene.getCollectionCount()+" collections");
                } else if ("Select Box".equals(item)) {
                    meshyViewport.setObjectBoxSelectEnabled(true);status.setText("Modeling | Box Select active — drag a rectangle around objects");
                } else if ("Select".equals(item)) {
                    meshyViewport.setObjectBoxSelectEnabled(false);
                    meshyViewport.setObjectTransformTool(-1);status.setText("Modeling | Click Select active");
                } else if (("Move".equals(item)||"Rotate".equals(item)||"Scale".equals(item))
                        && !"Edit Mode".equals(activeEditorMode)) {
                    meshyViewport.setObjectTransformTool("Move".equals(item)?0:"Rotate".equals(item)?1:2);
                    status.setText("Modeling | "+item+" gizmo active — drag the X, Y or Z handle");
                } else if ("Vertex".equalsIgnoreCase(item) || "Vertex Select".equalsIgnoreCase(item)) {
                    meshyViewport.setMeshEditMode(true);meshyViewport.setSelectionMode("Vertex");
                    status.setText("Modeling | Vertex selection | Tap vertices to select");
                } else if ("Edge".equalsIgnoreCase(item) || "Edge Select".equalsIgnoreCase(item)) {
                    meshyViewport.setMeshEditMode(true);meshyViewport.setSelectionMode("Edge");
                    status.setText("Modeling | Edge selection | Tap edges to select");
                } else if ("Face".equalsIgnoreCase(item) || "Face Select".equalsIgnoreCase(item)) {
                    meshyViewport.setMeshEditMode(true);meshyViewport.setSelectionMode("Face");
                    status.setText("Modeling | Face selection | Tap faces to select");
                } else if ("Select All".equals(item)) {
                    checkpointMeshEdit();meshyViewport.selectAllElements();
                    status.setText("Modeling | All " + meshyViewport.getSelectionMode().toLowerCase()
                            + " elements selected (" + meshyViewport.getSelectedElementCount() + ")");
                } else if ("Select Loops".equals(item)) {
                    meshyViewport.selectLoops();status.setText("Modeling | Connected quad edge loops selected");
                } else if ("Select Similar".equals(item)) {
                    meshyViewport.selectSimilar();status.setText("Modeling | Similar mesh elements selected");
                } else if ("Move".equals(item) || "Rotate".equals(item) || "Scale".equals(item)
                        || "Transform".equals(item)) {
                    if ("Edit Mode".equals(activeEditorMode)) {
                        meshyViewport.setMeshEditMode(true);
                        showTransformDialog(item.equals("Transform") ? "Move" : item);
                    } else showObjectTransformDialog(item.equals("Transform") ? "Move" : item);
                } else if ("Extrude Region".equals(item) || "Extrude Along Normals".equals(item)
                        || "Extrude Individual Faces".equals(item) || "Extrude".equals(item)) {
                    final boolean individual="Extrude Individual Faces".equals(item);
                    final boolean alongNormals="Extrude Along Normals".equals(item);
                    showAmountDialog("Extrude distance",0.01f,2.0f,value->{
                        checkpointMeshEdit();
                        if(individual||alongNormals)meshyViewport.extrudeSelectedAlongNormals(value,individual);
                        else meshyViewport.extrudeSelected(value);
                        status.setText("Modeling | "+(individual?"Individual face extrusion":alongNormals?"Extrude along normals":"Region extrusion")+" applied");
                    });
                } else if ("Inset Faces".equals(item) || "Inset".equals(item)) {
                    showAmountDialog("Inset thickness",0.01f,0.9f,value->{
                        checkpointMeshEdit();meshyViewport.insetSelected(value);
                        status.setText("Modeling | Inset operation applied to selected faces");
                    });
                } else if ("Bevel Edges".equals(item) || "Bevel".equals(item)) {
                    showAmountDialog("Bevel width",0.01f,0.45f,value->{
                        checkpointMeshEdit();meshyViewport.bevelSelected(value);
                        status.setText("Modeling | Bevel applied to selected edges");
                    });
                } else if ("Loop Cut".equals(item)) {
                    showAmountDialog("Loop cut position",0.05f,0.95f,value->{
                        checkpointMeshEdit();meshyViewport.loopCutSelected(value);
                        status.setText("Modeling | Loop cut evaluated on selected edge");
                    });
                } else if ("Mirror".equals(item) || "Mirror Modifier".equals(item) || "Symmetrize".equals(item)) {
                    String[] axes={"X","Y","Z"};
                    new android.app.AlertDialog.Builder(activity).setTitle("Mirror across axis")
                            .setItems(axes,(d,w)->{checkpointMeshEdit();meshyViewport.mirrorSelected(w);status.setText("Modeling | Mirrored selected geometry across "+axes[w]);}).show();
                } else if ("Shrink / Fatten".equals(item)) {
                    showAmountDialog("Shrink / Fatten distance",-1f,1f,value->{checkpointMeshEdit();meshyViewport.shrinkFattenSelected(value);status.setText("Modeling | Vertices moved along their normals");});
                } else if ("Shear".equals(item) || "Bend".equals(item)) {
                    showAmountDialog("Shear amount",-1.5f,1.5f,value->{checkpointMeshEdit();meshyViewport.shearSelected(value);status.setText("Modeling | Selected geometry sheared");});
                } else if ("To Sphere".equals(item)) {
                    showAmountDialog("To Sphere factor",0f,1f,value->{checkpointMeshEdit();meshyViewport.toSphereSelected(value);status.setText("Modeling | Selected geometry blended toward a sphere");});
                } else if ("Randomize".equals(item)) {
                    showAmountDialog("Random displacement",0f,.5f,value->{checkpointMeshEdit();meshyViewport.randomizeSelected(value);status.setText("Modeling | Selected vertices randomized");});
                } else if ("Make Planar".equals(item)) {
                    checkpointMeshEdit();meshyViewport.makePlanarSelected();status.setText("Modeling | Selected vertices projected onto a best-fit plane");
                } else if ("Flip Normals".equals(item)) {
                    checkpointMeshEdit();meshyViewport.flipSelectedNormals();status.setText("Modeling | Selected face winding flipped");
                } else if ("Fill".equals(item)) {
                    checkpointMeshEdit();meshyViewport.fillSelectedBoundary();
                    status.setText("Modeling | Selected closed boundary filled with an n-gon");
                } else if ("Grid Fill".equals(item)) {
                    checkpointMeshEdit();
                    if(meshyViewport.gridFillSelectedBoundary())
                        status.setText("Modeling | Selected boundary filled with a quad grid");
                    else Toast.makeText(activity,"Grid Fill requires one selected boundary loop with four equal edge spans.",Toast.LENGTH_LONG).show();
                } else if ("Bridge".equals(item) || "Bridge Edge Loops".equals(item)) {
                    checkpointMeshEdit();meshyViewport.bridgeSelectedLoops();status.setText("Modeling | Selected equal-count boundary loops bridged");
                } else if ("Bisect".equals(item)) {
                    String[] axes={"X plane","Y plane","Z plane"};
                    new android.app.AlertDialog.Builder(activity).setTitle("Bisect — choose plane")
                            .setItems(axes,(d,w)->showAmountDialog("Bisect plane offset",-1f,1f,value->{
                                checkpointMeshEdit();meshyViewport.bisectMesh(w==0?1:0,w==1?1:0,w==2?1:0,value);
                                status.setText("Modeling | Mesh bisected by "+axes[w]);
                            })).show();
                } else if ("Plane Cut".equals(item)) {
                    String[] axes={"X plane","Y plane","Z plane"};
                    new android.app.AlertDialog.Builder(activity).setTitle("Plane Cut")
                            .setItems(axes,(d,w)->showAmountDialog("Cut plane offset",-1f,1f,value->{
                                checkpointMeshEdit();meshyViewport.bisectMesh(w==0?1:0,w==1?1:0,w==2?1:0,value);
                                status.setText("Modeling | Mesh cut along "+axes[w]);
                            })).show();
                } else if ("Poly Build".equals(item)) {
                    meshyViewport.startPolyBuild();status.setText("Modeling | Poly Build: tap points, then tap the first point to close the face");
                } else if ("Un-Subdivide".equals(item)) {
                    checkpointMeshEdit();meshyViewport.unSubdividePlanar();status.setText("Modeling | Coplanar subdivision edges dissolved");
                } else if ("Decimate".equals(item)) {
                    showAmountDialog("Planar decimation angle",0f,45f,value->{
                        checkpointMeshEdit();meshyViewport.decimatePlanar(value,Math.max(4,meshyViewport.getFaceCount()/2));
                        status.setText("Modeling | Planar decimation reduced coplanar face count");
                    });
                } else if ("Spin".equals(item) || "Screw".equals(item)) {
                    final boolean screw="Screw".equals(item);
                    showAmountDialog(screw?"Screw rotation (degrees)":"Spin rotation (degrees)",15f,360f,value->{
                        checkpointMeshEdit();meshyViewport.spinSelectedFaces((float)Math.toRadians(value),8,screw?1f:0f);
                        status.setText("Modeling | "+item+" generated eight rotational steps");
                    });
                } else if ("Separate Selection".equals(item)) {
                    checkpointMeshEdit();
                    try {
                        String separatedId=meshyViewport.separateSelectedFacesToObject();
                        if(separatedId==null)status.setText("Modeling | Select faces (or fully selected vertices/edges) before Separate Selection");
                        else {
                            status.setText("Modeling | Selected geometry separated into a new object");
                            activeEditorMode="Object Mode";if(modeButton!=null)modeButton.setText(activeEditorMode);
                            refreshModelingOutliner();
                        }
                    } catch(Exception error) { showObjectOperationError(error); }
                } else if ("Split".equals(item) || "Rip".equals(item)) {
                    checkpointMeshEdit();meshyViewport.splitSelectedFaces();
                    status.setText("Modeling | Selected region disconnected inside the same object");
                } else if ("Solidify".equals(item)) {
                    showAmountDialog("Solidify thickness",0.01f,.5f,value->{checkpointMeshEdit();meshyViewport.solidifySelected(value);status.setText("Modeling | Solid thickness generated");});
                } else if ("Bevel Modifier".equals(item)) {
                    showAmountDialog("Bevel width",.001f,.45f,value->{checkpointMeshEdit();meshyViewport.bevelSelected(value);status.setText("Modeling | Bevel geometry generated");});
                } else if ("Wireframe Modifier".equals(item)) {
                    Meshy3DObjectScene.Object3D active=meshyViewport.getObjectScene().getActiveObject();
                    if(active==null||active.mesh==null)Toast.makeText(activity,"Select a mesh object first.",Toast.LENGTH_SHORT).show();
                    else try {
                        checkpointMeshEdit();
                        Meshy3DObjectScene.Modifier3D modifier=meshyViewport.getObjectScene().addModifier(active.id,"WIREFRAME");
                        meshyViewport.invalidate();showObjectModifierEditor(modifier);
                    } catch(Exception error) { showObjectOperationError(error); }
                } else if ("Remesh".equals(item)) {
                    new android.app.AlertDialog.Builder(activity).setTitle(item)
                            .setMessage("This operator is not implemented yet. No geometry was changed.")
                            .setPositiveButton("OK",null).show();
                } else if ("Poke Faces".equals(item) || "Poke".equals(item)) {
                    showAmountDialog("Poke offset",0f,1f,value->{checkpointMeshEdit();meshyViewport.pokeSelectedFaces(value);status.setText("Modeling | Selected faces poked into triangle fans");});
                } else if ("Delete Edges".equals(item) || "Dissolve Faces".equals(item) || "Limited Dissolve".equals(item)) {
                    checkpointMeshEdit();
                    if("Dissolve Faces".equals(item))meshyViewport.deleteSelectedFaces();else meshyViewport.dissolveSelectedEdges();
                    status.setText("Modeling | "+item+" operation applied to the current selection");
                } else if ("Degenerate Dissolve".equals(item) || "Weld".equals(item) || "Merge by Distance".equals(item) || "Merge".equals(item)) {
                    showAmountDialog("Merge distance",0.001f,0.25f,value->{checkpointMeshEdit();meshyViewport.mergeSelected(value);status.setText("Modeling | Nearby selected vertices merged");});
                } else if ("Subdivision Surface".equals(item)) {
                    checkpointMeshEdit();meshyViewport.subdivideCatmullClark(1);
                    status.setText("Modeling | Catmull-Clark subdivision level 1 applied");
                } else if ("Recalculate Normals".equals(item) || "Normals".equals(item)) {
                    checkpointMeshEdit();meshyViewport.recalculateNormals();status.setText("Modeling | Face winding and normals recalculated");
                } else if ("Edge Slide".equals(item) || "Vertex Slide".equals(item)) {
                    showAmountDialog(item+" factor",0f,1f,value->{checkpointMeshEdit();meshyViewport.slideSelected(value,"Edge Slide".equals(item));status.setText("Modeling | Selected elements slid along connected topology");});
                } else if ("Subdivide".equals(item)) {
                    checkpointMeshEdit();meshyViewport.subdivideSelected();
                    status.setText("Modeling | Mesh subdivided");
                } else if ("Dissolve Edges".equals(item) || "Dissolve Edge".equals(item)) {
                    checkpointMeshEdit();meshyViewport.dissolveSelectedEdges();
                    status.setText("Modeling | Selected shared edge dissolved");
                } else if ("Triangulate Faces".equals(item) || "Triangulate".equals(item)) {
                    checkpointMeshEdit();meshyViewport.triangulateSelectedFaces();
                    status.setText("Modeling | Selected faces triangulated");
                } else if ("Delete Faces".equals(item)) {
                    checkpointMeshEdit();meshyViewport.deleteSelectedFaces();
                    status.setText("Modeling | Selected faces deleted");
                } else if ("Delete Vertices".equals(item)) {
                    checkpointMeshEdit();meshyViewport.deleteSelectedVertices();
                    status.setText("Modeling | Selected vertices and connected faces deleted");
                } else if ("Recalculate Normals".equals(item)) {
                    checkpointMeshEdit();meshyViewport.recalculateNormals();
                    status.setText("Modeling | Face winding and outward normals recalculated");

                } else if ("Smooth Vertices".equals(item) || "Smooth".equals(item))
                    showAmountDialog("Smoothing amount",0.05f,0.8f,value->{checkpointMeshEdit();meshyViewport.smoothMesh(value,2);});
                else if ("Mesh Statistics".equals(item) || "Vertex / Edge / Face Counts".equals(item)
                        || "Topology Overview".equals(item) || "Vertices".equals(item) || "Edges".equals(item) || "Faces".equals(item))
                    status.setText("Mesh | " + meshyViewport.getVertexCount() + " vertices | "
                            + meshyViewport.getEdgeCount() + " edges | " + meshyViewport.getFaceCount() + " faces");
                else if ("Selection Mode".equals(item)) {
                    new android.app.AlertDialog.Builder(activity).setTitle("Selection Mode")
                            .setItems(new String[]{"Vertex","Edge","Face"},(d,w)->{
                                String mode=new String[]{"Vertex","Edge","Face"}[w];meshyViewport.setSelectionMode(mode);
                                status.setText("Modeling | "+mode+" selection mode");
                            }).show();
                } else if ("Transform Orientation".equals(item)) showTransformOrientationDialog();
                else if ("Pivot Point".equals(item)) showPivotPointDialog();
                else if ("Operation Amount".equals(item) || "Offset".equals(item) || "Segments".equals(item)
                        || "Profile".equals(item) || "Loop Cut Count".equals(item) || "Merge Threshold".equals(item)) {
                    showAmountDialog(item,0f,"Segments".equals(item)||"Loop Cut Count".equals(item)?12f:1f,
                            value->status.setText("Modeling | "+item+" parameter set to "+String.format(java.util.Locale.US,"%.2f",value)));
                }
                else if ("Object Mode / Edit Mode".equals(item) || "Edit Mode".equals(item)) {
                    activeEditorMode = "Edit Mode";if(modeButton!=null)modeButton.setText(activeEditorMode);
                    meshyViewport.setMeshEditMode(true);meshyViewport.setObjectSelectionEnabled(false);
                    meshyViewport.setShowTopology(true);status.setText("Modeling | Edit Mode");
                } else if ("Object Mode".equals(item)) {
                    activeEditorMode = "Object Mode";if(modeButton!=null)modeButton.setText(activeEditorMode);
                    meshyViewport.setMeshEditMode(false);meshyViewport.setObjectSelectionEnabled(true);
                    status.setText("Modeling | Object Mode");
                } else if ("Toggle Topology Overlay".equals(item)) meshyViewport.toggleTopology();
                else if ("Reset Mesh".equals(item)) {checkpointMeshEdit();meshyViewport.resetMesh();}
                else showWorkspaceActionInfo(item);
                break;
            case 3:
                if ("Unwrap".equals(item) || "Smart UV Project".equals(item) || "Lightmap Pack".equals(item)) {
                    checkpointMeshEdit();meshyViewport.unwrapMesh();
                    status.setText("UV Editing | LSCM conformal unwrap | " + meshyViewport.getUvIslandCount() + " UV islands");
                } else if ("Follow Active Quads".equals(item) || "Minimize Stretch".equals(item)
                        || "Straighten".equals(item)) {
                    checkpointMeshEdit();meshyViewport.unwrapMesh();
                    status.setText("UV Editing | LSCM unwrap applied; dedicated "+item+" algorithm is not implemented yet");
                } else if ("Mark Seam".equals(item)) {
                    checkpointMeshEdit();meshyViewport.markSeams(false);meshyViewport.unwrapMesh();status.setText("UV Editing | Seams marked and LSCM islands regenerated");
                } else if ("Clear Seam".equals(item)) {
                    checkpointMeshEdit();meshyViewport.markSeams(true);meshyViewport.unwrapMesh();status.setText("UV Editing | Selected seams cleared and UVs regenerated");
                } else if ("Pin".equals(item) || "Unpin".equals(item)) {
                    checkpointMeshEdit();meshyViewport.pinSelectedUvs("Pin".equals(item));
                    status.setText("UV Editing | Selected UV coordinates "+("Pin".equals(item)?"pinned":"unpinned"));
                } else if ("Pack Islands".equals(item) || "Average Islands Scale".equals(item)) {
                    checkpointMeshEdit();meshyViewport.packUvIslands();status.setText("UV Editing | UV islands packed into the 0–1 tile");
                } else if ("Select Linked".equals(item)) {
                    meshyViewport.selectLinkedUvIsland();status.setText("UV Editing | Linked seam-bounded region selected");
                } else if ("Select Pinned".equals(item)) {
                    status.setText(meshyViewport.selectPinnedUvIsland()
                            ?"UV Editing | Island with pinned UV coordinates selected"
                            :"UV Editing | No island has pinned coordinates");
                } else if ("Select Overlapping".equals(item)) {
                    status.setText(meshyViewport.selectOverlappingUvIsland()
                            ?"UV Editing | First overlapping island bounds selected"
                            :"UV Editing | No overlapping island bounds found");
                } else if ("Select Boundary".equals(item)) {
                    status.setText(meshyViewport.selectBoundaryUvIsland()
                            ?"UV Editing | Largest boundary island selected"
                            :"UV Editing | No UV islands available");
                } else if ("Mirror X".equals(item) || "Mirror Y".equals(item)) {
                    checkpointMeshEdit();meshyViewport.mirrorSelectedUv("Mirror Y".equals(item)?1:0);
                    status.setText("UV Editing | Selected island mirrored on "+("Mirror Y".equals(item)?"Y":"X"));
                } else if ("Align X".equals(item) || "Align Y".equals(item)) {
                    checkpointMeshEdit();meshyViewport.alignSelectedUv("Align Y".equals(item)?1:0);
                    status.setText("UV Editing | Selected island aligned to the "+("Align Y".equals(item)?"V":"U")+" tile edge");
                } else if ("Weld".equals(item) || "Stitch".equals(item)) {
                    checkpointMeshEdit();meshyViewport.weldSelectedUv("Stitch".equals(item)?.005f:.001f);
                    status.setText("UV Editing | Duplicate UV corners welded within the active island");
                } else if ("Split".equals(item)) {
                    checkpointMeshEdit();
                    status.setText(meshyViewport.splitSelectedUvIsland()
                            ?"UV Editing | Island boundary split and unwrap regenerated"
                            :"UV Editing | Select an island with shared boundary faces first");
                } else if ("Snap to Pixels".equals(item)) {
                    checkpointMeshEdit();
                    int w=meshyViewport.getDocumentCore().getTextureWidth();
                    int h=meshyViewport.getDocumentCore().getTextureHeight();
                    meshyViewport.snapSelectedUvToPixels(w>0?w:1024,h>0?h:1024);
                    status.setText("UV Editing | Selected island snapped to image pixels");
                } else if ("Snap to Vertex".equals(item)) {
                    checkpointMeshEdit();
                    status.setText(meshyViewport.snapSelectedUvToNearestVertex(.08f)
                            ?"UV Editing | Island snapped to nearest UV corner"
                            :"UV Editing | No nearby UV corner found");
                } else if ("Pack Margin".equals(item) || "Margin".equals(item)) {
                    showAmountDialog("UV Island Pack Margin",0f,.15f,value->{
                        checkpointMeshEdit();meshyViewport.packUvIslands(value);
                        status.setText("UV Editing | Packed islands with "+String.format(java.util.Locale.US,"%.3f",value)+" margin");
                    });
                } else if ("New UV Map".equals(item)) {
                    checkpointMeshEdit();meshyViewport.unwrapMesh();
                    status.setText("UV Editing | Created/rebuilt the active UVMap using LSCM");
                } else if ("Remove UV Map".equals(item)) {
                    checkpointMeshEdit();meshyViewport.removeUvMap();
                    status.setText("UV Editing | Active UVMap removed");
                } else if ("UV Maps".equals(item) || "Active UV Map".equals(item)) {
                    showUvMapManagementDialog();
                } else if ("Unwrap Method".equals(item)) {
                    new android.app.AlertDialog.Builder(activity).setTitle("UV Unwrap Method")
                            .setMessage("Meshy3D currently implements LSCM (conformal) unwrap. Other Blender unwrap algorithms are not available yet.")
                            .setPositiveButton("Use LSCM",(d,w)->{checkpointMeshEdit();meshyViewport.unwrapMesh();})
                            .setNegativeButton("Close",null).show();
                } else if ("Move UV".equals(item) || "Rotate UV".equals(item) || "Scale UV".equals(item)
                        || "UV Canvas".equals(item) || "Image / UV View".equals(item)
                        || "UV Islands".equals(item) || "UV Editor".equals(item)) {
                    if(meshyViewport.getUvIslandCount()==0)meshyViewport.unwrapMesh();
                    showUvEditor(false);
                } else if ("Export UV Layout".equals(item)) {
                    android.content.ClipboardManager clipboard = (android.content.ClipboardManager)
                            activity.getSystemService(Activity.CLIPBOARD_SERVICE);
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText(
                            "Meshy3D UV Layout", meshyViewport.exportUvCsv()));
                    status.setText("UV Editing | UV coordinates copied as CSV");
                } else showWorkspaceActionInfo(item);
                break;
            case 4:
                if ("New Texture".equals(item)) {
                    checkpointMeshEdit();
                    meshyViewport.createPaintTexture(1024,1024,0xFFFFFFFF);
                    showUvEditor(true,"Draw");
                    status.setText("Texture Paint | Created 1024 × 1024 RGBA image");
                } else if ("Brush Color".equals(item)) showPaintColorDialog();
                else if ("Brush Radius".equals(item)) showAmountDialog("Texture Brush Radius",1f,256f,
                        value->meshyViewport.setPaintBrush(meshyViewport.getDocumentCore().getBrushColor(),value,
                                meshyViewport.getDocumentCore().getBrushOpacity()));
                else if ("Strength".equals(item)) showAmountDialog("Texture Brush Strength",.01f,1f,
                        value->meshyViewport.setPaintBrush(meshyViewport.getDocumentCore().getBrushColor(),
                                meshyViewport.getDocumentCore().getBrushRadius(),value));
                else if ("Active Texture".equals(item) || "Texture Canvas".equals(item)
                        || "Canvas".equals(item)) {
                    if(meshyViewport.getDocumentCore().getTexturePixels()==null)
                        meshyViewport.createPaintTexture(1024,1024,0xFFFFFFFF);
                    showUvEditor(true,"Draw");
                } else if (java.util.Arrays.asList("Draw","Soften","Smear","Clone","Fill","Mask",
                        "Erase Alpha","Line","Polyline","Airbrush","Gradient","Sample Color","Set Clone").contains(item)) {
                    if(meshyViewport.getDocumentCore().getTexturePixels()==null)
                        meshyViewport.createPaintTexture(1024,1024,0xFFFFFFFF);
                    showUvEditor(true,item);
                } else if ("Save Image".equals(item) || "Image Save / Reload".equals(item)) {
                    showTextureImageSaveDialog();
                } else showWorkspaceActionInfo(item);
                break;
            case 5:
                if ("Shader Editor".equals(item) || "Shader Nodes".equals(item) || "Node Graph".equals(item)
                        || "Principled BSDF".equals(item)) showNodeGraph(1);
                else if ("Base Color".equals(item) || "Viewport Display".equals(item)
                        || "New Material".equals(item) || "Material Preview".equals(item)) showPaintColorDialog();
                else if ("Metallic".equals(item)) showAmountDialog("Metallic",0f,1f,value->{
                    checkpointMeshEdit();meshyViewport.setMaterialParameters(value,meshyViewport.getRoughness());
                    status.setText("Shading | Metallic "+value);
                });
                else if ("Roughness".equals(item)) showAmountDialog("Roughness",.02f,1f,value->{
                    checkpointMeshEdit();meshyViewport.setMaterialParameters(meshyViewport.getMetallic(),value);
                    status.setText("Shading | Roughness "+value);
                });
                else showWorkspaceActionInfo(item);
                break;
            case 6:
                if ("Insert Keyframe".equals(item) || "Keyframes".equals(item)) {
                    checkpointMeshEdit();meshyViewport.storeMeshKeyframe(currentTimelineFrame);
                    status.setText("Animation | Keyframe stored at frame "+currentTimelineFrame);
                } else if ("Delete Keyframe".equals(item)) {
                    checkpointMeshEdit();meshyViewport.deleteMeshKeyframe(currentTimelineFrame);
                    status.setText("Animation | Keyframe at frame "+currentTimelineFrame+" deleted");
                } else if ("Duplicate Keyframes".equals(item)) {
                    checkpointMeshEdit();
                    boolean copied=meshyViewport.duplicateMeshKeyframe(currentTimelineFrame,
                            currentTimelineFrame<250?currentTimelineFrame+1:currentTimelineFrame-1);
                    status.setText(copied?"Animation | Keyframe duplicated to adjacent frame"
                            :"Animation | No keyframe exists at the current frame");
                } else if ("Snap".equals(item)) {
                    java.util.ArrayList<Integer> keys=meshyViewport.getMeshKeyframeFrames();
                    if(keys.isEmpty())status.setText("Animation | No keyframes to snap to");
                    else {
                        int nearest=keys.get(0);
                        for(int key:keys)if(Math.abs(key-currentTimelineFrame)<Math.abs(nearest-currentTimelineFrame))nearest=key;
                        currentTimelineFrame=nearest;meshyViewport.applyAnimationFrame(nearest);
                        status.setText("Animation | Playhead snapped to keyframe "+nearest);
                    }
                } else if ("Mirror".equals(item)) {
                    checkpointMeshEdit();
                    int count=meshyViewport.mirrorMeshKeyframesAround(currentTimelineFrame);
                    status.setText("Animation | Mirrored "+count+" keyframe states around frame "+currentTimelineFrame);
                } else if ("Timeline".equals(item) || "Playback Controls".equals(item)
                        || "Current Frame: 1".equals(item) || "Dope Sheet".equals(item)
                        || "Graph Editor".equals(item)) showAnimationTimeline();
                else showWorkspaceActionInfo(item);
                break;
            case 7:
                if ("Render Image".equals(item) || "Render Viewport".equals(item) || "Last Render".equals(item)
                        || "Render Result".equals(item)) saveViewportRender();
                else if ("Settings".equals(item) || "Render Settings".equals(item)
                        || "Output Properties".equals(item)) showRenderOutputSettings();
                else if ("Resolution X".equals(item)) showAmountDialog("Output Width (pixels)",64f,2048f,renderOutputWidth,value->{
                    renderOutputWidth=Math.max(64,Math.min(2048,Math.round(value)));
                    status.setText("Rendering | Output width set to "+renderOutputWidth+" px");
                });
                else if ("Resolution Y".equals(item)) showAmountDialog("Output Height (pixels)",64f,2048f,renderOutputHeight,value->{
                    renderOutputHeight=Math.max(64,Math.min(2048,Math.round(value)));
                    status.setText("Rendering | Output height set to "+renderOutputHeight+" px");
                });
                else if ("Percentage".equals(item)) showAmountDialog("Resolution Percentage",10f,100f,renderResolutionPercent,value->{
                    renderResolutionPercent=Math.max(10,Math.min(100,Math.round(value)));
                    status.setText("Rendering | Resolution scale set to "+renderResolutionPercent+"%");
                });
                else if ("Camera View".equals(item)) {
                    try{meshyViewport.toggleActiveCameraView();}
                    catch(RuntimeException error){status.setText("Rendering | "+error.getMessage());}
                } else if ("Render Engine".equals(item)) {
                    new android.app.AlertDialog.Builder(activity).setTitle("Render Engine")
                            .setMessage("Meshy3D currently uses its CPU software rasterizer with scene lighting and compositor effects. Cycles/path tracing and GPU render engines are not implemented.")
                            .setPositiveButton("OK",null).show();
                } else showWorkspaceActionInfo(item);
                break;
            case 8:
                if ("Node Editor".equals(item) || "Compositor".equals(item) || "Node Graph".equals(item)
                        || "Add Node".equals(item)) {
                    if(meshyViewport.getDocumentCore().getTexturePixels()==null)
                        meshyViewport.createPaintTexture(512,512,0xFF808080);
                    showNodeGraph(2);
                } else showWorkspaceActionInfo(item);
                break;
            case 9:
                if ("Node Graph".equals(item) || "Geometry Nodes".equals(item) || "Add Node".equals(item)
                        || "Group Input".equals(item) || "Group Output".equals(item)) showNodeGraph(0);
                else if ("Transform Geometry".equals(item) || "Set Position".equals(item) || "Move".equals(item))
                    showTransformDialog("Move");
                else if ("Set Shade Smooth".equals(item) || "Smooth".equals(item)) {
                    checkpointMeshEdit();meshyViewport.smoothMesh(.18f,1);
                    status.setText("Geometry Nodes | Smooth operation evaluated on active mesh");
                } else showWorkspaceActionInfo(item);
                break;
            default: showWorkspaceActionInfo(item);
        }
    }

    private void showUvMapManagementDialog() {
        boolean hasMap=meshyViewport.hasUvMap();
        String summary=hasMap?"UVMap (Active) • "+meshyViewport.getUvIslandCount()+" islands":"No active UV map";
        new android.app.AlertDialog.Builder(activity).setTitle("UV Maps")
                .setMessage(summary+"\n\nMeshy3D currently stores one active UV map per mesh.")
                .setPositiveButton(hasMap?"Rebuild UVMap":"Create UVMap",(d,w)->{
                    checkpointMeshEdit();meshyViewport.unwrapMesh();
                    status.setText("UV Editing | Active UVMap generated with LSCM");
                })
                .setNeutralButton(hasMap?"Remove UVMap":null,(d,w)->{
                    checkpointMeshEdit();meshyViewport.removeUvMap();
                    status.setText("UV Editing | UVMap removed");
                })
                .setNegativeButton("Close",null).show();
    }

    private void showTextureImageSaveDialog() {
        Meshy3DCore core=meshyViewport.getDocumentCore();
        int[] pixels=core.getTexturePixelsUnsafe();
        int width=core.getTextureWidth(),height=core.getTextureHeight();
        if(pixels==null||width<1||height<1){
            Toast.makeText(activity,"There is no active texture to save.",Toast.LENGTH_SHORT).show();
            return;
        }
        android.graphics.Bitmap bitmap=null;
        try {
            bitmap=android.graphics.Bitmap.createBitmap(pixels,width,height,android.graphics.Bitmap.Config.ARGB_8888);
            File directory=new File(activity.getFilesDir(),"Meshy3D/Textures");
            if(!directory.exists()&&!directory.mkdirs())throw new java.io.IOException("Cannot create texture folder");
            File output=new File(directory,"Texture-"+System.currentTimeMillis()+".png");
            try(java.io.FileOutputStream stream=new java.io.FileOutputStream(output)){
                if(!bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,stream))
                    throw new java.io.IOException("PNG encoding failed");
                stream.flush();
            }
            status.setText("Texture Paint | Saved "+width+" × "+height+" PNG: "+output.getAbsolutePath());
            Toast.makeText(activity,"Texture saved as PNG",Toast.LENGTH_SHORT).show();
        } catch(Exception error) {
            status.setText("Texture Paint | Save failed: "+error.getMessage());
            Toast.makeText(activity,"Texture save failed",Toast.LENGTH_SHORT).show();
        } finally {
            if(bitmap!=null&&!bitmap.isRecycled())bitmap.recycle();
        }
    }

    private void showUvEditor(boolean paintMode) {
        showUvEditor(paintMode,paintMode?"Draw":"");
    }

    private void showUvEditor(boolean paintMode,String initialTool) {
        Meshy3DCore core=meshyViewport.getDocumentCore();
        if(paintMode&&core.getTexturePixelsUnsafe()==null)
            meshyViewport.createPaintTexture(1024,1024,0xFFFFFFFF);
        Meshy3DUvEditorView editor=new Meshy3DUvEditorView(activity,core,paintMode,this::checkpointMeshEdit);
        if(paintMode)editor.setPaintTool(initialTool);
        LinearLayout root=new LinearLayout(activity);root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF20252C);
        HorizontalScrollView tools=new HorizontalScrollView(activity);tools.setHorizontalScrollBarEnabled(false);
        LinearLayout buttons=new LinearLayout(activity);buttons.setOrientation(LinearLayout.HORIZONTAL);
        String[] actions=paintMode
                ?new String[]{"Draw","Soften","Smear","Clone","Set Clone","Fill","Mask","Erase Alpha",
                    "Line","Polyline","Airbrush","Gradient","Sample Color","Orange","Blue","White",
                    "Brush -","Brush +","Opacity +","Opacity -"}
                :new String[]{"Select Linked","Select Pinned","Select Overlap","Select Boundary",
                    "Mirror X","Mirror Y","Align X","Align Y","Move +","Move -","Rotate +","Rotate -",
                    "Scale +","Scale -","Weld","Split","Pin","Unpin","Mark Seam","Clear Seam",
                    "Snap Pixels","Snap Vertex","Pack Islands","Unwrap","Export UV"};
        for(String action:actions){
            TextView b=dockItem(action,false);
            buttons.addView(b);
            b.setOnClickListener(v->{
                if(paintMode){
                    if(action.equals("Orange"))editor.setBrush(0xFFFF6B35,core.getBrushRadius(),core.getBrushOpacity());
                    else if(action.equals("Blue"))editor.setBrush(0xFF368BFF,core.getBrushRadius(),core.getBrushOpacity());
                    else if(action.equals("White"))editor.setBrush(0xFFFFFFFF,core.getBrushRadius(),core.getBrushOpacity());
                    else if(action.equals("Brush -"))editor.setBrush(core.getBrushColor(),Math.max(1,core.getBrushRadius()-4),core.getBrushOpacity());
                    else if(action.equals("Brush +"))editor.setBrush(core.getBrushColor(),Math.min(256,core.getBrushRadius()+4),core.getBrushOpacity());
                    else if(action.equals("Opacity +"))editor.setBrush(core.getBrushColor(),core.getBrushRadius(),Math.min(1,core.getBrushOpacity()+.1f));
                    else if(action.equals("Opacity -"))editor.setBrush(core.getBrushColor(),core.getBrushRadius(),Math.max(.05f,core.getBrushOpacity()-.1f));
                    else {
                        editor.setPaintTool(action);
                        status.setText("Texture Paint | "+action+" tool selected");
                    }
                }else{
                    switch(action){
                        case "Select Linked": meshyViewport.selectLinkedUvIsland();break;
                        case "Select Pinned": meshyViewport.selectPinnedUvIsland();break;
                        case "Select Overlap": meshyViewport.selectOverlappingUvIsland();break;
                        case "Select Boundary": meshyViewport.selectBoundaryUvIsland();break;
                        case "Mirror X": checkpointMeshEdit();meshyViewport.mirrorSelectedUv(0);break;
                        case "Mirror Y": checkpointMeshEdit();meshyViewport.mirrorSelectedUv(1);break;
                        case "Align X": checkpointMeshEdit();meshyViewport.alignSelectedUv(0);break;
                        case "Align Y": checkpointMeshEdit();meshyViewport.alignSelectedUv(1);break;
                        case "Weld": checkpointMeshEdit();meshyViewport.weldSelectedUv(.001f);break;
                        case "Split": checkpointMeshEdit();meshyViewport.splitSelectedUvIsland();break;
                        case "Snap Pixels":
                            checkpointMeshEdit();meshyViewport.snapSelectedUvToPixels(
                                    core.getTextureWidth()>0?core.getTextureWidth():1024,
                                    core.getTextureHeight()>0?core.getTextureHeight():1024);break;
                        case "Snap Vertex": checkpointMeshEdit();meshyViewport.snapSelectedUvToNearestVertex(.08f);break;
                        case "Move +": checkpointMeshEdit();core.transformSelectedUv(.05f,0,1,0);break;
                        case "Move -": checkpointMeshEdit();core.transformSelectedUv(-.05f,0,1,0);break;
                        case "Rotate +": checkpointMeshEdit();core.transformSelectedUv(0,0,1,(float)Math.toRadians(15));break;
                        case "Rotate -": checkpointMeshEdit();core.transformSelectedUv(0,0,1,(float)Math.toRadians(-15));break;
                        case "Scale +": checkpointMeshEdit();core.transformSelectedUv(0,0,1.1f,0);break;
                        case "Scale -": checkpointMeshEdit();core.transformSelectedUv(0,0,.9f,0);break;
                        case "Pin": checkpointMeshEdit();meshyViewport.pinSelectedUvs(true);break;
                        case "Unpin": checkpointMeshEdit();meshyViewport.pinSelectedUvs(false);break;
                        case "Mark Seam": checkpointMeshEdit();meshyViewport.markSeams(false);break;
                        case "Clear Seam": checkpointMeshEdit();meshyViewport.markSeams(true);break;
                        case "Pack Islands": checkpointMeshEdit();meshyViewport.packUvIslands();break;
                        case "Unwrap": checkpointMeshEdit();meshyViewport.unwrapMesh();break;
                        case "Export UV":
                            android.content.ClipboardManager clipboard=(android.content.ClipboardManager)
                                    activity.getSystemService(Activity.CLIPBOARD_SERVICE);
                            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Meshy3D UV Layout",meshyViewport.exportUvCsv()));
                            Toast.makeText(activity,"UV CSV copied",Toast.LENGTH_SHORT).show();break;
                    }
                    editor.invalidate();
                }
            });
        }
        tools.addView(buttons);
        root.addView(tools,new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT,dp(42)));
        root.addView(editor,new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT,0,1f));
        android.app.AlertDialog dialog=new android.app.AlertDialog.Builder(activity)
                .setTitle(paintMode?"Texture Paint Image":"UV Editing")
                .setView(root)
                .setPositiveButton("Done",(d,w)->status.setText(paintMode
                        ?"Texture Paint | Texture changes applied":"UV Editing | UV changes applied"))
                .setNegativeButton("Close",null).create();
        dialog.show();
        if(dialog.getWindow()!=null){
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.black);
            dialog.getWindow().setLayout(android.view.WindowManager.LayoutParams.MATCH_PARENT,
                    android.view.WindowManager.LayoutParams.MATCH_PARENT);
        }
    }

    private void showAnimationTimeline() {
        LinearLayout panel=new LinearLayout(activity);panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(14),dp(10),dp(14),dp(8));
        TextView frameLabel=new TextView(activity);
        frameLabel.setTextColor(Color.WHITE);
        SeekBar frame=new SeekBar(activity);frame.setMax(249);
        frame.setProgress(Math.max(0,Math.min(249,currentTimelineFrame-1)));
        Runnable refreshLabel=()->{
            String[] interpolation={"Smooth","Linear","Constant"};
            frameLabel.setText("Frame "+currentTimelineFrame+"  •  "+meshyViewport.getMeshKeyframeCount()
                    +" keyframes  •  "+interpolation[meshyViewport.getAnimationInterpolationMode()]);
        };
        frame.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onProgressChanged(SeekBar b,int p,boolean user){
                currentTimelineFrame=p+1;refreshLabel.run();
                meshyViewport.applyAnimationFrame(currentTimelineFrame);
            }
            public void onStartTrackingTouch(SeekBar b){}
            public void onStopTrackingTouch(SeekBar b){}
        });
        refreshLabel.run();
        panel.addView(frameLabel);panel.addView(frame);

        HorizontalScrollView actions=new HorizontalScrollView(activity);
        actions.setHorizontalScrollBarEnabled(false);
        LinearLayout row=new LinearLayout(activity);row.setOrientation(LinearLayout.HORIZONTAL);
        TextView first=dockItem("|◀",false),last=dockItem("▶|",false);
        TextView insert=dockItem("Insert Keyframe",false),remove=dockItem("Delete Keyframe",false);
        TextView duplicate=dockItem("Duplicate",false);
        TextView interpolation=dockItem("Smooth",false),reverse=dockItem("Reverse",false);
        TextView play=dockItem("Play",false);
        for(TextView control:new TextView[]{first,last,insert,remove,duplicate,interpolation,reverse,play})
            row.addView(control);
        actions.addView(row);panel.addView(actions);

        first.setOnClickListener(v->{
            currentTimelineFrame=1;frame.setProgress(0);meshyViewport.applyAnimationFrame(1);refreshLabel.run();
        });
        last.setOnClickListener(v->{
            currentTimelineFrame=250;frame.setProgress(249);meshyViewport.applyAnimationFrame(250);refreshLabel.run();
        });
        insert.setOnClickListener(v->{
            checkpointMeshEdit();meshyViewport.storeMeshKeyframe(currentTimelineFrame);
            refreshLabel.run();status.setText("Animation | Keyframe stored for scene objects and mesh");
        });
        remove.setOnClickListener(v->{
            checkpointMeshEdit();meshyViewport.deleteMeshKeyframe(currentTimelineFrame);
            refreshLabel.run();status.setText("Animation | Keyframe removed");
        });
        duplicate.setOnClickListener(v->{
            int target=currentTimelineFrame>=250?1:currentTimelineFrame+1;
            checkpointMeshEdit();
            boolean copied=meshyViewport.duplicateMeshKeyframe(currentTimelineFrame,target);
            status.setText(copied?"Animation | Duplicated keyframe to "+target
                    :"Animation | No keyframe exists at the current frame");
            refreshLabel.run();
        });
        interpolation.setOnClickListener(v->{
            int mode=(meshyViewport.getAnimationInterpolationMode()+1)%3;
            meshyViewport.setAnimationInterpolationMode(mode);
            String[] labels={"Smooth","Linear","Constant"};
            interpolation.setText(labels[mode]);refreshLabel.run();
            meshyViewport.applyAnimationFrame(currentTimelineFrame);
        });
        interpolation.setText(new String[]{"Smooth","Linear","Constant"}[
                meshyViewport.getAnimationInterpolationMode()]);
        reverse.setOnClickListener(v->{
            timelineReverse=!timelineReverse;
            reverse.setText(timelineReverse?"Reverse ✓":"Reverse");
        });
        play.setOnClickListener(v->{
            timelinePlaying=!timelinePlaying;
            play.setText(timelinePlaying?"Pause":"Play");
            if(timelinePlaying){
                if(timelineTick!=null)timelineHandler.removeCallbacks(timelineTick);
                timelineTick=new Runnable(){@Override public void run(){
                    if(!timelinePlaying)return;
                    int next=currentTimelineFrame+(timelineReverse?-1:1);
                    if(next>250)next=1;
                    if(next<1)next=250;
                    currentTimelineFrame=next;
                    frame.setProgress(next-1);
                    refreshLabel.run();
                    timelineHandler.postDelayed(this,33L);
                }};
                timelineHandler.post(timelineTick);
            }else if(timelineTick!=null)timelineHandler.removeCallbacks(timelineTick);
        });

        android.app.AlertDialog dialog=new android.app.AlertDialog.Builder(activity)
                .setTitle("Animation Timeline")
                .setView(panel).setPositiveButton("Done",null)
                .setNegativeButton("Close",null).create();
        dialog.setOnDismissListener(d->{
            timelinePlaying=false;
            if(timelineTick!=null)timelineHandler.removeCallbacks(timelineTick);
        });
        dialog.show();
    }

    private void showNodeGraph(int graph) {
        Meshy3DCore core=meshyViewport.getDocumentCore();
        String title=graph==0?"Geometry Nodes":graph==1?"Shader Editor":"Compositor";
        Meshy3DNodeGraphView graphView=new Meshy3DNodeGraphView(activity);
        graphView.setNodes(core.getGraphNodeLabels(graph));
        graphView.setLinks(core.getGraphLinks(graph));
        graphView.setOnNodeClickListener(index->{
            String type=core.getGraphNodeType(graph,index);float current=core.getGraphNodeValue(graph,index);
            if("Material Output".equals(type)) {
                status.setText(title+" | Output node receives the evaluated material graph");
            } else if(type.equals("Base Color")||type.equals("Set Material")){
                String[] names={"Grey","Orange","Blue","Green","Red","White"};
                int[] colors={0xFF9DAAB7,0xFFE79A45,0xFF5D9BEA,0xFF63B887,0xFFD85B5B,0xFFF2F2F2};
                new android.app.AlertDialog.Builder(activity).setTitle(type+" Node Color").setItems(names,(d,w)->{
                    core.setGraphNodeValue(graph,index,colors[w]&0xFFFFFF);graphView.invalidate();
                }).show();
            }else{
                float min=(type.equals("Metallic")||type.equals("Roughness"))?0f:-100f;
                float max=(type.equals("Metallic")||type.equals("Roughness"))?1f:100f;
                if(type.equals("Blur")){min=1f;max=8f;}else if(type.equals("Alpha Over")){min=0f;max=1f;}else if(type.equals("Instance on Points")){min=1f;max=20f;}else if(graph==0&&type.equals("Smooth")){min=0f;max=1f;}
                showAmountDialog(type+" Node Value",min,max,value->{
                    core.setGraphNodeValue(graph,index,value);graphView.invalidate();
                    status.setText(title+" | "+type+" node value updated");
                });
            }
        });
        graphView.setOnLinkListener((from,to)->{
            boolean connected=core.connectGraphNodes(graph,from,to);graphView.setLinks(core.getGraphLinks(graph));
            status.setText(connected?title+" | Connected "+from+" → "+to:title+" | Connection rejected (cycle or invalid socket)");
        });
        LinearLayout root=new LinearLayout(activity);root.setOrientation(LinearLayout.VERTICAL);
        HorizontalScrollView actions=new HorizontalScrollView(activity);actions.setHorizontalScrollBarEnabled(false);
        LinearLayout row=new LinearLayout(activity);row.setOrientation(LinearLayout.HORIZONTAL);
        TextView add=dockItem("Add Node",false),evaluate=dockItem("Evaluate Graph",false);
        row.addView(add);row.addView(evaluate);actions.addView(row);
        add.setOnClickListener(v->{
            String[] choices=graph==0
                    ?new String[]{"Transform Geometry","Set Position","Subdivide Mesh","Smooth","Delete Geometry","Instance on Points","Set Material","Random Value","Math"}
                    :graph==1?new String[]{"Base Color","Metallic","Roughness","Material Output"}
                    :new String[]{"Grayscale","Brightness","Contrast","Blur","Invert","Alpha Over","Color Balance","Composite"};
            new android.app.AlertDialog.Builder(activity).setTitle("Add "+title+" Node").setItems(choices,(d,w)->{
                String type=choices[w];float value=graph==0?.15f:graph==1
                        ?(type.equals("Metallic")?.5f:type.equals("Roughness")?.65f:type.equals("Base Color")?(float)(core.getBaseColor()&0xFFFFFF):0f)
                        :type.equals("Blur")?2f:type.equals("Alpha Over")?.25f:20f;
                if(graph==0)core.addGeometryNode(type,type.equals("Instance on Points")?2f:0f,0f,0f,type.equals("Instance on Points")?3f:value);
                else core.addNodeToGraph(graph,type,value);
                graphView.setNodes(core.getGraphNodeLabels(graph));graphView.setLinks(core.getGraphLinks(graph));
                status.setText(title+" | Added "+type+" node");
            }).setNegativeButton("Cancel",null).show();
        });
        evaluate.setOnClickListener(v->{
            if(graph==2) {
                try {
                    android.graphics.Bitmap source=meshyViewport.renderFinalImage(640,360);
                    int width=source.getWidth(),height=source.getHeight();
                    int[] pixels=new int[width*height];
                    source.getPixels(pixels,0,width,0,0,width,height);
                    source.recycle();
                    int[] processed=core.evaluateCompositor(pixels,width,height);
                    android.graphics.Bitmap preview=android.graphics.Bitmap.createBitmap(
                            processed,width,height,android.graphics.Bitmap.Config.ARGB_8888);
                    android.widget.ImageView image=new android.widget.ImageView(activity);
                    image.setImageBitmap(preview);
                    image.setAdjustViewBounds(true);
                    image.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
                    new android.app.AlertDialog.Builder(activity).setTitle("Compositor Preview")
                            .setView(image).setPositiveButton("Done",(d,w)->preview.recycle()).show();
                    status.setText("Compositor | Preview evaluated through "+core.getCompositorNodeCount()+" nodes");
                } catch(Exception error) {
                    status.setText("Compositor | Preview failed: "+error.getMessage());
                }
                return;
            }
            checkpointMeshEdit();core.evaluateGraph(graph);
            if(graph==0){meshyViewport.setMeshTint(core.getBaseColor());meshyViewport.refreshFromDocument();status.setText("Geometry Nodes | "+core.getVertexCount()+" vertices, "+core.getFaceCount()+" faces evaluated");}
            else if(graph==1){meshyViewport.setMeshTint(core.getBaseColor());meshyViewport.setMaterialParameters(core.getMetallic(),core.getRoughness());status.setText("Shading | Shader graph evaluated");}
            graphView.setNodes(core.getGraphNodeLabels(graph));graphView.setLinks(core.getGraphLinks(graph));
        });
        root.addView(actions,new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT,dp(44)));
        int graphHeight=Math.max(390,55+((core.getGraphNodeLabels(graph).length+2)/3)*125);
        android.widget.HorizontalScrollView graphHorizontal=new android.widget.HorizontalScrollView(activity);
        graphHorizontal.setHorizontalScrollBarEnabled(true);
        graphHorizontal.addView(graphView,new android.widget.HorizontalScrollView.LayoutParams(dp(620),dp(graphHeight)));
        android.widget.ScrollView graphVertical=new android.widget.ScrollView(activity);
        graphVertical.addView(graphHorizontal,new android.widget.ScrollView.LayoutParams(LayoutParams.WRAP_CONTENT,LayoutParams.WRAP_CONTENT));
        root.addView(graphVertical,new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT,dp(390)));
        new android.app.AlertDialog.Builder(activity).setTitle(title+" • Executable Node Graph")
                .setView(root).setPositiveButton("Done",null).setNegativeButton("Close",null).show();
    }

    private void showWorkspaceActionInfo(String item) {
        status.setText(WORKSPACE_NAMES[activeWorkspaceIndex] + " | " + item
                + " is not implemented yet; no project data was changed.");
    }

    private void showTransformDialog(String operation) {
        if ("Scale".equals(operation) && meshyViewport.isMeshEditMode()) {
            showComponentScaleDialog();
            return;
        }
        LinearLayout panel = new LinearLayout(activity);
        panel.setPadding(dp(18), dp(8), dp(18), dp(8)); panel.setOrientation(LinearLayout.VERTICAL);
        EditText[] fields = new EditText[3]; String[] axes = {"X", "Y", "Z"};
        for (int i = 0; i < 3; i++) {
            TextView label = new TextView(activity); label.setText(axes[i]);
            fields[i] = new EditText(activity); fields[i].setSingleLine(true);
            fields[i].setInputType(android.text.InputType.TYPE_CLASS_NUMBER
                    | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
                    | android.text.InputType.TYPE_NUMBER_FLAG_SIGNED);
            fields[i].setText(operation.equals("Scale") ? "1.0" : "0.0");
            panel.addView(label); panel.addView(fields[i]);
        }
        new android.app.AlertDialog.Builder(activity).setTitle(operation + " Cube").setView(panel)
                .setPositiveButton("Apply", (d, w) -> {
                    try {
                        float x=Float.parseFloat(fields[0].getText().toString());
                        float y=Float.parseFloat(fields[1].getText().toString());
                        float z=Float.parseFloat(fields[2].getText().toString());
                        checkpointMeshEdit();
                        meshyViewport.transformMesh(operation.equals("Move") ? 0 : operation.equals("Rotate") ? 1 : 2,
                                x, y, z);
                        status.setText(WORKSPACE_NAMES[activeWorkspaceIndex] + " | " + operation + " applied to mesh");
                    } catch (NumberFormatException error) {
                        Toast.makeText(activity, "Enter valid X, Y and Z values.", Toast.LENGTH_SHORT).show();
                    }
                }).setNegativeButton("Cancel", null).show();
    }

    /**
     * Scale only the selected vertices (including vertices belonging to selected
     * edges/faces). Plane buttons leave the perpendicular axis unchanged.
     */
    private void showComponentScaleDialog() {
        if (meshyViewport.getSelectedElementCount() <= 0) {
            Toast.makeText(activity, "Select vertices, edges or faces first.", Toast.LENGTH_SHORT).show();
            return;
        }
        LinearLayout panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(16), dp(10), dp(16), dp(8));
        TextView hint = new TextView(activity);
        hint.setText("Scale selected " + meshyViewport.getSelectionMode().toLowerCase()
                + " components around their median pivot.");
        hint.setTextColor(0xFFE0E0E0);
        hint.setTextSize(13);
        panel.addView(hint);
        EditText factorInput = new EditText(activity);
        factorInput.setSingleLine(true);
        factorInput.setHint("Scale factor (e.g. 1.5)");
        factorInput.setInputType(android.text.InputType.TYPE_CLASS_NUMBER
                | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
                | android.text.InputType.TYPE_NUMBER_FLAG_SIGNED);
        factorInput.setText("1.5");
        panel.addView(factorInput);
        TextView axesHint = new TextView(activity);
        axesHint.setText("Choose an axis or plane:");
        axesHint.setTextColor(0xFFB8C2CE);
        axesHint.setTextSize(12);
        axesHint.setPadding(0, dp(8), 0, dp(4));
        panel.addView(axesHint);
        android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(activity)
                .setTitle("Scale Selected Components")
                .setView(panel)
                .setNegativeButton("Cancel", null)
                .create();
        String[] labels = {"X", "Y", "Z", "XY", "XZ", "YZ", "XYZ"};
        for (int start = 0; start < labels.length; start += 4) {
            LinearLayout row = new LinearLayout(activity);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER);
            panel.addView(row);
            for (int i = start; i < Math.min(labels.length, start + 4); i++) {
                final String axis = labels[i];
                TextView button = new TextView(activity);
                button.setText(axis);
                button.setTextColor(Color.WHITE);
                button.setTextSize(12);
                button.setGravity(Gravity.CENTER);
                button.setBackground(makePanelBackground(0xFF394653, dp(5)));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(40), 1f);
                lp.setMargins(dp(3), dp(3), dp(3), dp(3));
                row.addView(button, lp);
                button.setOnClickListener(v -> {
                    try {
                        float factor = Float.parseFloat(factorInput.getText().toString().trim());
                        if (Float.isNaN(factor) || Float.isInfinite(factor) || factor <= 0f || factor > 100f) {
                            throw new NumberFormatException();
                        }
                        float x = axis.contains("X") ? factor : 1f;
                        float y = axis.contains("Y") ? factor : 1f;
                        float z = axis.contains("Z") ? factor : 1f;
                        checkpointMeshEdit();
                        meshyViewport.transformMesh(2, x, y, z);
                        status.setText("Modeling | Selected components scaled on " + axis
                                + " by " + factor);
                        dialog.dismiss();
                    } catch (NumberFormatException error) {
                        factorInput.setError("Enter a scale factor greater than 0 and up to 100.");
                    }
                });
            }
        }
        dialog.show();
    }

    private android.graphics.drawable.Drawable makePanelBackground(int color, float radius) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(color);
        background.setCornerRadius(radius);
        return background;
    }

    /** Entry point used by the viewport Add button and Modeling workspace. */
    private void showPrimitiveDialog() { showAddMenuDialog(); }

    /** Blender-style Add menu: categories first, then a dedicated Mesh submenu. */
    private void showAddMenuDialog() {
        String[] categories={"Mesh  ›","Camera","Light","Empty"};
        new android.app.AlertDialog.Builder(activity).setTitle("Add")
                .setItems(categories,(dialog,which)->{
                    switch(which) {
                        case 0: showAddMeshMenuDialog(); break;
                        case 1: addSceneObjectAtCursor("Camera"); break;
                        case 2: showAddLightMenuDialog(); break;
                        case 3: addSceneObjectAtCursor("Empty"); break;
                    }
                }).setNegativeButton("Close",null).show();
    }

    /** Add → Mesh. These are the currently supported editable mesh primitives. */
    private void showAddMeshMenuDialog() {
        String[] meshes={"Plane","Cube","Circle","UV Sphere","Icosphere",
                "Cylinder","Cone","Torus","Grid","Monkey (Suzanne)"};
        new android.app.AlertDialog.Builder(activity).setTitle("Add  ›  Mesh")
                .setItems(meshes,(dialog,which)->{
                    String primitive=meshes[which];
                    if("Monkey (Suzanne)".equals(primitive))primitive="Monkey";
                    addMeshPrimitive(primitive);
                }).setNegativeButton("Back", (dialog,which)->showAddMenuDialog()).show();
    }

    private void addMeshPrimitive(String primitive) {
        boolean editMode=meshyViewport.isMeshEditMode();
        checkpointMeshEdit();
        try {
            String activeId=meshyViewport.addPrimitiveObject(primitive);
            if(editMode&&activeId!=null) {
                activeEditorMode="Edit Mode";
                if(modeButton!=null)modeButton.setText(activeEditorMode);
                meshyViewport.setMeshEditMode(true);
                meshyViewport.setObjectSelectionEnabled(false);
                status.setText("Modeling | "+primitive+" geometry added to active mesh at the 3D Cursor");
            } else {
                activeEditorMode="Object Mode";
                if(modeButton!=null)modeButton.setText(activeEditorMode);
                meshyViewport.setMeshEditMode(false);
                meshyViewport.setObjectSelectionEnabled(true);
                status.setText("Modeling | "+primitive+" object added at the 3D Cursor");
            }
            refreshModelingOutliner();
        } catch(Exception error) {
            showObjectOperationError(error);
        }
    }

    private void showAddLightMenuDialog() {
        String[] lights={"Point Light","Sun Light","Spot Light","Area Light"};
        new android.app.AlertDialog.Builder(activity).setTitle("Add  ›  Light")
                .setItems(lights,(dialog,which)->addSceneObjectAtCursor(lights[which]))
                .setNegativeButton("Back",(dialog,which)->showAddMenuDialog()).show();
    }

    private void addSceneObjectAtCursor(String type) {
        checkpointMeshEdit();
        try {
            float[] cursor=meshyViewport.getSceneCursorLocation();
            String id;
            if("Camera".equals(type)) {
                id=meshyViewport.addCameraObject();
            } else if("Empty".equals(type)) {
                id=meshyViewport.addEmptyObject();
            } else {
                id=meshyViewport.addLightObject();
                String lightType=type.substring(0,type.indexOf(" Light"))
                        .toUpperCase(java.util.Locale.ROOT);
                meshyViewport.setActiveLightSettings(lightType,1000f,45f,0xFFFFFFFF);
            }
            if(id!=null)meshyViewport.getObjectScene().setWorldLocation(id,cursor[0],cursor[1],cursor[2]);
            activeEditorMode="Object Mode";
            if(modeButton!=null)modeButton.setText(activeEditorMode);
            meshyViewport.setMeshEditMode(false);
            meshyViewport.setObjectSelectionEnabled(true);
            refreshModelingOutliner();
            status.setText("Modeling | "+type+" added at the 3D Cursor");
        } catch(Exception error) {
            showObjectOperationError(error);
        }
    }

    private void showObjectTypeDialog() {
        String[] labels={"Meshes","Cameras","Lights","Empties"};
        String[] types={"MESH","CAMERA","LIGHT","EMPTY"};
        new android.app.AlertDialog.Builder(activity).setTitle("Select Objects by Type")
                .setItems(labels,(d,w)->{
                    meshyViewport.selectObjectsByType(types[w],false);refreshModelingOutliner();
                    status.setText("Modeling | Selected "+labels[w].toLowerCase(java.util.Locale.ROOT));
                }).setNegativeButton("Cancel",null).show();
    }

    private void showDeleteObjectsByTypeDialog() {
        String[] labels={"Meshes","Cameras","Lights","Empties"};
        String[] types={"MESH","CAMERA","LIGHT","EMPTY"};
        new android.app.AlertDialog.Builder(activity).setTitle("Delete Objects by Type")
                .setItems(labels,(d,w)->{
                    Meshy3DObjectScene scene=meshyViewport.getObjectScene();
                    int count=0;for(Meshy3DObjectScene.Object3D object:scene.getObjects())
                        if(object.type.equalsIgnoreCase(types[w]))count++;
                    if(count==0){Toast.makeText(activity,"No "+labels[w].toLowerCase(java.util.Locale.ROOT)+" in the scene.",Toast.LENGTH_SHORT).show();return;}
                    final int deleteCount=count;
                    final String typeLabel=labels[w].toLowerCase(java.util.Locale.ROOT);
                    final String objectType=types[w];
                    new android.app.AlertDialog.Builder(activity).setTitle("Confirm Delete")
                            .setMessage("Delete "+deleteCount+" "+typeLabel+"?")
                            .setPositiveButton("Delete",(dialog,button)->{
                                try{
                                    checkpointMeshEdit();meshyViewport.selectObjectsByType(objectType,false);
                                    meshyViewport.deleteSelectedObjects();refreshModelingOutliner();
                                    status.setText("Modeling | Deleted "+deleteCount+" "+typeLabel);
                                }catch(Exception error){showObjectOperationError(error);}
                            }).setNegativeButton("Cancel",null).show();
                }).setNegativeButton("Cancel",null).show();
    }

    private void showHierarchySelectionDialog() {
        String[] actions={"Parent","Children","All Descendants","Siblings","Same Collection"};
        new android.app.AlertDialog.Builder(activity).setTitle("Select Hierarchy")
                .setItems(actions,(d,w)->{
                    if(w==0)meshyViewport.selectParent(false);
                    else if(w==1)meshyViewport.selectChildren(false,false);
                    else if(w==2)meshyViewport.selectChildren(true,false);
                    else if(w==3)meshyViewport.selectSiblings(false);
                    else meshyViewport.selectSameCollection(false);
                    refreshModelingOutliner();status.setText("Modeling | Hierarchy selection: "+actions[w]);
                }).setNegativeButton("Cancel",null).show();
    }

    private void showObjectPatternDialog() {
        EditText input=new EditText(activity);input.setSingleLine(true);input.setHint("Example: Cube* or Light?");
        new android.app.AlertDialog.Builder(activity).setTitle("Select by Object Name")
                .setMessage("Use * for any number of characters and ? for one character.")
                .setView(input).setPositiveButton("Select",(d,w)->{
                    meshyViewport.selectObjectPattern(input.getText().toString(),false,false);
                    refreshModelingOutliner();status.setText("Modeling | Object name selection updated");
                }).setNegativeButton("Cancel",null).show();
    }

    private void showSceneCursorDialog() {
        float[] cursor=meshyViewport.getSceneCursorLocation();
        LinearLayout panel=new LinearLayout(activity);panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(16),dp(8),dp(16),dp(8));
        String[] axes={"X","Y","Z"};EditText[] fields=new EditText[3];
        for(int i=0;i<3;i++){
            TextView label=new TextView(activity);label.setText("Cursor "+axes[i]);
            fields[i]=new EditText(activity);fields[i].setSingleLine(true);
            fields[i].setInputType(android.text.InputType.TYPE_CLASS_NUMBER
                    |android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
                    |android.text.InputType.TYPE_NUMBER_FLAG_SIGNED);
            fields[i].setText(fmt(cursor[i]));panel.addView(label);panel.addView(fields[i]);
        }
        new android.app.AlertDialog.Builder(activity).setTitle("3D Cursor Location").setView(panel)
                .setPositiveButton("Set Cursor",(d,w)->{
                    try{
                        float x=Float.parseFloat(fields[0].getText().toString());
                        float y=Float.parseFloat(fields[1].getText().toString());
                        float z=Float.parseFloat(fields[2].getText().toString());
                        meshyViewport.setSceneCursorLocation(x,y,z);
                        status.setText("Modeling | 3D cursor location updated");
                    }catch(Exception error){Toast.makeText(activity,"Enter valid cursor coordinates.",Toast.LENGTH_SHORT).show();}
                }).setNegativeButton("Cancel",null).show();
    }

    private void showGridSnapDialog() {
        EditText input=new EditText(activity);input.setSingleLine(true);
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER|android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        input.setText("1.0");
        new android.app.AlertDialog.Builder(activity).setTitle("Snap Selected Objects to Grid")
                .setMessage("Enter the grid spacing in scene units.")
                .setView(input).setPositiveButton("Snap",(d,w)->{
                    try{
                        float step=Float.parseFloat(input.getText().toString());
                        checkpointMeshEdit();meshyViewport.snapSelectedObjectsToGrid(step);refreshModelingOutliner();
                        status.setText("Modeling | Selected object origins snapped to "+fmt(step)+" unit grid");
                    }catch(Exception error){showObjectOperationError(error);}
                }).setNegativeButton("Cancel",null).show();
    }

    private void showCameraSettingsDialog() {
        Meshy3DObjectScene.Object3D active=meshyViewport.getObjectScene().getActiveObject();
        if(active==null||!"CAMERA".equals(active.type)){
            Toast.makeText(activity,"Select a Camera object first.",Toast.LENGTH_SHORT).show();return;
        }
        LinearLayout panel=new LinearLayout(activity);panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(16),dp(8),dp(16),dp(8));
        String[] labels={"Lens (mm)","Clip Start","Clip End"};
        float[] values={active.cameraLens,active.cameraClipStart,active.cameraClipEnd};
        EditText[] fields=new EditText[3];
        for(int i=0;i<3;i++){
            TextView label=new TextView(activity);label.setText(labels[i]);
            fields[i]=new EditText(activity);fields[i].setSingleLine(true);
            fields[i].setInputType(android.text.InputType.TYPE_CLASS_NUMBER
                    |android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
            fields[i].setText(fmt(values[i]));panel.addView(label);panel.addView(fields[i]);
        }
        new android.app.AlertDialog.Builder(activity).setTitle("Camera Settings").setView(panel)
                .setPositiveButton("Apply",(d,w)->{
                    try{
                        float lens=Float.parseFloat(fields[0].getText().toString());
                        float start=Float.parseFloat(fields[1].getText().toString());
                        float end=Float.parseFloat(fields[2].getText().toString());
                        checkpointMeshEdit();meshyViewport.setActiveCameraSettings(lens,start,end);
                        status.setText("Modeling | Camera optics updated");
                    }catch(Exception error){showObjectOperationError(error);}
                }).setNegativeButton("Cancel",null).show();
    }

    private void showLightSettingsDialog() {
        Meshy3DObjectScene.Object3D active=meshyViewport.getObjectScene().getActiveObject();
        if(active==null||!"LIGHT".equals(active.type)){
            Toast.makeText(activity,"Select a Light object first.",Toast.LENGTH_SHORT).show();return;
        }
        String[] types={"POINT","SUN","SPOT","AREA"};
        String[] labels={"Point","Sun","Spot","Area"};
        LinearLayout panel=new LinearLayout(activity);panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(16),dp(8),dp(16),dp(8));
        TextView typeLabel=new TextView(activity);typeLabel.setText("Light Type: "+active.lightType);
        panel.addView(typeLabel);
        android.widget.Spinner typeSpinner=new android.widget.Spinner(activity);
        android.widget.ArrayAdapter<String> adapter=new android.widget.ArrayAdapter<>(activity,
                android.R.layout.simple_spinner_item,labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        typeSpinner.setAdapter(adapter);
        int selected=0;for(int i=0;i<types.length;i++)if(types[i].equals(active.lightType))selected=i;
        typeSpinner.setSelection(selected);panel.addView(typeSpinner);
        TextView powerLabel=new TextView(activity);powerLabel.setText("Power: "+fmt(active.lightPower));
        SeekBar power=new SeekBar(activity);power.setMax(1000);
        power.setProgress((int)Math.max(0,Math.min(1000,active.lightPower/10000f*1000f)));
        power.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onProgressChanged(SeekBar s,int p,boolean user){powerLabel.setText("Power: "+fmt(p*10f));}
            public void onStartTrackingTouch(SeekBar s){}
            public void onStopTrackingTouch(SeekBar s){}
        });
        TextView angleLabel=new TextView(activity);angleLabel.setText("Spot / Area Angle: "+fmt(active.lightAngle));
        SeekBar angle=new SeekBar(activity);angle.setMax(180);angle.setProgress((int)active.lightAngle);
        angle.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onProgressChanged(SeekBar s,int p,boolean user){angleLabel.setText("Spot / Area Angle: "+p);}
            public void onStartTrackingTouch(SeekBar s){}
            public void onStopTrackingTouch(SeekBar s){}
        });

        int initialRed=Color.red(active.lightColor),initialGreen=Color.green(active.lightColor),
                initialBlue=Color.blue(active.lightColor);
        TextView colorLabel=new TextView(activity);
        colorLabel.setText(String.format(java.util.Locale.US,"Light Color: #%02X%02X%02X",
                initialRed,initialGreen,initialBlue));
        panel.addView(colorLabel);
        SeekBar red=new SeekBar(activity);red.setMax(255);red.setProgress(initialRed);
        SeekBar green=new SeekBar(activity);green.setMax(255);green.setProgress(initialGreen);
        SeekBar blue=new SeekBar(activity);blue.setMax(255);blue.setProgress(initialBlue);
        TextView redLabel=new TextView(activity);redLabel.setText("Red");
        TextView greenLabel=new TextView(activity);greenLabel.setText("Green");
        TextView blueLabel=new TextView(activity);blueLabel.setText("Blue");
        android.widget.SeekBar.OnSeekBarChangeListener colorListener=new android.widget.SeekBar.OnSeekBarChangeListener(){
            public void onProgressChanged(SeekBar bar,int value,boolean user){
                colorLabel.setText(String.format(java.util.Locale.US,"Light Color: #%02X%02X%02X",
                        red.getProgress(),green.getProgress(),blue.getProgress()));
            }
            public void onStartTrackingTouch(SeekBar bar){}
            public void onStopTrackingTouch(SeekBar bar){}
        };
        red.setOnSeekBarChangeListener(colorListener);
        green.setOnSeekBarChangeListener(colorListener);
        blue.setOnSeekBarChangeListener(colorListener);

        panel.addView(powerLabel);panel.addView(power);panel.addView(angleLabel);panel.addView(angle);
        panel.addView(redLabel);panel.addView(red);
        panel.addView(greenLabel);panel.addView(green);
        panel.addView(blueLabel);panel.addView(blue);
        new android.app.AlertDialog.Builder(activity).setTitle("Light Settings").setView(panel)
                .setPositiveButton("Apply",(d,w)->{
                    try{
                        int color=Color.argb(255,red.getProgress(),green.getProgress(),blue.getProgress());
                        checkpointMeshEdit();
                        meshyViewport.setActiveLightSettings(types[typeSpinner.getSelectedItemPosition()],
                                power.getProgress()*10f,angle.getProgress(),color);
                        refreshModelingOutliner();status.setText("Modeling | Light settings updated");
                    }catch(Exception error){showObjectOperationError(error);}
                }).setNegativeButton("Cancel",null).show();
    }

    private void showMirrorObjectsDialog() {
        String[] options={"Global X","Global Y","Global Z","Local X","Local Y","Local Z"};
        new android.app.AlertDialog.Builder(activity).setTitle("Mirror Selected Objects")
                .setItems(options,(d,w)->{
                    try{
                        checkpointMeshEdit();
                        meshyViewport.mirrorSelectedObjects(w%3,w>=3);
                        refreshModelingOutliner();
                        status.setText("Modeling | Selected objects mirrored across "+options[w]);
                    }catch(Exception error){showObjectOperationError(error);}
                }).setNegativeButton("Cancel",null).show();
    }

    private void showObjectGeometrySnapDialog() {
        String[] choices={"Nearest Vertex","Nearest Edge","Nearest Face Surface"};
        new android.app.AlertDialog.Builder(activity).setTitle("Snap Selected Objects to Active Mesh")
                .setItems(choices,(dialog,which)->{
                    try{
                        checkpointMeshEdit();
                        int count=meshyViewport.getObjectScene().snapSelectedToActiveGeometry(which);
                        meshyViewport.invalidate();
                        refreshModelingOutliner();
                        status.setText("Modeling | Snapped "+count+" object(s) to active mesh "+choices[which].toLowerCase(java.util.Locale.ROOT));
                    }catch(Exception error){showObjectOperationError(error);}
                }).setNegativeButton("Cancel",null).show();
    }

    private void showAlignObjectsDialog() {
        String[] axes={"X","Y","Z","All Axes"};
        new android.app.AlertDialog.Builder(activity).setTitle("Align Selected Objects to Active")
                .setItems(axes,(d,w)->{
                    try{
                        checkpointMeshEdit();meshyViewport.alignSelectedObjectsToActive(w);
                        refreshModelingOutliner();status.setText("Modeling | Selected objects aligned on "+axes[w]);
                    }catch(Exception error){showObjectOperationError(error);}
                }).setNegativeButton("Cancel",null).show();
    }

    private void showTransformOrientationDialog() {
        String[] choices={"Global","Local"};
        new android.app.AlertDialog.Builder(activity).setTitle("Transform Orientation")
                .setItems(choices,(d,w)->{
                    meshyViewport.setObjectTransformLocalOrientation(w==1);
                    status.setText("Modeling | Transform orientation: "+choices[w]);
                }).setNegativeButton("Cancel",null).show();
    }

    private void showPivotPointDialog() {
        String[] choices={"Median Point","Active Object","3D Cursor","Individual Origins"};
        new android.app.AlertDialog.Builder(activity).setTitle("Pivot Point")
                .setItems(choices,(d,w)->{
                    meshyViewport.setObjectPivotMode(w);
                    status.setText("Modeling | Pivot point: "+choices[w]);
                }).setNegativeButton("Cancel",null).show();
    }

    private void showObjectTransformTypeDialog() {
        String[] operations={"Move","Rotate","Scale"};
        new android.app.AlertDialog.Builder(activity).setTitle("Numeric Object Transform")
                .setItems(operations,(d,w)->showObjectTransformDialog(operations[w]))
                .setNegativeButton("Cancel",null).show();
    }

    private void showObjectTransformDialog(String operation) {
        Meshy3DObjectScene.Object3D active=meshyViewport.getObjectScene().getActiveObject();
        if(active==null)return;
        LinearLayout panel=new LinearLayout(activity);panel.setPadding(dp(18),dp(8),dp(18),dp(8));
        panel.setOrientation(LinearLayout.VERTICAL);
        EditText[] fields=new EditText[3];String[] axes={"X","Y","Z"};
        float[] values=operation.equals("Move")
                ?new float[]{active.transform.x,active.transform.y,active.transform.z}
                :operation.equals("Rotate")
                ?new float[]{active.transform.rotationX,active.transform.rotationY,active.transform.rotationZ}
                :new float[]{active.transform.scaleX,active.transform.scaleY,active.transform.scaleZ};
        for(int i=0;i<3;i++){
            TextView label=new TextView(activity);label.setText(axes[i]);fields[i]=new EditText(activity);
            fields[i].setSingleLine(true);
            fields[i].setInputType(android.text.InputType.TYPE_CLASS_NUMBER
                    |android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
                    |android.text.InputType.TYPE_NUMBER_FLAG_SIGNED);
            fields[i].setText(String.format(java.util.Locale.US,"%.3f",values[i]));panel.addView(label);panel.addView(fields[i]);
        }
        new android.app.AlertDialog.Builder(activity).setTitle(operation+" Object").setView(panel)
                .setPositiveButton("Apply",(d,w)->{
                    try{
                        float x=Float.parseFloat(fields[0].getText().toString());
                        float y=Float.parseFloat(fields[1].getText().toString());
                        float z=Float.parseFloat(fields[2].getText().toString());
                        checkpointMeshEdit();
                        if(operation.equals("Move"))meshyViewport.transformActiveObject(0,x-active.transform.x,y-active.transform.y,z-active.transform.z);
                        else if(operation.equals("Rotate"))meshyViewport.transformActiveObject(1,x-active.transform.rotationX,y-active.transform.rotationY,z-active.transform.rotationZ);
                        else meshyViewport.transformActiveObject(2,x/active.transform.scaleX,y/active.transform.scaleY,z/active.transform.scaleZ);
                        status.setText("Modeling | Object "+operation.toLowerCase(java.util.Locale.ROOT)+" updated");
                    }catch(Exception error){Toast.makeText(activity,"Enter valid X, Y and Z values.",Toast.LENGTH_SHORT).show();}
                }).setNegativeButton("Cancel",null).show();
    }

    private void refreshModelingOutliner(){if(activeWorkspaceIndex==1)renderWorkspaceLayout(1);}

    private void showObjectVisibilitySettingsDialog() {
        Meshy3DObjectScene.Object3D active=meshyViewport.getObjectScene().getActiveObject();
        if(active==null)return;
        LinearLayout panel=new LinearLayout(activity);panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(16),dp(8),dp(16),dp(8));
        android.widget.CheckBox viewport=new android.widget.CheckBox(activity);
        viewport.setText("Visible in Viewport");viewport.setChecked(active.visible);
        android.widget.CheckBox selectable=new android.widget.CheckBox(activity);
        selectable.setText("Selectable");selectable.setChecked(active.selectable);
        android.widget.CheckBox render=new android.widget.CheckBox(activity);
        render.setText("Visible in Render");render.setChecked(active.renderVisible);
        panel.addView(viewport);panel.addView(selectable);panel.addView(render);
        new android.app.AlertDialog.Builder(activity).setTitle("Object Visibility")
                .setView(panel).setPositiveButton("Apply",(d,w)->{
                    try{
                        checkpointMeshEdit();
                        meshyViewport.setActiveObjectVisible(viewport.isChecked());
                        meshyViewport.setActiveObjectSelectable(selectable.isChecked());
                        meshyViewport.setActiveObjectRenderVisible(render.isChecked());
                        refreshModelingOutliner();status.setText("Modeling | Object visibility settings updated");
                    }catch(Exception error){showObjectOperationError(error);}
                }).setNegativeButton("Cancel",null).show();
    }

    private void showObjectPropertiesDialog() {
        final Meshy3DObjectScene scene=meshyViewport.getObjectScene();
        final Meshy3DObjectScene.Object3D object=scene.getActiveObject();
        if(object==null){
            Toast.makeText(activity,"Select an object first.",Toast.LENGTH_SHORT).show();
            return;
        }

        ScrollView scroll=new ScrollView(activity);
        LinearLayout panel=new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(16),dp(8),dp(16),dp(12));
        scroll.addView(panel);

        TextView identityHeading=new TextView(activity);
        identityHeading.setText("IDENTITY");
        identityHeading.setTextColor(ACCENT);
        identityHeading.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        panel.addView(identityHeading);

        EditText nameField=new EditText(activity);
        nameField.setSingleLine(true);
        nameField.setHint("Object name");
        nameField.setText(object.name);
        panel.addView(nameField);

        String[] components={"Location X","Location Y","Location Z",
                "Rotation X (°)","Rotation Y (°)","Rotation Z (°)",
                "Scale X","Scale Y","Scale Z"};
        float[] initial={object.transform.x,object.transform.y,object.transform.z,
                object.transform.rotationX,object.transform.rotationY,object.transform.rotationZ,
                object.transform.scaleX,object.transform.scaleY,object.transform.scaleZ};
        EditText[] fields=new EditText[components.length];

        TextView transformHeading=new TextView(activity);
        transformHeading.setText("TRANSFORM");
        transformHeading.setTextColor(ACCENT);
        transformHeading.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        transformHeading.setPadding(0,dp(12),0,dp(4));
        panel.addView(transformHeading);

        int numericType=android.text.InputType.TYPE_CLASS_NUMBER
                |android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
                |android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL;
        for(int i=0;i<components.length;i++){
            LinearLayout row=new LinearLayout(activity);
            row.setGravity(Gravity.CENTER_VERTICAL);
            TextView label=new TextView(activity);
            label.setText(components[i]);
            label.setTextColor(Color.WHITE);
            row.addView(label,new LinearLayout.LayoutParams(dp(112),LayoutParams.WRAP_CONTENT));
            fields[i]=new EditText(activity);
            fields[i].setSingleLine(true);
            fields[i].setInputType(numericType);
            fields[i].setSelectAllOnFocus(true);
            fields[i].setText(fmt(initial[i]));
            row.addView(fields[i],new LinearLayout.LayoutParams(0,LayoutParams.WRAP_CONTENT,1f));
            LinearLayout.LayoutParams rowParams=new LinearLayout.LayoutParams(
                    LayoutParams.MATCH_PARENT,LayoutParams.WRAP_CONTENT);
            rowParams.bottomMargin=dp(2);
            panel.addView(row,rowParams);
        }

        TextView stateHeading=new TextView(activity);
        stateHeading.setText("OBJECT STATE");
        stateHeading.setTextColor(ACCENT);
        stateHeading.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        stateHeading.setPadding(0,dp(12),0,dp(4));
        panel.addView(stateHeading);

        android.widget.CheckBox viewportVisible=new android.widget.CheckBox(activity);
        viewportVisible.setText("Visible in viewport");
        viewportVisible.setTextColor(Color.WHITE);
        viewportVisible.setChecked(object.visible);
        panel.addView(viewportVisible);

        android.widget.CheckBox selectable=new android.widget.CheckBox(activity);
        selectable.setText("Selectable");
        selectable.setTextColor(Color.WHITE);
        selectable.setChecked(object.selectable);
        panel.addView(selectable);

        android.widget.CheckBox renderVisible=new android.widget.CheckBox(activity);
        renderVisible.setText("Visible in render");
        renderVisible.setTextColor(Color.WHITE);
        renderVisible.setChecked(object.renderVisible);
        panel.addView(renderVisible);

        String parentName="None";
        if(object.parentId!=null){
            Meshy3DObjectScene.Object3D parent=scene.getObject(object.parentId);
            if(parent!=null)parentName=parent.name;
        }
        float[] dimensions=scene.dimensions(object.id);
        StringBuilder details=new StringBuilder();
        details.append("Type: ").append(object.type)
                .append("\nParent: ").append(parentName)
                .append("\nViewport display: ").append(object.viewportDisplayType)
                .append("\nCustom properties: ").append(scene.getCustomProperties(object.id).size())
                .append("\nWorld dimensions: ").append(fmt(dimensions[0])).append(" × ")
                .append(fmt(dimensions[1])).append(" × ").append(fmt(dimensions[2]));
        if(object.mesh!=null){
            details.append("\nModifiers: ").append(scene.getModifiers(object.id).size())
                    .append("\nMesh data users: ").append(scene.getMeshDataUserCount(object.id))
                    .append("\nVertices: ").append(object.mesh.getVertexCount())
                    .append("   Edges: ").append(object.mesh.getEdgeCount())
                    .append("   Faces: ").append(object.mesh.getFaceCount());
        }
        if("CAMERA".equals(object.type)){
            details.append("\nLens: ").append(fmt(object.cameraLens)).append(" mm")
                    .append("\nClip range: ").append(fmt(object.cameraClipStart))
                    .append(" – ").append(fmt(object.cameraClipEnd));
        }else if("LIGHT".equals(object.type)){
            details.append("\nLight: ").append(object.lightType)
                    .append("\nPower: ").append(fmt(object.lightPower))
                    .append("\nAngle: ").append(fmt(object.lightAngle))
                    .append("\nColor: #").append(String.format(java.util.Locale.US,"%08X",object.lightColor));
        }
        TextView dataInfo=new TextView(activity);
        dataInfo.setText(details.toString());
        dataInfo.setTextColor(0xFFCBD2DC);
        dataInfo.setPadding(0,dp(12),0,dp(4));
        panel.addView(dataInfo);

        android.app.AlertDialog.Builder builder=new android.app.AlertDialog.Builder(activity)
                .setTitle("Object Properties")
                .setView(scroll)
                .setPositiveButton("Apply",null)
                .setNegativeButton("Cancel",null);
        if("CAMERA".equals(object.type)){
            builder.setNeutralButton("Camera Data",(dialog,which)->showCameraSettingsDialog());
        }else if("LIGHT".equals(object.type)){
            builder.setNeutralButton("Light Data",(dialog,which)->showLightSettingsDialog());
        }

        android.app.AlertDialog dialog=builder.create();
        dialog.setOnShowListener(ignored->dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(view->{
                    try{
                        String newName=nameField.getText().toString().trim();
                        if(newName.isEmpty())throw new IllegalArgumentException("Object name cannot be empty.");
                        float[] values=new float[fields.length];
                        for(int i=0;i<fields.length;i++){
                            values[i]=Float.parseFloat(fields[i].getText().toString().trim());
                            if(!Float.isFinite(values[i])||Math.abs(values[i])>100000f)
                                throw new IllegalArgumentException("Transform values must be finite and within ±100000.");
                        }
                        for(int i=6;i<9;i++){
                            if(Math.abs(values[i])<0.0001f||Math.abs(values[i])>10000f)
                                throw new IllegalArgumentException("Scale values must be between 0.0001 and 10000 in magnitude.");
                        }
                        checkpointMeshEdit();
                        scene.rename(object.id,newName);
                        Meshy3DObjectScene.Transform transform=object.transform.copy();
                        transform.x=values[0];transform.y=values[1];transform.z=values[2];
                        transform.rotationX=values[3];transform.rotationY=values[4];transform.rotationZ=values[5];
                        transform.scaleX=values[6];transform.scaleY=values[7];transform.scaleZ=values[8];
                        scene.setTransform(object.id,transform);
                        scene.setVisibility(object.id,viewportVisible.isChecked());
                        scene.setSelectable(object.id,selectable.isChecked());
                        scene.setRenderVisibility(object.id,renderVisible.isChecked());
                        meshyViewport.invalidate();
                        refreshModelingOutliner();
                        status.setText("Modeling | Object properties applied to "+newName);
                        dialog.dismiss();
                    }catch(Exception error){
                        showObjectOperationError(error);
                    }
                }));
        dialog.show();
    }

    private void showObjectViewportDisplayDialog() {
        Meshy3DObjectScene.Object3D active=meshyViewport.getObjectScene().getActiveObject();
        if(active==null)return;
        LinearLayout panel=new LinearLayout(activity);panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(16),dp(8),dp(16),dp(8));
        TextView modeLabel=new TextView(activity);modeLabel.setText("Display As");
        panel.addView(modeLabel);
        String[] modes={"SOLID","WIRE","BOUNDS"};
        String[] modeLabels={"Solid","Wire","Bounds"};
        android.widget.Spinner displaySpinner=new android.widget.Spinner(activity);
        android.widget.ArrayAdapter<String> adapter=new android.widget.ArrayAdapter<>(activity,
                android.R.layout.simple_spinner_item,modeLabels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        displaySpinner.setAdapter(adapter);
        for(int i=0;i<modes.length;i++)if(modes[i].equals(active.viewportDisplayType))displaySpinner.setSelection(i);
        panel.addView(displaySpinner);
        TextView colorLabel=new TextView(activity);colorLabel.setText("Viewport Display Color (RRGGBB)");
        panel.addView(colorLabel);
        EditText colorField=new EditText(activity);colorField.setSingleLine(true);
        colorField.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        colorField.setText(String.format(java.util.Locale.US,"%06X",active.viewportDisplayColor&0xFFFFFF));
        panel.addView(colorField);
        new android.app.AlertDialog.Builder(activity).setTitle(active.name+" | Viewport Display")
                .setView(panel).setPositiveButton("Apply",(dialog,which)->{
                    try{
                        String hex=colorField.getText().toString().trim().replace("#","");
                        if(hex.length()!=6)throw new IllegalArgumentException("Enter exactly six hexadecimal color digits (RRGGBB).");
                        int color=0xFF000000|(Integer.parseInt(hex,16)&0xFFFFFF);
                        checkpointMeshEdit();
                        meshyViewport.getObjectScene().setViewportDisplay(active.id,
                                modes[displaySpinner.getSelectedItemPosition()],color);
                        meshyViewport.invalidate();status.setText("Modeling | Viewport display updated");
                    }catch(Exception error){showObjectOperationError(error);}
                }).setNegativeButton("Cancel",null).show();
    }

    private void showObjectCustomPropertiesDialog() {
        Meshy3DObjectScene scene=meshyViewport.getObjectScene();
        Meshy3DObjectScene.Object3D active=scene.getActiveObject();
        if(active==null){
            Toast.makeText(activity,"Select an object first.",Toast.LENGTH_SHORT).show();return;
        }
        ArrayList<Meshy3DObjectScene.CustomProperty3D> properties=
                new ArrayList<>(scene.getCustomProperties(active.id));
        String[] options=new String[properties.size()+1];options[0]="+ Add Custom Property";
        for(int i=0;i<properties.size();i++){
            Meshy3DObjectScene.CustomProperty3D property=properties.get(i);
            options[i+1]=property.key+"  ["+property.type+"] = "+property.value;
        }
        new android.app.AlertDialog.Builder(activity).setTitle(active.name+" | Custom Properties")
                .setItems(options,(dialog,which)->{
                    if(which==0)showObjectCustomPropertyEditor(null);
                    else if(which-1<properties.size())showObjectCustomPropertyEditor(properties.get(which-1));
                }).setNegativeButton("Close",null).show();
    }

    private void showObjectCustomPropertyEditor(Meshy3DObjectScene.CustomProperty3D existing) {
        Meshy3DObjectScene scene=meshyViewport.getObjectScene();
        Meshy3DObjectScene.Object3D active=scene.getActiveObject();
        if(active==null)return;
        LinearLayout panel=new LinearLayout(activity);panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(16),dp(8),dp(16),dp(8));
        TextView keyLabel=new TextView(activity);keyLabel.setText("Property Name");
        panel.addView(keyLabel);
        EditText keyField=new EditText(activity);keyField.setSingleLine(true);
        keyField.setText(existing==null?"":existing.key);keyField.setHint("Example: asset_id");
        panel.addView(keyField);
        TextView typeLabel=new TextView(activity);typeLabel.setText("Value Type");
        panel.addView(typeLabel);
        String[] types={"STRING","NUMBER","BOOLEAN"};
        String[] typeLabels={"String","Number","Boolean"};
        android.widget.Spinner typeSpinner=new android.widget.Spinner(activity);
        android.widget.ArrayAdapter<String> adapter=new android.widget.ArrayAdapter<>(activity,
                android.R.layout.simple_spinner_item,typeLabels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        typeSpinner.setAdapter(adapter);
        if(existing!=null)for(int i=0;i<types.length;i++)if(types[i].equals(existing.type))typeSpinner.setSelection(i);
        panel.addView(typeSpinner);
        TextView valueLabel=new TextView(activity);valueLabel.setText("Value");
        panel.addView(valueLabel);
        EditText valueField=new EditText(activity);valueField.setSingleLine(true);
        valueField.setText(existing==null?"":existing.value);
        valueField.setHint("Enter property value");
        panel.addView(valueField);

        android.app.AlertDialog.Builder builder=new android.app.AlertDialog.Builder(activity)
                .setTitle(existing==null?"Add Custom Property":"Edit Custom Property")
                .setView(panel).setPositiveButton("Save",null).setNegativeButton("Cancel",null);
        if(existing!=null)builder.setNeutralButton("Delete",(dialog,which)->{
            try{
                checkpointMeshEdit();scene.removeCustomProperty(active.id,existing.key);
                status.setText("Modeling | Custom property deleted");
                showObjectCustomPropertiesDialog();
            }catch(Exception error){showObjectOperationError(error);}
        });
        android.app.AlertDialog dialog=builder.create();
        dialog.setOnShowListener(ignored->dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(view->{
                    try{
                        String key=keyField.getText().toString();
                        String type=types[typeSpinner.getSelectedItemPosition()];
                        String value=valueField.getText().toString();
                        checkpointMeshEdit();
                        if(existing==null)scene.addCustomProperty(active.id,key,type,value);
                        else scene.setCustomProperty(active.id,existing.key,key,type,value);
                        meshyViewport.invalidate();refreshModelingOutliner();
                        status.setText("Modeling | Custom property saved");
                        dialog.dismiss();showObjectCustomPropertiesDialog();
                    }catch(Exception error){showObjectOperationError(error);}
                }));
        dialog.show();
    }

    private void showObjectConstraintsDialog() {
        Meshy3DObjectScene scene=meshyViewport.getObjectScene();
        Meshy3DObjectScene.Object3D active=scene.getActiveObject();
        if(active==null){
            Toast.makeText(activity,"Select an object first.",Toast.LENGTH_SHORT).show();
            return;
        }
        ArrayList<Meshy3DObjectScene.Constraint3D> constraints=
                new ArrayList<>(scene.getConstraints(active.id));
        String[] options=new String[constraints.size()+1];
        options[0]="+ Add Constraint";
        for(int i=0;i<constraints.size();i++){
            Meshy3DObjectScene.Constraint3D constraint=constraints.get(i);
            Meshy3DObjectScene.Object3D target=scene.getObject(constraint.targetId);
            String targetName=target==null?"Missing target":target.name;
            options[i+1]=(constraint.enabled?"✓ ":"○ ")+constraint.type.replace('_',' ')
                    +" → "+targetName+" ("+fmt(constraint.influence*100f)+"%)";
        }
        new android.app.AlertDialog.Builder(activity).setTitle(active.name+" | Constraints")
                .setItems(options,(dialog,which)->{
                    if(which==0)showObjectConstraintEditor(null);
                    else if(which-1<constraints.size())showObjectConstraintEditor(constraints.get(which-1));
                }).setNegativeButton("Close",null).show();
    }

    private void showObjectConstraintEditor(Meshy3DObjectScene.Constraint3D existing) {
        Meshy3DObjectScene scene=meshyViewport.getObjectScene();
        Meshy3DObjectScene.Object3D active=scene.getActiveObject();
        if(active==null)return;
        ArrayList<Meshy3DObjectScene.Object3D> targets=new ArrayList<>();
        ArrayList<String> targetNames=new ArrayList<>();
        int selectedTarget=0;
        for(Meshy3DObjectScene.Object3D candidate:scene.getObjects()){
            if(candidate.id.equals(active.id))continue;
            targets.add(candidate);
            targetNames.add(candidate.name+"  ·  "+candidate.type);
            if(existing!=null&&candidate.id.equals(existing.targetId))selectedTarget=targets.size()-1;
        }
        if(targets.isEmpty()){
            Toast.makeText(activity,"Add another scene object before creating a constraint.",Toast.LENGTH_SHORT).show();
            return;
        }

        String[] typeLabels={"Copy Location","Copy Rotation","Copy Scale","Copy Transforms"};
        String[] typeCodes={"COPY_LOCATION","COPY_ROTATION","COPY_SCALE","COPY_TRANSFORMS"};
        LinearLayout panel=new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(16),dp(8),dp(16),dp(8));

        TextView typeLabel=new TextView(activity);
        typeLabel.setText("Constraint Type");
        panel.addView(typeLabel);
        android.widget.Spinner typeSpinner=new android.widget.Spinner(activity);
        android.widget.ArrayAdapter<String> typeAdapter=new android.widget.ArrayAdapter<>(activity,
                android.R.layout.simple_spinner_item,typeLabels);
        typeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        typeSpinner.setAdapter(typeAdapter);
        if(existing!=null){
            for(int i=0;i<typeCodes.length;i++)if(typeCodes[i].equals(existing.type))typeSpinner.setSelection(i);
        }
        panel.addView(typeSpinner);

        TextView targetLabel=new TextView(activity);
        targetLabel.setText("Target Object");
        panel.addView(targetLabel);
        android.widget.Spinner targetSpinner=new android.widget.Spinner(activity);
        android.widget.ArrayAdapter<String> targetAdapter=new android.widget.ArrayAdapter<>(activity,
                android.R.layout.simple_spinner_item,targetNames);
        targetAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        targetSpinner.setAdapter(targetAdapter);
        targetSpinner.setSelection(Math.max(0,selectedTarget));
        panel.addView(targetSpinner);

        TextView influenceLabel=new TextView(activity);
        influenceLabel.setText("Influence (0.0 – 1.0)");
        panel.addView(influenceLabel);
        EditText influenceField=new EditText(activity);
        influenceField.setSingleLine(true);
        influenceField.setInputType(android.text.InputType.TYPE_CLASS_NUMBER
                |android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
                |android.text.InputType.TYPE_NUMBER_FLAG_SIGNED);
        influenceField.setText(fmt(existing==null?1f:existing.influence));
        panel.addView(influenceField);

        android.widget.CheckBox enabledCheck=new android.widget.CheckBox(activity);
        enabledCheck.setText("Enabled");
        enabledCheck.setChecked(existing==null||existing.enabled);
        panel.addView(enabledCheck);

        android.app.AlertDialog.Builder builder=new android.app.AlertDialog.Builder(activity)
                .setTitle(existing==null?"Add Object Constraint":"Edit Object Constraint")
                .setView(panel).setPositiveButton(existing==null?"Add":"Apply",null)
                .setNegativeButton("Cancel",null);
        if(existing!=null)builder.setNeutralButton("Remove",(dialog,which)->{
            try{
                checkpointMeshEdit();
                scene.removeConstraint(active.id,existing.id);
                meshyViewport.invalidate();
                status.setText("Modeling | Constraint removed from "+active.name);
                showObjectConstraintsDialog();
            }catch(Exception error){showObjectOperationError(error);}
        });
        android.app.AlertDialog dialog=builder.create();
        dialog.setOnShowListener(ignored->dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(view->{
                    try{
                        int targetIndex=targetSpinner.getSelectedItemPosition();
                        if(targetIndex<0||targetIndex>=targets.size())
                            throw new IllegalArgumentException("Choose a valid target object.");
                        float influence=Float.parseFloat(influenceField.getText().toString().trim());
                        if(!Float.isFinite(influence)||influence<0f||influence>1f)
                            throw new IllegalArgumentException("Influence must be between 0.0 and 1.0.");
                        checkpointMeshEdit();
                        if(existing==null){
                            Meshy3DObjectScene.Constraint3D created=scene.addConstraint(active.id,
                                    typeCodes[typeSpinner.getSelectedItemPosition()],targets.get(targetIndex).id);
                            scene.setConstraintInfluence(active.id,created.id,influence);
                            scene.setConstraintEnabled(active.id,created.id,enabledCheck.isChecked());
                        }else{
                            scene.setConstraintTarget(active.id,existing.id,targets.get(targetIndex).id);
                            scene.setConstraintInfluence(active.id,existing.id,influence);
                            scene.setConstraintEnabled(active.id,existing.id,enabledCheck.isChecked());
                            scene.setConstraintType(active.id,existing.id,typeCodes[typeSpinner.getSelectedItemPosition()]);
                        }
                        meshyViewport.invalidate();
                        status.setText("Modeling | Constraint updated on "+active.name);
                        dialog.dismiss();
                    }catch(Exception error){
                        showObjectOperationError(error);
                    }
                }));
        dialog.show();
    }

    private void showObjectModifiersDialog() {
        Meshy3DObjectScene scene=meshyViewport.getObjectScene();
        Meshy3DObjectScene.Object3D active=scene.getActiveObject();
        if(active==null||active.mesh==null||!"MESH".equals(active.type)){
            Toast.makeText(activity,"Select a mesh object to manage modifiers.",Toast.LENGTH_SHORT).show();
            return;
        }
        ArrayList<Meshy3DObjectScene.Modifier3D> modifiers=new ArrayList<>(scene.getModifiers(active.id));
        String[] options=new String[modifiers.size()+3];
        options[0]="+ Add Modifier";
        options[1]="Apply Entire Stack";
        options[2]="Remove All Modifiers";
        for(int i=0;i<modifiers.size();i++){
            Meshy3DObjectScene.Modifier3D modifier=modifiers.get(i);
            options[i+3]=(modifier.enabled?"✓ ":"○ ")+modifier.type.replace('_',' ');
        }
        new android.app.AlertDialog.Builder(activity).setTitle(active.name+" | Modifier Stack")
                .setItems(options,(dialog,which)->{
                    try{
                        if(which==0)showObjectModifierTypeChooser();
                        else if(which==1){
                            if(modifiers.isEmpty()){
                                Toast.makeText(activity,"The modifier stack is empty.",Toast.LENGTH_SHORT).show();return;
                            }
                            checkpointMeshEdit();
                            meshyViewport.applyActiveObjectModifiersThrough(modifiers.size()-1);
                            refreshModelingOutliner();
                            status.setText("Modeling | Entire modifier stack applied to "+active.name);
                            showObjectModifiersDialog();
                        }else if(which==2){
                            if(modifiers.isEmpty())return;
                            new android.app.AlertDialog.Builder(activity).setTitle("Remove All Modifiers")
                                    .setMessage("Remove every modifier from "+active.name+"? The source mesh will remain unchanged.")
                                    .setPositiveButton("Remove All",(confirm,button)->{
                                        checkpointMeshEdit();scene.clearModifiers(active.id);meshyViewport.invalidate();
                                        status.setText("Modeling | Modifier stack cleared");showObjectModifiersDialog();
                                    }).setNegativeButton("Cancel",null).show();
                        }else if(which-3<modifiers.size()){
                            showObjectModifierEditor(modifiers.get(which-3));
                        }
                    }catch(Exception error){showObjectOperationError(error);}
                }).setNegativeButton("Close",null).show();
    }

    private void showObjectModifierTypeChooser() {
        String[] labels={"Mirror","Array","Bevel","Solidify","Linear Subdivide",
                "Catmull-Clark Subdivision","Triangulate","Planar Decimate","Smooth"};
        String[] types={"MIRROR","ARRAY","BEVEL","SOLIDIFY","SUBDIVIDE",
                "CATMULL_CLARK","TRIANGULATE","PLANAR_DECIMATE","SMOOTH","WIREFRAME"};
        new android.app.AlertDialog.Builder(activity).setTitle("Add Modifier")
                .setItems(labels,(dialog,which)->{
                    try{
                        checkpointMeshEdit();
                        Meshy3DObjectScene.Object3D active=meshyViewport.getObjectScene().getActiveObject();
                        Meshy3DObjectScene.Modifier3D modifier=meshyViewport.getObjectScene()
                                .addModifier(active.id,types[which]);
                        meshyViewport.invalidate();
                        String warning=meshyViewport.validateActiveObjectModifiers();
                        if(warning!=null)showObjectOperationError(new IllegalStateException(warning));
                        showObjectModifierEditor(modifier);
                    }catch(Exception error){showObjectOperationError(error);}
                }).setNegativeButton("Cancel",null).show();
    }

    private EditText modifierNumberField(LinearLayout panel,String label,float value,boolean integer) {
        LinearLayout row=new LinearLayout(activity);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView caption=new TextView(activity);
        caption.setText(label);
        row.addView(caption,new LinearLayout.LayoutParams(dp(116),LayoutParams.WRAP_CONTENT));
        EditText field=new EditText(activity);
        field.setSingleLine(true);
        field.setInputType(integer?android.text.InputType.TYPE_CLASS_NUMBER:
                android.text.InputType.TYPE_CLASS_NUMBER|android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
                        |android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        field.setText(integer?Integer.toString((int)value):fmt(value));
        row.addView(field,new LinearLayout.LayoutParams(0,LayoutParams.WRAP_CONTENT,1f));
        panel.addView(row);
        return field;
    }

    private void showObjectModifierEditor(Meshy3DObjectScene.Modifier3D modifier) {
        Meshy3DObjectScene scene=meshyViewport.getObjectScene();
        Meshy3DObjectScene.Object3D active=scene.getActiveObject();
        if(active==null||modifier==null)return;
        LinearLayout panel=new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(16),dp(8),dp(16),dp(8));

        android.widget.CheckBox enabled=new android.widget.CheckBox(activity);
        enabled.setText("Modifier Enabled");
        enabled.setChecked(modifier.enabled);
        panel.addView(enabled);

        android.widget.Spinner axisSpinner=null;
        EditText countField=null,levelsField=null,valueField=null;
        EditText offsetXField=null,offsetYField=null,offsetZField=null;
        if("MIRROR".equals(modifier.type)){
            TextView label=new TextView(activity);label.setText("Mirror Axis");panel.addView(label);
            axisSpinner=new android.widget.Spinner(activity);
            android.widget.ArrayAdapter<String> adapter=new android.widget.ArrayAdapter<>(activity,
                    android.R.layout.simple_spinner_item,new String[]{"X","Y","Z"});
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            axisSpinner.setAdapter(adapter);axisSpinner.setSelection(modifier.axis);panel.addView(axisSpinner);
        }else if("ARRAY".equals(modifier.type)){
            countField=modifierNumberField(panel,"Copies (1–20)",modifier.count,true);
            offsetXField=modifierNumberField(panel,"Offset X",modifier.offsetX,false);
            offsetYField=modifierNumberField(panel,"Offset Y",modifier.offsetY,false);
            offsetZField=modifierNumberField(panel,"Offset Z",modifier.offsetZ,false);
        }else if("SUBDIVIDE".equals(modifier.type)||"CATMULL_CLARK".equals(modifier.type)){
            levelsField=modifierNumberField(panel,"Levels (1–3)",modifier.levels,true);
        }else if("PLANAR_DECIMATE".equals(modifier.type)){
            valueField=modifierNumberField(panel,"Maximum planar angle (0–90°)",modifier.value,false);
        }else if("SMOOTH".equals(modifier.type)){
            valueField=modifierNumberField(panel,"Smoothing amount (0–1)",modifier.value,false);
            levelsField=modifierNumberField(panel,"Iterations (1–20)",modifier.levels,true);
        }else if("WIREFRAME".equals(modifier.type)){
            valueField=modifierNumberField(panel,"Thickness (0.001–0.5)",modifier.value,false);
        }else if("BEVEL".equals(modifier.type)){
            valueField=modifierNumberField(panel,"Width (0.001–0.49)",modifier.value,false);
        }else if("SOLIDIFY".equals(modifier.type)){
            valueField=modifierNumberField(panel,"Thickness",modifier.value,false);
        }

        final android.widget.Spinner selectedAxisSpinner=axisSpinner;
        final EditText selectedCountField=countField,selectedLevelsField=levelsField,selectedValueField=valueField;
        final EditText selectedOffsetXField=offsetXField,selectedOffsetYField=offsetYField,selectedOffsetZField=offsetZField;
        android.app.AlertDialog.Builder builder=new android.app.AlertDialog.Builder(activity)
                .setTitle(modifier.type.replace('_',' ')+" Modifier")
                .setView(panel).setPositiveButton("Save",null)
                .setNegativeButton("Close",null);
        android.app.AlertDialog dialog=builder.create();

        LinearLayout actions=new LinearLayout(activity);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        android.widget.Button up=new android.widget.Button(activity);up.setText("Move Up");
        android.widget.Button down=new android.widget.Button(activity);down.setText("Move Down");
        android.widget.Button apply=new android.widget.Button(activity);apply.setText("Apply");
        android.widget.Button remove=new android.widget.Button(activity);remove.setText("Remove");
        actions.addView(up,new LinearLayout.LayoutParams(0,LayoutParams.WRAP_CONTENT,1f));
        actions.addView(down,new LinearLayout.LayoutParams(0,LayoutParams.WRAP_CONTENT,1f));
        actions.addView(apply,new LinearLayout.LayoutParams(0,LayoutParams.WRAP_CONTENT,1f));
        actions.addView(remove,new LinearLayout.LayoutParams(0,LayoutParams.WRAP_CONTENT,1f));
        panel.addView(actions);

        up.setOnClickListener(view->{
            try{checkpointMeshEdit();scene.moveModifier(active.id,modifier.id,-1);
                String warning=meshyViewport.validateActiveObjectModifiers();
                if(warning!=null)throw new IllegalStateException(warning);
                meshyViewport.invalidate();dialog.dismiss();showObjectModifierEditor(modifier);}
            catch(Exception error){showObjectOperationError(error);}
        });
        down.setOnClickListener(view->{
            try{checkpointMeshEdit();scene.moveModifier(active.id,modifier.id,1);
                String warning=meshyViewport.validateActiveObjectModifiers();
                if(warning!=null)throw new IllegalStateException(warning);
                meshyViewport.invalidate();dialog.dismiss();showObjectModifierEditor(modifier);}
            catch(Exception error){showObjectOperationError(error);}
        });
        remove.setOnClickListener(view->{
            try{checkpointMeshEdit();scene.removeModifier(active.id,modifier.id);
                meshyViewport.invalidate();dialog.dismiss();showObjectModifiersDialog();}
            catch(Exception error){showObjectOperationError(error);}
        });
        apply.setOnClickListener(view->{
            try{
                ArrayList<Meshy3DObjectScene.Modifier3D> stack=
                        new ArrayList<>(scene.getModifiers(active.id));
                int index=-1;for(int i=0;i<stack.size();i++)if(stack.get(i).id.equals(modifier.id)){index=i;break;}
                if(index<0)throw new IllegalArgumentException("Modifier no longer exists.");
                checkpointMeshEdit();
                meshyViewport.applyActiveObjectModifiersThrough(index);
                status.setText("Modeling | Modifier stack applied through "+modifier.type+" on "+active.name);
                dialog.dismiss();showObjectModifiersDialog();
            }catch(Exception error){showObjectOperationError(error);}
        });

        dialog.setOnShowListener(ignored->dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(view->{
                    try{
                        int axis=selectedAxisSpinner==null?modifier.axis:selectedAxisSpinner.getSelectedItemPosition();
                        int count=selectedCountField==null?modifier.count:Integer.parseInt(selectedCountField.getText().toString().trim());
                        int levels=selectedLevelsField==null?modifier.levels:Integer.parseInt(selectedLevelsField.getText().toString().trim());
                        float value=selectedValueField==null?modifier.value:Float.parseFloat(selectedValueField.getText().toString().trim());
                        float offsetX=selectedOffsetXField==null?modifier.offsetX:Float.parseFloat(selectedOffsetXField.getText().toString().trim());
                        float offsetY=selectedOffsetYField==null?modifier.offsetY:Float.parseFloat(selectedOffsetYField.getText().toString().trim());
                        float offsetZ=selectedOffsetZField==null?modifier.offsetZ:Float.parseFloat(selectedOffsetZField.getText().toString().trim());
                        checkpointMeshEdit();
                        scene.setModifierParameters(active.id,modifier.id,axis,count,levels,value,offsetX,offsetY,offsetZ);
                        scene.setModifierEnabled(active.id,modifier.id,enabled.isChecked());
                        String warning=meshyViewport.validateActiveObjectModifiers();
                        if(warning!=null)throw new IllegalStateException(warning);
                        meshyViewport.invalidate();status.setText("Modeling | Modifier settings updated");
                        dialog.dismiss();showObjectModifiersDialog();
                    }catch(Exception error){showObjectOperationError(error);}
                }));
        dialog.show();
    }

    private void showObjectOperationError(Exception error) {
        String message=error.getMessage()==null?"The operation could not be completed.":error.getMessage();
        new android.app.AlertDialog.Builder(activity).setTitle("Object Mode Operation")
                .setMessage(message).setPositiveButton("OK",null).show();
    }
    private String fmt(float value){return String.format(java.util.Locale.US,"%.3f",value);}

    private void showObjectRenameDialog() {
        Meshy3DObjectScene.Object3D active=meshyViewport.getObjectScene().getActiveObject();if(active==null)return;
        EditText input=new EditText(activity);input.setSingleLine(true);input.setText(active.name);
        new android.app.AlertDialog.Builder(activity).setTitle("Rename Object").setView(input)
                .setPositiveButton("Rename",(d,w)->{
                    try{meshyViewport.renameActiveObject(input.getText().toString());refreshModelingOutliner();status.setText("Modeling | Object renamed");}
                    catch(Exception e){Toast.makeText(activity,"Enter a valid object name.",Toast.LENGTH_SHORT).show();}
                }).setNegativeButton("Cancel",null).show();
    }

    private void showObjectPickerDialog(boolean parentMode) {
        Meshy3DObjectScene scene=meshyViewport.getObjectScene();
        java.util.ArrayList<Meshy3DObjectScene.Object3D> objects=new java.util.ArrayList<>(scene.getObjects());
        java.util.ArrayList<String> names=new java.util.ArrayList<>();
        for(Meshy3DObjectScene.Object3D o:objects)names.add(o.name+(o.id.equals(scene.getActiveObjectId())?"  (Active)":""));
        if(names.isEmpty())return;
        new android.app.AlertDialog.Builder(activity).setTitle(parentMode?"Choose Parent":"Select Object")
                .setItems(names.toArray(new String[0]),(d,w)->{
                    Meshy3DObjectScene.Object3D target=objects.get(w);
                    try{
                        if(parentMode){if(!target.id.equals(scene.getActiveObjectId()))meshyViewport.setActiveObjectParent(target.id);
                            status.setText("Modeling | Parent set to "+target.name);}
                        else {meshyViewport.selectSceneObject(target.id);refreshModelingOutliner();status.setText("Modeling | Active object: "+target.name);}
                    }catch(Exception error){new android.app.AlertDialog.Builder(activity).setTitle("Object Relationship")
                            .setMessage(error.getMessage()).setPositiveButton("OK",null).show();}
                }).setNegativeButton("Cancel",null).show();
    }

    private void showCreateCollectionDialog() {
        EditText input=new EditText(activity);input.setSingleLine(true);input.setHint("Collection name");
        new android.app.AlertDialog.Builder(activity).setTitle("New Collection").setView(input)
                .setPositiveButton("Create",(d,w)->{
                    String name=input.getText().toString().trim();if(name.isEmpty())name="Collection";
                    Meshy3DObjectScene scene=meshyViewport.getObjectScene();
                    Meshy3DObjectScene.Collection3D collection=scene.createCollection(name);
                    if(scene.getSelectedObjects().isEmpty()){
                        Meshy3DObjectScene.Object3D active=scene.getActiveObject();
                        if(active!=null)scene.moveToCollection(active.id,collection.id);
                    }else meshyViewport.moveSelectedToCollection(collection.id);
                    refreshModelingOutliner();status.setText("Modeling | Collection created: "+collection.name);
                }).setNegativeButton("Cancel",null).show();
    }

    private void showOutlinerObjectActions(Meshy3DObjectScene.Object3D object) {
        if(meshyViewport.getObjectScene().getObject(object.id)==null)return;
        String[] actions={"Make Active","Rename","Duplicate","Duplicate Linked","Hide / Show",
                "Object Properties","Parent To","Set Origin to Geometry","Set Origin to 3D Cursor","Delete"};
        new android.app.AlertDialog.Builder(activity).setTitle(object.name)
                .setItems(actions,(d,which)->{
                    try{
                        meshyViewport.selectSceneObject(object.id);refreshModelingOutliner();
                        if(which==0)status.setText("Modeling | Active object: "+object.name);
                        else if(which==1)showObjectRenameDialog();
                        else if(which==2||which==3){
                            checkpointMeshEdit();meshyViewport.duplicateActiveObject(which==3);refreshModelingOutliner();
                            status.setText("Modeling | "+(which==3?"Linked duplicate":"Duplicate")+" created");
                        }else if(which==4){
                            checkpointMeshEdit();meshyViewport.setActiveObjectVisible(!object.visible);
                            refreshModelingOutliner();status.setText("Modeling | "+object.name+" visibility toggled");
                        }else if(which==5)showObjectPropertiesDialog();
                        else if(which==6)showObjectPickerDialog(true);
                        else if(which==7||which==8){
                            checkpointMeshEdit();
                            if(which==7)meshyViewport.setActiveObjectOriginToGeometry();
                            else meshyViewport.setActiveObjectOriginToCursor();
                            status.setText("Modeling | Object origin updated");
                        }else if(which==9){
                            checkpointMeshEdit();meshyViewport.deleteActiveObject();refreshModelingOutliner();
                            status.setText("Modeling | "+object.name+" deleted");
                        }
                    }catch(Exception error){showObjectOperationError(error);}
                }).setNegativeButton("Cancel",null).show();
    }

    private void showCollectionManagerPicker() {
        Meshy3DObjectScene scene=meshyViewport.getObjectScene();
        java.util.ArrayList<Meshy3DObjectScene.Collection3D> collections=new java.util.ArrayList<>(scene.getCollections());
        if(collections.isEmpty())return;
        String[] names=new String[collections.size()];
        for(int i=0;i<names.length;i++)names[i]=collections.get(i).name;
        new android.app.AlertDialog.Builder(activity).setTitle("Manage Collections")
                .setItems(names,(d,index)->showCollectionActions(collections.get(index)))
                .setNegativeButton("Cancel",null).show();
    }

    private void showCollectionActions(Meshy3DObjectScene.Collection3D collection) {
        String[] actions={"Select Objects","Create Child Collection","Move Under Collection",
                "Toggle Viewport Visibility","Toggle Render Visibility","Rename","Delete"};
        new android.app.AlertDialog.Builder(activity).setTitle(collection.name)
                .setItems(actions,(d,which)->{
                    Meshy3DObjectScene scene=meshyViewport.getObjectScene();
                    try{
                        if(which==0){
                            meshyViewport.selectCollectionObjects(collection.id,false);refreshModelingOutliner();
                            status.setText("Modeling | Objects in "+collection.name+" selected");
                        }else if(which==1){
                            EditText input=new EditText(activity);input.setSingleLine(true);input.setHint("Child collection name");
                            new android.app.AlertDialog.Builder(activity).setTitle("Create Child Collection").setView(input)
                                    .setPositiveButton("Create",(dialog,button)->{
                                        String name=input.getText().toString().trim();
                                        scene.createCollection(name.isEmpty()?"Collection":name,collection.id);
                                        refreshModelingOutliner();status.setText("Modeling | Child collection created");
                                    }).setNegativeButton("Cancel",null).show();
                        }else if(which==2)showMoveCollectionParentDialog(collection);
                        else if(which==3){
                            boolean visible=!collection.visible;
                            meshyViewport.setCollectionViewportVisibility(collection.id,visible);refreshModelingOutliner();
                            status.setText("Modeling | "+collection.name+" viewport visibility: "+visible);
                        }else if(which==4){
                            boolean visible=!collection.renderVisible;
                            meshyViewport.setCollectionRenderVisibility(collection.id,visible);
                            status.setText("Modeling | "+collection.name+" render visibility: "+visible);
                        }else if(which==5){
                            EditText input=new EditText(activity);input.setSingleLine(true);input.setText(collection.name);
                            new android.app.AlertDialog.Builder(activity).setTitle("Rename Collection").setView(input)
                                    .setPositiveButton("Save",(dialog,button)->{
                                        try{scene.renameCollection(collection.id,input.getText().toString());
                                            refreshModelingOutliner();status.setText("Modeling | Collection renamed");}
                                        catch(Exception error){showObjectOperationError(error);}
                                    }).setNegativeButton("Cancel",null).show();
                        }else{
                            new android.app.AlertDialog.Builder(activity).setTitle("Delete Collection")
                                    .setMessage("Remove "+collection.name+"? Child collections will move to its parent.")
                                    .setPositiveButton("Delete",(dialog,button)->{
                                        try{meshyViewport.deleteSceneCollection(collection.id);refreshModelingOutliner();
                                            status.setText("Modeling | Collection deleted");}
                                        catch(Exception error){showObjectOperationError(error);}
                                    }).setNegativeButton("Cancel",null).show();
                        }
                    }catch(Exception error){showObjectOperationError(error);}
                }).setNegativeButton("Cancel",null).show();
    }

    private void showMoveCollectionParentDialog(Meshy3DObjectScene.Collection3D collection) {
        java.util.ArrayList<Meshy3DObjectScene.Collection3D> choices=
                new java.util.ArrayList<>(meshyViewport.getObjectScene().getCollections());
        choices.removeIf(item->item.id.equals(collection.id));
        String[] names=new String[choices.size()+1];names[0]="Scene Root (No Parent)";
        for(int i=0;i<choices.size();i++)names[i]=choices.get(i).name;
        new android.app.AlertDialog.Builder(activity).setTitle("Move Collection Under")
                .setItems(names,(d,which)->{
                    try{
                        meshyViewport.getObjectScene().setCollectionParent(collection.id,which==0?null:choices.get(which-1).id);
                        refreshModelingOutliner();status.setText("Modeling | Collection hierarchy updated");
                    }catch(Exception error){showObjectOperationError(error);}
                }).setNegativeButton("Cancel",null).show();
    }

    private void showCollectionPickerDialog(boolean link) {
        Meshy3DObjectScene scene=meshyViewport.getObjectScene();
        java.util.ArrayList<Meshy3DObjectScene.Collection3D> collections=new java.util.ArrayList<>(scene.getCollections());
        String[] names=new String[collections.size()];for(int i=0;i<names.length;i++)names[i]=collections.get(i).name;
        if(names.length==0)return;
        new android.app.AlertDialog.Builder(activity).setTitle((link?"Link":"Move")+" Selected Objects To Collection")
                .setItems(names,(d,w)->{
                    checkpointMeshEdit();Meshy3DObjectScene.Collection3D target=collections.get(w);
                    if(link)meshyViewport.linkSelectedToCollection(target.id);else meshyViewport.moveSelectedToCollection(target.id);
                    refreshModelingOutliner();status.setText("Modeling | Selected objects "+(link?"linked to ":"moved to ")+target.name);
                }).setNegativeButton("Cancel",null).show();
    }

    private interface AmountAction { void apply(float amount); }
    private void showAmountDialog(String title, float min, float max, AmountAction action) {
        showAmountDialog(title,min,max,min+(max-min)*.25f,action);
    }

    private void showAmountDialog(String title, float min, float max, float initial, AmountAction action) {
        SeekBar bar = new SeekBar(activity);
        bar.setMax(1000);
        float safeInitial=Math.max(min,Math.min(max,initial));
        bar.setProgress(Math.round((safeInitial-min)/(max-min)*1000f));
        TextView value = new TextView(activity);
        value.setText(String.format(java.util.Locale.US, "%.3f", safeInitial));
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar b, int p, boolean from) {
                value.setText(String.format(java.util.Locale.US, "%.3f", min+(max-min)*p/1000f));
            }
            public void onStartTrackingTouch(SeekBar b) {}
            public void onStopTrackingTouch(SeekBar b) {}
        });
        LinearLayout panel = new LinearLayout(activity); panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(16), dp(8), dp(16), dp(8)); panel.addView(value); panel.addView(bar);
        new android.app.AlertDialog.Builder(activity).setTitle(title).setView(panel)
                .setPositiveButton("Apply", (d, w) -> action.apply(min+(max-min)*bar.getProgress()/1000f))
                .setNegativeButton("Cancel", null).show();
    }

    private void showPaintColorDialog() {
        String[] names={"Clay Grey","Warm Orange","Blue","Green","Red","White"};
        int[] colors={0xFF9DAAB7,0xFFE79A45,0xFF5D9BEA,0xFF63B887,0xFFD85B5B,0xFFF2F2F2};
        boolean texturePaint=activeWorkspaceIndex==4;
        new android.app.AlertDialog.Builder(activity).setTitle(texturePaint?"Texture Brush Color":"Mesh Material Color")
                .setItems(names,(d,w)->{
                    checkpointMeshEdit();
                    if(texturePaint)meshyViewport.setPaintBrush(colors[w],18f,.9f);
                    else meshyViewport.setMeshTint(colors[w]);
                    status.setText(WORKSPACE_NAMES[activeWorkspaceIndex]+" | "+(texturePaint?"Pixel paint brush color updated":"Mesh material color updated"));
                }).show();
    }

    private void showRenderOutputSettings() {
        LinearLayout panel=new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(18),dp(10),dp(18),dp(8));
        TextView engine=new TextView(activity);
        engine.setTextColor(Color.WHITE);
        engine.setText("Engine: Meshy3D CPU Rasterizer (PNG output)");
        panel.addView(engine);
        TextView size=new TextView(activity);
        size.setTextColor(Color.WHITE);
        size.setPadding(0,dp(12),0,dp(8));
        panel.addView(size);
        SeekBar width=new SeekBar(activity);width.setMax(1984);
        width.setProgress(Math.max(0,Math.min(1984,renderOutputWidth-64)));
        SeekBar height=new SeekBar(activity);height.setMax(1984);
        height.setProgress(Math.max(0,Math.min(1984,renderOutputHeight-64)));
        SeekBar percent=new SeekBar(activity);percent.setMax(90);
        percent.setProgress(Math.max(0,Math.min(90,renderResolutionPercent-10)));
        TextView widthLabel=new TextView(activity),heightLabel=new TextView(activity),percentLabel=new TextView(activity);
        for(TextView label:new TextView[]{widthLabel,heightLabel,percentLabel})label.setTextColor(Color.WHITE);
        Runnable refresh=()->{
            widthLabel.setText("Width: "+(64+width.getProgress())+" px");
            heightLabel.setText("Height: "+(64+height.getProgress())+" px");
            percentLabel.setText("Resolution: "+(10+percent.getProgress())+"%");
            size.setText("Effective output: "+((64+width.getProgress())*(10+percent.getProgress())/100)
                    +" × "+((64+height.getProgress())*(10+percent.getProgress())/100)+" px");
        };
        width.setOnSeekBarChangeListener(simpleSeekListener(refresh));
        height.setOnSeekBarChangeListener(simpleSeekListener(refresh));
        percent.setOnSeekBarChangeListener(simpleSeekListener(refresh));
        panel.addView(widthLabel);panel.addView(width);
        panel.addView(heightLabel);panel.addView(height);
        panel.addView(percentLabel);panel.addView(percent);
        refresh.run();
        new android.app.AlertDialog.Builder(activity).setTitle("Render Output")
                .setView(panel).setPositiveButton("Apply",(d,w)->{
                    renderOutputWidth=64+width.getProgress();
                    renderOutputHeight=64+height.getProgress();
                    renderResolutionPercent=10+percent.getProgress();
                    status.setText("Rendering | Output "+renderOutputWidth+" × "+renderOutputHeight
                            +" at "+renderResolutionPercent+"%");
                }).setNeutralButton("Render Now",(d,w)->{
                    renderOutputWidth=64+width.getProgress();
                    renderOutputHeight=64+height.getProgress();
                    renderResolutionPercent=10+percent.getProgress();
                    saveViewportRender();
                }).setNegativeButton("Cancel",null).show();
    }

    private SeekBar.OnSeekBarChangeListener simpleSeekListener(Runnable refresh) {
        return new SeekBar.OnSeekBarChangeListener(){
            public void onProgressChanged(SeekBar seek,int progress,boolean fromUser){refresh.run();}
            public void onStartTrackingTouch(SeekBar seek){}
            public void onStopTrackingTouch(SeekBar seek){}
        };
    }

    private void saveViewportRender() {
        try {
            int width=Math.max(64,Math.min(2048,renderOutputWidth*renderResolutionPercent/100));
            int height=Math.max(64,Math.min(2048,renderOutputHeight*renderResolutionPercent/100));
            long pixelCount=(long)width*height;
            if(pixelCount>4_194_304L)throw new IllegalArgumentException("Render exceeds the 4-megapixel safety limit on Android");
            android.graphics.Bitmap raw=meshyViewport.renderFinalImage(width,height);
            int[] sourcePixels=new int[width*height];raw.getPixels(sourcePixels,0,width,0,0,width,height);raw.recycle();
            int[] finalPixels=meshyViewport.getDocumentCore().evaluateCompositor(sourcePixels,width,height);
            android.graphics.Bitmap bitmap=android.graphics.Bitmap.createBitmap(finalPixels,width,height,android.graphics.Bitmap.Config.ARGB_8888);
            File dir=new File(activity.getFilesDir(),"Meshy3D/Renders");
            if(!dir.exists()&&!dir.mkdirs())throw new java.io.IOException("Could not create render folder");
            File output=new File(dir,"Meshy3D-Render-"+System.currentTimeMillis()+".png");
            try(java.io.FileOutputStream stream=new java.io.FileOutputStream(output)){
                if(!bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,stream))
                    throw new java.io.IOException("PNG export failed");
                stream.flush();
            }
            bitmap.recycle();status.setText("Rendering | CPU scene render saved: "+output.getAbsolutePath());
            Toast.makeText(activity,"Final scene render saved",Toast.LENGTH_SHORT).show();
        }catch(Exception error){status.setText("Rendering | Render failed: "+error.getMessage());}
    }

    private interface FloatSetter { void set(float value); }
    private void showSculptSlider(String title,float min,float max,float current,FloatSetter setter) {
        LinearLayout layout=new LinearLayout(activity); layout.setPadding(dp(18),dp(8),dp(18),dp(8));
        layout.setOrientation(LinearLayout.VERTICAL);
        TextView valueLabel=new TextView(activity);
        valueLabel.setText(String.format(java.util.Locale.US,"%.2f",current));
        SeekBar seek=new SeekBar(activity); seek.setMax(1000);
        seek.setProgress((int)(((current-min)/(max-min))*1000));
        layout.addView(valueLabel); layout.addView(seek);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onProgressChanged(SeekBar b,int p,boolean from){float v=min+(max-min)*p/1000f;valueLabel.setText(String.format(java.util.Locale.US,"%.2f",v));}
            public void onStartTrackingTouch(SeekBar b){}
            public void onStopTrackingTouch(SeekBar b){}
        });
        new android.app.AlertDialog.Builder(activity).setTitle(title).setView(layout)
                .setPositiveButton("Apply",(d,w)->{setter.set(min+(max-min)*seek.getProgress()/1000f);updateSculptViewport();})
                .setNegativeButton("Cancel",null).show();
    }

    private LinearLayout createDock(String title, int width, int gravity) {
        LinearLayout dock = new LinearLayout(activity);
        dock.setOrientation(LinearLayout.VERTICAL);
        dock.setBackgroundColor(0xF0181D24);
        dock.addView(dockHeading(title), new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                dp(width), LayoutParams.MATCH_PARENT, gravity);
        params.topMargin = dp(36);
        // The Modeling docks stop above the fixed bottom tool shelf.
        params.bottomMargin = activeWorkspaceIndex == 1 ? dp(56) : dp(8);
        params.leftMargin = dp(6);
        params.rightMargin = dp(6);
        workspaceLayoutLayer.addView(dock, params);
        return dock;
    }

    private void addDockItems(LinearLayout dock, String heading, String... items) {
        dock.addView(dockHeading(heading));
        ScrollView scroll = new ScrollView(activity);
        scroll.setFillViewport(false);
        scroll.setVerticalScrollBarEnabled(true);
        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(content, new ScrollView.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        if ("Modeling Tools".equals(heading) && meshyViewport != null) {
            Meshy3DObjectScene scene=meshyViewport.getObjectScene();
            content.addView(dockHeading("COLLECTIONS"));
            for(Meshy3DObjectScene.Collection3D collection:scene.getCollections()) {
                int depth=scene.getCollectionDepth(collection.id);
                StringBuilder indent=new StringBuilder();for(int level=0;level<Math.min(8,depth);level++)indent.append("  ");
                TextView collectionRow=dockItem(indent+(collection.visible?"▾ ":"▸ ")+collection.name,false);
                collectionRow.setOnClickListener(v->{meshyViewport.selectCollectionObjects(collection.id,false);
                    refreshModelingOutliner();status.setText("Modeling | Collection: "+collection.name);});
                collectionRow.setOnLongClickListener(v->{showCollectionActions(collection);return true;});
                content.addView(collectionRow);
            }
            content.addView(dockHeading("SCENE OUTLINER"));
            outlinerObjectRows.clear();outlinerExpandRows.clear();
            outlinerParentIds.clear();outlinerObjectNames.clear();

            EditText searchField=new EditText(activity);
            searchField.setSingleLine(true);
            searchField.setTextColor(Color.WHITE);
            searchField.setHintTextColor(0xFF9AA6B4);
            searchField.setHint("Search scene objects…");
            searchField.setTextSize(12f);
            searchField.setPadding(dp(8),dp(4),dp(8),dp(4));
            searchField.setBackgroundColor(0xFF27313D);
            content.addView(searchField,new LinearLayout.LayoutParams(
                    LayoutParams.MATCH_PARENT,dp(38)));
            TextView rootDropTarget=dockItem("↥  Drop object here to clear its parent",false);
            rootDropTarget.setGravity(Gravity.CENTER_VERTICAL);
            content.addView(rootDropTarget,new LinearLayout.LayoutParams(
                    LayoutParams.MATCH_PARENT,dp(32)));
            attachOutlinerDropTarget(rootDropTarget,null);
            searchField.addTextChangedListener(new android.text.TextWatcher(){
                public void beforeTextChanged(CharSequence text,int start,int count,int after){}
                public void onTextChanged(CharSequence text,int start,int before,int count){
                    updateOutlinerObjectRowVisibility(text==null?"":text.toString());
                }
                public void afterTextChanged(android.text.Editable editable){}
            });

            for(Meshy3DObjectScene.Object3D object:scene.getObjects()) {
                int depth=scene.getObjectHierarchyDepth(object.id);
                boolean hasChildren=false;
                for(Meshy3DObjectScene.Object3D candidate:scene.getObjects())
                    if(object.id.equals(candidate.parentId)){hasChildren=true;break;}

                String typeIcon="MESH".equals(object.type)?"◈ ":"CAMERA".equals(object.type)?"▣ ":"LIGHT".equals(object.type)?"☼ ":"◇ ";
                StringBuilder indentation=new StringBuilder();
                for(int level=0;level<Math.min(8,depth);level++)indentation.append("  ");
                String flags=(object.visible?"":"◌ ")+(object.selectable?"":"🔒 ")+(object.renderVisible?"":"R ");
                LinearLayout objectLine=new LinearLayout(activity);
                objectLine.setGravity(Gravity.CENTER_VERTICAL);
                TextView expandRow=dockItem(hasChildren?(collapsedOutlinerObjectIds.contains(object.id)?"▸":"▾"):"·",false);
                expandRow.setGravity(Gravity.CENTER);
                objectLine.addView(expandRow,new LinearLayout.LayoutParams(dp(22),LayoutParams.WRAP_CONTENT));
                TextView dragHandle=dockItem("⠿",false);
                dragHandle.setGravity(Gravity.CENTER);
                objectLine.addView(dragHandle,new LinearLayout.LayoutParams(dp(22),LayoutParams.WRAP_CONTENT));
                TextView objectRow=dockItem(indentation+typeIcon+object.name+"  "+flags,object.active);
                objectLine.addView(objectRow,new LinearLayout.LayoutParams(0,LayoutParams.WRAP_CONTENT,1f));
                dragHandle.setOnLongClickListener(v->{
                    android.content.ClipData dragData=android.content.ClipData.newPlainText("Meshy3DObject",object.id);
                    return v.startDrag(dragData,new View.DragShadowBuilder(objectRow),object.id,0);
                });
                attachOutlinerDropTarget(objectRow,object.id);
                outlinerObjectRows.put(object.id,objectRow);
                outlinerExpandRows.put(object.id,expandRow);
                outlinerParentIds.put(object.id,object.parentId);
                outlinerObjectNames.put(object.id,object.name+" "+object.type);
                if(hasChildren)expandRow.setOnClickListener(v->{
                    if(!collapsedOutlinerObjectIds.add(object.id))collapsedOutlinerObjectIds.remove(object.id);
                    expandRow.setText(collapsedOutlinerObjectIds.contains(object.id)?"▸":"▾");
                    updateOutlinerObjectRowVisibility(searchField.getText().toString());
                });
                else expandRow.setEnabled(false);
                objectRow.setOnClickListener(v->{
                    meshyViewport.selectSceneObject(object.id);refreshModelingOutliner();
                    status.setText("Modeling | Active object: "+object.name);
                });
                objectRow.setOnLongClickListener(v->{showOutlinerObjectActions(object);return true;});
                content.addView(objectLine);
            }
            updateOutlinerObjectRowVisibility("");
            content.addView(dockHeading("OBJECT OPERATIONS"));
        }
        for (String item : items) content.addView(dockItem(item, false));
        dock.addView(scroll, new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, 0, 1f));
    }

    private void attachOutlinerDropTarget(View targetView,String targetObjectId) {
        targetView.setOnDragListener((view,event)->{
            switch(event.getAction()){
                case android.view.DragEvent.ACTION_DRAG_STARTED:
                    return event.getClipDescription()!=null
                            &&event.getClipDescription().hasMimeType("text/plain");
                case android.view.DragEvent.ACTION_DRAG_ENTERED:
                    view.setBackgroundColor(0x665B91C7);
                    return true;
                case android.view.DragEvent.ACTION_DRAG_EXITED:
                    view.setBackgroundColor(Color.TRANSPARENT);
                    return true;
                case android.view.DragEvent.ACTION_DROP:
                    view.setBackgroundColor(Color.TRANSPARENT);
                    Object local=event.getLocalState();
                    if(!(local instanceof String))return false;
                    String sourceObjectId=(String)local;
                    try{
                        checkpointMeshEdit();
                        meshyViewport.getObjectScene().setParent(sourceObjectId,targetObjectId);
                        meshyViewport.invalidate();
                        refreshModelingOutliner();
                        Meshy3DObjectScene.Object3D source=meshyViewport.getObjectScene().getObject(sourceObjectId);
                        Meshy3DObjectScene.Object3D parent=targetObjectId==null?null:
                                meshyViewport.getObjectScene().getObject(targetObjectId);
                        status.setText("Modeling | "+(source==null?"Object":source.name)
                                +(parent==null?" moved to scene root":" parented to "+parent.name));
                        return true;
                    }catch(Exception error){
                        showObjectOperationError(error);
                        return false;
                    }
                case android.view.DragEvent.ACTION_DRAG_ENDED:
                    view.setBackgroundColor(Color.TRANSPARENT);
                    return true;
                default:return true;
            }
        });
    }

    private void updateOutlinerObjectRowVisibility(String rawFilter) {
        String filter=rawFilter==null?"":rawFilter.trim().toLowerCase(java.util.Locale.ROOT);
        java.util.HashSet<String> matches=new java.util.HashSet<>();
        for(java.util.Map.Entry<String,String> entry:outlinerObjectNames.entrySet())
            if(filter.isEmpty()||entry.getValue().toLowerCase(java.util.Locale.ROOT).contains(filter))
                matches.add(entry.getKey());

        java.util.HashSet<String> visible=new java.util.HashSet<>(matches);
        if(!filter.isEmpty()){
            for(String match:new java.util.ArrayList<>(matches)){
                String parent=outlinerParentIds.get(match);int guard=0;
                while(parent!=null&&guard++<outlinerParentIds.size()){
                    visible.add(parent);parent=outlinerParentIds.get(parent);
                }
            }
        }

        for(String objectId:outlinerObjectRows.keySet()){
            boolean show=filter.isEmpty()||visible.contains(objectId);
            if(show&&filter.isEmpty()){
                String parent=outlinerParentIds.get(objectId);int guard=0;
                while(parent!=null&&guard++<outlinerParentIds.size()){
                    if(collapsedOutlinerObjectIds.contains(parent)){show=false;break;}
                    parent=outlinerParentIds.get(parent);
                }
            }
            TextView row=outlinerObjectRows.get(objectId);
            TextView expand=outlinerExpandRows.get(objectId);
            if(row!=null)row.setVisibility(show?View.VISIBLE:View.GONE);
            if(expand!=null)expand.setVisibility(show?View.VISIBLE:View.GONE);
        }
    }

    private void renderWorkspaceLayout(int index) {
        if (workspaceLayoutLayer == null) return;
        boolean preserveModelingDockState=index==1&&activeWorkspaceIndex==1&&workspacePanelsOpen;
        workspaceLayoutLayer.removeAllViews();
        workspaceLeftDock = null;
        workspaceRightDock = null;
        workspaceBottomDock = null;
        leftDockTab = null;
        rightDockTab = null;
        viewportFocusMode = false;
        if (meshyViewport != null) {
            boolean sculptWorkspace = index == 2;
            boolean uvWorkspace = index == 3;
            boolean editWorkspace = (index == 1 && "Edit Mode".equals(activeEditorMode)) || uvWorkspace;
            meshyViewport.setMeshEditMode(editWorkspace);
            // Layout and Modeling must both allow selecting the starter cube and scene objects.
            meshyViewport.setObjectSelectionEnabled(!sculptWorkspace && !editWorkspace);
            meshyViewport.setSculptSettings(sculptWorkspace, selectedSculptBrush,
                    sculptRadiusUi, sculptStrengthUi, sculptFalloffUi,
                    sculptSymmetryUi, sculptSubtractUi, sculptPressureUi);
            meshyViewport.setSculptStrokeMethod(sculptStrokeMethodUi);
        }
        workspaceToolbar = new LinearLayout(activity);
        workspaceToolbar.setGravity(Gravity.CENTER_VERTICAL);
        workspaceToolbar.setBackgroundColor(0xE02B333D);
        workspaceToolbar.setPadding(dp(6), 0, dp(6), 0);
        workspaceToolbar.setVisibility(View.GONE); // Shared viewport rail and bottom bar replace the extra tool row.
        HorizontalScrollView toolbarScroll = new HorizontalScrollView(activity);
        toolbarScroll.setHorizontalScrollBarEnabled(false);
        toolbarScroll.addView(workspaceToolbar, new HorizontalScrollView.LayoutParams(
                LayoutParams.WRAP_CONTENT, dp(32)));
        FrameLayout.LayoutParams toolbarParams = new FrameLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, dp(32), Gravity.TOP);
        workspaceLayoutLayer.addView(toolbarScroll, toolbarParams);

        String[] tools;
        String leftTitle;
        String rightTitle;
        String bottomTitle = null;
        String[] leftItems;
        String[] rightItems;
        String[] bottomItems = null;
        switch (index) {
            case 0:
                tools = new String[]{"Select Box", "3D Cursor", "Move", "Rotate", "Scale", "Transform",
                        "Annotate", "Measure", "Add", "Camera View"};
                leftTitle = "Scene Outliner";
                leftItems = new String[]{"Scene Collection", "Collection", "  Camera", "  Cube", "  Light",
                        "Add Collection", "Filter Objects", "Hide / Show Objects"};
                rightTitle = "Properties Editor";
                rightItems = new String[]{"Tool", "Render", "Output", "View Layer", "Scene", "World",
                        "Collection", "Object", "Modifiers", "Particles", "Physics", "Constraints",
                        "Object Data", "Material"};
                bottomTitle = "Timeline";
                bottomItems = new String[]{"Current Frame: 1", "Playback Controls", "Start Frame: 1",
                        "End Frame: 250", "Keyframes"};
                break;
            case 1:
                tools = new String[]{"Select", "Select Box", "Move", "Rotate", "Scale", "Add Cube", "Duplicate",
                        "Duplicate Linked", "Delete Object", "Rename Object", "Apply Rotation & Scale",
                        "Vertex", "Edge", "Face", "Extrude", "Inset", "Bevel", "Loop Cut", "Plane Cut",
                        "Bisect", "Bridge", "Fill", "Subdivide", "Merge", "Dissolve", "Separate"};
                leftTitle = "Modeling Tools";
                leftItems = new String[]{
                        "OBJECT MODE", "Select", "Select Box", "Add Menu", "Add → Mesh", "Add Camera", "Add Light", "Add Empty", "Add Cube",
                        "Duplicate", "Duplicate Linked", "Delete Object", "Delete Selected Objects", "Delete by Type", "Join Selected Meshes",
                        "Select All Objects", "Deselect Objects", "Invert Selection", "Select Object", "Select By Type",
                        "Select Hierarchy", "Select by Name", "Select Active Camera", "Select Linked Data", "Rename Object", "Hide / Show Object",
                        "Hide Selected", "Hide Unselected", "Show All Objects", "Invert Hidden", "Toggle Local View",
                        "Lock Selection", "Unlock Selection", "Disable in Render", "Enable in Render",
                        "Apply Location", "Apply Rotation & Scale", "Apply All Transforms", "Clear Location", "Clear Rotation", "Clear Scale",
                        "Set Origin to Geometry", "Set Origin to 3D Cursor", "Align Objects", "Mirror Objects", "Parent To", "Parent Selected to Active", "Clear Parent",
                        "Add Collection", "Manage Collections", "Move To Collection", "Link To Collection", "Make Single User", "Link Mesh Data",
                        "Set Active Camera", "View Camera", "Camera Settings", "Light Settings", "Object Statistics",
                        "EDIT MODE", "Vertex Select", "Edge Select", "Face Select",
                        "Select All", "Select Linked", "Select Loops", "Select Similar", "TRANSFORM",
                        "Move", "Rotate", "Scale", "Mirror", "Shrink / Fatten", "Shear", "Bend",
                        "To Sphere", "Edge Slide", "Vertex Slide", "MESH EDIT", "Extrude Region",
                        "Extrude Along Normals", "Extrude Individual Faces", "Inset Faces", "Bevel Edges",
                        "Loop Cut", "Plane Cut", "Bisect", "Poly Build", "Bridge Edge Loops", "Spin",
                        "Screw", "Fill", "Grid Fill", "Bridge", "TOPOLOGY", "Subdivide", "Un-Subdivide",
                        "Merge by Distance", "Dissolve Edges", "Dissolve Faces", "Limited Dissolve",
                        "Delete Vertices", "Delete Edges", "Delete Faces", "Separate Selection", "Split",
                        "Rip", "Decimate", "MESH CLEANUP", "Recalculate Normals", "Flip Normals",
                        "Degenerate Dissolve", "Make Planar", "Smooth Vertices", "Randomize",
                        "SYMMETRY & MODIFIERS", "Symmetrize", "Bisect", "Mirror Modifier",
                        "Subdivision Surface", "Solidify", "Bevel Modifier", "Wireframe Modifier", "Recalculate Normals",
                        "Remesh", "Weld", "Triangulate", "Toggle Topology Overlay"
                };
                rightTitle = "Tool Properties";
                rightItems = new String[]{"Object Properties", "Object Transform", "Numeric Transform", "Dimensions",
                        "Set 3D Cursor", "Snap to Grid", "Snap to 3D Cursor", "Snap to Active Geometry",
                        "Frame Selected", "Frame All",
                        "Parent", "Constraints", "Modifiers", "Custom Properties", "Viewport Display", "Collections", "Manage Collections",
                        "Visibility", "Selectable", "Render Visibility", "Active Camera", "Camera Settings", "Light Settings",
                        "Active Tool", "Selection Mode", "Operation Amount", "Offset", "Segments", "Profile",
                        "Loop Cut Count", "Merge Threshold", "Mesh Statistics", "Vertices", "Edges", "Faces",
                        "Normals", "Transform Orientation", "Pivot Point"};
                bottomTitle = "Mesh Information";
                bottomItems = new String[]{"Object Mode / Edit Mode", "Selected Mesh", "Topology Overview",
                        "Vertex / Edge / Face Counts"};
                break;
            case 2:
                tools = new String[]{"Draw", "Smooth", "Grab", "Inflate", "Pinch", "Flatten", "Crease", "Mask"};
                leftTitle = "Sculpt Brushes";
                leftItems = BRUSHES;
                rightTitle = "Brush Settings";
                rightItems = new String[]{"Radius", "Strength", "Falloff", "Pressure", "Symmetry", "Add / Subtract"};
                bottomTitle = "Sculpt Options";
                bottomItems = new String[]{"Brush Preview", "Stroke Method", "Dyntopo / Remesh options"};
                break;
            case 3:
                tools = new String[]{"Select", "Select Linked", "Select Pinned", "Move UV", "Rotate UV", "Scale UV", "Mirror X", "Mirror Y", "Pin", "Unpin", "Mark Seam", "Clear Seam", "Unwrap", "Smart UV Project", "Lightmap Pack", "Follow Active Quads", "Minimize Stretch", "Average Islands Scale", "Pack Islands", "Align X", "Align Y", "Straighten", "Stitch", "Weld", "Export UV Layout"};
                leftTitle = "UV Tools";
                leftItems = new String[]{"SELECT", "Select", "Select Linked", "Select Pinned", "Select Overlapping", "Select Boundary", "TRANSFORM", "Move", "Rotate", "Scale", "Mirror X", "Mirror Y", "Align X", "Align Y", "Straighten", "Straighten X", "Straighten Y", "PIN & SEAMS", "Pin", "Unpin", "Mark Seam", "Clear Seam", "Mark Sharp", "UNWRAP", "Unwrap", "Smart UV Project", "Lightmap Pack", "Follow Active Quads", "Minimize Stretch", "UV TOOLS", "Average Islands Scale", "Pack Islands", "Stitch", "Weld", "Split", "Export UV Layout"};
                rightTitle = "UV Properties";
                rightItems = new String[]{"UV Maps", "Active UV Map", "New UV Map", "Remove UV Map", "Island Transform", "Pivot Point", "Proportional Editing", "Snap to Pixels", "Snap to Vertex", "Unwrap Method", "Angle Limit", "Margin", "Correct Aspect", "Stretch Display", "Overlap Display", "Image Settings", "Image Selection", "UDIM Tiles"};
                bottomTitle = "UV Editor";
                bottomItems = new String[]{"UV Canvas", "Image / UV View", "UV Islands", "Vertex / Edge / Face Selection", "UV Sync Selection", "UV Statistics", "Image Menu", "View Menu", "UV Menu"};
                break;
            case 4:
                tools = new String[]{"Draw", "Soften", "Smear", "Clone", "Fill", "Mask", "Erase Alpha", "Line", "Polyline", "Airbrush", "Gradient", "Sample Color", "Set Clone"};
                leftTitle = "Paint Tools";
                leftItems = new String[]{"DRAW", "Draw", "Soften", "Smear", "Clone", "Fill", "Mask", "Erase Alpha", "Line", "Polyline", "Airbrush", "Gradient", "Sample Color", "Set Clone", "BRUSH CONTROLS", "Brush Asset", "Brush Texture", "Stroke Method", "Stroke Spacing", "Jitter", "Stabilize Stroke", "Symmetry X", "Symmetry Y", "Symmetry Z"};
                rightTitle = "Texture Properties";
                rightItems = new String[]{"Active Texture", "Texture Slots", "New Texture", "Open Image", "Save Image", "Image Format", "Image Size", "Color Space", "Alpha", "Brush Color", "Secondary Color", "Brush Radius", "Strength", "Blend Mode", "Falloff", "Texture Mask", "Stencil", "UV Map", "Canvas"};
                bottomTitle = "Image Editor";
                bottomItems = new String[]{"Texture Image Canvas", "Image Slots", "Paint Layers", "Image Channels", "Image Metadata", "Image Save / Reload", "UDIM Tiles", "Texture Paint Slots"};
                break;
            case 5:
                tools = new String[]{"New Material", "Material Slots", "Preview", "Add Node", "Connect", "Disconnect", "Reroute", "Frame", "Group", "Ungroup", "Search", "Node Sidebar"};
                leftTitle = "Material Browser";
                leftItems = new String[]{"Scene Materials", "Material Slots", "New Material", "Duplicate Material", "Assign Material", "Material Preview", "Surface", "Volume", "Displacement", "Principled BSDF", "World Materials"};
                rightTitle = "Shader Properties";
                rightItems = new String[]{"Material", "Base Color", "Metallic", "Roughness", "IOR", "Alpha", "Emission Color", "Emission Strength", "Normal", "Coat", "Sheen", "Transmission", "Subsurface", "Anisotropic", "Thin Film", "Volume", "Displacement", "Viewport Display", "Material Preview Settings"};
                bottomTitle = "Shader Editor";
                bottomItems = new String[]{"Material Node Graph", "Shader Nodes", "Input Nodes", "Output Nodes", "Texture Nodes", "Color Nodes", "Vector Nodes", "Converter Nodes", "Script Nodes", "Group Nodes", "Layout Nodes", "Node Search", "Node Sidebar", "Node Properties"};
                break;
            case 6:
                tools = new String[]{"Select", "Move", "Rotate", "Scale", "Insert Keyframe", "Delete Keyframe", "Duplicate Keyframes", "Snap", "Mirror", "Bake Action", "Play", "Pause", "Reverse", "Jump to First", "Jump to Last"};
                leftTitle = "Animation Channels";
                leftItems = new String[]{"Scene", "Object Transforms", "Location", "Rotation", "Scale", "Delta Transforms", "Object Properties", "Visibility", "Custom Properties", "Shape Keys", "Armature", "Pose Bones", "Constraints", "Modifiers", "Materials", "Cameras", "Lights", "World", "Action Editor", "NLA Tracks"};
                rightTitle = "Keyframe Properties";
                rightItems = new String[]{"Active Action", "Action Slots", "Frame", "Interpolation", "Bezier", "Linear", "Constant", "Handle Type", "Automatic Handles", "Aligned Handles", "Free Handles", "Easing", "Keyframe Type", "Extrapolation", "Playback Range", "Frame Rate", "Motion Paths"};
                bottomTitle = "Dope Sheet / Timeline";
                bottomItems = new String[]{"Dope Sheet", "Action Editor", "Timeline", "Graph Editor", "NLA Editor", "Keyframe Tracks", "Markers", "Playback Controls", "Start Frame", "End Frame", "Current Frame", "Preview Range", "Keying Sets"};
                break;
            case 7:
                tools = new String[]{"Render Image", "Render Animation", "Render Viewport", "Cancel Render", "Pause Render", "Resume Render", "Camera View", "Render Region", "Clear Render Region", "Settings"};
                leftTitle = "Render Settings";
                leftItems = new String[]{"Render Engine", "Sampling", "Viewport Samples", "Render Samples", "Denoising", "Light Paths", "Shadows", "Ray Tracing", "Ambient Occlusion", "Motion Blur", "Film", "Simplify", "Freestyle", "Performance", "Bake", "Color Management", "View Transform", "Look", "Exposure", "Gamma"};
                rightTitle = "Output Properties";
                rightItems = new String[]{"Resolution X", "Resolution Y", "Percentage", "Aspect Ratio", "Frame Rate", "Frame Start", "Frame End", "Frame Step", "File Format", "Color Mode", "Color Depth", "Compression", "Output Path", "File Extensions", "Color Management", "Metadata", "Multiview"};
                bottomTitle = "Render Status";
                bottomItems = new String[]{"Viewport Preview", "Render Queue", "Render Progress", "Current Sample", "Denoising Status", "Last Render", "Render Result", "Render Slots", "Render Layers", "Render Passes"};
                break;
            case 8:
                tools = new String[]{"Add Node", "Connect", "Disconnect", "Reroute", "Frame", "Group", "Ungroup", "Mute", "Bypass", "Search", "Preview", "Backdrop", "Fit View"};
                leftTitle = "Compositor Nodes";
                leftItems = new String[]{"INPUT", "Render Layers", "Image", "Texture", "Mask", "Movie Clip", "OUTPUT", "Composite", "Viewer", "File Output", "COLOR", "Mix Color", "RGB Curves", "Hue Saturation Value", "Color Balance", "Color Correction", "FILTER", "Blur", "Bilateral Blur", "Glare", "Defocus", "Denoise", "Filter", "Distort", "Lens Distortion", "Displace", "Map UV", "Transform", "Corner Pin", "Vector Blur", "KEYING", "Keying", "Cryptomatte", "Matte", "MASK", "Box Mask", "Ellipse Mask", "Polygon Mask", "UTILITIES", "Alpha Over", "Convert Color Space", "Math", "Time", "Switch", "Group"};
                rightTitle = "Node Properties";
                rightItems = new String[]{"Selected Node", "Node Label", "Inputs", "Outputs", "Properties", "Node Color", "Mute", "Hide", "Preview", "Execution Settings"};
                bottomTitle = "Compositor Node Editor";
                bottomItems = new String[]{"Node Graph Canvas", "Connect Sockets", "Backdrop Preview", "Viewer Node", "Composite Output", "Node Sidebar", "Node Search", "Node Groups"};
                break;
            default:
                tools = new String[]{"Add Node", "Search", "Connect", "Disconnect", "Group", "Ungroup", "Reroute", "Frame", "Duplicate", "Mute", "Preview", "Viewer", "Bake"};
                leftTitle = "Geometry Node Library";
                leftItems = new String[]{"INPUT", "Group Input", "Object Info", "Collection Info", "Scene Time", "Index", "Named Attribute", "GEOMETRY", "Join Geometry", "Transform Geometry", "Delete Geometry", "Separate Geometry", "Set Position", "Set ID", "Set Material", "MESH", "Mesh Cube", "Mesh UV Sphere", "Mesh Ico Sphere", "Mesh Cylinder", "Mesh Cone", "Mesh Grid", "Mesh Circle", "Mesh Line", "Mesh to Curve", "Curve to Mesh", "Subdivide Mesh", "Triangulate", "Subdivision Surface", "Extrude Mesh", "Flip Faces", "Set Shade Smooth", "CURVE", "Curve Circle", "Curve Line", "Resample Curve", "Curve to Points", "Trim Curve", "Fillet Curve", "Set Curve Radius", "INSTANCES", "Instance on Points", "Realize Instances", "Rotate Instances", "Scale Instances", "UTILITIES", "Math", "Vector Math", "Boolean Math", "Random Value", "Map Range", "Switch", "Compare", "Repeat Zone", "Simulation Zone", "OUTPUT", "Group Output", "Viewer"};
                rightTitle = "Modifier Properties";
                rightItems = new String[]{"Geometry Nodes Modifier", "Node Group", "Group Inputs", "Input Defaults", "Attributes", "Bake", "Simulation Cache", "Performance", "Output Geometry"};
                bottomTitle = "Geometry Node Editor";
                bottomItems = new String[]{"Node Graph Canvas", "Group Inputs", "Group Outputs", "Node Search", "Node Sidebar", "Spreadsheet", "Viewer", "Simulation Zone", "Repeat Zone", "Bake Nodes"};
                break;
        }
        for (String tool : tools) {
            TextView item = dockItem(tool, false);
            item.setPadding(dp(9), dp(6), dp(9), dp(6));
            item.setOnClickListener(v -> {
                if (activeWorkspaceIndex == 2) handleSculptPanelItem(tool);
                else handleWorkspaceItem(tool);
            });
            workspaceToolbar.addView(item, new LinearLayout.LayoutParams(
                    LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT));
        }
        workspaceLeftDock = createDock(leftTitle, index == 1 ? 126 : index == 2 ? 126 : 142, Gravity.LEFT);
        addDockItems(workspaceLeftDock, leftTitle, leftItems);
        workspaceRightDock = createDock(rightTitle, index == 1 ? 140 : index == 2 ? 142 : 156, Gravity.RIGHT);
        addDockItems(workspaceRightDock, rightTitle, rightItems);
        // Modeling docks start collapsed so the mesh remains unobstructed on a phone.
        workspacePanelsOpen = index == 1 ? preserveModelingDockState : index != 0;
        int dockVisibility = workspacePanelsOpen ? View.VISIBLE : View.GONE;
        workspaceLeftDock.setVisibility(dockVisibility);
        workspaceRightDock.setVisibility(dockVisibility);
        if(index==1){
            leftDockTab=dockRailTab("TOOLS",Gravity.LEFT|Gravity.CENTER_VERTICAL);
            rightDockTab=dockRailTab("PROPS",Gravity.RIGHT|Gravity.CENTER_VERTICAL);
            leftDockTab.setVisibility(workspacePanelsOpen?View.GONE:View.VISIBLE);
            rightDockTab.setVisibility(workspacePanelsOpen?View.GONE:View.VISIBLE);
            leftDockTab.setOnClickListener(v->setModelingDockVisibility(true));
            rightDockTab.setOnClickListener(v->setModelingDockVisibility(true));
        }
        if (viewportChrome != null) viewportChrome.setNavigationVisible(!workspacePanelsOpen);
        if (bottomTitle != null) {
            workspaceBottomDock = new LinearLayout(activity);
            workspaceBottomDock.setOrientation(LinearLayout.HORIZONTAL);
            workspaceBottomDock.setGravity(Gravity.CENTER_VERTICAL);
            workspaceBottomDock.setBackgroundColor(0xF0181D24);
            if(index==1){
                addModelingBottomButton("OBJECT / EDIT",()->toggleMeshyModeFromPhone());
                addModelingBottomButton("VERTEX",()->setModelingSelectionMode("Vertex"));
                addModelingBottomButton("EDGE",()->setModelingSelectionMode("Edge"));
                addModelingBottomButton("FACE",()->setModelingSelectionMode("Face"));
                addModelingBottomButton("SELECT ALL",()->{meshyViewport.selectAllElements();status.setText("Modeling | All "+meshyViewport.getSelectionMode().toLowerCase()+" elements selected");});
                addModelingBottomButton("ADD MESH",this::showAddMeshMenuDialog);
                addModelingBottomButton("MOVE",()->runModelingTransform("Move"));
                addModelingBottomButton("ROTATE",()->runModelingTransform("Rotate"));
                addModelingBottomButton("SCALE",()->runModelingTransform("Scale"));
                addModelingBottomButton("NUMERIC",()->showModelingNumericTransform());
                addModelingBottomButton("EXTRUDE",()->handleWorkspaceItem("Extrude Region"));
                addModelingBottomButton("INSET",()->handleWorkspaceItem("Inset Faces"));
                addModelingBottomButton("BEVEL",()->handleWorkspaceItem("Bevel Edges"));
                addModelingBottomButton("LOOP CUT",()->handleWorkspaceItem("Loop Cut"));
                addModelingBottomButton("SUBDIVIDE",()->handleWorkspaceItem("Subdivide"));
                addModelingBottomButton("DOCKS",()->setModelingDockVisibility(!workspacePanelsOpen));
            }else{
                TextView heading=dockHeading(bottomTitle);
                workspaceBottomDock.addView(heading,new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT,LayoutParams.MATCH_PARENT));
                for(String item:bottomItems){
                    TextView row=dockItem(item,false);
                    workspaceBottomDock.addView(row,new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT,LayoutParams.MATCH_PARENT));
                }
            }
            FrameLayout.LayoutParams bottomParams = new FrameLayout.LayoutParams(
                    LayoutParams.MATCH_PARENT, dp(index==1?50:(index == 0 || index == 6 ? 72 : 82)), Gravity.BOTTOM);
            bottomParams.leftMargin = dp(index==1?4:154);
            bottomParams.rightMargin = dp(index==1?4:166);
            bottomParams.bottomMargin = dp(index==1?4:4);
            if(index==1){
                HorizontalScrollView modelingShelf = new HorizontalScrollView(activity);
                modelingShelf.setHorizontalScrollBarEnabled(true);
                modelingShelf.setFillViewport(false);
                modelingShelf.setBackgroundColor(0xF0181D24);
                modelingShelf.addView(workspaceBottomDock,new HorizontalScrollView.LayoutParams(
                        LayoutParams.WRAP_CONTENT,LayoutParams.MATCH_PARENT));
                workspaceLayoutLayer.addView(modelingShelf,bottomParams);
            }else{
                workspaceLayoutLayer.addView(workspaceBottomDock,bottomParams);
            }
        }
        if (viewportChrome != null) {
            viewportChrome.setModelingWorkspace(index == 1);
            viewportChrome.setSculptWorkspace(index == 2);
        }
        if(index==1) applyModelingViewportInsets(workspacePanelsOpen);
        else applyModelingViewportInsets(false);
        if (index == 2) {
            workspaceLeftDock.setVisibility(sculptEnabled ? View.VISIBLE : View.GONE);
            workspaceRightDock.setVisibility(sculptEnabled ? View.VISIBLE : View.GONE);
            workspacePanelsOpen = sculptEnabled;
            if (viewportChrome != null) viewportChrome.setNavigationVisible(false);
        }
        if ((index == 0 || index == 1) && workspaceBottomDock != null) workspaceBottomDock.setVisibility(index==1?View.VISIBLE:View.GONE);
        status.setText("Meshy3D  |  " + WORKSPACE_NAMES[index] + " Workspace");
    }

    private TextView dockRailTab(String label,int gravity){
        TextView tab=new TextView(activity);
        tab.setText(label);
        tab.setTextColor(0xFFE5EAF0);
        tab.setTextSize(9);
        tab.setGravity(Gravity.CENTER);
        tab.setBackgroundColor(0xEE202833);
        FrameLayout.LayoutParams lp=new FrameLayout.LayoutParams(dp(30),dp(66),gravity);
        lp.topMargin=dp(42);lp.bottomMargin=dp(58);
        workspaceLayoutLayer.addView(tab,lp);
        tab.setElevation(dp(12));
        return tab;
    }

    private void addModelingBottomButton(String label,Runnable action){
        TextView button=dockItem(label,false);
        button.setGravity(Gravity.CENTER);
        button.setTextSize(9);
        button.setPadding(dp(9),0,dp(9),0);
        button.setBackgroundColor(0xFF293542);
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT,dp(38));
        lp.setMargins(dp(2),0,dp(2),0);
        workspaceBottomDock.addView(button,lp);
        button.setOnClickListener(v->{action.run();requestFocus();});
    }

    private void applyModelingViewportInsets(boolean sideDocksOpen){
        if(meshyViewport==null)return;
        FrameLayout.LayoutParams params=(FrameLayout.LayoutParams)meshyViewport.getLayoutParams();
        params.topMargin=dp(86);
        // Side docks are 126dp + 6dp left margin and 140dp + 6dp right margin.
        params.leftMargin=sideDocksOpen?dp(132):0;
        params.rightMargin=sideDocksOpen?dp(146):0;
        // The bottom shelf is always visible in Modeling, so reserve its height.
        params.bottomMargin=activeWorkspaceIndex==1?dp(54):0;
        meshyViewport.setLayoutParams(params);
    }

    private void setModelingDockVisibility(boolean visible){
        workspacePanelsOpen=visible;
        int state=visible?View.VISIBLE:View.GONE;
        if(workspaceLeftDock!=null)workspaceLeftDock.setVisibility(state);
        if(workspaceRightDock!=null)workspaceRightDock.setVisibility(state);
        if(leftDockTab!=null)leftDockTab.setVisibility(visible?View.GONE:View.VISIBLE);
        if(rightDockTab!=null)rightDockTab.setVisibility(visible?View.GONE:View.VISIBLE);
        applyModelingViewportInsets(visible);
        // Modeling uses docked side properties; do not re-open the floating
        // navigation panel when the side drawers collapse.
    }

    private void runModelingTransform(String operation){
        checkpointMeshEdit();
        if(meshyViewport.isMeshEditMode()){
            showTransformDialog(operation);
        }else{
            Meshy3DObjectScene.Object3D active=meshyViewport.getObjectScene().getActiveObject();
            if(active==null){Toast.makeText(activity,"Select a mesh object first.",Toast.LENGTH_SHORT).show();return;}
            meshyViewport.setObjectTransformTool(
                    "Move".equals(operation)?0:"Rotate".equals(operation)?1:2);
            status.setText("Modeling | "+operation+" gizmo active");
        }
    }

    private void showModelingNumericTransform(){
        if(meshyViewport.isMeshEditMode()){
            new android.app.AlertDialog.Builder(activity).setTitle("Numeric Transform")
                    .setItems(new String[]{"Move","Rotate","Scale"},(dialog,which)->
                            showTransformDialog(new String[]{"Move","Rotate","Scale"}[which]))
                    .setNegativeButton("Cancel",null).show();
        }else{
            showObjectTransformTypeDialog();
        }
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if(activeWorkspaceIndex==1 && event.getAction()==KeyEvent.ACTION_DOWN
                && event.getRepeatCount()==0 && handleModelingShortcut(event)) return true;
        return super.dispatchKeyEvent(event);
    }

    private boolean handleModelingShortcut(KeyEvent event) {
        int code=event.getKeyCode();
        boolean control=(event.getMetaState()&KeyEvent.META_CTRL_ON)!=0;
        boolean alt=(event.getMetaState()&KeyEvent.META_ALT_ON)!=0;
        if(control || (event.getMetaState()&KeyEvent.META_META_ON)!=0)return false;
        if(code==KeyEvent.KEYCODE_TAB){
            modelingNumericPending=false;modelingNumericEntry.setLength(0);
            toggleMeshyModeFromPhone();return true;
        }
        if(code==KeyEvent.KEYCODE_ESCAPE){
            modelingNumericPending=false;modelingNumericEntry.setLength(0);
            modelingNumericAxis=-1;meshyViewport.setKeyboardAxisConstraint(-1);
            meshyViewport.setObjectTransformTool(-1);
            if(status!=null)status.setText("Modeling | Operation cancelled");
            return true;
        }
        if(!modelingNumericPending){
            if(code==KeyEvent.KEYCODE_1){setModelingSelectionMode("Vertex");return true;}
            if(code==KeyEvent.KEYCODE_2){setModelingSelectionMode("Edge");return true;}
            if(code==KeyEvent.KEYCODE_3){setModelingSelectionMode("Face");return true;}
        }
        if(!meshyViewport.isMeshEditMode()) {
            if(code==KeyEvent.KEYCODE_G){runModelingTransform("Move");return true;}
            if(code==KeyEvent.KEYCODE_R){runModelingTransform("Rotate");return true;}
            if(code==KeyEvent.KEYCODE_S){runModelingTransform("Scale");return true;}
            return false;
        }
        if(code==KeyEvent.KEYCODE_A){
            if(alt){meshyViewport.clearElementSelection();if(status!=null)status.setText("Modeling | Selection cleared");}
            else {meshyViewport.selectAllElements();if(status!=null)status.setText("Modeling | All "+meshyViewport.getSelectionMode().toLowerCase()+" elements selected");}
            return true;
        }
        if(code==KeyEvent.KEYCODE_G||code==KeyEvent.KEYCODE_R||code==KeyEvent.KEYCODE_S){
            int operation=code==KeyEvent.KEYCODE_G?0:code==KeyEvent.KEYCODE_R?1:2;
            meshyViewport.setObjectTransformTool(operation);
            modelingNumericPending=true;modelingNumericEntry.setLength(0);modelingNumericAxis=-1;
            if(status!=null)status.setText("Modeling | "+(operation==0?"Move":operation==1?"Rotate":"Scale")+" selected elements; choose X/Y/Z or type a value");
            return true;
        }
        if(code==KeyEvent.KEYCODE_X||code==KeyEvent.KEYCODE_Y||code==KeyEvent.KEYCODE_Z){
            modelingNumericAxis=code==KeyEvent.KEYCODE_X?0:code==KeyEvent.KEYCODE_Y?1:2;
            meshyViewport.setKeyboardAxisConstraint(modelingNumericAxis);
            if(status!=null)status.setText("Modeling | "+(modelingNumericAxis==0?"X":modelingNumericAxis==1?"Y":"Z")+" axis constrained");
            return true;
        }
        if(modelingNumericPending){
            if(code==KeyEvent.KEYCODE_ENTER||code==KeyEvent.KEYCODE_NUMPAD_ENTER){
                if(modelingNumericEntry.length()>0){
                    try{
                        float amount=Float.parseFloat(modelingNumericEntry.toString());
                        checkpointMeshEdit();
                        meshyViewport.applyNumericTransform(modelingNumericAxis,amount);
                        if(status!=null)status.setText("Modeling | Numeric transform applied: "+modelingNumericEntry);
                    }catch(NumberFormatException ignored){
                        if(status!=null)status.setText("Modeling | Invalid numeric transform");
                    }
                }
                modelingNumericPending=false;modelingNumericEntry.setLength(0);return true;
            }
            if(code==KeyEvent.KEYCODE_DEL){
                if(modelingNumericEntry.length()>0)modelingNumericEntry.deleteCharAt(modelingNumericEntry.length()-1);
                return true;
            }
            int unicode=event.getUnicodeChar();
            if((unicode>='0'&&unicode<='9')||unicode=='.'||unicode=='-'){
                modelingNumericEntry.append((char)unicode);
                if(status!=null)status.setText("Modeling | Value: "+modelingNumericEntry+" (Enter to apply)");
                return true;
            }
        }
        return false;
    }

    private void setModelingSelectionMode(String mode){
        if(!meshyViewport.isMeshEditMode()){
            activeEditorMode="Edit Mode";
            if(modeButton!=null)modeButton.setText(activeEditorMode);
            meshyViewport.setMeshEditMode(true);
            meshyViewport.setObjectSelectionEnabled(false);
        }
        meshyViewport.setSelectionMode(mode);
        status.setText("Modeling | "+mode+" selection mode — tap mesh elements to select");
    }

    private void toggleWorkspacePanels() {
        workspacePanelsOpen = !workspacePanelsOpen;
        if(activeWorkspaceIndex==1){
            setModelingDockVisibility(workspacePanelsOpen);
            status.setText(workspacePanelsOpen?"Modeling | Side docks open":"Modeling | Viewport expanded");
            return;
        }
        int visibility = workspacePanelsOpen ? View.VISIBLE : View.GONE;
        if (workspaceLeftDock != null) workspaceLeftDock.setVisibility(visibility);
        if (workspaceRightDock != null) workspaceRightDock.setVisibility(visibility);
        if (workspaceBottomDock != null) workspaceBottomDock.setVisibility(visibility);
        if (activeWorkspaceIndex == 2 && !sculptEnabled) {
            if (workspaceLeftDock != null) workspaceLeftDock.setVisibility(View.GONE);
            if (workspaceRightDock != null) workspaceRightDock.setVisibility(View.GONE);
        }
        if (viewportChrome != null) viewportChrome.setNavigationVisible(!workspacePanelsOpen);
        if (status != null) status.setText("Meshy3D | " +
                (workspacePanelsOpen ? "Workspace panels shown" : "Expanded viewport"));
    }

    private void showViewportViewMenu() {
        String[] options={"Orbit Mode","Pan Mode","Zoom In","Zoom Out","Frame Selected",
                "Frame All","Reset View","Front View","Back View","Right View","Left View",
                "Top View","Bottom View","Camera View"};
        new android.app.AlertDialog.Builder(activity).setTitle("Viewport View")
                .setItems(options,(dialog,which)->{
                    switch(which) {
                        case 0: meshyViewport.setViewportNavigationMode(0); break;
                        case 1: meshyViewport.setViewportNavigationMode(1); break;
                        case 2: meshyViewport.zoomBy(1.22f); break;
                        case 3: meshyViewport.zoomBy(.82f); break;
                        case 4: meshyViewport.frameSelectedObjects(); break;
                        case 5: meshyViewport.frameAllObjects(); break;
                        case 6: meshyViewport.resetView(); break;
                        case 7: meshyViewport.setViewPreset(0); break;
                        case 8: meshyViewport.setViewPreset(1); break;
                        case 9: meshyViewport.setViewPreset(2); break;
                        case 10: meshyViewport.setViewPreset(3); break;
                        case 11: meshyViewport.setViewPreset(4); break;
                        case 12: meshyViewport.setViewPreset(5); break;
                        case 13:
                            try { meshyViewport.toggleActiveCameraView(); }
                            catch(Exception error){showObjectOperationError(error);}
                            break;
                    }
                    status.setText("Meshy3D | "+options[which]);
                }).setNegativeButton("Close",null).show();
    }

    private void showViewportSelectMenu() {
        String[] options={"Select All Objects","Deselect All Objects","Invert Selection",
                "Box Select","Vertex Selection","Edge Selection","Face Selection"};
        new android.app.AlertDialog.Builder(activity).setTitle("Select")
                .setItems(options,(dialog,which)->{
                    switch(which) {
                        case 0: meshyViewport.selectAllSceneObjects();refreshModelingOutliner();break;
                        case 1: meshyViewport.deselectAllSceneObjects();refreshModelingOutliner();break;
                        case 2: meshyViewport.invertSceneSelection();refreshModelingOutliner();break;
                        case 3:
                            meshyViewport.setMeshEditMode(false);
                            meshyViewport.setObjectSelectionEnabled(true);
                            meshyViewport.setObjectBoxSelectEnabled(true);
                            break;
                        case 4: case 5: case 6:
                            meshyViewport.setMeshEditMode(true);
                            meshyViewport.setObjectSelectionEnabled(false);
                            meshyViewport.setSelectionMode(which==4?"Vertex":which==5?"Edge":"Face");
                            activeEditorMode="Edit Mode";
                            if(modeButton!=null)modeButton.setText(activeEditorMode);
                            break;
                    }
                    status.setText("Meshy3D | "+options[which]);
                }).setNegativeButton("Close",null).show();
    }

    private void showViewportObjectMenu() {
        Meshy3DObjectScene.Object3D active=meshyViewport.getObjectScene().getActiveObject();
        if(active==null) {
            Toast.makeText(activity,"Select an object first.",Toast.LENGTH_SHORT).show();
            return;
        }
        String[] options={"Rename Object","Duplicate","Delete Object","Object Properties",
                "Modifiers","Constraints","Apply Location","Apply Rotation & Scale",
                "Set Origin to Geometry","Set Origin to 3D Cursor","Camera Settings","Light Settings"};
        new android.app.AlertDialog.Builder(activity).setTitle("Object | "+active.name)
                .setItems(options,(dialog,which)->{
                    switch(which) {
                        case 0: showObjectRenameDialog();break;
                        case 1:
                            checkpointMeshEdit();meshyViewport.duplicateActiveObject(false);
                            refreshModelingOutliner();break;
                        case 2:
                            checkpointMeshEdit();meshyViewport.deleteActiveObject();
                            refreshModelingOutliner();break;
                        case 3: showObjectPropertiesDialog();break;
                        case 4: showObjectModifiersDialog();break;
                        case 5: showObjectConstraintsDialog();break;
                        case 6: checkpointMeshEdit();meshyViewport.applyActiveObjectLocation();break;
                        case 7: checkpointMeshEdit();meshyViewport.applyActiveObjectRotationAndScale();break;
                        case 8: checkpointMeshEdit();meshyViewport.setActiveObjectOriginToGeometry();break;
                        case 9: checkpointMeshEdit();meshyViewport.setActiveObjectOriginToCursor();break;
                        case 10: showCameraSettingsDialog();break;
                        case 11: showLightSettingsDialog();break;
                    }
                    meshyViewport.invalidate();
                    status.setText("Meshy3D | "+options[which]);
                }).setNegativeButton("Close",null).show();
    }

    private void toggleViewportFocus() {
        viewportFocusMode = !viewportFocusMode;
        int visibility = viewportFocusMode ? View.GONE : View.VISIBLE;
        if (workspaceLeftDock != null) workspaceLeftDock.setVisibility(visibility);
        if (workspaceRightDock != null) workspaceRightDock.setVisibility(visibility);
        if (workspaceBottomDock != null) workspaceBottomDock.setVisibility(visibility);
        if (viewportChrome != null) viewportChrome.setVisibility(viewportFocusMode ? View.GONE : View.VISIBLE);
        if (phoneControls != null) phoneControls.setVisibility(View.GONE);
        if (status != null) status.setText(viewportFocusMode
                ? "Meshy3D  |  Viewport Focus  •  Tap viewport to restore panels"
                : "Meshy3D  |  " + WORKSPACE_NAMES[activeWorkspaceIndex] + " Workspace");
    }

    public void checkpointMeshEdit() {
        if (meshyViewport == null) return;
        meshUndoStack.addLast(exportMeshyDocumentState());
        while (meshUndoStack.size() > 40) meshUndoStack.removeFirst();
        meshRedoStack.clear();
    }

    public boolean undoMeshEdit() {
        if (meshUndoStack.isEmpty()) return false;
        meshRedoStack.addLast(exportMeshyDocumentState());
        org.json.JSONObject previous=meshUndoStack.removeLast();
        return importMeshyDocumentState(previous);
    }

    public boolean redoMeshEdit() {
        if (meshRedoStack.isEmpty()) return false;
        meshUndoStack.addLast(exportMeshyDocumentState());
        org.json.JSONObject next=meshRedoStack.removeLast();
        return importMeshyDocumentState(next);
    }

    public int getMeshUndoCount() { return meshUndoStack.size(); }

    public int importGlbIntoMeshy3D(java.util.List<Meshy3DGlbImporter.ImportedMesh> imported) {
        if (meshyViewport == null) throw new IllegalStateException("The Meshy3D viewport is not open.");
        checkpointMeshEdit();
        int count = meshyViewport.importMeshyMeshes(imported);
        if (count <= 0) throw new IllegalArgumentException("The GLB did not contain importable triangle meshes.");
        refreshModelingOutliner();
        if (status != null) status.setText("File | Imported " + count + " editable GLB mesh object(s)");
        return count;
    }

    public String getDocumentDataPreview() {
        if (meshyViewport == null) return "Meshy3D viewport is not open.";
        Meshy3DObjectScene scene = meshyViewport.getObjectScene();
        java.util.List<Meshy3DObjectScene.Object3D> objects = scene.getObjects();
        int meshObjects = 0;
        int totalVertices = 0;
        int totalEdges = 0;
        int totalFaces = 0;
        StringBuilder summary = new StringBuilder();
        summary.append("Workspace: ").append(WORKSPACE_NAMES[activeWorkspaceIndex]).append('\n');
        summary.append("Objects: ").append(objects.size())
                .append(" | Collections: ").append(scene.getCollectionCount())
                .append(" | Selected: ").append(scene.getSelectedObjects().size()).append("\n\n");
        for (Meshy3DObjectScene.Object3D object : objects) {
            summary.append(object.active ? "● " : "  ")
                    .append(object.name).append("  [").append(object.type).append("]");
            if (object.mesh != null) {
                int vertices = object.mesh.getVertexCount();
                int edges = object.mesh.getEdgeCount();
                int faces = object.mesh.getFaceCount();
                meshObjects++;
                totalVertices += vertices;
                totalEdges += edges;
                totalFaces += faces;
                summary.append("\n     ").append(vertices).append(" vertices, ")
                        .append(edges).append(" edges, ").append(faces).append(" faces");
            }
            if (object.modifiers != null && !object.modifiers.isEmpty()) {
                summary.append("\n     ").append(object.modifiers.size()).append(" modifier(s)");
            }
            if (object.parentId != null && !object.parentId.isEmpty()) {
                Meshy3DObjectScene.Object3D parent = scene.getObject(object.parentId);
                if (parent != null) summary.append("\n     Parent: ").append(parent.name);
            }
            summary.append('\n');
        }
        summary.append("\nMesh totals: ").append(meshObjects).append(" mesh object(s), ")
                .append(totalVertices).append(" vertices, ")
                .append(totalEdges).append(" edges, ")
                .append(totalFaces).append(" faces");
        return summary.toString();
    }

    public String getActiveObjectName() {
        if (meshyViewport == null) return null;
        Meshy3DObjectScene.Object3D active = meshyViewport.getObjectScene().getActiveObject();
        return active == null ? null : active.name;
    }

    public java.util.List<Meshy3DGeometryExporter.ObjectSnapshot> snapshotGeometryForExport() {
        if (meshyViewport == null) throw new IllegalStateException("The Meshy3D viewport is not open.");
        return Meshy3DGeometryExporter.snapshot(meshyViewport.getObjectScene());
    }

    public int getSelectedObjectCount() {
        if (meshyViewport == null) return 0;
        return meshyViewport.getObjectScene().getSelectedObjects().size();
    }

    public boolean renameActiveMeshyObject(String name) {
        if (meshyViewport == null || meshyViewport.getObjectScene().getActiveObject() == null) return false;
        if (name == null || name.trim().isEmpty()) return false;
        checkpointMeshEdit();
        meshyViewport.renameActiveObject(name.trim());
        refreshModelingOutliner();
        status.setText("Meshy3D | Active object renamed");
        return true;
    }

    public int renameSelectedMeshyObjects(String prefix, int firstNumber) {
        if (meshyViewport == null) return 0;
        String clean = prefix == null ? "" : prefix.trim();
        if (clean.isEmpty()) clean = "Object";
        java.util.ArrayList<Meshy3DObjectScene.Object3D> selected =
                new java.util.ArrayList<>(meshyViewport.getObjectScene().getSelectedObjects());
        if (selected.isEmpty()) return 0;
        checkpointMeshEdit();
        int number = firstNumber;
        for (Meshy3DObjectScene.Object3D object : selected) {
            meshyViewport.getObjectScene().rename(object.id,
                    clean + String.format(java.util.Locale.US, "_%03d", number++));
        }
        meshyViewport.invalidate();
        refreshModelingOutliner();
        status.setText("Meshy3D | Renamed " + selected.size() + " selected objects");
        return selected.size();
    }

    public boolean executeEditMenuMeshOperation(int operation, float amount) {
        if (meshyViewport == null || !"Edit Mode".equals(activeEditorMode)
                || !meshyViewport.isMeshEditMode()) return false;
        if (meshyViewport.getSelectedElementCount() <= 0) return false;
        checkpointMeshEdit();
        try {
            switch (operation) {
                case 0: meshyViewport.extrudeSelected(amount); break;
                case 1: meshyViewport.insetSelected(amount); break;
                case 2: meshyViewport.bevelSelected(amount); break;
                case 3: meshyViewport.loopCutSelected(amount); break;
                case 4: meshyViewport.subdivideSelected(); break;
                case 5: meshyViewport.mergeSelected(Math.max(0.0001f, amount)); break;
                default:
                    undoMeshEdit();
                    return false;
            }
            status.setText("Meshy3D | " + new String[]{
                    "Extrude", "Inset", "Bevel", "Loop Cut", "Subdivide", "Merge by Distance"
            }[operation] + " completed");
            return true;
        } catch (RuntimeException error) {
            undoMeshEdit();
            throw error;
        }
    }

    /** Return the live document state owned by this Meshy3D workspace. */
    public org.json.JSONObject exportMeshyDocumentState() {
        org.json.JSONObject document = new org.json.JSONObject();
        try {
            document.put("format", "Meshy3D");
            document.put("version", 2);
            document.put("workspace", activeWorkspaceIndex);
            document.put("mesh", meshyViewport.exportDocumentState());
            return document;
        } catch (org.json.JSONException error) {
            throw new IllegalStateException("Could not serialize Meshy3D document", error);
        }
    }

    /** Restore a saved Meshy3D document into this workspace's own viewport. */
    public boolean importMeshyDocumentState(org.json.JSONObject document) {
        if (document == null || !"Meshy3D".equals(document.optString("format"))) return false;
        org.json.JSONObject mesh = document.optJSONObject("mesh");
        // Version-1 starter documents contained only object metadata; keep the
        // current default mesh when opening those older files.
        if (mesh == null) return true;
        if (meshyViewport == null || !meshyViewport.importDocumentState(mesh)) return false;
        int savedWorkspace = document.optInt("workspace", activeWorkspaceIndex);
        if (savedWorkspace >= 0 && savedWorkspace < WORKSPACE_NAMES.length) setActiveWorkspaceIndex(savedWorkspace);
        return true;
    }

    public Meshy3DViewport getMeshyViewport() { return meshyViewport; }

    public int getActiveWorkspaceIndex() { return activeWorkspaceIndex; }

    public android.graphics.Bitmap renderFinalImage(int width, int height) {
        if (meshyViewport == null) return null;
        return meshyViewport.renderFinalImage(Math.max(1, width), Math.max(1, height));
    }

    public void setStatusBarVisible(boolean visible) {
        if (status != null) status.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    public void showWorkspaceChooser() {
        new android.app.AlertDialog.Builder(activity)
                .setTitle("Meshy3D Workspaces")
                .setSingleChoiceItems(WORKSPACE_NAMES, activeWorkspaceIndex,
                        (dialog, which) -> {
                            setActiveWorkspaceIndex(which);
                            dialog.dismiss();
                        })
                .setNegativeButton("Cancel", null).show();
    }

    public void setObjectModesLocked(boolean locked) {
        objectModesLocked = locked;
        if (modeButton != null) modeButton.setAlpha(locked ? 0.55f : 1.0f);
    }

    private void showEditorModeMenu() {
        final String[] modes = {"Object Mode", "Edit Mode", "Sculpt Mode"};
        new android.app.AlertDialog.Builder(activity).setTitle("Meshy3D Mode")
                .setItems(modes, (dialog, which) -> {
                    String next = modes[which];
                    if (objectModesLocked && !next.equals(activeEditorMode)) {
                        Toast.makeText(activity, "Object mode switching is locked. Unlock it from Edit > Lock Object Modes.", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    activeEditorMode = next;
                    sculptEnabled = "Sculpt Mode".equals(next);
                    updateSculptViewport();
                    if (modeButton != null) modeButton.setText(next);
                    if (brushDock != null) brushDock.setVisibility(sculptEnabled ? View.VISIBLE : View.GONE);
                    if (brushControls != null) brushControls.setVisibility(sculptEnabled ? View.VISIBLE : View.GONE);
                    renderWorkspaceLayout(activeWorkspaceIndex);
                    if (status != null) status.setText("Meshy3D  |  " + next);
                    invalidate();
                }).setNegativeButton("Cancel", null).show();
    }

    private int dp(float value) {
        return (int)(value * getResources().getDisplayMetrics().density * uiScale + 0.5f);
    }

}
