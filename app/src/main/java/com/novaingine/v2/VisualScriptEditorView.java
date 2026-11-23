package com.novaingine.v2;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/** A lightweight touch-editable execution graph canvas for Novaingine. */
public final class VisualScriptEditorView extends View {
    public static final int BEGIN_PLAY = 0;
    public static final int TICK = 1;
    public static final int MOVE = 2;
    public static final int VISIBILITY = 4;
    public static final int BRANCH = 5;
    public static final int END = 6;
    public static final int ROTATE = 7;
    public static final int SCALE = 8;

    public static final class Node {
        public int id;
        public int type;
        public int next;
        public int trueNext;
        public int falseNext;
        public int objectId;
        public float x;
        public float y;
        public float vx;
        public float vy;
        public float vz;
        public float value = 1.0f;
        public float compareValue;
        public boolean condition = true;
    }

    private final List<Node> nodes = new ArrayList<>();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int selectedIndex = -1;
    private float lastX;
    private float lastY;
    private boolean dragging;
    private static final float NODE_W = 178.0f;
    private static final float NODE_H = 102.0f;

    public VisualScriptEditorView(Context context) {
        super(context);
        setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        setBackgroundColor(Color.rgb(19, 23, 30));
        addNode(BEGIN_PLAY);
        addNode(MOVE);
        addNode(END);
        autoConnect();
    }

    public Node addNode(int type) {
        Node node = new Node();
        node.id = nodes.size() + 1;
        node.type = type;
        node.x = 26.0f + (nodes.size() % 3) * 218.0f;
        node.y = 30.0f + (nodes.size() / 3) * 145.0f;
        if (type == MOVE) { node.vx = 0.0f; node.vy = 0.0f; node.vz = 0.25f; }
        if (type == ROTATE) { node.vy = 25.0f; }
        if (type == SCALE) { node.vx = 0.1f; node.vy = 0.1f; node.vz = 0.1f; }
        nodes.add(node);
        selectedIndex = nodes.size() - 1;
        invalidate();
        return node;
    }

    public void autoConnect() {
        for (int i = 0; i < nodes.size(); i++) {
            Node node = nodes.get(i);
            node.next = 0;
            node.trueNext = 0;
            node.falseNext = 0;
            if (i + 1 < nodes.size()) {
                if (node.type == BRANCH) {
                    node.trueNext = nodes.get(i + 1).id;
                    if (i + 2 < nodes.size()) node.falseNext = nodes.get(i + 2).id;
                } else if (node.type != END) {
                    node.next = nodes.get(i + 1).id;
                }
            }
        }
        invalidate();
    }

    public Node getSelectedNode() {
        if (selectedIndex < 0 || selectedIndex >= nodes.size()) return null;
        return nodes.get(selectedIndex);
    }

    public int[] buildNodeData() {
        int[] data = new int[nodes.size() * 6];
        for (int i = 0; i < nodes.size(); i++) {
            Node n = nodes.get(i);
            int p = i * 6;
            data[p] = n.id;
            data[p + 1] = n.type;
            data[p + 2] = n.next;
            data[p + 3] = n.trueNext;
            data[p + 4] = n.falseNext;
            data[p + 5] = n.objectId;
        }
        return data;
    }

    public float[] buildNodeValues() {
        float[] values = new float[nodes.size() * 6];
        for (int i = 0; i < nodes.size(); i++) {
            Node n = nodes.get(i);
            int p = i * 6;
            values[p] = n.vx;
            values[p + 1] = n.vy;
            values[p + 2] = n.vz;
            values[p + 3] = n.value;
            values[p + 4] = n.compareValue;
            values[p + 5] = n.condition ? 1.0f : 0.0f;
        }
        return values;
    }

    private Node findNode(int id) {
        for (Node n : nodes) if (n.id == id) return n;
        return null;
    }

