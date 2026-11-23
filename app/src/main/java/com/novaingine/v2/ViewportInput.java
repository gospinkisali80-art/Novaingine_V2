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

    private float leftCenterX, rightCenterX, centerY;
    private float leftKnobX, leftKnobY, rightKnobX, rightKnobY;
    private float joystickRadius, knobRadius, density;

    private int movePointerId = -1;
    private int lookPointerId = -1;
    private int upPointerId = -1;
    private int downPointerId = -1;

    private boolean moveActive;
    private boolean lookActive;
    private boolean movingUp;
    private boolean movingDown;
    private boolean viewportTouchActive;

    private final RectF upButton = new RectF();
    private final RectF downButton = new RectF();

    public ViewportInput(Context context, EngineSurface engineSurface) {
        super(context);
        this.engineSurface = engineSurface;
        density = getResources().getDisplayMetrics().density;
        setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        setClickable(true);
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);

        joystickRadius = 55.0f * density;
        knobRadius = 23.0f * density;

        leftCenterX = 85.0f * density;
        rightCenterX = width - 85.0f * density;
        centerY = height - 90.0f * density;

        leftKnobX = leftCenterX;
        leftKnobY = centerY;
        rightKnobX = rightCenterX;
        rightKnobY = centerY;

        float buttonSize = 38.0f * density;
        float right = width - 150.0f * density;
        upButton.set(right - buttonSize, 8.0f * density,
                right, 8.0f * density + buttonSize);
        downButton.set(right - buttonSize, 52.0f * density,
                right, 52.0f * density + buttonSize);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        drawStick(canvas, leftCenterX, leftKnobX, leftKnobY, moveActive, "MOVE");
        drawStick(canvas, rightCenterX, rightKnobX, rightKnobY, lookActive, "LOOK");
        drawVerticalButtons(canvas);
    }

    private void drawStick(Canvas canvas, float centerX, float knobX,
                           float knobY, boolean active, String label) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0x55303030);
        canvas.drawCircle(centerX, centerY, joystickRadius, paint);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2.0f * density);
        paint.setColor(0x99FFFFFF);
        canvas.drawCircle(centerX, centerY, joystickRadius, paint);

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(active ? 0xDDFF9A00 : 0xAAFFFFFF);
        canvas.drawCircle(knobX, knobY, knobRadius, paint);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.5f * density);
        paint.setColor(Color.WHITE);
        canvas.drawCircle(knobX, knobY, knobRadius, paint);

        paint.setStyle(Paint.Style.FILL);
        paint.setTextSize(10.0f * density);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setColor(0xDDFFFFFF);
        canvas.drawText(label, centerX, centerY + joystickRadius + 14.0f * density, paint);
        paint.setTextAlign(Paint.Align.LEFT);
    }

    private void drawVerticalButtons(Canvas canvas) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(movingUp ? 0xFFFF9A00 : 0x88303030);
        canvas.drawRoundRect(upButton, 7.0f * density, 7.0f * density, paint);
        paint.setColor(movingDown ? 0xFFFF9A00 : 0x88303030);
        canvas.drawRoundRect(downButton, 7.0f * density, 7.0f * density, paint);

        paint.setColor(Color.WHITE);
        paint.setTextSize(18.0f * density);
        paint.setTextAlign(Paint.Align.CENTER);
        canvas.drawText("+", upButton.centerX(), upButton.centerY() + 6.0f * density, paint);
        canvas.drawText("-", downButton.centerX(), downButton.centerY() + 6.0f * density, paint);
        paint.setTextAlign(Paint.Align.LEFT);
    }

    private boolean insideStick(float x, float y, float centerX) {
        float dx = x - centerX;
        float dy = y - centerY;
        return dx * dx + dy * dy <= joystickRadius * joystickRadius * 2.5f;
    }

    private void updateStick(float x, float y, boolean look) {
        float centerX = look ? rightCenterX : leftCenterX;
        float dx = x - centerX;
        float dy = y - centerY;
        float distance = (float) Math.sqrt(dx * dx + dy * dy);

        if (distance > joystickRadius) {
            float scale = joystickRadius / distance;
            dx *= scale;
            dy *= scale;
        }

        float knobX = centerX + dx;
        float knobY = centerY + dy;
        float horizontal = dx / joystickRadius;
        float vertical = -dy / joystickRadius;

        if (look) {
            rightKnobX = knobX;
            rightKnobY = knobY;
            engineSurface.setLookJoystick(horizontal, -vertical);
        } else {
            leftKnobX = knobX;
            leftKnobY = knobY;
            engineSurface.setJoystick(vertical, horizontal);
        }
        invalidate();
    }

    private void resetMoveStick() {
        moveActive = false;
        movePointerId = -1;
        leftKnobX = leftCenterX;
        leftKnobY = centerY;
        engineSurface.setJoystick(0.0f, 0.0f);
        invalidate();
    }

    private void resetLookStick() {
        lookActive = false;
        lookPointerId = -1;
        rightKnobX = rightCenterX;
        rightKnobY = centerY;
        engineSurface.setLookJoystick(0.0f, 0.0f);
        invalidate();
    }

    private void updateVerticalMovement() {
        float movement = (movingUp ? 1.0f : 0.0f) - (movingDown ? 1.0f : 0.0f);
        engineSurface.setVerticalMovement(movement);
        invalidate();
    }

    private void releasePointer(int pointerId, float x, float y) {
        if (pointerId == movePointerId) resetMoveStick();
        if (pointerId == lookPointerId) resetLookStick();

        if (pointerId == upPointerId) {
            upPointerId = -1;
            movingUp = false;
        }
        if (pointerId == downPointerId) {
            downPointerId = -1;
            movingDown = false;
        }
        updateVerticalMovement();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        int index = event.getActionIndex();
        int pointerId = event.getPointerId(index);
        float x = event.getX(index);
        float y = event.getY(index);

        switch (action) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN:
                boolean controlTouch = insideStick(x, y, leftCenterX)
                        || insideStick(x, y, rightCenterX)
                        || upButton.contains(x, y) || downButton.contains(x, y);
                if (action == MotionEvent.ACTION_DOWN) viewportTouchActive = !controlTouch;
                if (viewportTouchActive) engineSurface.handleSculptTouch(event, getTop());
                if (viewportTouchActive && !controlTouch) engineSurface.dispatchTouchEvent(event);
                if (movePointerId == -1 && insideStick(x, y, leftCenterX)) {
                    movePointerId = pointerId;
                    moveActive = true;
                    updateStick(x, y, false);
                } else if (lookPointerId == -1 && insideStick(x, y, rightCenterX)) {
                    lookPointerId = pointerId;
                    lookActive = true;
                    updateStick(x, y, true);
                } else if (upPointerId == -1 && upButton.contains(x, y)) {
                    upPointerId = pointerId;
                    movingUp = true;
                    updateVerticalMovement();
                } else if (downPointerId == -1 && downButton.contains(x, y)) {
                    downPointerId = pointerId;
                    movingDown = true;
                    updateVerticalMovement();
                }
                return true;

            case MotionEvent.ACTION_MOVE:
                if (viewportTouchActive) {
                    engineSurface.handleSculptTouch(event, getTop());
                    engineSurface.dispatchTouchEvent(event);
                }
                if (movePointerId != -1) {
                    int moveIndex = event.findPointerIndex(movePointerId);
                    if (moveIndex >= 0) updateStick(event.getX(moveIndex), event.getY(moveIndex), false);
                }
                if (lookPointerId != -1) {
                    int lookIndex = event.findPointerIndex(lookPointerId);
                    if (lookIndex >= 0) updateStick(event.getX(lookIndex), event.getY(lookIndex), true);
                }
                return true;

            case MotionEvent.ACTION_POINTER_UP:
            case MotionEvent.ACTION_UP:
                if (viewportTouchActive) engineSurface.dispatchTouchEvent(event);
                releasePointer(pointerId, x, y);
                if (action == MotionEvent.ACTION_UP) {
                    if (movePointerId != -1) resetMoveStick();
                    if (lookPointerId != -1) resetLookStick();
                    upPointerId = -1;
                    downPointerId = -1;
                    movingUp = false;
                    movingDown = false;
                    updateVerticalMovement();
                    viewportTouchActive = false;
                }
                return true;

            case MotionEvent.ACTION_CANCEL:
                if (viewportTouchActive) engineSurface.dispatchTouchEvent(event);
                viewportTouchActive = false;
                resetMoveStick();
                resetLookStick();
                upPointerId = -1;
                downPointerId = -1;
                movingUp = false;
                movingDown = false;
                updateVerticalMovement();
                return true;

            default:
                return true;
        }
    }
}
