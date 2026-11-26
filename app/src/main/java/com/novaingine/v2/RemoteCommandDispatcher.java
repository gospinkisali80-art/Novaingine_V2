package com.novaingine.v2;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Local, typed command boundary for the Novaingine remote-agent work.
 *
 * This class deliberately has no network listener and does not store credentials.
 * A future authenticated transport must call submit() only after verifying the
 * gateway/device session and must keep the deviceId bound to the paired device.
 */
public final class RemoteCommandDispatcher implements AutoCloseable {
    public interface ResultCallback {
        void onResult(JSONObject result);
    }

    private interface RenderCall<T> { T run(); }

    private final EngineSurface engine;
    private final String pairedDeviceId;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Set<String> completedIds = new LinkedHashSet<>();

    public RemoteCommandDispatcher(EngineSurface engine, String pairedDeviceId) {
        if (engine == null) throw new IllegalArgumentException("EngineSurface is required.");
        if (pairedDeviceId == null || pairedDeviceId.trim().length() < 8)
            throw new IllegalArgumentException("A paired device ID of at least 8 characters is required.");
        this.engine = engine;
        this.pairedDeviceId = pairedDeviceId.trim();
    }

    /** Submit a protocol-v1 JSON command. Callback is invoked on the worker thread. */
    public void submit(JSONObject command, ResultCallback callback) {
        worker.execute(() -> {
            JSONObject result = dispatch(command);
            if (callback != null) callback.onResult(result);
        });
    }

