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
    private final Handler timelineHandler = new Handler(Looper.getMainLooper());
    private boolean timelinePlaying;
    private long timelineStartMs;

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
        Meshy3DViewport meshyViewport = new Meshy3DViewport(activity, this::toggleViewportFocus);
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
        return view;
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
        for (String item : items) content.addView(dockItem(item, false));
        dock.addView(scroll, new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, 0, 1f));
    }

    private void renderWorkspaceLayout(int index) {
        if (workspaceLayoutLayer == null) return;
        workspaceLayoutLayer.removeAllViews();
        viewportFocusMode = false;
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
                tools = new String[]{"Vertex", "Edge", "Face", "Move", "Rotate", "Scale", "Extrude", "Inset",
                        "Bevel", "Loop Cut", "Knife", "Bisect", "Bridge", "Fill", "Subdivide",
                        "Merge", "Dissolve", "Separate", "Spin", "Screw", "Shear", "Bend"};
                leftTitle = "Modeling Tools";
                leftItems = new String[]{
                        "SELECTION", "Vertex Select", "Edge Select", "Face Select", "Select All", "Select Linked",
                        "Select Loops", "Select Similar", "TRANSFORM", "Move", "Rotate", "Scale", "Mirror",
                        "Shrink / Fatten", "Shear", "Bend", "To Sphere", "Edge Slide", "Vertex Slide",
                        "MESH EDIT", "Extrude Region", "Extrude Along Normals", "Extrude Individual Faces",
                        "Inset Faces", "Bevel Edges", "Loop Cut", "Knife", "Bisect", "Poly Build",
                        "Bridge Edge Loops", "Spin", "Screw", "Fill", "Grid Fill", "Bridge",
                        "TOPOLOGY", "Subdivide", "Un-Subdivide", "Merge by Distance", "Dissolve Edges",
                        "Dissolve Faces", "Limited Dissolve", "Delete Vertices", "Delete Edges",
                        "Delete Faces", "Separate Selection", "Split", "Rip", "Decimate",
                        "MESH CLEANUP", "Recalculate Normals", "Flip Normals", "Merge by Distance",
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
                tools = new String[]{"Select", "Move UV", "Rotate UV", "Scale UV", "Pin", "Unpin", "Pack Islands"};
                leftTitle = "UV Tools";
                leftItems = new String[]{"Select", "Move", "Rotate", "Scale", "Pin", "Unpin", "Pack Islands"};
                rightTitle = "UV Properties";
                rightItems = new String[]{"UV Maps", "Island Transform", "Unwrap Options", "Image Settings"};
                bottomTitle = "UV Editor";
                bottomItems = new String[]{"UV workspace panel", "Image / UV canvas area", "UV island controls"};
                break;
            case 4:
                tools = new String[]{"Draw", "Soften", "Smear", "Clone", "Fill", "Mask"};
                leftTitle = "Paint Tools";
                leftItems = new String[]{"Draw", "Soften", "Smear", "Clone", "Fill", "Mask"};
                rightTitle = "Texture Properties";
                rightItems = new String[]{"Active Texture", "Brush Color", "Brush Radius", "Strength", "Blend Mode", "Texture Slots"};
                bottomTitle = "Image Editor";
                bottomItems = new String[]{"Texture image area", "Image Slots", "Paint Layers"};
                break;
            case 5:
                tools = new String[]{"New Material", "Preview", "Add Node", "Connect", "Frame", "Search"};
                leftTitle = "Material Browser";
                leftItems = new String[]{"Scene Materials", "Material Preview", "Surface", "Principled Shader"};
                rightTitle = "Shader Properties";
                rightItems = new String[]{"Base Color", "Metallic", "Roughness", "Normal", "Alpha", "Emission"};
                bottomTitle = "Shader Editor";
                bottomItems = new String[]{"Material Node Graph", "Add Node", "Connect Node Sockets"};
                break;
            case 6:
                tools = new String[]{"Select", "Move", "Rotate", "Scale", "Insert Keyframe", "Play"};
                leftTitle = "Animation Channels";
                leftItems = new String[]{"Scene", "Object Transforms", "Location", "Rotation", "Scale", "Keyframes"};
                rightTitle = "Keyframe Properties";
                rightItems = new String[]{"Frame", "Interpolation", "Handle Type", "Playback Range"};
                bottomTitle = "Dope Sheet / Timeline";
                bottomItems = new String[]{"Frame 1", "Keyframe Track", "Play / Pause", "Start / End"};
                break;
            case 7:
                tools = new String[]{"Render Image", "Render Animation", "Preview", "Camera View", "Settings"};
                leftTitle = "Render Settings";
                leftItems = new String[]{"Render Engine", "Sampling", "Resolution", "Frame Range", "Output Format"};
                rightTitle = "Output Properties";
                rightItems = new String[]{"Resolution", "Frame Rate", "File Format", "Output Path", "Color Management"};
                bottomTitle = "Render Status";
                bottomItems = new String[]{"Viewport Preview", "Render Queue", "Last Render"};
                break;
            case 8:
                tools = new String[]{"Add Node", "Connect", "Reroute", "Frame", "Search", "Preview"};
                leftTitle = "Compositor Nodes";
                leftItems = new String[]{"Input", "Output", "Color", "Filter", "Distort", "Utilities"};
                rightTitle = "Node Properties";
                rightItems = new String[]{"Selected Node", "Inputs", "Outputs", "Properties", "Preview"};
                bottomTitle = "Compositor Node Editor";
                bottomItems = new String[]{"Node Graph Canvas", "Connect Sockets", "Backdrop Preview"};
                break;
            default:
                tools = new String[]{"Add Node", "Search", "Connect", "Group", "Reroute", "Preview"};
                leftTitle = "Geometry Node Library";
                leftItems = new String[]{"Input", "Geometry", "Mesh", "Curve", "Instances", "Utilities", "Output"};
                rightTitle = "Modifier Properties";
                rightItems = new String[]{"Geometry Nodes Modifier", "Group Inputs", "Attributes", "Output Geometry"};
                bottomTitle = "Geometry Node Editor";
                bottomItems = new String[]{"Node Graph Canvas", "Group Inputs", "Group Outputs"};
                break;
        }
        for (String tool : tools) {
            TextView item = dockItem(tool, false);
            item.setPadding(dp(9), dp(6), dp(9), dp(6));
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
