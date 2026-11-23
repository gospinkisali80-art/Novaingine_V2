package com.novaingine.v2;

import android.content.Context;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.SeekBar;
import android.widget.TextView;

public final class EditorToolbar extends LinearLayout {

    public interface ViewportActions {
        void onToggleMaximize();
        void onOpenToolsDrawer();
        void onTogglePlay();
    }

    private static final int COLOR_BACKGROUND = 0xE61B1B1B;
    private static final int COLOR_BUTTON = 0xFF303030;
    private static final int COLOR_SELECTED = 0xFF555555;
    private static final int COLOR_TEXT = 0xFFE5E5E5;
    private static final int COLOR_ACCENT = 0xFFFF9A00;

    private final EngineSurface engineSurface;
    private final ViewportActions actions;

    private TextView worldSpaceButton;
    private TextView snapButton;

    private boolean worldSpace = true;
    private boolean snapEnabled = true;

    private float gridSnap = 10.0f;
    private float rotationSnap = 10.0f;
    private float scaleSnap = 0.25f;

    private int transformMode = 0;
    private boolean physicsEnabled = false;

    private final TextView[] transformButtons = new TextView[4];

    public EditorToolbar(
            Context context,
            EngineSurface surface,
            ViewportActions actions) {

        super(context);

        this.engineSurface = surface;
        this.actions = actions;

        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        setPadding(5, 5, 5, 5);
        setBackground(createBackground(COLOR_BACKGROUND, 10));

        addButton(context, this, "▶ PLAY", "Enter live play mode",
                () -> this.actions.onTogglePlay());
        addButton(context, this, "MORE  ☰", "Open the editor sidebar",
                () -> this.actions.onOpenToolsDrawer());
    }

    private GradientDrawable createBackground(
            int color,
            int radius) {

        GradientDrawable drawable = new GradientDrawable();

        drawable.setColor(color);
        drawable.setCornerRadius(radius * getResources()
                .getDisplayMetrics().density);

        drawable.setStroke(
                1,
                0xFF444444
        );

        return drawable;
    }

    private TextView createButton(
            Context context,
            String text) {

        TextView button = new TextView(context);

        button.setText(text);
        button.setTextColor(COLOR_TEXT);
        button.setTextSize(11);
        button.setTypeface(Typeface.DEFAULT, Typeface.NORMAL);
        button.setGravity(Gravity.CENTER);
        button.setSingleLine(true);
        button.setPadding(10, 8, 10, 8);

        button.setBackground(
                createBackground(COLOR_BUTTON, 5)
        );

        return button;
    }

    private void addButton(
            Context context,
            LinearLayout parent,
            String text,
            String description,
            Runnable action) {

        TextView button = createButton(context, text);

        button.setContentDescription(description);

        LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(
                        LayoutParams.WRAP_CONTENT,
                        LayoutParams.WRAP_CONTENT
                );

        params.setMargins(2, 0, 2, 0);

        parent.addView(button, params);

        button.setOnClickListener(view -> action.run());
    }

    private void addSeparator(
            Context context,
            LinearLayout parent) {

        View separator = new View(context);

        separator.setBackgroundColor(0xFF555555);

        LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(
                        1,
                        27
                );

        params.setMargins(6, 0, 6, 0);

        parent.addView(separator, params);
    }

    private void buildLeftControls(
            Context context,
            LinearLayout parent) {

        addButton(
                context,
                parent,
                "☰",
                "Viewport options",
                actions::onOpenToolsDrawer
        );

        addButton(
                context,
                parent,
                "Perspective ▾",
                "Camera perspective",
                () -> showPerspectiveMenu(
                        context,
                        parent.getChildAt(1)
                )
        );

        addButton(
                context,
                parent,
                "Lit ▾",
                "Rendering mode",
                () -> showLitMenu(
                        context,
                        parent.getChildAt(2)
                )
        );

        addButton(
                context,
                parent,
                "Show ▾",
                "Viewport show settings",
                () -> showVisibilityMenu(
                        context,
                        parent.getChildAt(3)
                )
        );
    }

    private void buildTransformControls(
            Context context,
            LinearLayout parent) {

        String[] names = {
                "Select",
                "Move",
                "Rotate",
                "Scale"
        };

        for (int i = 0; i < names.length; i++) {

            final int mode = i;

            TextView button = createButton(
                    context,
                    names[i]
            );

            transformButtons[i] = button;

            LinearLayout.LayoutParams params =
                    new LinearLayout.LayoutParams(
                            LayoutParams.WRAP_CONTENT,
                            LayoutParams.WRAP_CONTENT
                    );

            params.setMargins(2, 0, 2, 0);

            parent.addView(button, params);

            button.setOnClickListener(view -> {

                transformMode = mode;

                engineSurface.setTransformMode(mode);

                updateTransformButtons();
            });
        }

        updateTransformButtons();

        worldSpaceButton = createButton(
                context,
                "World"
        );

        parent.addView(
                worldSpaceButton,
                new LayoutParams(
                        LayoutParams.WRAP_CONTENT,
                        LayoutParams.WRAP_CONTENT
                )
        );

        worldSpaceButton.setOnClickListener(view -> {

            worldSpace = !worldSpace;

            engineSurface.setWorldSpace(worldSpace);

            worldSpaceButton.setText(
                    worldSpace ? "World" : "Local"
            );
        });
    }

