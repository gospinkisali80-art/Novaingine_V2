package com.novaingine.v2;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Built-in touchscreen control surface for Meshy3D. It contains a virtual
 * mouse drag pad, viewport navigation controls and Blender-style shortcut keys.
 * No external keyboard/mouse utility or service is required.
 */
final class Meshy3DPhoneControls extends LinearLayout {
    interface Actions {
        void undo();
        void redo();
        void toggleMode();
    }

    private final Meshy3DViewport viewport;
    private final Actions actions;
    private final int dp;
    private LinearLayout body;
    private TextView modeLabel;
    private int mouseMode = 0; // 0 orbit, 1 pan, 2 select-drag
    private boolean expanded;

    Meshy3DPhoneControls(Context context, Meshy3DViewport viewport, Actions actions) {
        super(context);
        this.viewport = viewport;
        this.actions = actions;
        dp = Math.max(1, (int)(getResources().getDisplayMetrics().density + .5f));
        setOrientation(VERTICAL);
        setGravity(Gravity.END);
        setBackgroundColor(0xE91C222B);
        setPadding(6*dp, 5*dp, 6*dp, 5*dp);

        Button toggle = button("⌨  Viewport Controls", () -> {
            expanded = !expanded;
            body.setVisibility(expanded ? VISIBLE : GONE);
        });
        addView(toggle, new LayoutParams(LayoutParams.WRAP_CONTENT, 38*dp));

        body = new LinearLayout(context);
        body.setOrientation(VERTICAL);
        body.setVisibility(GONE);
        addView(body, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));

        LinearLayout modes = row();
        add(modes, button("Orbit", () -> setMouseMode(0)));
        add(modes, button("Pan", () -> setMouseMode(1)));
        add(modes, button("Select", () -> setMouseMode(2)));
        body.addView(modes);

        modeLabel = text("Virtual mouse: Orbit-drag");
        body.addView(modeLabel);

        MousePad pad = new MousePad(context);
        LayoutParams padParams = new LayoutParams(260*dp, 74*dp);
        padParams.topMargin = 4*dp;
        body.addView(pad, padParams);

        LinearLayout nav = row();
        add(nav, button("−", () -> viewport.zoomBy(0.82f)));
        add(nav, button("+", () -> viewport.zoomBy(1.22f)));
        add(nav, button("Frame", viewport::frameSelectedObjects));
        add(nav, button("All", viewport::frameAllObjects));
        add(nav, button("Reset", viewport::resetView));
        body.addView(nav);

        LinearLayout views = row();
        add(views, button("Front", () -> viewport.setViewPreset(0)));
        add(views, button("Back", () -> viewport.setViewPreset(1)));
        add(views, button("Right", () -> viewport.setViewPreset(2)));
        add(views, button("Left", () -> viewport.setViewPreset(3)));
        add(views, button("Top", () -> viewport.setViewPreset(4)));
        add(views, button("Bottom", () -> viewport.setViewPreset(5)));
        body.addView(views);

        LinearLayout tools = row();
        add(tools, button("G Move", () -> viewport.setObjectTransformTool(0)));
        add(tools, button("R Rotate", () -> viewport.setObjectTransformTool(1)));
        add(tools, button("S Scale", () -> viewport.setObjectTransformTool(2)));
        add(tools, button("Tab Mode", actions::toggleMode));
        body.addView(tools);

        LinearLayout keys = row();
        String[] shortcuts = {"G", "R", "S", "X", "Y", "Z", "Tab", "Esc", "Ctrl+Z", "Ctrl+Shift+Z"};
        for (String key : shortcuts) add(keys, button(key, () -> pressKey(key)));
        body.addView(keys);

        LinearLayout mouse = row();
        add(mouse, button("L Click", () -> viewport.virtualLeftClick()));
        add(mouse, button("R Click", () -> showContextMenu()));
        add(mouse, button("M Click", () -> setMouseMode(mouseMode == 1 ? 0 : 1)));
        body.addView(mouse);

