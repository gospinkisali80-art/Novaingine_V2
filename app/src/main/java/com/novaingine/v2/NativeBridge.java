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
    public static native int nativeGetFps();

    public static native int nativeGetDrawCalls();

    public static native int nativeGetTriangles();

    public static native void nativeResetScene();
}
