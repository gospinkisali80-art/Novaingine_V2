package com.novaingine.v2;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
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
    private float strength = 0.12f;
    private float falloff = 0.5f;
    private float pressure = 1.0f;
    private boolean symmetry = true;
    private boolean sculptEnabled = true;

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

        status = label("Create a sphere or plane, then drag on the model to sculpt.", 10, 0xFFB8C4D1, false);
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
                updateBrushButtons();
                applyOptions();
            });
        }
        updateBrushButtons();

        addSlider(bottom, "RADIUS", 1, 200, 18, value -> {
            radius = 0.01f + (value / 200.0f) * 1.99f;
            applyOptions();
        });
        addSlider(bottom, "STRENGTH", 1, 100, 24, value -> {
            strength = 0.001f + (value / 100.0f) * 0.499f;
            applyOptions();
        });
        addSlider(bottom, "FALLOFF", 0, 100, 50, value -> {
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

        TextView sphere = button("+ SPHERE");
        sphere.setOnClickListener(v -> createMesh(0, 64));
        actions.addView(sphere, weighted(0, 1));

        TextView plane = button("+ PLANE");
        plane.setOnClickListener(v -> createMesh(1, 128));
        actions.addView(plane, weighted(0, 1));

        TextView mirror = button("X: ON");
        mirror.setTextColor(ACCENT);
        mirror.setOnClickListener(v -> {
            symmetry = !symmetry;
            mirror.setText(symmetry ? "X: ON" : "X: OFF");
            mirror.setTextColor(symmetry ? ACCENT : Color.WHITE);
            applyOptions();
        });
        actions.addView(mirror, weighted(0, 1));

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
                status.setText(shape == 0
                        ? "Sculpt sphere created and selected. Drag to sculpt."
                        : "Dense sculpt plane created and selected. Drag to sculpt.");
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
                falloff, symmetry, pressure);
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
            engine.handleSculptTouch(event, 0);
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
                engine.queueEvent(() -> NativeBridge.nativeTouch(dx, dy));
                lastX = event.getX();
                lastY = event.getY();
            }
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL
                    || action == MotionEvent.ACTION_POINTER_UP) lastDistance = 0;
            return true;
        }
    }
}
