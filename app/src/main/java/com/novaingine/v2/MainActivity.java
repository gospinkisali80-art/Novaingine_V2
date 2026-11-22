package com.novaingine.v2;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.pm.ActivityInfo;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

public final class MainActivity extends Activity
        implements EditorToolbar.ViewportActions {

    private FrameLayout rootLayout;

    private EngineSurface engineSurface;
    private ViewportInput viewportInput;
    private EditorToolbar editorToolbar;
    private CameraControls cameraControls;

    private TextView statisticsText;

    private boolean viewportMaximized;

    private int normalToolbarHeight;
    private int normalInputHeight;

    @Override
    protected void onCreate(Bundle savedInstanceState) {

        super.onCreate(savedInstanceState);

        getWindow().setFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN
        );

        getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        );

        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        );

        setRequestedOrientation(
                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        );

        normalToolbarHeight = dp(48);
        normalInputHeight = dp(190);

        createViewport();
        createToolbar();
        createTouchControls();
        createStatistics();
        createEditorPanels();

        setContentView(rootLayout);

        cameraControls = new CameraControls(engineSurface);
    }

    private void createViewport() {

        rootLayout = new FrameLayout(this);

        rootLayout.setBackgroundColor(Color.BLACK);

        engineSurface = new EngineSurface(this);

        FrameLayout.LayoutParams viewportParams =
                new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                );

        rootLayout.addView(
                engineSurface,
                viewportParams
        );
    }

    private void createToolbar() {

        editorToolbar = new EditorToolbar(
                this,
                engineSurface,
                this
        );

        FrameLayout.LayoutParams toolbarParams =
                new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        normalToolbarHeight,
                        Gravity.TOP
                );

        rootLayout.addView(
                editorToolbar,
                toolbarParams
        );
    }

    private void createTouchControls() {

        viewportInput = new ViewportInput(
                this,
                engineSurface
        );

        FrameLayout.LayoutParams inputParams =
                new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        normalInputHeight,
                        Gravity.BOTTOM
                );

        rootLayout.addView(
                viewportInput,
                inputParams
        );
    }

    private void createStatistics() {

        statisticsText = new TextView(this);

        statisticsText.setTextColor(Color.WHITE);
        statisticsText.setTextSize(11);
        statisticsText.setPadding(
                dp(8),
                dp(5),
                dp(8),
                dp(5)
        );

        statisticsText.setBackgroundColor(
                0x99000000
        );

        statisticsText.setText(
                "NOVAINGINE V2  |  VIEWPORT"
        );

        FrameLayout.LayoutParams statsParams =
                new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        Gravity.TOP | Gravity.END
                );

        statsParams.setMargins(
                0,
                dp(55),
                dp(8),
                0
        );

        rootLayout.addView(
                statisticsText,
                statsParams
        );
    }


    private void createEditorPanels() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.HORIZONTAL);
        panel.setPadding(dp(5), dp(4), dp(5), dp(4));
        panel.setBackgroundColor(0xDD202020);

        TextView outliner = new TextView(this);
        outliner.setText("WORLD OUTLINER");
        outliner.setTextColor(Color.WHITE);
        outliner.setTextSize(10);
        outliner.setGravity(Gravity.CENTER);
        outliner.setPadding(dp(10), dp(8), dp(10), dp(8));
        outliner.setBackgroundColor(0xFF3A3A3A);
        outliner.setOnClickListener(view -> showWorldOutliner());

        TextView details = new TextView(this);
        details.setText("DETAILS");
        details.setTextColor(Color.WHITE);
        details.setTextSize(10);
        details.setGravity(Gravity.CENTER);
        details.setPadding(dp(10), dp(8), dp(10), dp(8));
        details.setBackgroundColor(0xFF3A3A3A);
        details.setOnClickListener(view -> showDetailsInspector());

        TextView undo = new TextView(this);
        undo.setText("UNDO");
        undo.setTextColor(Color.WHITE);
        undo.setTextSize(10);
        undo.setGravity(Gravity.CENTER);
        undo.setPadding(dp(10), dp(8), dp(10), dp(8));
        undo.setBackgroundColor(0xFF3A3A3A);
        undo.setOnClickListener(view -> engineSurface.undoScene());

        TextView redo = new TextView(this);
        redo.setText("REDO");
        redo.setTextColor(Color.WHITE);
        redo.setTextSize(10);
        redo.setGravity(Gravity.CENTER);
        redo.setPadding(dp(10), dp(8), dp(10), dp(8));
        redo.setBackgroundColor(0xFF3A3A3A);
        redo.setOnClickListener(view -> engineSurface.redoScene());

        LinearLayout.LayoutParams itemParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        itemParams.setMargins(dp(3), 0, dp(3), 0);
        panel.addView(outliner, itemParams);
        panel.addView(details, itemParams);
        panel.addView(undo, itemParams);
        panel.addView(redo, itemParams);

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.END
        );
        params.setMargins(0, dp(55), dp(8), 0);
        rootLayout.addView(panel, params);
    }

    private void showWorldOutliner() {
        String[] objects = engineSurface.getSceneObjects();
        if (objects == null || objects.length == 0) {
            new AlertDialog.Builder(this)
                    .setTitle("World Outliner")
                    .setMessage("The scene contains no objects.")
                    .setPositiveButton("Close", null)
                    .show();
            return;
        }

        String[] labels = new String[objects.length];
        int[] ids = new int[objects.length];
        for (int i = 0; i < objects.length; i++) {
            String row = objects[i] == null ? "" : objects[i];
            int split = row.indexOf('|');
            try {
                ids[i] = Integer.parseInt(split < 0 ? row : row.substring(0, split));
            } catch (NumberFormatException ignored) {
                ids[i] = 0;
            }
            labels[i] = split < 0 ? row : row.substring(split + 1);
        }

        new AlertDialog.Builder(this)
                .setTitle("World Outliner  •  " + objects.length + " objects")
                .setItems(labels, (dialog, which) -> {
                    if (ids[which] != 0) {
                        engineSurface.selectObjectById(ids[which]);
                    }
                })
                .setNegativeButton("Close", null)
                .show();
    }

    private void showDetailsInspector() {
        float[] values = engineSurface.getSelectedTransform();
        if (values == null || values.length < 9) return;

        String[] names = {
                "Position X", "Position Y", "Position Z",
                "Rotation X", "Rotation Y", "Rotation Z",
                "Scale X", "Scale Y", "Scale Z"
        };

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(18), dp(8), dp(18), dp(4));

        EditText[] fields = new EditText[9];
        for (int i = 0; i < fields.length; i++) {
            TextView label = new TextView(this);
            label.setText(names[i]);
            label.setTextColor(Color.DKGRAY);
            label.setTextSize(11);
            content.addView(label);

            EditText field = new EditText(this);
            field.setSingleLine(true);
            field.setInputType(android.text.InputType.TYPE_CLASS_NUMBER
                    | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
                    | android.text.InputType.TYPE_NUMBER_FLAG_SIGNED);
            field.setText(String.format(java.util.Locale.US, "%.3f", values[i]));
            content.addView(field);
            fields[i] = field;
        }

        new AlertDialog.Builder(this)
                .setTitle("Details Inspector")
                .setView(content)
                .setPositiveButton("Apply", (dialog, which) -> {
                    float[] updated = values.clone();
                    for (int i = 0; i < fields.length; i++) {
                        try {
                            updated[i] = Float.parseFloat(
                                    fields[i].getText().toString().trim()
                            );
                        } catch (NumberFormatException ignored) {
                            updated[i] = values[i];
                        }
                    }
                    engineSurface.setSelectedTransform(updated);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    @Override
    public void onToggleMaximize() {

        viewportMaximized = !viewportMaximized;

        if (viewportMaximized) {

            editorToolbar.setVisibility(View.GONE);
            viewportInput.setVisibility(View.GONE);
            statisticsText.setVisibility(View.GONE);

        } else {

            editorToolbar.setVisibility(View.VISIBLE);
            viewportInput.setVisibility(View.VISIBLE);
            statisticsText.setVisibility(View.VISIBLE);
        }
    }

    private int dp(float value) {

        return (int) (
                value * getResources()
                        .getDisplayMetrics()
                        .density + 0.5f
        );
    }

    @Override
    protected void onResume() {

        super.onResume();

        if (engineSurface != null) {
            engineSurface.onResume();
        }
    }

    @Override
    protected void onPause() {

        if (engineSurface != null) {
            engineSurface.onPause();
        }

        super.onPause();
    }

    @Override
    protected void onDestroy() {

        cameraControls = null;
        viewportInput = null;
        editorToolbar = null;
        engineSurface = null;
        rootLayout = null;

        super.onDestroy();
    }
}
