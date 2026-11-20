package com.novaingine.v2;

import android.content.Context;
import android.opengl.GLSurfaceView;
import android.view.MotionEvent;
import java.util.concurrent.atomic.AtomicInteger;

public class EngineSurface extends GLSurfaceView
        implements GLSurfaceView.Renderer {

    private float previousX;
    private float previousY;
    private float previousDistance;

    private boolean multiTouch = false;

    private long previousFrameTime = 0;
    private long fpsStartTime = 0;
    private int fpsFrameCount = 0;

    private final AtomicInteger currentFps = new AtomicInteger(0);

    public EngineSurface(Context context) {
        super(context);

        setEGLContextClientVersion(3);
        setPreserveEGLContextOnPause(true);

        setRenderer(this);
        setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);
    }

    @Override
    public void onSurfaceCreated(
            javax.microedition.khronos.opengles.GL10 gl,
            javax.microedition.khronos.egl.EGLConfig config) {

        NativeBridge.nativeInit();

        previousFrameTime = System.nanoTime();
        fpsStartTime = previousFrameTime;
        fpsFrameCount = 0;
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

        if (deltaTime <= 0.0f || deltaTime > 0.25f) {
            deltaTime = 0.016f;
        }

        NativeBridge.nativeRender(deltaTime);

        fpsFrameCount++;

        long elapsed = currentTime - fpsStartTime;

        if (elapsed >= 1_000_000_000L) {
            int fps = (int) (
                    fpsFrameCount /
                    (elapsed / 1_000_000_000.0)
            );

            currentFps.set(fps);

            fpsFrameCount = 0;
            fpsStartTime = currentTime;
        }
    }

    public int getFps() {
        return currentFps.get();
    }

    public void setEnginePaused(boolean paused) {
        queueEvent(() -> NativeBridge.nativeSetPaused(paused));
    }

    public void addCube() {
        queueEvent(NativeBridge::nativeAddCube);
    }

    private float calculateDistance(MotionEvent event) {
        if (event.getPointerCount() < 2) {
            return 0.0f;
        }

        float x = event.getX(0) - event.getX(1);
        float y = event.getY(0) - event.getY(1);

        return (float) Math.sqrt(x * x + y * y);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {

        int action = event.getActionMasked();

        switch (action) {

            case MotionEvent.ACTION_DOWN:
                previousX = event.getX(0);
                previousY = event.getY(0);
                multiTouch = false;
                return true;

            case MotionEvent.ACTION_POINTER_DOWN:
                if (event.getPointerCount() >= 2) {
                    previousDistance = calculateDistance(event);
                    multiTouch = true;
                }
                return true;

            case MotionEvent.ACTION_MOVE:

                if (event.getPointerCount() >= 2) {

                    float newDistance = calculateDistance(event);

                    if (previousDistance > 0.0f &&
                            newDistance > 0.0f) {

                        float scale = newDistance / previousDistance;

                        queueEvent(() ->
                                NativeBridge.nativeZoom(scale)
                        );
                    }

                    previousDistance = newDistance;
                    multiTouch = true;

                } else if (!multiTouch) {

                    float x = event.getX(0);
                    float y = event.getY(0);

                    float deltaX = x - previousX;
                    float deltaY = y - previousY;

                    queueEvent(() ->
                            NativeBridge.nativeTouch(deltaX, deltaY)
                    );

                    previousX = x;
                    previousY = y;
                }

                return true;

            case MotionEvent.ACTION_POINTER_UP:

                if (event.getPointerCount() > 1) {

                    int remainingPointer =
                            event.getActionIndex() == 0 ? 1 : 0;

                    if (remainingPointer < event.getPointerCount()) {
                        previousX = event.getX(remainingPointer);
                        previousY = event.getY(remainingPointer);
                    }
                }

                previousDistance = 0.0f;
                multiTouch = true;

                return true;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:

                previousDistance = 0.0f;
                multiTouch = false;

                return true;
        }

        return true;
    }
}
