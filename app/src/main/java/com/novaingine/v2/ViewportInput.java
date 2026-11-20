package com.novaingine.v2;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

public final class ViewportInput extends View {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final EngineSurface engineSurface;

    private float centerX;
    private float centerY;

    private float joystickX;
    private float joystickY;

    private float knobX;
    private float knobY;

    private float joystickRadius;
    private float knobRadius;

    private float activePointerX;
    private float activePointerY;

    private int joystickPointerId = -1;

    private boolean joystickActive;
    private boolean movingUp;
    private boolean movingDown;

    private final RectF upButton = new RectF();
    private final RectF downButton = new RectF();

    private float density;

    public ViewportInput(
            Context context,
            EngineSurface engineSurface) {

        super(context);

        this.engineSurface = engineSurface;

        density = getResources()
                .getDisplayMetrics().density;

        setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        setClickable(true);
    }

    @Override
    protected void onSizeChanged(
            int width,
            int height,
            int oldWidth,
            int oldHeight) {

        super.onSizeChanged(
                width,
                height,
                oldWidth,
                oldHeight
        );

        joystickRadius = 55.0f * density;
        knobRadius = 23.0f * density;

        centerX = 85.0f * density;
        centerY = height - 90.0f * density;

        joystickX = centerX;
        joystickY = centerY;

        knobX = centerX;
        knobY = centerY;

        float buttonSize = 42.0f * density;

        float right = width - 18.0f * density;
        float bottom = height - 18.0f * density;

        upButton.set(
                right - buttonSize,
                bottom - buttonSize * 2.3f,
                right,
                bottom - buttonSize * 1.3f
        );

        downButton.set(
                right - buttonSize,
                bottom - buttonSize,
                right,
                bottom
        );
    }

    @Override
    protected void onDraw(Canvas canvas) {

        super.onDraw(canvas);

        drawJoystick(canvas);
        drawVerticalButtons(canvas);
    }

    private void drawJoystick(Canvas canvas) {

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0x55303030);

        canvas.drawCircle(
                centerX,
                centerY,
                joystickRadius,
                paint
        );

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2.0f * density);
        paint.setColor(0x99FFFFFF);

        canvas.drawCircle(
                centerX,
                centerY,
                joystickRadius,
                paint
        );

        paint.setStyle(Paint.Style.FILL);

        paint.setColor(
                joystickActive
                        ? 0xDDFF9A00
                        : 0xAAFFFFFF
        );

        canvas.drawCircle(
                knobX,
                knobY,
                knobRadius,
                paint
        );

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.5f * density);
        paint.setColor(Color.WHITE);

        canvas.drawCircle(
                knobX,
                knobY,
                knobRadius,
                paint
        );

        paint.setStyle(Paint.Style.FILL);
    }

    private void drawVerticalButtons(Canvas canvas) {

        paint.setStyle(Paint.Style.FILL);

        paint.setColor(
                movingUp ? 0xFFFF9A00 : 0x88303030
        );

        canvas.drawRoundRect(
                upButton,
                8.0f * density,
                8.0f * density,
                paint
        );

        paint.setColor(
                movingDown ? 0xFFFF9A00 : 0x88303030
        );

        canvas.drawRoundRect(
                downButton,
                8.0f * density,
                8.0f * density,
                paint
        );

        paint.setColor(Color.WHITE);
        paint.setTextSize(18.0f * density);
        paint.setTextAlign(Paint.Align.CENTER);

        canvas.drawText(
                "+",
                upButton.centerX(),
                upButton.centerY() + 6.0f * density,
                paint
        );

        canvas.drawText(
                "-",
                downButton.centerX(),
                downButton.centerY() + 6.0f * density,
                paint
        );

        paint.setTextAlign(Paint.Align.LEFT);
    }

    private boolean insideJoystick(
            float x,
            float y) {

        float dx = x - centerX;
        float dy = y - centerY;

        return dx * dx + dy * dy
                <= joystickRadius * joystickRadius * 2.5f;
    }

    private void updateJoystick(
            float x,
            float y) {

        float dx = x - centerX;
        float dy = y - centerY;

        float distance = (float) Math.sqrt(
                dx * dx + dy * dy
        );

        if (distance > joystickRadius) {

            float scale = joystickRadius / distance;

            dx *= scale;
            dy *= scale;
        }

        knobX = centerX + dx;
        knobY = centerY + dy;

        float right = dx / joystickRadius;
        float forward = -dy / joystickRadius;

        engineSurface.setJoystick(
                forward,
                right
        );

        invalidate();
    }

    private void resetJoystick() {

        joystickActive = false;
        joystickPointerId = -1;

        knobX = centerX;
        knobY = centerY;

        engineSurface.setJoystick(
                0.0f,
                0.0f
        );

        invalidate();
    }

    private void updateVerticalMovement() {

        float movement = 0.0f;

        if (movingUp) {
            movement += 1.0f;
        }

        if (movingDown) {
            movement -= 1.0f;
        }

        engineSurface.setVerticalMovement(movement);

        invalidate();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {

        int action = event.getActionMasked();

        int actionIndex = event.getActionIndex();

        float x = event.getX(actionIndex);
        float y = event.getY(actionIndex);

        switch (action) {

            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN:

                if (!joystickActive && insideJoystick(x, y)) {

                    joystickActive = true;

                    joystickPointerId =
                            event.getPointerId(actionIndex);

                    activePointerX = x;
                    activePointerY = y;

                    updateJoystick(x, y);

                    return true;
                }

                if (upButton.contains(x, y)) {

                    movingUp = true;

                    updateVerticalMovement();

                    return true;
                }

                if (downButton.contains(x, y)) {

                    movingDown = true;

                    updateVerticalMovement();

                    return true;
                }

                return true;

            case MotionEvent.ACTION_MOVE:

                if (joystickActive) {

                    int pointerIndex =
                            event.findPointerIndex(
                                    joystickPointerId
                            );

                    if (pointerIndex >= 0) {

                        updateJoystick(
                                event.getX(pointerIndex),
                                event.getY(pointerIndex)
                        );
                    }
                }

                return true;

            case MotionEvent.ACTION_POINTER_UP:
            case MotionEvent.ACTION_UP:

                int releasedPointer =
                        event.getPointerId(actionIndex);

                if (releasedPointer == joystickPointerId) {
                    resetJoystick();
                }

                if (upButton.contains(x, y)) {
                    movingUp = false;
                }

                if (downButton.contains(x, y)) {
                    movingDown = false;
                }

                updateVerticalMovement();

                if (action == MotionEvent.ACTION_UP) {

                    movingUp = false;
                    movingDown = false;

                    updateVerticalMovement();
                }

                return true;

            case MotionEvent.ACTION_CANCEL:

                movingUp = false;
                movingDown = false;

                resetJoystick();
                updateVerticalMovement();

                return true;

            default:
                return true;
        }
    }
}
