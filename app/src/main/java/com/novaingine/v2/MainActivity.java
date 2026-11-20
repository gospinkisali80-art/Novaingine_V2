package com.novaingine.v2;

import android.app.Activity;
import android.os.Bundle;
import android.content.pm.ActivityInfo;
import android.view.Window;
import android.view.WindowManager;

public final class MainActivity extends Activity {

    private EngineSurface engineSurface;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        requestWindowFeature(Window.FEATURE_NO_TITLE);

        setRequestedOrientation(
                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        );

        getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        );

        engineSurface = new EngineSurface(this);

        setContentView(engineSurface);
    }

    @Override
    protected void onPause() {
        super.onPause();

        if (engineSurface != null) {
            engineSurface.onPause();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();

        if (engineSurface != null) {
            engineSurface.onResume();
        }
    }

    @Override
    protected void onDestroy() {
        if (engineSurface != null) {
            engineSurface.onPause();
        }

        super.onDestroy();
    }
}
