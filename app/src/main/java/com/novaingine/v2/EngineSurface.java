package com.novaingine.v2;

import android.content.Context;
import android.opengl.GLSurfaceView;
import android.view.MotionEvent;
import android.view.View;

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
    private volatile float lookHorizontal;
    private volatile float lookVertical;

    private boolean enginePaused;
    private final java.util.concurrent.atomic.AtomicBoolean sceneSaveInProgress = new java.util.concurrent.atomic.AtomicBoolean(false);
    private volatile boolean sculptMode;
    private volatile float sculptRadius = 0.18f;
    private volatile float sculptStrength = 0.12f;
    private volatile float sculptFalloff = 0.5f;
    private volatile float sculptPressure = 1.0f;
    private volatile int sculptBrush;
    private volatile int sculptSymmetryAxis = 0;
    private volatile boolean sculptSubtract;
    private boolean sculptStrokeActive;
    private volatile boolean terrainSculptMode;
    private volatile int terrainBrushMode;
    private volatile float terrainBrushRadius = 5.0f;
    private volatile float terrainBrushStrength = 0.35f;
    private volatile boolean terrainPaintMode;
    private volatile boolean terrainHoleMode;
    private volatile boolean terrainHoleValue = true;
    private volatile int terrainMaterial = 0;

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

            // Independent right-stick camera look runs alongside movement.
            NativeBridge.nativeTouch(
                    lookHorizontal * 120.0f * deltaTime,
                    lookVertical * 120.0f * deltaTime
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

    public void importGlb(String path, java.util.function.IntConsumer callback) {
        queueEvent(() -> {
            int imported = NativeBridge.nativeImportGlb(path);
            if (callback != null) post(() -> callback.accept(imported));
        });
    }

    public void setWorldEnvironment(float[] values) {
        if (values == null || values.length < 28) return;
        float[] copy = java.util.Arrays.copyOf(values, 28);
        queueEvent(() -> NativeBridge.nativeSetWorldEnvironment(copy));
    }

    public void setManualSculptWorkspace(boolean enabled) {
        queueEvent(() -> NativeBridge.nativeSetManualSculptWorkspace(enabled));
    }

    public void setSculptOptions(boolean enabled, int brush, float radius, float strength, float falloff, int symmetryAxis, boolean subtract, float pressure) {
        sculptMode = enabled;
        sculptBrush = Math.max(0, Math.min(24, brush));
        sculptRadius = Math.max(0.02f, Math.min(8.0f, radius));
        sculptStrength = Math.max(0.0f, Math.min(1.0f, strength));
        sculptFalloff = Math.max(0.0f, Math.min(1.0f, falloff));
        sculptSymmetryAxis = Math.max(-1, Math.min(2, symmetryAxis));
        sculptSubtract = subtract;
        sculptPressure = Math.max(0.05f, Math.min(1.0f, pressure));
        queueEvent(() -> NativeBridge.nativeSetSculptMode(enabled));
    }

    // Brush dabs are computed by the native worker; the GL thread only queues samples.
    public void enterSculptMode() {
        sculptMode = true;
        sculptStrokeActive = false;
        queueEvent(() -> NativeBridge.nativeEnterSculptMode());
    }

    public void handleSculptTouch(MotionEvent event, float viewportTopOffset) {
        handleSculptTouch(event, viewportTopOffset, 0.0f, 0.0f);
    }

    // Convert overlay coordinates into the actual GL SurfaceView coordinate space.
    public void handleSculptTouchFromView(MotionEvent event, View sourceView) {
        if (sourceView == null) {
            handleSculptTouch(event, 0.0f);
            return;
        }
        int[] sourceLocation = new int[2];
        int[] surfaceLocation = new int[2];
        sourceView.getLocationOnScreen(sourceLocation);
        getLocationOnScreen(surfaceLocation);
        handleSculptTouch(event, 0.0f,
                sourceLocation[0] - surfaceLocation[0],
                sourceLocation[1] - surfaceLocation[1]);
    }

    private void handleSculptTouch(MotionEvent event, float viewportTopOffset,
                                   float coordinateOffsetX, float coordinateOffsetY) {
        if (!sculptMode) return;
        int action = event.getActionMasked();
        if ((action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE)
                && event.getPointerCount() == 1) {
            final float x = event.getX(0) + coordinateOffsetX;
            final float y = event.getY(0) + viewportTopOffset + coordinateOffsetY;
            float sensed = event.getPressure(0);
            final float pressure = (sensed > 0.05f && sensed <= 1.5f ? Math.min(1.0f, sensed) : 1.0f) * sculptPressure;
            final float radius = sculptRadius;
            final float strength = sculptStrength;
            final float hardness = sculptFalloff;
            final int brush = sculptBrush;
            final int symmetryAxis = sculptSymmetryAxis;
            final boolean subtract = sculptSubtract;
            if (action == MotionEvent.ACTION_DOWN) {
                sculptStrokeActive = true;
                queueEvent(() -> NativeBridge.nativeBeginSculptStroke());
            }
            queueEvent(() -> {
                NativeBridge.nativeSculptAtScreen(x, y, radius, strength, brush,
                        hardness, symmetryAxis, subtract, pressure);
                NativeBridge.nativeUpdateSculptCursor(x, y, radius, true);
            });
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL
                || action == MotionEvent.ACTION_POINTER_DOWN || action == MotionEvent.ACTION_POINTER_UP) {
            if (sculptStrokeActive) {
                sculptStrokeActive = false;
                queueEvent(() -> NativeBridge.nativeEndSculptStroke());
            }
            queueEvent(() -> NativeBridge.nativeUpdateSculptCursor(-1.0f, -1.0f, sculptRadius, false));
        }
    }

    public void createSculptMesh(int shape, int density, java.util.function.IntConsumer callback) {
        queueEvent(() -> { int id = NativeBridge.nativeCreateSculptMesh(shape, density); if (callback != null) post(() -> callback.accept(id)); });
    }

    public void exportSelectedObj(String path, java.util.function.Consumer<Boolean> callback) {
        queueEvent(() -> { boolean ok = NativeBridge.nativeExportSelectedObj(path); if (callback != null) post(() -> callback.accept(ok)); });
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

    public void setLookJoystick(float horizontal, float vertical) {
        lookHorizontal = clamp(horizontal);
        lookVertical = clamp(vertical);
    }

    public boolean saveScene(String path) {
        return NativeBridge.nativeSaveScene(path);
    }

    public boolean loadScene(String path) {
        return NativeBridge.nativeLoadScene(path);
    }

    public void saveSceneAsync(String path, java.util.function.Consumer<Boolean> callback) {
        if (!sceneSaveInProgress.compareAndSet(false, true)) {
            if (callback != null) post(() -> callback.accept(false));
            return;
        }
        new Thread(() -> {
            boolean saved = false;
            try {
                saved = NativeBridge.nativeSaveScene(path);
            } catch (Throwable ignored) {
                saved = false;
            } finally {
                sceneSaveInProgress.set(false);
            }
            final boolean result = saved;
            if (callback != null) post(() -> callback.accept(result));
        }, "NovaProjectAutoSave").start();
    }

    public void loadSceneAsync(String path, java.util.function.Consumer<Boolean> callback) {
        queueEvent(() -> {
            boolean loaded = NativeBridge.nativeLoadScene(path);
            if (callback != null) post(() -> callback.accept(loaded));
        });
    }

    public void createEmptyScene(String path, java.util.function.Consumer<Boolean> callback) {
        queueEvent(() -> {
            NativeBridge.nativeCreateEmptyScene();
            boolean saved = NativeBridge.nativeSaveScene(path);
            if (callback != null) post(() -> callback.accept(saved));
        });
    }

    public void setVisualScript(int[] nodeData, float[] nodeValues, boolean enabled, java.util.function.Consumer<Boolean> callback) {
        int[] dataCopy = nodeData == null ? null : nodeData.clone();
        float[] valuesCopy = nodeValues == null ? null : nodeValues.clone();
        queueEvent(() -> {
            boolean ok = NativeBridge.nativeSetVisualScript(dataCopy, valuesCopy, enabled);
            if (callback != null) post(() -> callback.accept(ok));
        });
    }

    public int paintFoliage(float x, float z, float radius, int count, int kind) {
        return NativeBridge.nativePaintFoliage(x, z, radius, count, kind);
    }

    public void refineSculptMesh(java.util.function.IntConsumer callback) {
        queueEvent(() -> {
            int result = NativeBridge.nativeRefineSculptMesh();
            if (callback != null) post(() -> callback.accept(result));
        });
    }

    public void sculptSelectedMesh(float x, float y, float z, float radius, float strength, int mode, boolean symmetry, java.util.function.IntConsumer callback) {
        queueEvent(() -> {
            int result = NativeBridge.nativeSculptSelectedMesh(x, y, z, radius, strength, mode, symmetry);
            if (callback != null) post(() -> callback.accept(result));
        });
    }

    public int clearFoliage() {
        return NativeBridge.nativeClearFoliage();
    }

    public void setTerrainSculptMode(boolean enabled, int brushMode, float radius, float strength) {
        terrainBrushMode = Math.max(0, Math.min(6, brushMode));
        terrainBrushRadius = Math.max(0.5f, Math.min(24.0f, radius));
        terrainBrushStrength = Math.max(0.005f, Math.min(2.5f, strength));
        terrainSculptMode = enabled;
    }

    private void terrainStroke(float x, float y, int phase) {
        final int mode = terrainBrushMode;
        final float radius = terrainBrushRadius;
        final float strength = terrainBrushStrength;
        queueEvent(() -> NativeBridge.nativeSculptTerrainAtScreen(
                x, y, radius, strength, mode, phase));
    }

    public void setTerrainPaintMode(boolean enabled, int material, float radius, float strength) {
        terrainPaintMode = enabled;
        terrainHoleMode = false;
        terrainMaterial = Math.max(0, Math.min(3, material));
        terrainBrushRadius = Math.max(0.5f, Math.min(24.0f, radius));
        terrainBrushStrength = Math.max(0.0f, Math.min(1.0f, strength));
    }

    public void setTerrainHoleMode(boolean enabled, boolean makeHole) {
        terrainHoleMode = enabled;
        terrainHoleValue = makeHole;
        terrainPaintMode = false;
        terrainSculptMode = false;
    }

    public void runTerrainErosion(int type, int iterations, float strength, java.util.function.Consumer<Boolean> callback) {
        queueEvent(() -> {
            boolean ok = NativeBridge.nativeRunTerrainErosion(type, iterations, strength);
            if (callback != null) post(() -> callback.accept(ok));
        });
    }

    public void selectLandscapeLayer(int index, java.util.function.Consumer<Boolean> callback) {
        queueEvent(() -> {
            boolean ok = NativeBridge.nativeSelectLandscapeLayer(index);
            if (callback != null) post(() -> callback.accept(ok));
        });
    }

    public void getLandscapeLayers(java.util.function.Consumer<String[]> callback) {
        queueEvent(() -> {
            int count = NativeBridge.nativeGetLandscapeLayerCount();
            String[] names = new String[Math.max(0, count)];
            for (int i = 0; i < names.length; i++) names[i] = NativeBridge.nativeGetLandscapeLayerName(i);
            if (callback != null) post(() -> callback.accept(names));
        });
    }

    public void getLandscapeChunkCount(java.util.function.IntConsumer callback) {
        queueEvent(() -> {
            int count = NativeBridge.nativeGetLandscapeChunkCount();
            if (callback != null) post(() -> callback.accept(count));
        });
    }

    public void addLandscapeLayer(String name, java.util.function.IntConsumer callback) {
        queueEvent(() -> {
            int index = NativeBridge.nativeAddLandscapeLayer(name);
            if (callback != null) post(() -> callback.accept(index));
        });
    }

    public void setLandscapeLayerState(int index, boolean visible, boolean locked, float heightAlpha, float paintAlpha, java.util.function.Consumer<Boolean> callback) {
        queueEvent(() -> {
            boolean ok = NativeBridge.nativeSetLandscapeLayerState(index, visible, locked, heightAlpha, paintAlpha);
            if (callback != null) post(() -> callback.accept(ok));
        });
    }

    public void addLandscapeSpline(float[] points, java.util.function.Consumer<Boolean> callback) {
        float[] copy = points.clone();
        queueEvent(() -> {
            boolean ok = NativeBridge.nativeAddLandscapeSpline(copy);
            if (callback != null) post(() -> callback.accept(ok));
        });
    }

    public void setLandscapeChunkSize(int size, java.util.function.Consumer<Boolean> callback) {
        queueEvent(() -> {
            boolean ok = NativeBridge.nativeSetLandscapeChunkSize(size);
            if (callback != null) post(() -> callback.accept(ok));
        });
    }

    private void terrainPaintStroke(float x, float y, int phase) {
        final int material = terrainMaterial;
        final float radius = terrainBrushRadius;
        final float strength = terrainBrushStrength;
        queueEvent(() -> NativeBridge.nativePaintTerrainAtScreen(x, y, radius, strength, material, phase));
    }

    public void createTerrain(java.util.function.Consumer<Boolean> callback) {
        queueEvent(() -> {
            boolean created = NativeBridge.nativeCreateTerrain();
            if (callback != null) post(() -> callback.accept(created));
        });
    }

    public void setTerrainHeights(float[] heights, java.util.function.Consumer<Boolean> callback) {
        float[] copy = heights.clone();
        queueEvent(() -> {
            boolean ok = NativeBridge.nativeSetTerrainHeights(copy);
            if (callback != null) post(() -> callback.accept(ok));
        });
    }

    public void getTerrainHeights(java.util.function.Consumer<float[]> callback) {
        queueEvent(() -> {
            float[] heights = NativeBridge.nativeGetTerrainHeights();
            if (callback != null) post(() -> callback.accept(heights));
        });
    }

    public void sculptTerrain(
            float centerX, float centerZ, float radius, float strength, int mode,
            java.util.function.Consumer<Boolean> callback) {
        queueEvent(() -> {
            boolean changed = NativeBridge.nativeSculptTerrain(
                    centerX, centerZ, radius, strength, mode
            );
            if (callback != null) post(() -> callback.accept(changed));
        });
    }

    public String[] getSceneObjects() {
        return NativeBridge.nativeGetSceneObjects();
    }

    public float[] getSelectedTransform() {
        return NativeBridge.nativeGetSelectedTransform();
    }

    public float[] projectSelectedPosition() {
        return NativeBridge.nativeProjectSelectedPosition();
    }

    public float[] projectSelectedGizmo() {
        return NativeBridge.nativeProjectSelectedGizmo();
    }

    public void selectObjectById(int objectId) {
        queueEvent(() -> NativeBridge.nativeSelectObjectById(objectId));
    }

    public void setSelectedTransform(float[] values) {
        float[] copy = values.clone();
        queueEvent(() -> NativeBridge.nativeSetSelectedTransform(copy));
    }

    public void beginTransformEdit() {
        queueEvent(() -> NativeBridge.nativeBeginTransformEdit());
    }

    public void updateSelectedTransform(float[] values) {
        float[] copy=values.clone();
        queueEvent(() -> NativeBridge.nativeUpdateSelectedTransform(copy));
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

    public void undoScene() {
        queueEvent(NativeBridge::nativeUndo);
    }

    public void redoScene() {
        queueEvent(NativeBridge::nativeRedo);
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

        if (terrainHoleMode) {
            if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                final float x = event.getX(), y = event.getY();
                final boolean makeHole = terrainHoleValue;
                queueEvent(() -> NativeBridge.nativeSetTerrainHoleAtScreen(x, y, makeHole));
            }
            return true;
        }

        if (terrainPaintMode) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    terrainPaintStroke(event.getX(), event.getY(), 1);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (event.getPointerCount() == 1) terrainPaintStroke(event.getX(), event.getY(), 0);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    terrainPaintStroke(-1.0f, -1.0f, 2);
                    return true;
                default: return true;
            }
        }

        if (terrainSculptMode) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    terrainStroke(event.getX(), event.getY(), 1);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (event.getPointerCount() == 1) {
                        terrainStroke(event.getX(), event.getY(), 0);
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    terrainStroke(-1.0f, -1.0f, 2);
                    return true;
                default:
                    return true;
            }
        }

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
                    // Single-finger viewport dragging no longer rotates the camera.
                    // Use the dedicated LOOK joystick; two-finger pan/zoom remains enabled.
                    previousX = event.getX();
                    previousY = event.getY();
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
