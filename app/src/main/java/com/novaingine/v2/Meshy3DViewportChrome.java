package com.novaingine.v2;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Compact Blender-inspired viewport chrome designed for landscape Android.
 * All controls route to Meshy3D's existing viewport/workspace APIs.
 */
final class Meshy3DViewportChrome extends FrameLayout {
    interface Actions {
        void select();
        void cursor();
        void transform(int mode);
        void manualScale();
        void sculpt();
        void measure();
        void add();
        void undo();
        void redo();
        void toggleMode();
        void keyboard();
        void more();
        void settings();
        void snap();
        void escape();
    }

    private final Meshy3DViewport viewport;
    private final Actions actions;
    private final float scale;
    private final LinearLayout navigationPanel;
    private final ScrollView navigationScroll;
    private final LinearLayout leftTools;
    private final ScrollView leftToolScroll;
    private final LinearLayout bottomTools;
    private final HorizontalScrollView bottomScroll;
    private final LinearLayout transformTools;
    private boolean navigationVisible = true;
    private boolean moreMode;
    private int activeTool = 0;

    Meshy3DViewportChrome(Context context, Meshy3DViewport viewport, Actions actions, float uiScale) {
        super(context);
        this.viewport = viewport;
        this.actions = actions;
        this.scale = Math.max(.5f, Math.min(1f, uiScale));
        setClipChildren(false);
        setClipToPadding(false);
        setBackgroundColor(Color.TRANSPARENT);

        // Left-hand vertical tool rail.
        leftToolScroll = new ScrollView(context);
        leftToolScroll.setFillViewport(false);
        leftToolScroll.setVerticalScrollBarEnabled(false);
        leftTools = new LinearLayout(context);
        leftTools.setOrientation(LinearLayout.VERTICAL);
        leftTools.setPadding(dp(3), dp(3), dp(3), dp(3));
        leftTools.setBackground(panelBackground(0xE81A2028, 8f));
        leftToolScroll.addView(leftTools);
        addTool(leftTools, "▣", "Select", 0, () -> actions.select());
        addTool(leftTools, "⊕", "3D Cursor", 1, () -> actions.cursor());
        addTool(leftTools, "✥", "Move", 2, () -> setTransform(0));
        addTool(leftTools, "⟳", "Rotate", 3, () -> setTransform(1));
        addTool(leftTools, "□", "Scale", 4, () -> setTransform(2));
        addTool(leftTools, "♧", "Sculpt", 5, () -> actions.sculpt());
        addTool(leftTools, "∠", "Measure", 6, () -> actions.measure());
        addTool(leftTools, "⊞", "Add Object", 7, () -> actions.add());
        LayoutParams leftParams = new LayoutParams(dp(42), dp(260), Gravity.LEFT | Gravity.TOP);
        leftParams.leftMargin = dp(6);
        leftParams.topMargin = dp(12);
        addView(leftToolScroll, leftParams);

        // Floating transform strip above the bottom shortcut bar.
        transformTools = new LinearLayout(context);
        transformTools.setOrientation(LinearLayout.VERTICAL);
        transformTools.setPadding(dp(5), dp(3), dp(5), dp(4));
        transformTools.setBackground(panelBackground(0xE91A2028, 8f));
        TextView transformTitle = label("TRANSFORM TOOLS", true);
        transformTools.addView(transformTitle, new LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, dp(17)));
        LinearLayout transformRow = row(context);
        addActionButton(transformRow, "✥  Move", "Move tool", () -> setTransform(0), true);
        addActionButton(transformRow, "⟳  Rotate", "Rotate tool", () -> setTransform(1), false);
        addActionButton(transformRow, "□  Scale", "Scale tool", () -> setTransform(2), false);
        addActionButton(transformRow, "⌁  Snap", "Snap selected objects to grid", actions::snap, false);
        transformTools.addView(transformRow);
        LayoutParams transformParams = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT,
                Gravity.LEFT | Gravity.BOTTOM);
        transformParams.leftMargin = dp(6);
        transformParams.bottomMargin = dp(44);
        addView(transformTools, transformParams);

        // Right-side navigation/control panel.
        navigationPanel = new LinearLayout(context);
        navigationPanel.setOrientation(LinearLayout.VERTICAL);
        navigationPanel.setPadding(dp(6), dp(5), dp(6), dp(5));
        navigationPanel.setBackground(panelBackground(0xEC171D25, 9f));
        navigationScroll = new ScrollView(context);
        navigationScroll.setFillViewport(false);
        navigationScroll.setVerticalScrollBarEnabled(false);
        LinearLayout navContent = new LinearLayout(context);
        navContent.setOrientation(LinearLayout.VERTICAL);
        navigationScroll.addView(navContent);
        TextView navTitle = label("VIEWPORT CONTROLS   ˅", true);
        navTitle.setOnClickListener(v -> setNavigationVisible(false));
        navigationPanel.addView(navTitle);
        addNavButton(navContent, "Orbit  ·  Rotate view", () -> viewport.setViewportNavigationMode(0));
        addNavButton(navContent, "Pan  ·  Move view", () -> viewport.setViewportNavigationMode(1));
        addNavButton(navContent, "Zoom −", () -> viewport.zoomBy(.82f));
        addNavButton(navContent, "Zoom +", () -> viewport.zoomBy(1.22f));
        addNavButton(navContent, "Frame Selected", viewport::frameSelectedObjects);
        addNavButton(navContent, "Frame All", viewport::frameAllObjects);
        addNavButton(navContent, "Reset View", viewport::resetView);
        addNavButton(navContent, "Front View", () -> viewport.setViewPreset(0));
        addNavButton(navContent, "Back View", () -> viewport.setViewPreset(1));
        addNavButton(navContent, "Right View", () -> viewport.setViewPreset(2));
        addNavButton(navContent, "Left View", () -> viewport.setViewPreset(3));
        addNavButton(navContent, "Top View", () -> viewport.setViewPreset(4));
        addNavButton(navContent, "Bottom View", () -> viewport.setViewPreset(5));
        addNavButton(navContent, "Camera View", () -> {
            try { viewport.toggleActiveCameraView(); }
            catch (RuntimeException error) { android.widget.Toast.makeText(getContext(),
                    error.getMessage(), android.widget.Toast.LENGTH_SHORT).show(); }
        });
        navigationPanel.addView(navigationScroll, new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, dp(190)));
        LayoutParams navParams = new LayoutParams(dp(154), dp(218), Gravity.RIGHT | Gravity.TOP);
        navParams.rightMargin = dp(6);
        navParams.topMargin = dp(12);
        addView(navigationPanel, navParams);

        // Bottom shortcut row.
        bottomScroll = new HorizontalScrollView(context);
        bottomScroll.setHorizontalScrollBarEnabled(false);
        bottomScroll.setFillViewport(false);
        bottomScroll.setBackground(panelBackground(0xF0161C24, 6f));
        bottomTools = row(context);
        bottomTools.setPadding(dp(4), dp(3), dp(4), dp(3));
        addBottom("▣  Select", actions::select, true);
        addBottom("✥  Move", () -> setTransform(0), false);
        addBottom("⟳  Rotate", () -> setTransform(1), false);
        addBottom("□  Scale", () -> setTransform(2), false);
        addBottom("♧  Sculpt", actions::sculpt, false);
        addBottom("X", () -> viewport.setKeyboardAxisConstraint(0), false);
        addBottom("Y", () -> viewport.setKeyboardAxisConstraint(1), false);
        addBottom("Z", () -> viewport.setKeyboardAxisConstraint(2), false);
        addBottom("Tab", actions::toggleMode, false);
        addBottom("Esc", actions::escape, false);
        addBottom("⌨", actions::keyboard, false);
        addBottom("⚙", actions::settings, false);
        addBottom("More  ⋮", actions::more, false);
        bottomScroll.addView(bottomTools, new HorizontalScrollView.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT));
        LayoutParams bottomParams = new LayoutParams(LayoutParams.MATCH_PARENT, dp(38), Gravity.BOTTOM);
        bottomParams.leftMargin = dp(5);
        bottomParams.rightMargin = dp(5);
        bottomParams.bottomMargin = dp(3);
        addView(bottomScroll, bottomParams);
    }

    private int dp(float value) {
        return Math.max(1, (int)(value * getResources().getDisplayMetrics().density * scale + .5f));
    }

    private GradientDrawable panelBackground(int color, float radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radius));
        d.setStroke(dp(1), 0x663F4B59);
        return d;
    }

    private LinearLayout row(Context context) {
        LinearLayout r = new LinearLayout(context);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        return r;
    }

    private TextView label(String value, boolean bold) {
        TextView t = new TextView(getContext());
        t.setText(value);
        t.setTextColor(0xFFE4EAF0);
        t.setTextSize(9f * scale);
        t.setGravity(Gravity.CENTER_VERTICAL);
        if (bold) t.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        t.setPadding(dp(4), dp(1), dp(4), dp(1));
        return t;
    }

    private void addTool(LinearLayout parent, String glyph, String name, int id, Runnable action) {
        TextView b = label(glyph, true);
        b.setTextSize(19f * scale);
        b.setGravity(Gravity.CENTER);
        b.setBackground(panelBackground(id == activeTool ? 0xFF245D9B : 0xFF29323D, 5f));
        b.setContentDescription(name);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, dp(34));
        p.bottomMargin = dp(3);
        parent.addView(b, p);
        b.setOnClickListener(v -> { activeTool = id; updateToolHighlight(parent); action.run(); });
        b.setOnLongClickListener(v -> {
            android.widget.Toast.makeText(getContext(), name, android.widget.Toast.LENGTH_SHORT).show();
            return true;
        });
    }

    private void updateToolHighlight(LinearLayout parent) {
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            if (child instanceof TextView) {
                child.setAlpha(i == activeTool ? 1f : .88f);
            }
        }
    }

    private void addActionButton(LinearLayout parent, String title, String description,
                                 Runnable action, boolean selected) {
        TextView b = label(title, true);
        b.setTextSize(9f * scale);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(6), dp(3), dp(6), dp(3));
        b.setBackground(panelBackground(selected ? 0xFF245D9B : 0xFF303945, 5f));
        b.setContentDescription(description);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, dp(30));
        p.rightMargin = dp(3);
        parent.addView(b, p);
        b.setOnClickListener(v -> action.run());
    }

    private void addNavButton(LinearLayout parent, String title, Runnable action) {
        TextView b = label(title, false);
        b.setTextSize(9f * scale);
        b.setPadding(dp(5), dp(3), dp(5), dp(3));
        b.setBackground(panelBackground(0xFF28313B, 4f));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, dp(23));
        p.bottomMargin = dp(2);
        parent.addView(b, p);
        b.setOnClickListener(v -> action.run());
    }

    private void addBottom(String title, Runnable action, boolean selected) {
        TextView b = label(title, true);
        b.setTextSize(9f * scale);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(7), 0, dp(7), 0);
        b.setBackground(panelBackground(selected ? 0xFF245D9B : 0xFF29323D, 4f));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, dp(29));
        p.rightMargin = dp(3);
        bottomTools.addView(b, p);
        b.setOnClickListener(v -> action.run());
    }

    private void setTransform(int mode) {
        actions.transform(mode);
        activeTool = mode == 0 ? 2 : mode == 1 ? 3 : 4;
    }

    /** Hide floating controls while Modeling docks own the left, right and bottom regions. */
    void setModelingWorkspace(boolean modeling) {
        if (modeling) {
            leftToolScroll.setVisibility(GONE);
            transformTools.setVisibility(GONE);
            bottomScroll.setVisibility(GONE);
            navigationPanel.setVisibility(GONE);
        } else {
            leftToolScroll.setVisibility(VISIBLE);
            bottomScroll.setVisibility(VISIBLE);
            navigationPanel.setVisibility(navigationVisible ? VISIBLE : GONE);
            transformTools.setVisibility(VISIBLE);
        }
    }

    void setNavigationVisible(boolean visible) {
        navigationVisible = visible;
        navigationPanel.setVisibility(visible ? VISIBLE : GONE);
    }

    void toggleNavigationVisible() { setNavigationVisible(!navigationVisible); }
    boolean isNavigationVisible() { return navigationVisible; }

    /**
     * Sculpting uses one consolidated touch dock instead of three overlapping
     * control groups (left rail, floating transform strip, and bottom shortcuts).
     * Every entry routes to the same viewport/workspace actions as the desktop-style controls.
     */
    void setSculptWorkspace(boolean sculpt) {
        leftToolScroll.setVisibility(sculpt ? GONE : VISIBLE);
        transformTools.setVisibility(sculpt ? GONE : VISIBLE);
        bottomTools.removeAllViews();
        if (sculpt) {
            addBottom("Select", actions::select, true);
            addBottom("3D Cursor", actions::cursor, false);
            addBottom("Move", () -> setTransform(0), false);
            addBottom("Rotate", () -> setTransform(1), false);
            addBottom("Scale", () -> setTransform(2), false);
            addBottom("Scale Values", actions::manualScale, false);
            addBottom("Sculpt", actions::sculpt, false);
            addBottom("Measure", actions::measure, false);
            addBottom("Add", actions::add, false);
            addBottom("Snap", actions::snap, false);
            addBottom("X", () -> viewport.setKeyboardAxisConstraint(0), false);
            addBottom("Y", () -> viewport.setKeyboardAxisConstraint(1), false);
            addBottom("Z", () -> viewport.setKeyboardAxisConstraint(2), false);
            addBottom("Tab", actions::toggleMode, false);
            addBottom("Esc", actions::escape, false);
            addBottom("Keyboard", actions::keyboard, false);
            addBottom("Settings", actions::settings, false);
            addBottom("More", actions::more, false);
        } else {
            addBottom("▣  Select", actions::select, true);
            addBottom("✥  Move", () -> setTransform(0), false);
            addBottom("⟳  Rotate", () -> setTransform(1), false);
            addBottom("□  Scale", () -> setTransform(2), false);
            addBottom("Scale Values", actions::manualScale, false);
            addBottom("♧  Sculpt", actions::sculpt, false);
            addBottom("X", () -> viewport.setKeyboardAxisConstraint(0), false);
            addBottom("Y", () -> viewport.setKeyboardAxisConstraint(1), false);
            addBottom("Z", () -> viewport.setKeyboardAxisConstraint(2), false);
            addBottom("Tab", actions::toggleMode, false);
            addBottom("Esc", actions::escape, false);
            addBottom("⌨", actions::keyboard, false);
            addBottom("⚙", actions::settings, false);
            addBottom("More  ⋮", actions::more, false);
        }
    }

    void setCompact(boolean compact) {
        transformTools.setVisibility(compact ? GONE : VISIBLE);
    }
}
