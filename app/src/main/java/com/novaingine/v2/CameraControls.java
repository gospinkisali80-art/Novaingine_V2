package com.novaingine.v2;

import android.view.View;

public final class CameraControls {

    private final EngineSurface engineSurface;

    private float cameraSpeed = 3.0f;

    public CameraControls(EngineSurface engineSurface) {
        if (engineSurface == null) {
            throw new IllegalArgumentException(
                    "EngineSurface cannot be null"
            );
        }

        this.engineSurface = engineSurface;

        engineSurface.setCameraSpeed(cameraSpeed);
    }

    public void setSpeed(float speed) {

        cameraSpeed = clamp(speed, 0.1f, 10.0f);

        engineSurface.setCameraSpeed(cameraSpeed);
    }

    public float getSpeed() {
        return cameraSpeed;
    }

    public void setSlowSpeed() {
        setSpeed(1.0f);
    }

    public void setNormalSpeed() {
        setSpeed(3.0f);
    }

    public void setFastSpeed() {
        setSpeed(6.0f);
    }

    public void setVeryFastSpeed() {
        setSpeed(10.0f);
    }

    public void resetCamera() {
        engineSurface.setCameraView(0);
    }

    public void focusAt(float screenX, float screenY) {

        if (!Float.isFinite(screenX) ||
                !Float.isFinite(screenY)) {
            return;
        }

        engineSurface.focusAt(screenX, screenY);
    }

    public void focusCenter(View viewport) {

        if (viewport == null) {
            return;
        }

        float centerX = viewport.getWidth() * 0.5f;
        float centerY = viewport.getHeight() * 0.5f;

        focusAt(centerX, centerY);
    }

    public void setPerspective() {
        engineSurface.setCameraView(0);
    }

    public void setTopView() {
        engineSurface.setCameraView(1);
    }

    public void setBottomView() {
        engineSurface.setCameraView(2);
    }

    public void setLeftView() {
        engineSurface.setCameraView(3);
    }

    public void setRightView() {
        engineSurface.setCameraView(4);
    }

    public void setFrontView() {
        engineSurface.setCameraView(5);
    }

    public void setBackView() {
        engineSurface.setCameraView(6);
    }

    private float clamp(
            float value,
            float minimum,
            float maximum) {

        if (!Float.isFinite(value)) {
            return minimum;
        }

        return Math.max(
                minimum,
                Math.min(maximum, value)
        );
    }
}
