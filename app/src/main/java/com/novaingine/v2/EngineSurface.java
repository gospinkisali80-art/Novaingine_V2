package com.novaingine.v2;

import android.content.Context;
import android.opengl.GLSurfaceView;
import android.view.MotionEvent;

public final class EngineSurface extends GLSurfaceView
        implements GLSurfaceView.Renderer {

    private long previousFrameTime;
    private long fpsStartTime;

    private int fpsFrames;
    private int currentFps;

    private float previousX;
    private float previousY;
    private float previousDistance;

    private boolean multiTouch;

    private float joystickForward;
    private float joystickRight;
    private float verticalMovement;

    private boolean enginePaused;

    public EngineSurface(Context context) {
        super(context);

        setEGLContextClientVersion(3);
        setPreserveEGLContextOnPause(true);

        setRenderer(this);

        setRenderMode(
                GLSurfaceView.RENDERMODE_CONTINUOUSLY
        );
    }

    @Override
    public void onSurfaceCreated(
            javax.microedition.khronos.opengles.GL10 gl,
            javax.microedition.khronos.egl.EGLConfig config) {

        previousFrameTime = System.nanoTime();
        fpsStartTime = previousFrameTime;

        fpsFrames = 0;
        currentFps = 0;

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
                (currentTime - previousFrameTime)
                        / 1_000_000_000.0f;

        previousFrameTime = currentTime;

        if (deltaTime <= 0.0f || deltaTime > 0.1f) {
            deltaTime = 0.016f;
        }

        if (!enginePaused) {

            NativeBridge.nativeFly(
                    joystickForward,
                    joystickRight,
                    verticalMovement,
                    deltaTime
            );
        }

        NativeBridge.nativeRender(deltaTime);

        fpsFrames++;

        long elapsed = currentTime - fpsStartTime;

        if (elapsed >= 1_000_000_000L) {

            currentFps = (int) (
                    fpsFrames /
                    (elapsed / 1_000_000_000.0)
            );

            fpsFrames = 0;
            fpsStartTime = currentTime;
        }
    }

    public int getFps() {
        return currentFps;
    }

    public void setEnginePaused(boolean paused) {

        enginePaused = paused;

        queueEvent(() ->
                NativeBridge.nativeSetPaused(paused)
        );
    }

    public void setJoystick(
            float forward,
            float right) {

        joystickForward = clamp(forward);
        joystickRight = clamp(right);
    }

    public void setVerticalMovement(float value) {
        verticalMovement = clamp(value);
    }

    public boolean saveScene(String path) {
        return NativeBridge.nativeSaveScene(path);
    }

    public boolean loadScene(String path) {
        return NativeBridge.nativeLoadScene(path);
    }

    public String[] getSceneObjects() {
        return NativeBridge.nativeGetSceneObjects();
    }

    public float[] getSelectedTransform() {
        return NativeBridge.nativeGetSelectedTransform();
    }

    public void selectObjectById(int objectId) {
        queueEvent(() -> NativeBridge.nativeSelectObjectById(objectId));
    }

    public void setSelectedTransform(float[] values) {
        float[] copy = values.clone();
        queueEvent(() -> NativeBridge.nativeSetSelectedTransform(copy));
    }

    public void setPhysicsEnabled(boolean enabled) {
        queueEvent(() -> NativeBridge.nativeSetPhysicsEnabled(enabled));
    }

    public void setCameraSpeed(float speed) {

        queueEvent(() ->
                NativeBridge.nativeSetCameraSpeed(speed)
        );
    }

    public void setExposure(float exposure) {
        queueEvent(() -> NativeBridge.nativeSetExposure(exposure));
    }

    public void setViewMode(int mode) {

        queueEvent(() ->
                NativeBridge.nativeSetViewMode(mode)
        );
    }

    public void setCameraView(int view) {

        queueEvent(() ->
                NativeBridge.nativeSetCameraView(view)
        );
    }

    public void setShowFlags(int flags) {

        queueEvent(() ->
                NativeBridge.nativeSetShowFlags(flags)
        );
    }

    public void setTransformMode(int mode) {

        queueEvent(() ->
                NativeBridge.nativeSetTransformMode(mode)
        );
    }

    public void setWorldSpace(boolean worldSpace) {

        queueEvent(() ->
                NativeBridge.nativeSetWorldSpace(worldSpace)
        );
    }

    public void setSnap(
            boolean enabled,
            float grid,
            float rotation,
            float scale) {

        queueEvent(() ->
                NativeBridge.nativeSetSnap(
                        enabled,
                        grid,
                        rotation,
                        scale
                )
        );
    }

    public void selectAt(float x, float y) {

        queueEvent(() ->
                NativeBridge.nativeSelect(x, y)
        );
    }

    public void focusAt(float x, float y) {

        queueEvent(() ->
                NativeBridge.nativeFocus(x, y)
        );
    }

    public void moveSelected(
            float x,
            float y,
            float z) {

        queueEvent(() ->
                NativeBridge.nativeMoveSelected(x, y, z)
        );
    }

    public void resetScene() {

        queueEvent(NativeBridge::nativeResetScene);
    }

    private float clamp(float value) {
        return Math.max(-1.0f, Math.min(1.0f, value));
    }

    private float calculateDistance(MotionEvent event) {

        if (event.getPointerCount() < 2) {
            return 0.0f;
        }

        float dx = event.getX(0) - event.getX(1);
        float dy = event.getY(0) - event.getY(1);

        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {

        switch (event.getActionMasked()) {

            case MotionEvent.ACTION_DOWN:

                previousX = event.getX();
                previousY = event.getY();

                previousDistance = 0.0f;
                multiTouch = false;

                return true;

            case MotionEvent.ACTION_POINTER_DOWN:

                if (event.getPointerCount() >= 2) {

                    previousDistance = calculateDistance(event);
                    previousX = (event.getX(0) + event.getX(1)) * 0.5f;
                    previousY = (event.getY(0) + event.getY(1)) * 0.5f;

                    multiTouch = true;
                }

                return true;

            case MotionEvent.ACTION_MOVE:

                if (event.getPointerCount() >= 2) {

                    float distance = calculateDistance(event);

                    float centerX =
                            (event.getX(0) + event.getX(1)) * 0.5f;

                    float centerY =
                            (event.getY(0) + event.getY(1)) * 0.5f;

                    if (previousDistance > 0.0f && distance > 0.0f) {

                        float scale = distance / previousDistance;

                        queueEvent(() ->
                                NativeBridge.nativeZoom(scale)
                        );
                    }

                    float panX = centerX - previousX;
                    float panY = centerY - previousY;

                    if (Math.abs(panX) > 0.5f ||
                            Math.abs(panY) > 0.5f) {

                        queueEvent(() ->
                                NativeBridge.nativePan(panX, panY)
                        );
                    }

                    previousDistance = distance;
                    previousX = centerX;
                    previousY = centerY;

                    multiTouch = true;

                } else if (!multiTouch) {

                    float x = event.getX();
                    float y = event.getY();

                    float dx = x - previousX;
                    float dy = y - previousY;

                    previousX = x;
                    previousY = y;

                    queueEvent(() ->
                            NativeBridge.nativeTouch(dx, dy)
                    );
                }

                return true;

            case MotionEvent.ACTION_POINTER_UP:

                previousDistance = 0.0f;
                multiTouch = true;

                return true;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:

                previousDistance = 0.0f;
                multiTouch = false;

                return true;

            default:
                return true;
        }
    }
}
