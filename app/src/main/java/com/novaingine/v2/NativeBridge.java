package com.novaingine.v2;

public final class NativeBridge {

    static {
        System.loadLibrary("nova_engine");
    }

    private NativeBridge() {}

    public static native void nativeInit();

    public static native void nativeResize(int width, int height);

    public static native void nativeRender(float deltaTime);

    public static native void nativeTouch(float deltaX, float deltaY);

    public static native void nativeZoom(float scale);

    public static native void nativeAddCube();

    public static native void nativeSetPaused(boolean paused);
}
