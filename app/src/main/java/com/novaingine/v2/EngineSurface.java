package com.novaingine.v2;

import android.content.Context;
import android.opengl.GLSurfaceView;
import android.view.MotionEvent;

public final class EngineSurface extends GLSurfaceView
        implements GLSurfaceView.Renderer {

    private long previousFrameTime;
    private float previousX;
    private float previousY;

    public EngineSurface(Context context) {
        super(context);

        setEGLContextClientVersion(3);
        setPreserveEGLContextOnPause(false);
        setRenderer(this);
        setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);
    }

    @Override
    public void onSurfaceCreated(
            javax.microedition.khronos.opengles.GL10 gl,
            javax.microedition.khronos.egl.EGLConfig config) {

        previousFrameTime = System.nanoTime();
        NativeBridge.nativeInit();
    }

    @Override
    public void onSurfaceChanged(
            javax.microedition.khronos.opengles.GL10 gl,
            int width,
            int height) {

        NativeBridge.nativeResize(width, height);
    }

    @Override
    public void onDrawFrame(
            javax.microedition.khronos.opengles.GL10 gl) {

        long currentTime = System.nanoTime();

        float deltaTime =
                (currentTime - previousFrameTime) / 1_000_000_000.0f;

        previousFrameTime = currentTime;

        if (deltaTime > 0.1f) {
            deltaTime = 0.1f;
        }

        NativeBridge.nativeRender(deltaTime);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {

        switch (event.getActionMasked()) {

            case MotionEvent.ACTION_DOWN:
                previousX = event.getX();
                previousY = event.getY();
                return true;

            case MotionEvent.ACTION_MOVE:

                float currentX = event.getX();
                float currentY = event.getY();

                float deltaX = currentX - previousX;
                float deltaY = currentY - previousY;

                previousX = currentX;
                previousY = currentY;

                NativeBridge.nativeTouch(deltaX, deltaY);
                return true;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                return true;

            default:
                return true;
        }
    }
}
