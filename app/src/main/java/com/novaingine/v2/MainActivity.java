package com.novaingine.v2;

import android.app.Activity;
import android.content.pm.ActivityInfo;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
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