    private void updateTransformButtons() {

        for (int i = 0; i < transformButtons.length; i++) {

            transformButtons[i].setBackground(
                    createBackground(
                            i == transformMode
                                    ? COLOR_SELECTED
                                    : COLOR_BUTTON,
                            5
                    )
            );

            transformButtons[i].setTextColor(
                    i == transformMode
                            ? COLOR_ACCENT
                            : COLOR_TEXT
            );
        }
    }

    private void buildSnapControls(
            Context context,
            LinearLayout parent) {

        snapButton = createButton(
                context,
                "Snap: ON"
        );

        parent.addView(
                snapButton,
                new LayoutParams(
                        LayoutParams.WRAP_CONTENT,
                        LayoutParams.WRAP_CONTENT
                )
        );

        snapButton.setOnClickListener(view -> {

            snapEnabled = !snapEnabled;

            updateSnap();
        });

        addButton(
                context,
                parent,
                "Grid 10",
                "Grid snapping value",
                () -> showGridSnapMenu(context, parent.getChildAt(parent.getChildCount() - 2))
        );

        addButton(
                context,
                parent,
                "Rot 10°",
                "Rotation snapping value",
                () -> showRotationSnapMenu(context, parent.getChildAt(parent.getChildCount() - 1))
        );

        addButton(
                context,
                parent,
                "Scale 0.25",
                "Scale snapping value",
                () -> showScaleSnapMenu(context, parent.getChildAt(parent.getChildCount() - 1))
        );
    }

    private void updateSnap() {

        snapButton.setText(
                snapEnabled ? "Snap: ON" : "Snap: OFF"
        );

        snapButton.setTextColor(
                snapEnabled ? COLOR_ACCENT : COLOR_TEXT
        );

        engineSurface.setSnap(
                snapEnabled,
                gridSnap,
                rotationSnap,
                scaleSnap
        );
    }

    private void buildCameraControls(
            Context context,
            LinearLayout parent) {

        TextView physicsButton = createButton(context, "Physics: OFF");
        physicsButton.setContentDescription(
                "Toggle first-person physics, gravity and collision"
        );
        parent.addView(
                physicsButton,
                new LayoutParams(
                        LayoutParams.WRAP_CONTENT,
                        LayoutParams.WRAP_CONTENT
                )
        );
        physicsButton.setOnClickListener(view -> {
            physicsEnabled = !physicsEnabled;
            engineSurface.setPhysicsEnabled(physicsEnabled);
            physicsButton.setText(
                    physicsEnabled ? "Physics: ON" : "Physics: OFF"
            );
            physicsButton.setTextColor(
                    physicsEnabled ? COLOR_ACCENT : COLOR_TEXT
            );
        });

        TextView label = new TextView(context);

        label.setText("Speed");
        label.setTextColor(COLOR_TEXT);
        label.setTextSize(11);
        label.setGravity(Gravity.CENTER);

        parent.addView(
                label,
                new LayoutParams(
                        LayoutParams.WRAP_CONTENT,
                        LayoutParams.WRAP_CONTENT
                )
        );

        SeekBar speed = new SeekBar(context);

        speed.setMax(100);
        speed.setProgress(30);

        speed.setContentDescription("Camera speed");

        LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(
                        95,
                        LayoutParams.WRAP_CONTENT
                );

        params.setMargins(4, 0, 4, 0);

        parent.addView(speed, params);

        speed.setOnSeekBarChangeListener(
                new SeekBar.OnSeekBarChangeListener() {

                    @Override
                    public void onProgressChanged(
                            SeekBar seekBar,
                            int progress,
                            boolean fromUser) {

                        float value =
                                0.1f + (progress / 100.0f) * 9.9f;

                        engineSurface.setCameraSpeed(value);
                    }

                    @Override
                    public void onStartTrackingTouch(
                            SeekBar seekBar) {
                    }

                    @Override
                    public void onStopTrackingTouch(
                            SeekBar seekBar) {
                    }
                }
        );
    }

    private void addMenuItem(
            PopupMenu menu,
            String title,
            Runnable action) {

        menu.getMenu()
                .add(title)
                .setOnMenuItemClickListener(item -> {
                    action.run();
                    return true;
                });
    }

