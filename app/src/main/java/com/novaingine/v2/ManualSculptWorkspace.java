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
    private int activeWorkspaceIndex;
    private int workspaceTransitionToken;
    private boolean viewportFocusMode;
    private FrameLayout workspaceLayoutLayer;
    private LinearLayout workspaceToolbar;
    private LinearLayout workspaceLeftDock;
    private LinearLayout workspaceRightDock;
    private LinearLayout workspaceBottomDock;
    private final java.util.ArrayDeque<org.json.JSONObject> meshUndoStack = new java.util.ArrayDeque<>();
    private final java.util.ArrayDeque<org.json.JSONObject> meshRedoStack = new java.util.ArrayDeque<>();
    private final Handler timelineHandler = new Handler(Looper.getMainLooper());
    private boolean timelinePlaying;
    private long timelineStartMs;
    private int currentTimelineFrame=1;
    private Runnable timelineTick;

    public ManualSculptWorkspace(Activity activity, EngineSurface engine, Runnable onExit) {
        super(activity);
        this.activity = activity;
        this.onExit = onExit;
        float density = getResources().getDisplayMetrics().density;
        float availableHeightDp = getResources().getDisplayMetrics().heightPixels / density;
        uiScale = Math.max(0.55f, Math.min(1.0f, availableHeightDp / 260.0f));
        setBackgroundColor(Color.TRANSPARENT);
        setClickable(false);

        // Meshy3D owns this viewport and its starter cube; it is not the Novaingine GL scene.
        meshyViewport = new Meshy3DViewport(activity, this::toggleViewportFocus);
        addView(meshyViewport, new FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

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
        String[] menuNames = {"File", "Edit", "Window", "Help"};
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
            }
            viewportHeader.addView(item, new LinearLayout.LayoutParams(
                    LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT));
        }
        View headerSpacer = new View(activity);
        viewportHeader.addView(headerSpacer, new LinearLayout.LayoutParams(0, 1, 1f));
        String[] rightHeaderItems = {"↔  Global", "◇", "⌖", "▦", "◉", "⌄"};
        for (String value : rightHeaderItems) {
            TextView item = new TextView(activity);
            item.setText(value);
            item.setTextColor(0xFFD0D0D0);
            item.setTextSize(10);
            item.setGravity(Gravity.CENTER);
            item.setPadding(dp(7), 0, dp(7), 0);
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
                if ("Hide / Show Objects".equals(item)) {
                    checkpointMeshEdit(); meshyViewport.toggleMeshVisible();
                    status.setText("Layout | Cube visibility toggled");
                } else if ("Camera View".equals(item)) {
                    meshyViewport.resetView();
                    status.setText("Layout | View framed on Cube");
                } else if ("Show Grid".equals(item) || "Grid".equals(item)) {
                    checkpointMeshEdit(); meshyViewport.toggleFloorGrid();
                } else if ("Move".equals(item) || "Rotate".equals(item) || "Scale".equals(item)
                        || "Transform".equals(item)) showTransformDialog(item.equals("Transform") ? "Move" : item);
                else if ("Hide Object".equals(item)) meshyViewport.setMeshVisible(false);
                else showWorkspaceActionInfo(item);
                break;
            case 1:
                if ("Add Primitive".equals(item)) showPrimitiveDialog();
                else if ("Add Cube".equals(item)) {
                    checkpointMeshEdit();meshyViewport.addCubeObject();
                    activeEditorMode="Object Mode";if(modeButton!=null)modeButton.setText(activeEditorMode);
                    meshyViewport.setMeshEditMode(false);
                    status.setText("Modeling | New independent Cube object added");
                } else if ("Duplicate".equals(item) || "Duplicate Linked".equals(item)) {
                    checkpointMeshEdit();meshyViewport.duplicateActiveObject("Duplicate Linked".equals(item));
                    status.setText("Modeling | "+item+" created");
                } else if ("Delete Object".equals(item)) {
                    checkpointMeshEdit();meshyViewport.deleteActiveObject();
                    status.setText("Modeling | Active object deleted");
                } else if ("Rename Object".equals(item)) showObjectRenameDialog();
                else if ("Select Object".equals(item)) showObjectPickerDialog(false);
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
                else if ("Clear Parent".equals(item)) {
                    meshyViewport.setActiveObjectParent(null);status.setText("Modeling | Parent relationship cleared");
                } else if ("Add Collection".equals(item)) showCreateCollectionDialog();
                else if ("Move To Collection".equals(item)) showCollectionPickerDialog();
                else if ("Object Statistics".equals(item)) {
                    Meshy3DObjectScene scene=meshyViewport.getObjectScene();
                    status.setText("Scene | "+scene.getObjectCount()+" objects | "+scene.getCollectionCount()+" collections");
                } else if (("Move".equals(item)||"Rotate".equals(item)||"Scale".equals(item))
                        && !"Edit Mode".equals(activeEditorMode)) {
                    showObjectTransformDialog(item);
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
                } else if ("Fill".equals(item) || "Grid Fill".equals(item)) {
                    checkpointMeshEdit();meshyViewport.fillSelectedBoundary();status.setText("Modeling | Selected closed boundary filled");
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
                } else if ("Split".equals(item) || "Rip".equals(item) || "Separate Selection".equals(item)) {
                    checkpointMeshEdit();meshyViewport.splitSelectedFaces();
                    status.setText("Modeling | Selected faces disconnected by duplicating their boundary vertices (still in this object)");
                } else if ("Solidify".equals(item)) {
                    showAmountDialog("Solidify thickness",0.01f,.5f,value->{checkpointMeshEdit();meshyViewport.solidifySelected(value);status.setText("Modeling | Solid thickness generated");});
                } else if ("Bevel Modifier".equals(item)) {
                    showAmountDialog("Bevel width",.001f,.45f,value->{checkpointMeshEdit();meshyViewport.bevelSelected(value);status.setText("Modeling | Bevel geometry generated");});
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
                    checkpointMeshEdit();meshyViewport.subdivideSelected();status.setText("Modeling | Quad faces subdivided");
                } else if ("Weighted Normal".equals(item) || "Normals".equals(item)) {
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
                } else if ("Transform Orientation".equals(item) || "Pivot Point".equals(item)) {
                    final String[] choices="Transform Orientation".equals(item)
                            ?new String[]{"Global","Local","Normal","View"}
                            :new String[]{"Median Point","Active Element","3D Cursor","Individual Origins"};
                    new android.app.AlertDialog.Builder(activity).setTitle(item)
                            .setItems(choices,(d,w)->status.setText("Modeling | "+item+": "+choices[w])).show();
                } else if ("Operation Amount".equals(item) || "Offset".equals(item) || "Segments".equals(item)
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
                } else if ("Wireframe".equals(item)) meshyViewport.toggleTopology();
                else if ("Reset Mesh".equals(item)) {checkpointMeshEdit();meshyViewport.resetMesh();}
                else showWorkspaceActionInfo(item);
                break;
            case 3:
                if ("Unwrap".equals(item) || "Smart UV Project".equals(item) || "Lightmap Pack".equals(item)) {
                    checkpointMeshEdit();meshyViewport.unwrapMesh();
                    status.setText("UV Editing | LSCM conformal unwrap | " + meshyViewport.getUvIslandCount() + " UV islands");
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
                } else if ("Move UV".equals(item) || "Rotate UV".equals(item) || "Scale UV".equals(item)
                        || "UV Canvas".equals(item) || "Image / UV View".equals(item)
                        || "UV Islands".equals(item)) {
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
                    meshyViewport.createPaintTexture(1024,1024,0xFFFFFFFF);
                    showUvEditor(true);
                    status.setText("Texture Paint | Created 1024 × 1024 RGBA image");
                } else if ("Brush Color".equals(item)) showPaintColorDialog();
                else if ("Active Texture".equals(item) || "Draw".equals(item) || "Texture Canvas".equals(item)) {
                    if(meshyViewport.getDocumentCore().getTexturePixels()==null)
                        meshyViewport.createPaintTexture(1024,1024,0xFFFFFFFF);
                    showUvEditor(true);
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
                } else if ("Timeline".equals(item) || "Playback Controls".equals(item)
                        || "Current Frame: 1".equals(item) || "Dope Sheet".equals(item)
                        || "Graph Editor".equals(item)) showAnimationTimeline();
                else showWorkspaceActionInfo(item);
                break;
            case 7:
                if ("Render Image".equals(item) || "Render Viewport".equals(item) || "Last Render".equals(item))
                    saveViewportRender();
                else showWorkspaceActionInfo(item);
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

    private void showUvEditor(boolean paintMode) {
        Meshy3DUvEditorView editor=new Meshy3DUvEditorView(activity,meshyViewport.getDocumentCore(),paintMode);
        LinearLayout root=new LinearLayout(activity);root.setOrientation(LinearLayout.VERTICAL);
        HorizontalScrollView tools=new HorizontalScrollView(activity);tools.setHorizontalScrollBarEnabled(false);
        LinearLayout buttons=new LinearLayout(activity);buttons.setOrientation(LinearLayout.HORIZONTAL);
        String[] actions=paintMode?new String[]{"Orange","Blue","White","Smaller Brush","Larger Brush","Opacity +","Opacity -"}
                :new String[]{"Move +","Move -","Rotate 15°","Rotate -15°","Scale Up","Scale Down"};
        for(String action:actions){TextView b=dockItem(action,false);buttons.addView(b);
            b.setOnClickListener(v->{
                Meshy3DCore core=meshyViewport.getDocumentCore();
                if(paintMode){
                    if(action.equals("Orange"))editor.setBrush(0xFFFF6B35,core.getBrushRadius(),core.getBrushOpacity());
                    else if(action.equals("Blue"))editor.setBrush(0xFF368BFF,core.getBrushRadius(),core.getBrushOpacity());
                    else if(action.equals("White"))editor.setBrush(0xFFFFFFFF,core.getBrushRadius(),core.getBrushOpacity());
                    else if(action.equals("Smaller Brush"))editor.setBrush(core.getBrushColor(),Math.max(1,core.getBrushRadius()-4),core.getBrushOpacity());
                    else if(action.equals("Larger Brush"))editor.setBrush(core.getBrushColor(),Math.min(128,core.getBrushRadius()+4),core.getBrushOpacity());
                    else if(action.equals("Opacity +"))editor.setBrush(core.getBrushColor(),core.getBrushRadius(),Math.min(1,core.getBrushOpacity()+.1f));
                    else editor.setBrush(core.getBrushColor(),core.getBrushRadius(),Math.max(.05f,core.getBrushOpacity()-.1f));
                }else{
                    if(action.equals("Move +"))core.transformSelectedUv(.05f,0,1,0);
                    else if(action.equals("Move -"))core.transformSelectedUv(-.05f,0,1,0);
                    else if(action.equals("Rotate 15°"))core.transformSelectedUv(0,0,1,(float)Math.toRadians(15));
                    else if(action.equals("Rotate -15°"))core.transformSelectedUv(0,0,1,(float)Math.toRadians(-15));
                    else if(action.equals("Scale Up"))core.transformSelectedUv(0,0,1.1f,0);
                    else core.transformSelectedUv(0,0,.9f,0);
                    editor.invalidate();
                }
            });
        }
        tools.addView(buttons);root.addView(tools,new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT,dp(44)));
        root.addView(editor,new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT,dp(420)));
        new android.app.AlertDialog.Builder(activity).setTitle(paintMode?"Texture Paint Image":"UV Editor")
                .setView(root).setPositiveButton("Done",(d,w)->status.setText(paintMode
                        ?"Texture Paint | Pixel image updated":"UV Editing | UV island changes applied"))
                .setNegativeButton("Close",null).show();
    }

    private void showAnimationTimeline() {
        LinearLayout panel=new LinearLayout(activity);panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(14),dp(10),dp(14),dp(8));
        TextView frameLabel=new TextView(activity);frameLabel.setTextColor(Color.WHITE);frameLabel.setText("Frame "+currentTimelineFrame);
        SeekBar frame=new SeekBar(activity);frame.setMax(249);frame.setProgress(currentTimelineFrame-1);
        frame.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onProgressChanged(SeekBar b,int p,boolean user){currentTimelineFrame=p+1;frameLabel.setText("Frame "+currentTimelineFrame+"  •  "+meshyViewport.getMeshKeyframeCount()+" keyframes");meshyViewport.applyAnimationFrame(currentTimelineFrame);}
            public void onStartTrackingTouch(SeekBar b){}
            public void onStopTrackingTouch(SeekBar b){}
        });
        panel.addView(frameLabel);panel.addView(frame);
        HorizontalScrollView actions=new HorizontalScrollView(activity);actions.setHorizontalScrollBarEnabled(false);
        LinearLayout row=new LinearLayout(activity);row.setOrientation(LinearLayout.HORIZONTAL);
        TextView insert=dockItem("Insert Keyframe",false),remove=dockItem("Delete Keyframe",false),play=dockItem("Play",false);
        row.addView(insert);row.addView(remove);row.addView(play);actions.addView(row);panel.addView(actions);
        insert.setOnClickListener(v->{checkpointMeshEdit();meshyViewport.storeMeshKeyframe(currentTimelineFrame);frameLabel.setText("Frame "+currentTimelineFrame+"  •  "+meshyViewport.getMeshKeyframeCount()+" keyframes");});
        remove.setOnClickListener(v->{checkpointMeshEdit();meshyViewport.deleteMeshKeyframe(currentTimelineFrame);frameLabel.setText("Frame "+currentTimelineFrame+"  •  "+meshyViewport.getMeshKeyframeCount()+" keyframes");});
        play.setOnClickListener(v->{
            timelinePlaying=!timelinePlaying;play.setText(timelinePlaying?"Pause":"Play");
            if(timelinePlaying){
                timelineTick=new Runnable(){public void run(){
                    if(!timelinePlaying)return;currentTimelineFrame=currentTimelineFrame>=250?1:currentTimelineFrame+1;
                    frame.setProgress(currentTimelineFrame-1);meshyViewport.applyAnimationFrame(currentTimelineFrame);
                    timelineHandler.postDelayed(this,33L);
                }};timelineHandler.post(timelineTick);
            }else if(timelineTick!=null)timelineHandler.removeCallbacks(timelineTick);
        });
        android.app.AlertDialog dialog=new android.app.AlertDialog.Builder(activity).setTitle("Animation Timeline")
                .setView(panel).setPositiveButton("Done",null).setNegativeButton("Close",null).create();
        dialog.setOnDismissListener(d->{timelinePlaying=false;if(timelineTick!=null)timelineHandler.removeCallbacks(timelineTick);});
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
            if(type.equals("Base Color")||type.equals("Set Material")){
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
                    ?new String[]{"Transform Geometry","Set Position","Subdivide Mesh","Smooth","Delete Geometry","Join Geometry","Instance on Points","Set Material","Random Value","Math"}
                    :graph==1?new String[]{"Image Texture","Base Color","Principled BSDF","Metallic","Roughness","Material Output"}
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
            checkpointMeshEdit();core.evaluateGraph(graph);
            if(graph==0){meshyViewport.setMeshTint(core.getBaseColor());meshyViewport.refreshFromDocument();status.setText("Geometry Nodes | "+core.getVertexCount()+" vertices, "+core.getFaceCount()+" faces evaluated");}
            else if(graph==1){meshyViewport.setMeshTint(core.getBaseColor());meshyViewport.setMaterialParameters(core.getMetallic(),core.getRoughness());status.setText("Shading | Shader graph evaluated");}
            else {status.setText("Compositor | "+core.getCompositorNodeCount()+" image operations ready; they run on the next final render");}
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

    private void showPrimitiveDialog() {
        String[] primitives={"Cube","Plane","UV Sphere","Icosphere","Cylinder","Cone","Torus"};
        new android.app.AlertDialog.Builder(activity).setTitle("Add Mesh Primitive")
                .setItems(primitives,(dialog,which)->{
                    checkpointMeshEdit();
                    String primitive=primitives[which];
                    meshyViewport.addPrimitiveObject(primitive);
                    activeEditorMode="Object Mode";if(modeButton!=null)modeButton.setText(activeEditorMode);
                    meshyViewport.setMeshEditMode(false);meshyViewport.setObjectSelectionEnabled(true);
                    status.setText("Modeling | "+primitive+" object added");
                }).setNegativeButton("Cancel",null).show();
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

    private void showObjectRenameDialog() {
        Meshy3DObjectScene.Object3D active=meshyViewport.getObjectScene().getActiveObject();if(active==null)return;
        EditText input=new EditText(activity);input.setSingleLine(true);input.setText(active.name);
        new android.app.AlertDialog.Builder(activity).setTitle("Rename Object").setView(input)
                .setPositiveButton("Rename",(d,w)->{
                    try{meshyViewport.renameActiveObject(input.getText().toString());status.setText("Modeling | Object renamed");}
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
                        else {meshyViewport.selectSceneObject(target.id);status.setText("Modeling | Active object: "+target.name);}
                    }catch(Exception error){new android.app.AlertDialog.Builder(activity).setTitle("Object Relationship")
                            .setMessage(error.getMessage()).setPositiveButton("OK",null).show();}
                }).setNegativeButton("Cancel",null).show();
    }

    private void showCreateCollectionDialog() {
        EditText input=new EditText(activity);input.setSingleLine(true);input.setHint("Collection name");
        new android.app.AlertDialog.Builder(activity).setTitle("New Collection").setView(input)
                .setPositiveButton("Create",(d,w)->{
                    String name=input.getText().toString().trim();if(name.isEmpty())name="Collection";
                    Meshy3DObjectScene.Collection3D collection=meshyViewport.getObjectScene().createCollection(name);
                    Meshy3DObjectScene.Object3D active=meshyViewport.getObjectScene().getActiveObject();
                    if(active!=null)meshyViewport.getObjectScene().moveToCollection(active.id,collection.id);
                    status.setText("Modeling | Collection created: "+collection.name);
                }).setNegativeButton("Cancel",null).show();
    }

    private void showCollectionPickerDialog() {
        Meshy3DObjectScene scene=meshyViewport.getObjectScene();
        java.util.ArrayList<Meshy3DObjectScene.Collection3D> collections=new java.util.ArrayList<>(scene.getCollections());
        String[] names=new String[collections.size()];for(int i=0;i<names.length;i++)names[i]=collections.get(i).name;
        if(names.length==0)return;
        new android.app.AlertDialog.Builder(activity).setTitle("Move Object To Collection")
                .setItems(names,(d,w)->{
                    Meshy3DObjectScene.Object3D active=scene.getActiveObject();
                    if(active!=null){scene.moveToCollection(active.id,collections.get(w).id);
                        status.setText("Modeling | "+active.name+" added to "+collections.get(w).name);}
                }).setNegativeButton("Cancel",null).show();
    }

    private interface AmountAction { void apply(float amount); }
    private void showAmountDialog(String title, float min, float max, AmountAction action) {
        SeekBar bar = new SeekBar(activity); bar.setMax(1000); bar.setProgress(250);
        TextView value = new TextView(activity); value.setText("0.25");
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

    private void saveViewportRender() {
        try {
            int width=Math.max(640,Math.min(1280,meshyViewport.getWidth()*2));
            int height=Math.max(360,Math.min(720,(int)(width*(meshyViewport.getHeight()/(float)Math.max(1,meshyViewport.getWidth())))));
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
        params.bottomMargin = dp(8);
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
            content.addView(dockHeading("SCENE OUTLINER"));
            for(Meshy3DObjectScene.Object3D object:meshyViewport.getObjectScene().getObjects()) {
                TextView objectRow=dockItem(object.name,object.active);
                objectRow.setOnClickListener(v->{
                    meshyViewport.selectSceneObject(object.id);
                    status.setText("Modeling | Active object: "+object.name);
                });
                content.addView(objectRow);
            }
            content.addView(dockHeading("OBJECT OPERATIONS"));
        }
        for (String item : items) content.addView(dockItem(item, false));
        dock.addView(scroll, new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, 0, 1f));
    }

    private void renderWorkspaceLayout(int index) {
        if (workspaceLayoutLayer == null) return;
        workspaceLayoutLayer.removeAllViews();
        viewportFocusMode = false;
        if (meshyViewport != null) {
            meshyViewport.setMeshEditMode(index == 1 && "Edit Mode".equals(activeEditorMode));
            meshyViewport.setObjectSelectionEnabled(index == 1 && "Object Mode".equals(activeEditorMode));
        }
        workspaceToolbar = new LinearLayout(activity);
        workspaceToolbar.setGravity(Gravity.CENTER_VERTICAL);
        workspaceToolbar.setBackgroundColor(0xE02B333D);
        workspaceToolbar.setPadding(dp(6), 0, dp(6), 0);
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
                tools = new String[]{"Select", "Move", "Rotate", "Scale", "Add Cube", "Duplicate",
                        "Duplicate Linked", "Delete Object", "Rename Object", "Apply Rotation & Scale",
                        "Vertex", "Edge", "Face", "Extrude", "Inset", "Bevel", "Loop Cut", "Plane Cut",
                        "Bisect", "Bridge", "Fill", "Subdivide", "Merge", "Dissolve", "Separate"};
                leftTitle = "Modeling Tools";
                leftItems = new String[]{
                        "OBJECT MODE", "Add Primitive", "Add Cube", "Duplicate", "Duplicate Linked", "Delete Object",
                        "Rename Object", "Select Object", "Hide / Show Object", "Apply Location", "Apply Rotation & Scale",
                        "Apply All Transforms", "Clear Location", "Clear Rotation", "Clear Scale",
                        "Set Origin to Geometry", "Set Origin to 3D Cursor",
                        "Parent To", "Clear Parent", "Add Collection", "Move To Collection",
                        "Object Statistics", "EDIT MODE", "Vertex Select", "Edge Select", "Face Select",
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
                        "Subdivision Surface", "Solidify", "Bevel Modifier", "Weighted Normal",
                        "Remesh", "Weld", "Triangulate", "Wireframe"
                };
                rightTitle = "Tool Properties";
                rightItems = new String[]{"Active Tool", "Selection Mode", "Operation Amount", "Offset",
                        "Segments", "Profile", "Loop Cut Count", "Merge Threshold", "Mesh Statistics",
                        "Vertices", "Edges", "Faces", "Normals", "Transform Orientation", "Pivot Point"};
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
        workspaceLeftDock = createDock(leftTitle, index == 2 ? 126 : 142, Gravity.LEFT);
        addDockItems(workspaceLeftDock, leftTitle, leftItems);
        workspaceRightDock = createDock(rightTitle, index == 2 ? 142 : 156, Gravity.RIGHT);
        addDockItems(workspaceRightDock, rightTitle, rightItems);
        if (bottomTitle != null) {
            workspaceBottomDock = new LinearLayout(activity);
            workspaceBottomDock.setOrientation(LinearLayout.VERTICAL);
            workspaceBottomDock.setBackgroundColor(0xF0181D24);
            workspaceBottomDock.addView(dockHeading(bottomTitle));
            for (String item : bottomItems) workspaceBottomDock.addView(dockItem(item, false));
            FrameLayout.LayoutParams bottomParams = new FrameLayout.LayoutParams(
                    LayoutParams.MATCH_PARENT, dp(index == 0 || index == 6 ? 72 : 82), Gravity.BOTTOM);
            bottomParams.leftMargin = dp(154);
            bottomParams.rightMargin = dp(166);
            bottomParams.bottomMargin = dp(8);
            workspaceLayoutLayer.addView(workspaceBottomDock, bottomParams);
        }
        if (index == 2) {
            workspaceLeftDock.setVisibility(sculptEnabled ? View.VISIBLE : View.GONE);
            workspaceRightDock.setVisibility(sculptEnabled ? View.VISIBLE : View.GONE);
        }
        status.setText("Meshy3D  |  " + WORKSPACE_NAMES[index] + " Workspace");
    }

    private void toggleViewportFocus() {
        viewportFocusMode = !viewportFocusMode;
        int visibility = viewportFocusMode ? View.GONE : View.VISIBLE;
        if (workspaceLeftDock != null) workspaceLeftDock.setVisibility(visibility);
        if (workspaceRightDock != null) workspaceRightDock.setVisibility(visibility);
        if (workspaceBottomDock != null) workspaceBottomDock.setVisibility(visibility);
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

    public int getActiveWorkspaceIndex() { return activeWorkspaceIndex; }

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