        LinearLayout numeric = row();
        EditText value = new EditText(context);
        value.setSingleLine(true);
        value.setText("1.0");
        value.setTextColor(Color.WHITE);
        value.setHintTextColor(0xFF9BA6B3);
        value.setHint("Numeric value");
        value.setTextSize(12);
        value.setSelectAllOnFocus(true);
        value.setBackgroundColor(0xFF303945);
        numeric.addView(value, new LayoutParams(88*dp, 38*dp));
        add(numeric, button("Apply", () -> applyNumeric(value, -1)));
        add(numeric, button("Clear Axis", () -> {
            viewport.setKeyboardAxisConstraint(-1);
            Toast.makeText(getContext(), "Axis constraint cleared", Toast.LENGTH_SHORT).show();
        }));
        body.addView(numeric);
        addLetterRow(value, "QWERTYU");
        addLetterRow(value, "IOPASDF");
        addLetterRow(value, "GHJKLZX");
        addLetterRow(value, "CVBNM");
        LinearLayout finalKeys = row();
        add(finalKeys, button("Space", () -> value.append(" ")));
        add(finalKeys, button("⌫", () -> {
            int end = value.getSelectionStart();
            if (end > 0) value.getText().delete(end-1, end);
        }));
        body.addView(finalKeys);
    }

    private void setMouseMode(int mode) {
        mouseMode = mode;
        String label = mode == 0 ? "Virtual mouse: Orbit-drag" :
                mode == 1 ? "Virtual mouse: Pan-drag" : "Virtual mouse: Select by tapping viewport";
        modeLabel.setText(label);
    }

    private void pressKey(String key) {
        switch (key) {
            case "G": viewport.setObjectTransformTool(0); break;
            case "R": viewport.setObjectTransformTool(1); break;
            case "S": viewport.setObjectTransformTool(2); break;
            case "Tab": actions.toggleMode(); break;
            case "Esc": viewport.setObjectTransformTool(-1); break;
            case "Ctrl+Z": actions.undo(); break;
            case "Ctrl+Shift+Z": actions.redo(); break;
            case "X": viewport.setKeyboardAxisConstraint(0); modeLabel.setText("Virtual keyboard: X axis locked"); break;
            case "Y": viewport.setKeyboardAxisConstraint(1); modeLabel.setText("Virtual keyboard: Y axis locked"); break;
            case "Z": viewport.setKeyboardAxisConstraint(2); modeLabel.setText("Virtual keyboard: Z axis locked"); break;
        }
    }

    private void addLetterRow(EditText target, String letters) {
        LinearLayout keys = row();
        for (int i = 0; i < letters.length(); i++) {
            final String letter = String.valueOf(letters.charAt(i));
            add(keys, button(letter, () -> target.append(letter.toLowerCase())));
        }
        body.addView(keys);
    }

    private void applyNumeric(EditText field, int axis) {
        try {
            float amount = Float.parseFloat(field.getText().toString().trim());
            if (!Float.isFinite(amount)) throw new NumberFormatException();
            viewport.applyNumericTransform(axis, amount);
        } catch (RuntimeException error) {
            Toast.makeText(getContext(), "Enter a valid number.", Toast.LENGTH_SHORT).show();
        }
    }

    private void showContextMenu() {
        final String[] options = {"Frame Selected", "Frame All", "Move", "Rotate", "Scale", "Undo", "Redo"};
        new AlertDialog.Builder(getContext()).setTitle("Viewport context menu")
                .setItems(options, (dialog, which) -> {
                    switch (which) {
                        case 0: viewport.frameSelectedObjects(); break;
                        case 1: viewport.frameAllObjects(); break;
                        case 2: viewport.setObjectTransformTool(0); break;
                        case 3: viewport.setObjectTransformTool(1); break;
                        case 4: viewport.setObjectTransformTool(2); break;
                        case 5: actions.undo(); break;
                        case 6: actions.redo(); break;
                    }
                }).show();
    }

    private Button button(String label, Runnable action) {
        Button b = new Button(getContext());
        b.setText(label);
        b.setTextSize(10);
        b.setAllCaps(false);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setPadding(5*dp, 0, 5*dp, 0);
        b.setTextColor(Color.WHITE);
        b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF354252));
        b.setOnClickListener(v -> action.run());
        return b;
    }

    private TextView text(String value) {
        TextView t = new TextView(getContext());
        t.setText(value);
        t.setTextColor(0xFFE5EAF0);
        t.setTextSize(10);
        t.setPadding(3*dp, 4*dp, 3*dp, 2*dp);
        return t;
    }

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(getContext());
        r.setOrientation(HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        return r;
    }

    private void add(LinearLayout row, View child) {
        LayoutParams p = new LayoutParams(LayoutParams.WRAP_CONTENT, 36*dp);
        p.rightMargin = 2*dp;
        row.addView(child, p);
    }

    private final class MousePad extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float lastX, lastY;
        private boolean dragging;

        MousePad(Context context) {
            super(context);
            setBackgroundColor(0xFF111820);
            setContentDescription("Virtual mouse drag pad. Drag to orbit or pan according to selected mode.");
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            paint.setColor(0xFF344253);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1.5f*dp);
            canvas.drawRoundRect(new RectF(2*dp, 2*dp, getWidth()-2*dp, getHeight()-2*dp), 8*dp, 8*dp, paint);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.WHITE);
            paint.setTextSize(12*dp);
            String label = mouseMode == 0 ? "DRAG = ORBIT" : mouseMode == 1 ? "DRAG = PAN" : "TAP VIEWPORT TO SELECT";
            canvas.drawText(label, 12*dp, getHeight()/2f + 4*dp, paint);
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    lastX = event.getX(); lastY = event.getY(); dragging = true; return true;
                case MotionEvent.ACTION_MOVE:
                    if (!dragging) return true;
                    float dx = event.getX()-lastX, dy = event.getY()-lastY;
                    if (mouseMode == 0) viewport.orbitBy(dx, dy);
                    else if (mouseMode == 1) viewport.panByPixels(dx, dy);
                    lastX = event.getX(); lastY = event.getY(); return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    dragging = false; performClick(); return true;
            }
            return true;
        }

        @Override public boolean performClick() { super.performClick(); return true; }
    }
}
