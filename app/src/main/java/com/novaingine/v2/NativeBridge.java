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

    public static native void nativeOrbitSelected(
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

    public static native void nativeSetWorldEnvironment(float[] values);

    public static native void nativeSetPhysicsEnabled(
            boolean enabled
    );
    public static native boolean nativeSetSelectedRigidBody(boolean enabled, float mass, float restitution, float friction);

    public static native void nativeSetPaused(
            boolean paused
    );

    public static native boolean nativeSaveScene(String path);
    public static native boolean nativeLoadScene(String path);

    public static native String[] nativeGetSceneObjects();
    public static native void nativeSelectObjectById(int objectId);
    public static native float[] nativeGetSelectedTransform();
    public static native float[] nativeProjectSelectedPosition();
    public static native float[] nativeProjectSelectedGizmo();
    public static native void nativeSetSelectedTransform(float[] values);
    public static native void nativeBeginTransformEdit();
    public static native void nativeUpdateSelectedTransform(float[] values);
    public static native int nativePaintFoliage(float centerX, float centerZ, float radius, int count, int kind);
    public static native int nativeClearFoliage();
    public static native boolean nativeCreateTerrain();
    public static native boolean nativeSculptTerrain(
            float centerX, float centerZ, float radius, float strength, int mode
    );
    public static native boolean nativeSculptTerrainAtScreen(
            float screenX, float screenY, float radius, float strength, int mode, int phase
    );
    public static native boolean nativeSetTerrainHeights(float[] heights);
    public static native boolean nativePaintTerrainAtScreen(float screenX, float screenY, float radius, float strength, int material, int phase);
    public static native boolean nativeRunTerrainErosion(int type, int iterations, float strength);
    public static native int nativeAddLandscapeLayer(String name);
    public static native boolean nativeSelectLandscapeLayer(int index);
    public static native boolean nativeSetLandscapeLayerState(int index, boolean visible, boolean locked, float heightAlpha, float paintAlpha);
    public static native boolean nativeMoveLandscapeLayer(int from, int to);
    public static native boolean nativeDeleteLandscapeLayer(int index);
    public static native boolean nativeSetTerrainHoleAtScreen(float screenX, float screenY, boolean makeHole);
    public static native boolean nativeAddLandscapeSpline(float[] points);
    public static native boolean nativeCreateLandscapeCave(float[] pathPoints, float radius);
    public static native int nativeGetLandscapeChunkCount();
    public static native int nativeGetLandscapeLayerCount();
    public static native String nativeGetLandscapeLayerName(int index);
    public static native float[] nativeGetLandscapeLayerState(int index);
    public static native boolean nativeSetLandscapeChunkSize(int quadsPerChunk);
    public static native float[] nativeGetTerrainHeights();

    public static native int nativeImportGlb(String path);
    public static native int nativeCreateSculptMesh(int shape, int density);
    public static native int nativeRefineSculptMesh();
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

    public static native void nativeCreateEmptyScene();
    public static native void nativeResetScene();
    public static native boolean nativeUndo();
    public static native boolean nativeRedo();
}
