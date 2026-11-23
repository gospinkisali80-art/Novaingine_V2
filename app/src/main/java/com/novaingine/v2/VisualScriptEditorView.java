package com.novaingine.v2;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.MotionEvent;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONException;
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
    private boolean panning;
    private float panX;
    private float panY;
    private float zoom = 1.0f;
    private float lastPinchDistance;
    private int pendingOutputNode;
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
        node.id = 1;
        for (Node existing : nodes) node.id = Math.max(node.id, existing.id + 1);
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

        canvas.save();
        canvas.translate(panX, panY);
        canvas.scale(zoom, zoom);

        // Draw execution wires behind the nodes.
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(3.0f);
        for (Node n : nodes) {
            if (n.type == BRANCH) {
                drawWire(canvas, n, n.trueNext, Color.WHITE, -12.0f);
                drawWire(canvas, n, n.falseNext, Color.WHITE, 12.0f);
            } else {
                drawWire(canvas, n, n.next, Color.WHITE, 0.0f);
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
        canvas.restore();
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
        final int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_POINTER_DOWN && event.getPointerCount() >= 2) {
            dragging = false; panning = false;
            lastPinchDistance = distance(event);
            lastX = (event.getX(0) + event.getX(1)) * 0.5f;
            lastY = (event.getY(0) + event.getY(1)) * 0.5f;
            return true;
        }
        if (action == MotionEvent.ACTION_MOVE && event.getPointerCount() >= 2) {
            float d = distance(event);
            float cx = (event.getX(0) + event.getX(1)) * 0.5f;
            float cy = (event.getY(0) + event.getY(1)) * 0.5f;
            if (lastPinchDistance > 0.0f && d > 0.0f) {
                zoom = Math.max(0.45f, Math.min(2.0f, zoom * d / lastPinchDistance));
                panX += cx - lastX; panY += cy - lastY;
            }
            lastPinchDistance = d; lastX = cx; lastY = cy;
            invalidate(); return true;
        }
        if (action == MotionEvent.ACTION_DOWN) {
            lastX = event.getX(); lastY = event.getY();
            dragging = false; panning = false;
            float wx = (lastX - panX) / zoom;
            float wy = (lastY - panY) / zoom;
            for (int i = nodes.size() - 1; i >= 0; i--) {
                Node n = nodes.get(i);
                if (Math.hypot(wx - (n.x + NODE_W - 5), wy - (n.y + NODE_H * 0.5f)) < 17) {
                    pendingOutputNode = n.id; selectedIndex = i; invalidate(); return true;
                }
                if (Math.hypot(wx - (n.x + 5), wy - (n.y + NODE_H * 0.5f)) < 17
                        && pendingOutputNode != 0 && pendingOutputNode != n.id) {
                    Node from = findNode(pendingOutputNode);
                    if (from != null) {
                        if (from.type == BRANCH) {
                            if (from.trueNext == 0) from.trueNext = n.id;
                            else from.falseNext = n.id;
                        } else if (from.type != END) from.next = n.id;
                    }
                    pendingOutputNode = 0; invalidate(); return true;
                }
            }
            for (int i = nodes.size() - 1; i >= 0; i--) {
                Node n = nodes.get(i);
                if (wx >= n.x && wx <= n.x + NODE_W && wy >= n.y && wy <= n.y + NODE_H) {
                    selectedIndex = i; dragging = true; invalidate(); return true;
                }
            }
            panning = true; return true;
        }
        if (action == MotionEvent.ACTION_MOVE) {
            float dx = event.getX() - lastX, dy = event.getY() - lastY;
            if (dragging) {
                Node n = getSelectedNode();
                if (n != null) { n.x = Math.max(0, n.x + dx / zoom); n.y = Math.max(0, n.y + dy / zoom); }
            } else if (panning) { panX += dx; panY += dy; }
            lastX = event.getX(); lastY = event.getY();
            invalidate(); return true;
        }
        if (action == MotionEvent.ACTION_POINTER_UP) {
            lastPinchDistance = 0; dragging = false; panning = false; return true;
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            dragging = false; panning = false; lastPinchDistance = 0;
            performClick(); return true;
        }
        return true;
    }

    private float distance(MotionEvent e) {
        float dx = e.getX(0) - e.getX(1);
        float dy = e.getY(0) - e.getY(1);
        return (float)Math.sqrt(dx * dx + dy * dy);
    }

    public String toJson() {
        try {
            JSONObject root = new JSONObject();
            root.put("version", 1);
            JSONArray array = new JSONArray();
            for (Node n : nodes) {
                JSONObject item = new JSONObject();
                item.put("id", n.id); item.put("type", n.type);
                item.put("next", n.next); item.put("trueNext", n.trueNext); item.put("falseNext", n.falseNext);
                item.put("objectId", n.objectId); item.put("x", n.x); item.put("y", n.y);
                item.put("vx", n.vx); item.put("vy", n.vy); item.put("vz", n.vz);
                item.put("value", n.value); item.put("compareValue", n.compareValue); item.put("condition", n.condition);
                array.put(item);
            }
            root.put("nodes", array);
            return root.toString(2);
        } catch (JSONException error) { return "{}"; }
    }

    public boolean loadJson(String json) {
        try {
            JSONObject root = new JSONObject(json);
            JSONArray array = root.getJSONArray("nodes");
            if (array.length() == 0 || array.length() > 128) return false;
            List<Node> loaded = new ArrayList<>();
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.getJSONObject(i);
                Node n = new Node();
                n.id = item.getInt("id"); n.type = item.getInt("type");
                if (n.id <= 0 || n.type < BEGIN_PLAY || n.type > SCALE) return false;
                n.next = item.optInt("next"); n.trueNext = item.optInt("trueNext"); n.falseNext = item.optInt("falseNext");
                n.objectId = item.optInt("objectId"); n.x = (float)item.optDouble("x"); n.y = (float)item.optDouble("y");
                n.vx = (float)item.optDouble("vx"); n.vy = (float)item.optDouble("vy"); n.vz = (float)item.optDouble("vz");
                n.value = (float)item.optDouble("value", 1.0); n.compareValue = (float)item.optDouble("compareValue");
                n.condition = item.optBoolean("condition", true);
                loaded.add(n);
            }
            nodes.clear(); nodes.addAll(loaded); selectedIndex = 0; pendingOutputNode = 0; invalidate(); return true;
        } catch (Exception error) { return false; }
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }
}