    private String nodeName(int type) {
        switch (type) {
            case BEGIN_PLAY: return "EVENT • BEGIN PLAY";
            case TICK: return "EVENT • TICK";
            case MOVE: return "TRANSFORM • MOVE";
            case ROTATE: return "TRANSFORM • ROTATE";
            case SCALE: return "TRANSFORM • SCALE";
            case VISIBILITY: return "RENDER • SET VISIBILITY";
            case BRANCH: return "FLOW • BRANCH";
            default: return "FLOW • END";
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.0f);
        paint.setColor(0x223D5268);
        for (int x = 0; x < getWidth(); x += 24) canvas.drawLine(x, 0, x, getHeight(), paint);
        for (int y = 0; y < getHeight(); y += 24) canvas.drawLine(0, y, getWidth(), y, paint);

        // Draw execution wires behind the nodes.
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(3.0f);
        for (Node n : nodes) {
            if (n.type == BRANCH) {
                drawWire(canvas, n, n.trueNext, 0xFFFFB44C, -12.0f);
                drawWire(canvas, n, n.falseNext, 0xFF4DA8FF, 12.0f);
            } else {
                drawWire(canvas, n, n.next, 0xFF55C9A5, 0.0f);
            }
        }

        for (int i = 0; i < nodes.size(); i++) {
            Node n = nodes.get(i);
            RectF card = new RectF(n.x, n.y, n.x + NODE_W, n.y + NODE_H);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(n.type <= TICK ? 0xFF243B54 : n.type == BRANCH ? 0xFF493A27 : 0xFF303844);
            canvas.drawRoundRect(card, 12, 12, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(i == selectedIndex ? 4 : 2);
            paint.setColor(i == selectedIndex ? 0xFFFFB44C : 0xFF66788C);
            canvas.drawRoundRect(card, 12, 12, paint);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.WHITE);
            paint.setTextSize(12);
            canvas.drawText(nodeName(n.type), n.x + 10, n.y + 25, paint);
            paint.setColor(0xFFB9C5D2);
            paint.setTextSize(10);
            canvas.drawText("ID " + n.id + (n.objectId == 0 ? "  • Selected Object" : "  • Object " + n.objectId), n.x + 10, n.y + 48, paint);
            String detail = n.type == MOVE || n.type == ROTATE || n.type == SCALE
                    ? String.format(java.util.Locale.US, "X %.2f  Y %.2f  Z %.2f", n.vx, n.vy, n.vz)
                    : n.type == BRANCH ? "IF %.2f ≥ %.2f" : n.type == VISIBILITY ? (n.condition ? "Visible = true" : "Visible = false") : "Exec";
            canvas.drawText(detail, n.x + 10, n.y + 70, paint);
            paint.setColor(0xFF55C9A5);
            canvas.drawCircle(n.x + NODE_W - 5, n.y + NODE_H * 0.5f, 6, paint);
            paint.setColor(0xFF4DA8FF);
            canvas.drawCircle(n.x + 5, n.y + NODE_H * 0.5f, 6, paint);
        }
    }

    private void drawWire(Canvas canvas, Node from, int targetId, int color, float offset) {
        Node to = findNode(targetId);
        if (to == null) return;
        float sx = from.x + NODE_W;
        float sy = from.y + NODE_H * 0.5f + offset;
        float ex = to.x;
        float ey = to.y + NODE_H * 0.5f;
        float bend = Math.max(35.0f, Math.abs(ex - sx) * 0.45f);
        Path path = new Path();
        path.moveTo(sx, sy);
        path.cubicTo(sx + bend, sy, ex - bend, ey, ex, ey);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(3);
        paint.setColor(color);
        canvas.drawPath(path, paint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        float x = event.getX();
        float y = event.getY();
        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            lastX = x; lastY = y; dragging = false;
            for (int i = nodes.size() - 1; i >= 0; i--) {
                Node n = nodes.get(i);
                if (x >= n.x && x <= n.x + NODE_W && y >= n.y && y <= n.y + NODE_H) {
                    selectedIndex = i;
                    dragging = true;
                    invalidate();
                    return true;
                }
            }
            return true;
        }
        if (event.getAction() == MotionEvent.ACTION_MOVE && dragging) {
            Node n = getSelectedNode();
            if (n != null) {
                n.x = Math.max(0, n.x + x - lastX);
                n.y = Math.max(0, n.y + y - lastY);
                lastX = x; lastY = y;
                invalidate();
            }
            return true;
        }
        if (event.getAction() == MotionEvent.ACTION_UP) {
            performClick();
            return true;
        }
        return true;
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }
}