    private void showOptionsMenu(
            Context context,
            View anchor) {

        PopupMenu menu = new PopupMenu(context, anchor);

        addMenuItem(menu, "Reset Scene", engineSurface::resetScene);

        addMenuItem(menu, "Reset Camera", () ->
                engineSurface.setCameraView(0)
        );

        addMenuItem(menu, "Show Statistics", () ->
                engineSurface.setShowFlags(16)
        );

        menu.show();
    }

    private void showPerspectiveMenu(
            Context context,
            View anchor) {

        PopupMenu menu = new PopupMenu(context, anchor);

        String[] names = {
                "Perspective",
                "Top",
                "Bottom",
                "Left",
                "Right",
                "Front",
                "Back"
        };

        for (int i = 0; i < names.length; i++) {

            final int view = i;

            addMenuItem(menu, names[i], () ->
                    engineSurface.setCameraView(view)
            );
        }

        menu.show();
    }

    private void showLitMenu(
            Context context,
            View anchor) {

        PopupMenu menu = new PopupMenu(context, anchor);

        String[] names = {
                "Lit",
                "Unlit",
                "Wireframe",
                "Detail Lighting",
                "Lighting Only",
                "Normals",
                "Roughness",
                "Metallic"
        };

        for (int i = 0; i < names.length; i++) {

            final int mode = i;

            addMenuItem(menu, names[i], () ->
                    engineSurface.setViewMode(mode)
            );
        }

        menu.show();
    }

    private void showVisibilityMenu(
            Context context,
            View anchor) {

        PopupMenu menu = new PopupMenu(context, anchor);

        addMenuItem(menu, "Show All", () ->
                engineSurface.setShowFlags(63)
        );

        addMenuItem(menu, "Hide Grid", () ->
                engineSurface.setShowFlags(62)
        );

        addMenuItem(menu, "Hide Shadows", () ->
                engineSurface.setShowFlags(61)
        );

        addMenuItem(menu, "Hide Sky", () ->
                engineSurface.setShowFlags(59)
        );

        addMenuItem(menu, "Hide Characters", () ->
                engineSurface.setShowFlags(55)
        );

        addMenuItem(menu, "Show Statistics", () ->
                engineSurface.setShowFlags(16)
        );

        addMenuItem(menu, "Exposure", () -> showExposureDialog(context));

        menu.show();
    }

    private void showExposureDialog(Context context) {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(24, 12, 24, 8);

        TextView label = new TextView(context);
        label.setTextColor(COLOR_TEXT);
        label.setText("Exposure: 1.00");
        layout.addView(label);

        SeekBar slider = new SeekBar(context);
        slider.setMax(375);
        slider.setProgress(75);
        layout.addView(slider);

        slider.setOnSeekBarChangeListener(
                new SeekBar.OnSeekBarChangeListener() {
                    @Override
                    public void onProgressChanged(
                            SeekBar bar, int progress, boolean fromUser) {
                        float value = 0.25f + progress * 0.01f;
                        label.setText(String.format(
                                java.util.Locale.US,
                                "Exposure: %.2f", value
                        ));
                        if (fromUser) engineSurface.setExposure(value);
                    }

                    @Override
                    public void onStartTrackingTouch(SeekBar bar) {}

                    @Override
                    public void onStopTrackingTouch(SeekBar bar) {}
                }
        );

        new AlertDialog.Builder(context)
                .setTitle("Viewport Exposure")
                .setView(layout)
                .setPositiveButton("Done", null)
                .setNegativeButton("Reset", (dialog, which) ->
                        engineSurface.setExposure(1.0f))
                .show();
    }

    private void showGridSnapMenu(
            Context context,
            View anchor) {

        PopupMenu menu = new PopupMenu(context, anchor);

        float[] values = {1, 5, 10, 25, 50, 100};

        for (float value : values) {

            addMenuItem(menu, "Grid " + (int) value, () -> {

                gridSnap = value;
                updateSnap();
            });
        }

        menu.show();
    }

    private void showRotationSnapMenu(
            Context context,
            View anchor) {

        PopupMenu menu = new PopupMenu(context, anchor);

        float[] values = {1, 5, 10, 15, 30, 45, 90};

        for (float value : values) {

            addMenuItem(menu, "Rotation " + (int) value + "°", () -> {

                rotationSnap = value;
                updateSnap();
            });
        }

        menu.show();
    }

    private void showScaleSnapMenu(
            Context context,
            View anchor) {

        PopupMenu menu = new PopupMenu(context, anchor);

        float[] values = {0.1f, 0.25f, 0.5f, 1.0f};

        for (float value : values) {

            addMenuItem(menu, "Scale " + value, () -> {

                scaleSnap = value;
                updateSnap();
            });
        }

        menu.show();
    }
}
