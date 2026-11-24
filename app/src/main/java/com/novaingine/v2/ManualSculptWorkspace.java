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
import android.widget.FrameLayout;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;

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

    public ManualSculptWorkspace(Activity activity, EngineSurface engine, Runnable onExit) {
        super(activity);
        this.activity = activity;
        this.engine = engine;
        this.onExit = onExit;
        // Keep the GL viewport visible beneath the sculpt controls.
        // An opaque workspace background hides the SurfaceView and makes meshes appear missing.
        setBackgroundColor(Color.TRANSPARENT);

        SculptTouchSurface touch = new SculptTouchSurface();
        addView(touch, new FrameLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        LinearLayout top = new LinearLayout(activity);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(10), dp(7), dp(10), dp(7));
        top.setBackgroundColor(0xEE171D25);

        TextView exit = button("‹  EXIT");
        exit.setOnClickListener(v -> this.onExit.run());
        top.addView(exit, weighted(0, 1));

        TextView title = label("MANUAL SCULPT", 14, Color.WHITE, true);
        title.setGravity(Gravity.CENTER);
        top.addView(title, weighted(0, 2));

        TextView undo = button("UNDO");
        undo.setOnClickListener(v -> engine.undoScene());
        top.addView(undo, weighted(0, 1));

        TextView redo = button("REDO");
        redo.setOnClickListener(v -> engine.redoScene());
        top.addView(redo, weighted(0, 1));

        addView(top, new FrameLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, dp(52), Gravity.TOP));

        LinearLayout bottom = new LinearLayout(activity);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setPadding(dp(10), dp(7), dp(10), dp(8));
        bottom.setBackground(new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xF51B222C, 0xFF10151C}));
        FrameLayout.LayoutParams bottomParams = new FrameLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        addView(bottom, bottomParams);

        final TextView selectedToolIndicator = button("ACTIVE: DRAW  ▴ TOOLS");
        selectedToolIndicator.setTextColor(ACCENT);
        FrameLayout.LayoutParams indicatorParams = new FrameLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, dp(42), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        indicatorParams.bottomMargin = dp(12);
        addView(selectedToolIndicator, indicatorParams);
        final boolean[] drawerOpen = {false};
        final Runnable closeToolDrawer = () -> {
            if (!drawerOpen[0]) return;
            drawerOpen[0] = false;
            bottom.animate().translationY(bottom.getHeight() + dp(8)).setDuration(220).start();
            selectedToolIndicator.setTextColor(ACCENT);
        };
        selectedToolIndicator.setOnClickListener(v -> {
            if (drawerOpen[0]) {
                closeToolDrawer.run();
            } else {
                drawerOpen[0] = true;
                bottom.animate().translationY(0).setDuration(220).start();
                selectedToolIndicator.setText("CLOSE TOOLS  ▾");
                selectedToolIndicator.setTextColor(Color.WHITE);
            }
        });
        bottom.post(() -> bottom.setTranslationY(bottom.getHeight() + dp(8)));

        status = label("Choose a shape, then drag on the mesh to sculpt.", 10, 0xFFB8C4D1, false);
        bottom.addView(status);

        TextView brushSelector = button("SCULPT BRUSHES  ▾");
        brushSelector.setTextColor(ACCENT);
        bottom.addView(brushSelector, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(38)));
        ScrollView brushScroll = new ScrollView(activity);
        LinearLayout brushList = new LinearLayout(activity);
        brushList.setOrientation(LinearLayout.VERTICAL);
        brushList.setPadding(0, dp(4), 0, dp(4));
        brushScroll.addView(brushList);
        bottom.addView(brushScroll, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(190)));
        brushScroll.setVisibility(View.GONE);
        brushSelector.setOnClickListener(v -> {
            boolean opening = brushScroll.getVisibility() != View.VISIBLE;
            brushScroll.setVisibility(opening ? View.VISIBLE : View.GONE);
            brushSelector.setText(opening ? "SCULPT BRUSHES  ▴" : "SCULPT BRUSHES  ▾");
        });
        for (int i = 0; i < BRUSHES.length; i++) {
            final int index = i;
            LinearLayout entry = new LinearLayout(activity);
            entry.setOrientation(LinearLayout.VERTICAL);
            entry.setPadding(dp(9), dp(6), dp(9), dp(6));
            entry.setBackground(background(0xFF26313D));
            TextView name = label(BRUSHES[i], 11, Color.WHITE, true);
            TextView description = label(BRUSH_DESCRIPTIONS[i], 9, 0xFFB8C4D1, false);
            entry.addView(name);
            entry.addView(description);
            LinearLayout.LayoutParams entryParams = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
            entryParams.setMargins(0, 0, 0, dp(4));
            brushList.addView(entry, entryParams);
            brushButtons[i] = name;
            entry.setClickable(true);
            entry.setOnClickListener(v -> {
                selectedBrush = index;
                activeTransformMode = -1;
                gizmoAxis = -1;
                orbitMode = false;
                if (orbitButton != null) { orbitButton.setText("ORBIT VIEW: OFF"); orbitButton.setTextColor(Color.WHITE); }
                sculptEnabled = true;
                brushControls.setVisibility(View.VISIBLE);
                updateBrushButtons();
                applyOptions();
                selectedToolIndicator.setText("ACTIVE: " + BRUSHES[index] + "  ▴ TOOLS");
                brushScroll.setVisibility(View.GONE);
                brushSelector.setText("SCULPT BRUSHES  ▾");
                closeToolDrawer.run();
                status.setText(BRUSH_DESCRIPTIONS[index]);
            });
        }
        updateBrushButtons();

        brushControls = new LinearLayout(activity);
        brushControls.setOrientation(LinearLayout.VERTICAL);
        bottom.addView(brushControls);
        addSlider(brushControls, "BRUSH SIZE", 1, 300, 33, value -> {
            radius = 0.02f + ((value - 1) / 299.0f) * 2.98f;
            applyOptions();
        });
        addSlider(brushControls, "STRENGTH", 0, 100, 40, value -> {
            strength = value / 100.0f;
            applyOptions();
        });
        addSlider(brushControls, "HARDNESS", 0, 100, 50, value -> {
            falloff = value / 100.0f;
            applyOptions();
        });
        addSlider(brushControls, "PRESSURE", 5, 100, 100, value -> {
            pressure = value / 100.0f;
            applyOptions();
        });
        brushControls.setVisibility(View.GONE);

        LinearLayout actions = new LinearLayout(activity);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        bottom.addView(actions, new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, dp(42)));

        HorizontalScrollView shapeScroll = new HorizontalScrollView(activity);
        shapeScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout shapeRow = new LinearLayout(activity);
        shapeRow.setOrientation(LinearLayout.HORIZONTAL);
        shapeScroll.addView(shapeRow);
        TextView shapesToggle = button("SHAPES  ▾");
        shapesToggle.setTextColor(ACCENT);
        bottom.addView(shapesToggle, 0, new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, dp(38)));
        bottom.addView(shapeScroll, 1, new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, dp(44)));
        shapeScroll.setVisibility(View.GONE);
        shapesToggle.setOnClickListener(v -> {
            boolean opening = shapeScroll.getVisibility() != View.VISIBLE;
            shapeScroll.setVisibility(opening ? View.VISIBLE : View.GONE);
            shapesToggle.setText(opening ? "SHAPES  ▴" : "SHAPES  ▾");
        });

        LinearLayout transformRow = new LinearLayout(activity);
        transformRow.setGravity(Gravity.CENTER_VERTICAL);
        bottom.addView(transformRow, 2, new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, dp(42)));
        String[] axes = {"X", "Y", "Z"};
        float[] selectedTransform = engine.getSelectedTransform();
        for (int axis = 0; axis < 3; axis++) {
            final int index = axis;
            EditText field = new EditText(activity);
            positionInputs[axis] = field;
            field.setSingleLine(true);
            field.setTextSize(11);
            field.setTextColor(Color.WHITE);
            field.setHintTextColor(0xFF9AA8B7);
            field.setHint(axes[axis]);
            field.setInputType(android.text.InputType.TYPE_CLASS_NUMBER
                    | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
                    | android.text.InputType.TYPE_NUMBER_FLAG_SIGNED);
            field.setPadding(dp(6), 0, dp(6), 0);
            field.setBackground(background(0xFF26313D));
            LinearLayout.LayoutParams fieldParams = new LinearLayout.LayoutParams(0, dp(36), 1);
            fieldParams.setMargins(0, 0, dp(5), 0);
            transformRow.addView(field, fieldParams);
            if (selectedTransform != null && selectedTransform.length >= 3) {
                field.setText(String.valueOf(selectedTransform[axis + transformComponent * 3]));
            }
        }
        TextView applyPosition = button("SET XYZ");
        transformRow.addView(applyPosition, new LinearLayout.LayoutParams(dp(76), dp(36)));
        applyPosition.setOnClickListener(v -> {
            float[] transform = engine.getSelectedTransform();
            if (transform == null || transform.length < 9) return;
            try {
                for (int axis = 0; axis < 3; axis++) {
                    String value = positionInputs[axis].getText().toString().trim();
                    if (!value.isEmpty()) transform[axis + transformComponent * 3] = Float.parseFloat(value);
                }
                engine.setSelectedTransform(transform);
                status.setText(transformComponent == 0 ? "Selected mesh position updated."
                        : transformComponent == 1 ? "Selected mesh rotation updated."
                        : "Selected mesh scale updated.");
                selectedToolIndicator.setText("ACTIVE: XYZ TRANSFORM  ▴ TOOLS");
                closeToolDrawer.run();
            } catch (NumberFormatException error) {
                status.setText("Enter valid X, Y and Z coordinates.");
            }
        });

        LinearLayout transformModes = new LinearLayout(activity);
        transformModes.setGravity(Gravity.CENTER_VERTICAL);
        bottom.addView(transformModes, 3, new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, dp(38)));
        LinearLayout viewRow = new LinearLayout(activity);
        viewRow.setGravity(Gravity.CENTER_VERTICAL);
        bottom.addView(viewRow, 4, new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, dp(40)));
        orbitButton = button("ORBIT VIEW: OFF");
        orbitButton.setTextColor(ACCENT);
        viewRow.addView(orbitButton, weighted(0, 1));
        orbitButton.setOnClickListener(v -> {
            orbitMode = !orbitMode;
            if (orbitMode) {
                activeTransformMode = -1;
                sculptEnabled = false;
                applyOptions();
                orbitButton.setText("ORBIT VIEW: ON  • DRAG TO ROTATE");
                orbitButton.setTextColor(ACCENT);
                selectedToolIndicator.setText("ORBIT SELECTED OBJECT  ▴ TOOLS");
            } else {
                sculptEnabled = true;
                brushControls.setVisibility(View.VISIBLE);
                applyOptions();
                orbitButton.setText("ORBIT VIEW: OFF");
                orbitButton.setTextColor(Color.WHITE);
                selectedToolIndicator.setText("ACTIVE: " + BRUSHES[selectedBrush] + "  ▴ TOOLS");
            }
        });

        String[] modes = {"MOVE", "ROTATE", "SCALE"};
        for (int modeIndex = 0; modeIndex < modes.length; modeIndex++) {
            final int nativeMode = modeIndex;
            TextView modeButton = button(modes[modeIndex]);
            transformModes.addView(modeButton, weighted(0, 1));
            modeButton.setOnClickListener(v -> {
                engine.setTransformMode(nativeMode);
                activeTransformMode = nativeMode;
                gizmoAxis = -1;
                brushControls.setVisibility(View.GONE);
                orbitMode = false;
                orbitButton.setText("ORBIT VIEW: OFF");
                orbitButton.setTextColor(Color.WHITE);
                transformDragState = engine.getSelectedTransform();
                sculptEnabled = false;
                applyOptions();
                transformComponent = nativeMode;
                float[] values = engine.getSelectedTransform();
                if (values != null && values.length >= 9) {
                    for (int axis = 0; axis < 3; axis++) {
                        positionInputs[axis].setText(String.valueOf(values[axis + transformComponent * 3]));
                    }
                }
                applyPosition.setText(nativeMode == 0 ? "SET POS" : nativeMode == 1 ? "SET ROT" : "SET SCALE");
                selectedToolIndicator.setText("ACTIVE: " + modes[nativeMode] + "  ▴ TOOLS");
                closeToolDrawer.run();
            });
        }

        String[] shapeNames = {"SPHERE", "PLANE", "CUBE", "CYLINDER", "CONE",
                "TORUS", "CAPSULE", "WEDGE", "ROUND SPHERE"};
        int[] shapeDensities = {64, 128, 32, 48, 48, 48, 48, 32, 64};
        for (int shape = 0; shape < shapeNames.length; shape++) {
            final int shapeId = shape;
            TextView shapeButton = button("+ " + shapeNames[shape]);
            LinearLayout.LayoutParams shapeParams = new LinearLayout.LayoutParams(
                    LayoutParams.WRAP_CONTENT, dp(36));
            shapeParams.setMargins(0, 0, dp(6), 0);
            shapeRow.addView(shapeButton, shapeParams);
            shapeButton.setOnClickListener(v -> {
                activeTransformMode = -1;
                orbitMode = false;
                orbitButton.setText("ORBIT VIEW: OFF");
                orbitButton.setTextColor(Color.WHITE);
                sculptEnabled = true;
                applyOptions();
                selectedToolIndicator.setText("ACTIVE: " + shapeNames[shapeId] + "  ▴ TOOLS");
                shapeScroll.setVisibility(View.GONE);
                shapesToggle.setText("SHAPES  ▾");
                createMesh(shapeId, shapeDensities[shapeId]);
                closeToolDrawer.run();
            });
        }

        TextView mirror = button("SYMMETRY: X");
        mirror.setTextColor(ACCENT);
        mirror.setOnClickListener(v -> {
            symmetryAxis = symmetryAxis == 0 ? 1 : symmetryAxis == 1 ? 2 : symmetryAxis == 2 ? -1 : 0;
            String[] names = {"SYMMETRY: X", "SYMMETRY: Y", "SYMMETRY: Z", "SYMMETRY: OFF"};
            mirror.setText(names[symmetryAxis < 0 ? 3 : symmetryAxis]);
            mirror.setTextColor(symmetryAxis < 0 ? Color.WHITE : ACCENT);
            applyOptions();
        });
        actions.addView(mirror, weighted(0, 1));

        TextView subtractButton = button("SUBTRACT: OFF");
        subtractButton.setOnClickListener(v -> {
            subtract = !subtract;
            subtractButton.setText(subtract ? "SUBTRACT: ON" : "SUBTRACT: OFF");
            subtractButton.setTextColor(subtract ? ACCENT : Color.WHITE);
            applyOptions();
        });
        actions.addView(subtractButton, weighted(0, 1));

        TextView mode = button("SCULPT: ON");
        mode.setTextColor(ACCENT);
        mode.setOnClickListener(v -> {
            sculptEnabled = !sculptEnabled;
            mode.setText(sculptEnabled ? "SCULPT: ON" : "SCULPT: OFF");
            mode.setTextColor(sculptEnabled ? ACCENT : Color.WHITE);
            applyOptions();
        });
        actions.addView(mode, weighted(0, 1));

        TextView export = button("OBJ");
        export.setOnClickListener(v -> exportMesh());
        actions.addView(export, weighted(0, 1));

        applyOptions();
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
        return (int)(value * getResources().getDisplayMetrics().density + 0.5f);
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

        private int hitGizmoAxis(float x, float y) {
            float cx = getWidth() * 0.5f, cy = getHeight() * 0.48f, length = dp(76);
            float[][] ends = {{cx + length, cy}, {cx, cy - length}, {cx - length * 0.72f, cy + length * 0.72f}};
            for (int axis = 0; axis < 3; axis++) {
                if (distanceToSegment(x, y, cx, cy, ends[axis][0], ends[axis][1]) < dp(18)) return axis;
            }
            return -1;
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (activeTransformMode != 0) return;
            float cx = getWidth() * 0.5f, cy = getHeight() * 0.48f, length = dp(76);
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            paint.setStrokeWidth(dp(4));
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStyle(Paint.Style.STROKE);
            int[] colors = {0xFFFF5555, 0xFF55DD77, 0xFF5599FF};
            float[][] ends = {{cx + length, cy}, {cx, cy - length}, {cx - length * 0.72f, cy + length * 0.72f}};
            for (int axis = 0; axis < 3; axis++) {
                paint.setColor(colors[axis]);
                paint.setStrokeWidth(gizmoAxis == axis ? dp(7) : dp(4));
                canvas.drawLine(cx, cy, ends[axis][0], ends[axis][1], paint);
                float angle = (float)Math.atan2(ends[axis][1] - cy, ends[axis][0] - cx);
                float head = dp(13);
                Path arrow = new Path();
                arrow.moveTo(ends[axis][0], ends[axis][1]);
                arrow.lineTo(ends[axis][0] - head * (float)Math.cos(angle - 0.55f), ends[axis][1] - head * (float)Math.sin(angle - 0.55f));
                arrow.moveTo(ends[axis][0], ends[axis][1]);
                arrow.lineTo(ends[axis][0] - head * (float)Math.cos(angle + 0.55f), ends[axis][1] - head * (float)Math.sin(angle + 0.55f));
                canvas.drawPath(arrow, paint);
                paint.setStyle(Paint.Style.FILL);
                canvas.drawText(axis == 0 ? "X" : axis == 1 ? "Y" : "Z", ends[axis][0] + dp(5), ends[axis][1] - dp(5), paint);
                paint.setStyle(Paint.Style.STROKE);
            }
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
                        lastX = event.getX(); lastY = event.getY();
                        return true;
                    }
                } else if (gizmoAxis >= 0 && action == MotionEvent.ACTION_MOVE) {
                    float dx = event.getX() - lastX, dy = event.getY() - lastY;
                    if (transformDragState != null && transformDragState.length >= 9) {
                        float delta = gizmoAxis == 0 ? dx * 0.008f : gizmoAxis == 1 ? -dy * 0.008f : (dx - dy) * 0.00565f;
                        transformDragState[gizmoAxis] += delta;
                        engine.setSelectedTransform(transformDragState);
                        positionInputs[gizmoAxis].setText(String.valueOf(transformDragState[gizmoAxis]));
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
                        engine.setSelectedTransform(transform);
                        for (int axis = 0; axis < 3; axis++) {
                            positionInputs[axis].setText(String.valueOf(
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
