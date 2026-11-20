package com.novaingine.v2;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

public final class MainActivity extends Activity {

    private EngineSurface engineSurface;

    private boolean enginePaused = false;

    private Button playButton;
    private TextView fpsText;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private final Runnable fpsUpdater = new Runnable() {
        @Override
        public void run() {
            if (engineSurface != null && fpsText != null) {
                fpsText.setText(
                        "FPS: " + engineSurface.getFps()
                );

                handler.postDelayed(this, 500);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        );

        engineSurface = new EngineSurface(this);

        FrameLayout root = new FrameLayout(this);

        root.addView(
                engineSurface,
                new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                )
        );

        LinearLayout controls = new LinearLayout(this);

        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.CENTER_VERTICAL);
        controls.setPadding(8, 6, 8, 6);
        controls.setBackgroundColor(0xCC101820);

        playButton = new Button(this);
        playButton.setText("Pause");

        Button addCubeButton = new Button(this);
        addCubeButton.setText("+ Add Cube");

        fpsText = new TextView(this);
        fpsText.setText("FPS: --");
        fpsText.setTextColor(0xFFFFFFFF);
        fpsText.setTextSize(14);
        fpsText.setPadding(12, 0, 12, 0);

        controls.addView(playButton);
        controls.addView(addCubeButton);
        controls.addView(fpsText);

        FrameLayout.LayoutParams controlParams =
                new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        Gravity.TOP | Gravity.CENTER_HORIZONTAL
                );

        controlParams.topMargin = 6;

        root.addView(controls, controlParams);

        setContentView(root);

        playButton.setOnClickListener(view -> {

            enginePaused = !enginePaused;

            engineSurface.setEnginePaused(enginePaused);

            playButton.setText(
                    enginePaused ? "Play" : "Pause"
            );
        });

        addCubeButton.setOnClickListener(view -> {
            engineSurface.addCube();
        });

        handler.post(fpsUpdater);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(fpsUpdater);

        if (engineSurface != null) {
            engineSurface.onPause();
        }

        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();

        if (engineSurface != null) {
            engineSurface.onResume();
        }

        handler.post(fpsUpdater);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(fpsUpdater);

        super.onDestroy();
    }
}
