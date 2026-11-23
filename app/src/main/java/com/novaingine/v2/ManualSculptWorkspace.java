package com.novaingine.v2;

import android.app.Activity;
import android.graphics.Color;
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
            "Crease", "Mask", "Clay", "Scrape", "Layer", "Unmask"
    };
    private static final int BG = 0xFF15191F;
    private static final int PANEL = 0xF0202731;
    private static final int ACCENT = 0xFFFFA52B;

    private final Activity activity;
    private final EngineSurface engine;
    private final Runnable onExit;
    private final TextView status;
    private final TextView[] brushButtons = new TextView[BRUSHES.length];
    private int selectedBrush;
    private float radius = 0.18f;
    private float strength = 0.40f;
    private float falloff = 0.5f;
    private float pressure = 1.0f;
    private int symmetryAxis = 0;
    private boolean subtract;
    private boolean sculptEnabled = true;
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

        HorizontalScrollView brushScroll = new HorizontalScrollView(activity);
        brushScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout brushRow = new LinearLayout(activity);
        brushRow.setOrientation(LinearLayout.HORIZONTAL);
        brushRow.setPadding(0, dp(7), 0, dp(7));
        brushScroll.addView(brushRow);
        bottom.addView(brushScroll, new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, dp(49)));

        for (int i = 0; i < BRUSHES.length; i++) {
            final int index = i;
            TextView b = button(BRUSHES[i]);
            brushButtons[i] = b;
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                    LayoutParams.WRAP_CONTENT, dp(36));
            p.setMargins(0, 0, dp(5), 0);
            brushRow.addView(b, p);
            b.setOnClickListener(v -> {
                selectedBrush = index;
                activeTransformMode = -1;
                sculptEnabled = true;
                updateBrushButtons();
                applyOptions();
                selectedToolIndicator.setText("ACTIVE: " + BRUSHES[index] + "  ▴ TOOLS");
                closeToolDrawer.run();
            });
        }
        updateBrushButtons();

        addSlider(bottom, "BRUSH SIZE", 1, 300, 33, value -> {
            radius = 0.02f + ((value - 1) / 299.0f) * 2.98f;
            applyOptions();
        });
        addSlider(bottom, "STRENGTH", 0, 100, 40, value -> {
            strength = value / 100.0f;
            applyOptions();
        });
        addSlider(bottom, "HARDNESS", 0, 100, 50, value -> {
            falloff = value / 100.0f;
            applyOptions();
        });
        addSlider(bottom, "PRESSURE", 5, 100, 100, value -> {
            pressure = value / 100.0f;
            applyOptions();
        });

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
        bottom.addView(shapeScroll, 0, new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, dp(44)));

        LinearLayout transformRow = new LinearLayout(activity);
        transformRow.setGravity(Gravity.CENTER_VERTICAL);
        bottom.addView(transformRow, 1, new LinearLayout.LayoutParams(
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
        bottom.addView(transformModes, 2, new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, dp(38)));
        String[] modes = {"MOVE", "ROTATE", "SCALE"};
        for (int modeIndex = 0; modeIndex < modes.length; modeIndex++) {
            final int nativeMode = modeIndex;
            TextView modeButton = button(modes[modeIndex]);
            transformModes.addView(modeButton, weighted(0, 1));
            modeButton.setOnClickListener(v -> {
                engine.setTransformMode(nativeMode);
                activeTransformMode = nativeMode;
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
                sculptEnabled = true;
                applyOptions();
                selectedToolIndicator.setText("ACTIVE: " + shapeNames[shapeId] + "  ▴ TOOLS");
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
            brushButtons[i].setBackground(background(
                    i == selectedBrush ? 0xFF65451F : 0xFF303B48));
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

        private float distance(MotionEvent e) {
            if (e.getPointerCount() < 2) return 0;
            float dx = e.getX(0) - e.getX(1);
            float dy = e.getY(0) - e.getY(1);
            return (float)Math.sqrt(dx * dx + dy * dy);
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            int action = event.getActionMasked();
            engine.handleSculptTouch(event, this);
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