    private JSONObject dispatch(JSONObject command) {
        String commandId = command == null ? "" : command.optString("commandId", "");
        try {
            validateEnvelope(command);
            synchronized (completedIds) {
                if (completedIds.contains(commandId))
                    return result(commandId, "failed", "duplicate_command", "This command ID was already processed.");
                completedIds.add(commandId);
                while (completedIds.size() > 512) {
                    String first = completedIds.iterator().next();
                    completedIds.remove(first);
                }
            }

            JSONObject payload = command.getJSONObject("payload");
            String operation = command.getString("operation");
            String details;
            switch (operation) {
                case "device.get_status":
                    details = "Local Android command dispatcher is available. Network transport is not enabled in this build.";
                    break;
                case "scene.inspect":
                    details = inspectScene(payload.optBoolean("includeTransforms", true));
                    break;
                case "scene.select_object": {
                    int id = payload.getInt("objectId");
                    boolean selected = runOnRenderThread(() -> {
                        String[] objects = engine.getSceneObjects();
                        String prefix = id + "|";
                        if (objects == null) return false;
                        for (String object : objects) {
                            if (object != null && object.startsWith(prefix)) {
                                NativeBridge.nativeSelectObjectById(id);
                                return true;
                            }
                        }
                        return false;
                    });
                    if (!selected) throw new IllegalArgumentException("Object ID " + id + " does not exist in the current scene.");
                    details = "Selected object ID " + id + ".";
                    break;
                }
                case "object.set_transform": {
                    float[] t = new float[]{
                            vec(payload, "position", 0), vec(payload, "position", 1), vec(payload, "position", 2),
                            vec(payload, "rotation", 0), vec(payload, "rotation", 1), vec(payload, "rotation", 2),
                            vec(payload, "scale", 0), vec(payload, "scale", 1), vec(payload, "scale", 2)
                    };
                    boolean updated = runOnRenderThread(() -> {
                        float[] current = engine.getSelectedTransform();
                        if (current == null || current.length < 9
                                || current[6] <= 0 || current[7] <= 0 || current[8] <= 0) return false;
                        NativeBridge.nativeSetSelectedTransform(t);
                        return true;
                    });
                    if (!updated) throw new IllegalStateException("Select an object before changing its transform.");
                    details = "Selected object transform updated.";
                    break;
                }
                case "object.create_cube": {
                    int id = runOnRenderThread(() -> {
                        int createdId = NativeBridge.nativeAddPrimitive(
                                0, "Cube",
                                0.0f, 0.5f, 0.0f,
                                1.0f, 1.0f, 1.0f,
                                0.72f, 0.74f, 0.78f
                        );
                        if (createdId <= 0) return 0;
                        String[] objects = engine.getSceneObjects();
                        String prefix = createdId + "|";
                        if (objects != null) {
                            for (String object : objects) {
                                if (object != null && object.startsWith(prefix)) {
                                    NativeBridge.nativeSelectObjectById(createdId);
                                    return createdId;
                                }
                            }
                        }
                        return 0;
                    });
                    if (id <= 0) throw new IllegalStateException("Engine could not create the cube in the active scene.");
                    details = "Created and selected Cube, object ID " + id + ".";
                    break;
                }
                case "sculpt.create_mesh": {
                    String shape = payload.getString("shape");
                    int shapeId = "plane".equals(shape) ? 1 : 0;
                    int density = payload.getInt("density");
                    int id = runOnRenderThread(() -> NativeBridge.nativeCreateSculptMesh(shapeId, density));
                    if (id <= 0) throw new IllegalStateException("Engine rejected sculpt mesh creation (result " + id + ").");
                    details = "Created and selected " + shape + " SculptMesh, object ID " + id + ".";
                    break;
                }
                case "sculpt.stroke": {
                    JSONArray pointsJson = payload.getJSONArray("points");
                    float[] points = new float[pointsJson.length()];
                    for (int i = 0; i < points.length; i++) points[i] = (float) pointsJson.getDouble(i);
                    int brush = payload.getInt("brush");
                    float radius = (float) payload.getDouble("radius");
                    float strength = (float) payload.getDouble("strength");
                    float falloff = (float) payload.optDouble("falloff", 0.5);
                    int symmetry = payload.optInt("symmetryAxis", -1);
                    boolean subtract = payload.optBoolean("subtract", false);
                    float pressure = (float) payload.optDouble("pressure", 1.0);
                    int queued = runOnRenderThread(() -> NativeBridge.nativeAISculptStroke(
                            points, radius, strength, brush, falloff, symmetry, subtract, pressure, 0, 0, 0));
                    if (queued <= 0) throw new IllegalStateException("Sculpt stroke was rejected (result " + queued + ").");
                    details = "Sculpt stroke accepted: " + queued + " interpolated dabs queued.";
                    break;
                }
                case "scene.undo": {
                    boolean ok = runOnRenderThread(NativeBridge::nativeUndo);
                    if (!ok) throw new IllegalStateException("There is nothing to undo.");
                    details = "Undo completed.";
                    break;
                }
                case "scene.redo": {
                    boolean ok = runOnRenderThread(NativeBridge::nativeRedo);
                    if (!ok) throw new IllegalStateException("There is nothing to redo.");
                    details = "Redo completed.";
                    break;
                }
                default:
                    throw new IllegalArgumentException("Operation is not supported by this agent.");
            }
            return result(commandId, "succeeded", "ok", details);
        } catch (Exception ex) {
            return result(commandId, "failed", "command_rejected",
                    ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
        }
    }

    private void validateEnvelope(JSONObject c) throws Exception {
        if (c == null) throw new IllegalArgumentException("Command is missing.");
        if (c.getInt("protocolVersion") != 1) throw new IllegalArgumentException("Unsupported protocol version.");
        String id = c.getString("commandId");
        try { UUID.fromString(id); } catch (Exception e) { throw new IllegalArgumentException("commandId must be a UUID."); }
        if (!pairedDeviceId.equals(c.getString("deviceId")))
            throw new SecurityException("Command targets a different paired device.");
        Instant issued = Instant.parse(c.getString("issuedAt"));
        Instant expires = Instant.parse(c.getString("expiresAt"));
        Instant now = Instant.now();
        if (expires.isBefore(now) || expires.isAfter(now.plusSeconds(300)))
            throw new IllegalArgumentException("Command is expired or has an invalid expiry window.");
        if (issued.isAfter(now.plusSeconds(30)) || issued.isBefore(now.minusSeconds(300)))
            throw new IllegalArgumentException("Command issue time is outside the allowed clock window.");
        if (!expires.isAfter(issued)) throw new IllegalArgumentException("expiresAt must be after issuedAt.");
        JSONObject payload = c.getJSONObject("payload");
        String op = c.getString("operation");
        switch (op) {
            case "device.get_status":
            case "scene.inspect":
            case "scene.undo":
            case "scene.redo":
                if (payload.length() != 0 && !(op.equals("scene.inspect") && payload.length() == 1 && payload.has("includeTransforms")))
                    throw new IllegalArgumentException("Unexpected payload fields.");
                break;
            case "scene.select_object":
                int objectId = payload.getInt("objectId");
                if (objectId <= 0 || payload.length() != 1) throw new IllegalArgumentException("Invalid objectId payload.");
                break;
            case "object.set_transform":
                validateVector(payload.getJSONArray("position"), -1000, 1000);
                validateVector(payload.getJSONArray("rotation"), -36000, 36000);
                validateVector(payload.getJSONArray("scale"), 0.01, 100);
                if (payload.length() != 3) throw new IllegalArgumentException("Unexpected transform fields.");
                break;
            case "object.create_cube":
                if (payload.length() != 0) throw new IllegalArgumentException("Cube creation does not accept payload fields.");
                break;
            case "sculpt.create_mesh":
                String shape = payload.getString("shape");
                int density = payload.getInt("density");
                if (!shape.equals("sphere") && !shape.equals("plane")) throw new IllegalArgumentException("Unsupported sculpt mesh shape.");
                if (density < 16 || density > 80 || payload.length() != 2) throw new IllegalArgumentException("Sculpt density must be 16–80.");
                break;
            case "sculpt.stroke":
                JSONArray points = payload.getJSONArray("points");
                if (points.length() < 3 || points.length() > 192 || points.length() % 3 != 0)
                    throw new IllegalArgumentException("Sculpt points must contain 1–64 XYZ points.");
                Set<String> allowedStrokeFields = new LinkedHashSet<>();
                java.util.Collections.addAll(allowedStrokeFields,
                        "points", "radius", "strength", "brush",
                        "falloff", "symmetryAxis", "subtract", "pressure");
                if (payload.length() < 4 || payload.length() > allowedStrokeFields.size())
                    throw new IllegalArgumentException("Invalid sculpt stroke fields.");
                java.util.Iterator<String> fieldNames = payload.keys();
                while (fieldNames.hasNext()) {
                    if (!allowedStrokeFields.contains(fieldNames.next()))
                        throw new IllegalArgumentException("Unexpected sculpt stroke field.");
                }
                for (int i = 0; i < points.length(); i++) {
                    double v = points.getDouble(i);
                    if (!Double.isFinite(v) || v < -20 || v > 20) throw new IllegalArgumentException("Sculpt coordinates must be finite and within -20..20.");
                }
                double radius = payload.getDouble("radius"), strength = payload.getDouble("strength");
                int brush = payload.getInt("brush"), symmetry = payload.optInt("symmetryAxis", -1);
                double falloff = payload.optDouble("falloff", 0.5), pressure = payload.optDouble("pressure", 1);
                if (radius < 0.02 || radius > 1 || strength < 0 || strength > 1 || brush < 0 || brush > 24
                        || falloff < 0 || falloff > 1 || pressure < 0.05 || pressure > 1
                        || (symmetry != -1 && symmetry != 0 && symmetry != 1 && symmetry != 2))
                    throw new IllegalArgumentException("Sculpt parameters are outside safe limits.");
                break;
            default:
                throw new IllegalArgumentException("Operation is not allowlisted.");
        }
    }

    private void validateVector(JSONArray a, double min, double max) throws JSONException {
        if (a.length() != 3) throw new IllegalArgumentException("Vector must have exactly 3 values.");
        for (int i = 0; i < 3; i++) {
            double v = a.getDouble(i);
            if (!Double.isFinite(v) || v < min || v > max) throw new IllegalArgumentException("Vector component is outside allowed bounds.");
        }
    }

    private float vec(JSONObject payload, String key, int index) throws JSONException {
        return (float) payload.getJSONArray(key).getDouble(index);
    }

    private String inspectScene(boolean includeTransforms) throws Exception {
        return runOnRenderThread(() -> {
            JSONArray rows = new JSONArray();
            String[] objects = engine.getSceneObjects();
            if (objects != null) for (int i = 0; i < Math.min(objects.length, 200); i++) rows.put(objects[i]);
            float[] transform = engine.getSelectedTransform();
            JSONObject snapshot = new JSONObject();
            try {
                snapshot.put("objects", rows);
                boolean hasSelection = transform != null && transform.length >= 9
                        && transform[6] > 0 && transform[7] > 0 && transform[8] > 0;
                snapshot.put("hasSelection", hasSelection);
                if (includeTransforms && hasSelection) {
                    snapshot.put("selectedTransform", new JSONArray(new float[]{
                            transform[0], transform[1], transform[2], transform[3], transform[4],
                            transform[5], transform[6], transform[7], transform[8]}));
                }
                return snapshot.toString();
            } catch (JSONException e) {
                throw new IllegalStateException(e);
            }
        });
    }

    private <T> T runOnRenderThread(RenderCall<T> call) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<T> value = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicBoolean cancelled = new AtomicBoolean(false);
        AtomicBoolean started = new AtomicBoolean(false);
        Object startGate = new Object();
        engine.queueEvent(() -> {
            synchronized (startGate) {
                if (cancelled.get()) {
                    latch.countDown();
                    return;
                }
                started.set(true);
            }
            try { value.set(call.run()); }
            catch (Throwable t) { failure.set(t); }
            finally { latch.countDown(); }
        });
        if (!latch.await(12, TimeUnit.SECONDS)) {
            synchronized (startGate) {
                if (!started.get()) {
                    cancelled.set(true);
                    throw new IllegalStateException("Renderer did not respond within 12 seconds; command was cancelled before execution.");
                }
            }
            if (!latch.await(30, TimeUnit.SECONDS))
                throw new IllegalStateException("Renderer operation started but did not finish. Check the editor before retrying.");
        }
        if (failure.get() != null) throw new IllegalStateException(failure.get().getMessage());
        return value.get();
    }

    private JSONObject result(String id, String status, String code, String message) {
        JSONObject out = new JSONObject();
        try {
            out.put("protocolVersion", 1);
            out.put("commandId", id);
            out.put("deviceId", pairedDeviceId);
            out.put("status", status);
            out.put("code", code);
            out.put("message", message);
            out.put("completedAt", Instant.now().toString());
        } catch (JSONException ignored) { }
        return out;
    }

    @Override public void close() { worker.shutdownNow(); }
}
