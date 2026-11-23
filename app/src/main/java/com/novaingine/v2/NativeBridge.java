package com.novaingine.v2;

public final class NativeBridge {

    static {
        System.loadLibrary("nova_engine");
    }

    private NativeBridge() {
    }

    public static native void nativeInit();

    public static native void nativeResize(
            int width,
            int height
    );

    public static native void nativeRender(
            float deltaTime
    );

    public static native void nativeTouch(
            float deltaX,
            float deltaY
    );

    public static native void nativeZoom(
            float scale
    );

    public static native void nativePan(
            float deltaX,
            float deltaY
    );

    public static native void nativeFly(
            float forward,
            float right,
            float vertical,
            float deltaTime
    );

    public static native void nativeFocus(
            float screenX,
            float screenY
    );

    public static native void nativeSelect(
            float screenX,
            float screenY
    );

    public static native void nativeSetViewMode(
            int mode
    );

    public static native void nativeSetCameraView(
            int view
    );

    public static native void nativeSetShowFlags(
            int flags
    );

    public static native void nativeSetTransformMode(
            int mode
    );

    public static native void nativeSetWorldSpace(
            boolean worldSpace
    );

    public static native void nativeSetSnap(
            boolean enabled,
            float grid,
            float rotation,
            float scale
    );

    public static native void nativeMoveSelected(
            float deltaX,
            float deltaY,
            float deltaZ
    );

    public static native void nativeSetCameraSpeed(
            float speed
    );

    public static native void nativeSetExposure(float exposure);

    public static native void nativeSetPhysicsEnabled(
            boolean enabled
    );

    public static native void nativeSetPaused(
            boolean paused
    );

    public static native boolean nativeSaveScene(String path);
    public static native boolean nativeLoadScene(String path);

    public static native String[] nativeGetSceneObjects();
    public static native void nativeSelectObjectById(int objectId);
    public static native float[] nativeGetSelectedTransform();
    public static native void nativeSetSelectedTransform(float[] values);
    public static native int nativePaintFoliage(float centerX, float centerZ, float radius, int count, int kind);
    public static native int nativeClearFoliage();
    public static native boolean nativeCreateTerrain();
    public static native boolean nativeSculptTerrain(
            float centerX, float centerZ, float radius, float strength, int mode
    );

    public static native int nativeImportGlb(String path);
    public static native int nativeCreateSculptMesh(int shape, int density);
    public static native int nativeCreateMeshPart(float[] positions, int[] triangles, String name, float x, float y, float z, float r, float g, float b);
    public static native boolean nativeExportSelectedObj(String path);
    public static native void nativeSetManualSculptWorkspace(boolean enabled);
    public static native void nativeSetSculptMode(boolean enabled);
    public static native int nativeEnterSculptMode();
    public static native void nativeUpdateSculptCursor(float x, float y, float radius, boolean active);
    public static native void nativeBeginSculptStroke();
    public static native int nativeSculptAtScreen(float x, float y, float radius, float strength, int brush, float falloff, int symmetryAxis, boolean subtract, float pressure);
    public static native void nativeEndSculptStroke();
    public static native boolean nativeSetVisualScript(int[] nodeData, float[] nodeValues, boolean enabled);
    public static native int nativeSculptSelectedMesh(float x, float y, float z, float radius, float strength, int mode, boolean symmetry);
    public static native int nativeAISculptSelectedMesh(float x, float y, float z, float radius, float strength, int brush, float falloff, boolean symmetry, float dragX, float dragY, float dragZ);
    public static native int nativeAISculptStroke(float[] points, float radius, float strength, int brush, float hardness, int symmetryAxis, boolean subtract, float pressure, float dragX, float dragY, float dragZ);
    // operation: 0 extrude face, 1 inset face, 2 bevel edge, 3 loop cut, 4 subdivision, 5 merge by distance, 6 knife triangle split.
    public static native int nativeModelSelectedMesh(int operation, int elementIndex, float amount);

    public static native int nativeAddPrimitive(int primitive, String name, float x, float y, float z, float sx, float sy, float sz, float r, float g, float b);

    public static native int nativeGetFps();

    public static native int nativeGetDrawCalls();

    public static native int nativeGetTriangles();

    public static native void nativeResetScene();
    public static native boolean nativeUndo();
    public static native boolean nativeRedo();
}
