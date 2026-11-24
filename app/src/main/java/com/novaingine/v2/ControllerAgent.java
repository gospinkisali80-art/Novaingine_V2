package com.novaingine.v2;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Outbound-only Android client for the Novaingine local/hosted controller.
 * Runs all HTTP and command work off the UI and GL threads.
 *
 * API contract:
 * POST /api/v1/device/heartbeat
 * GET  /api/v1/device/commands/next?deviceId=<id>
 * POST /api/v1/device/commands/<commandId>/result
 */
public final class ControllerAgent implements AutoCloseable {
    public interface StatusListener { void onStatus(String status); }

    private static final String PREFS = "nova_controller";
    private static final String KEY_ALIAS = "nova_controller_token_key";
    private static final String PREF_URL = "controller_url";
    private static final String PREF_DEVICE = "device_id";
    private static final String PREF_TOKEN = "encrypted_token";

    private final Context appContext;
    private final EngineSurface engine;
    private final StatusListener listener;
    private final ScheduledExecutorService loop = Executors.newSingleThreadScheduledExecutor();
    private volatile boolean closed;
    private volatile boolean busy;
    private volatile long lastHeartbeat;
    private RemoteCommandDispatcher dispatcher;
    private String baseUrl;
    private String deviceId;
    private String token;

    public ControllerAgent(Context context, EngineSurface engine, StatusListener listener) {
        this.appContext = context.getApplicationContext();
        this.engine = engine;
        this.listener = listener;
        loadConfiguration();
    }

    public static boolean isConfigured(Context context) {
        SharedPreferences p = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return p.contains(PREF_URL) && p.contains(PREF_DEVICE) && p.contains(PREF_TOKEN);
    }

    public static String getOrCreateDeviceId(Context context) {
        SharedPreferences p = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String id = p.getString(PREF_DEVICE, null);
        if (id == null || id.length() < 8) {
            id = UUID.randomUUID().toString();
            p.edit().putString(PREF_DEVICE, id).apply();
        }
        return id;
    }

    public static void saveConfiguration(Context context, String url, String token) throws Exception {
        String normalized = url == null ? "" : url.trim().replaceAll("/+$", "");
        String secret = token == null ? "" : token.trim();
        if (!(normalized.startsWith("https://") || normalized.equals("http://127.0.0.1:8000")
                || normalized.equals("http://localhost:8000")))
            throw new IllegalArgumentException("Use HTTPS, or localhost/127.0.0.1 for local Termux testing.");
        if (secret.length() < 24) throw new IllegalArgumentException("Controller token is too short.");
        String device = getOrCreateDeviceId(context);
        byte[] iv;
        byte[] encrypted;
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey());
        iv = cipher.getIV();
        encrypted = cipher.doFinal(secret.getBytes(StandardCharsets.UTF_8));
        String stored = Base64.encodeToString(iv, Base64.NO_WRAP) + ":" +
                Base64.encodeToString(encrypted, Base64.NO_WRAP);
        context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(PREF_URL, normalized)
                .putString(PREF_DEVICE, device)
                .putString(PREF_TOKEN, stored)
                .apply();
    }

    public static void clearConfiguration(Context context) {
        context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .remove(PREF_URL).remove(PREF_TOKEN).apply();
    }

    private static SecretKey getOrCreateKey() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (store.containsAlias(KEY_ALIAS)) return (SecretKey) store.getKey(KEY_ALIAS, null);
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build());
        return generator.generateKey();
    }

    private void loadConfiguration() {
        try {
            SharedPreferences p = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            baseUrl = p.getString(PREF_URL, null);
            deviceId = p.getString(PREF_DEVICE, null);
            String stored = p.getString(PREF_TOKEN, null);
            if (baseUrl == null || deviceId == null || stored == null) return;
            String[] parts = stored.split(":", 2);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(),
                    new GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)));
            token = new String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), StandardCharsets.UTF_8);
            dispatcher = new RemoteCommandDispatcher(engine, deviceId);
        } catch (Exception e) {
            baseUrl = null;
            token = null;
            notifyStatus("PAIRING ERROR — reconnect controller");
        }
    }

    public boolean isReady() { return dispatcher != null && token != null; }

    public void start() {
        if (!isReady() || closed) return;
        notifyStatus("AI LINK CONNECTING");
        loop.scheduleWithFixedDelay(this::tickSafely, 0, 2, TimeUnit.SECONDS);
    }

    private void tickSafely() {
        if (closed || busy) return;
        busy = true;
        try {
            long now = System.currentTimeMillis();
            if (now - lastHeartbeat >= 20000L) {
                JSONObject heartbeat = new JSONObject();
                heartbeat.put("protocolVersion", 1);
                heartbeat.put("deviceId", deviceId);
                heartbeat.put("deviceName", android.os.Build.MODEL);
                heartbeat.put("platform", "android");
                heartbeat.put("appVersion", "2.0.0");
                request("POST", "/api/v1/device/heartbeat", heartbeat);
                lastHeartbeat = now;
                notifyStatus("AI LINK ONLINE");
            }
            String query = "?deviceId=" + java.net.URLEncoder.encode(deviceId, "UTF-8");
            JSONObject response = request("GET", "/api/v1/device/commands/next" + query, null);
            JSONObject command = response.optJSONObject("command");
            if (command != null) {
                notifyStatus("AI LINK EXECUTING");
                dispatcher.submit(command, result -> {
                    try {
                        request("POST", "/api/v1/device/commands/" +
                                java.net.URLEncoder.encode(result.optString("commandId"), "UTF-8") + "/result", result);
                        notifyStatus("AI LINK ONLINE");
                    } catch (Exception e) {
                        notifyStatus("AI LINK RESULT RETRY REQUIRED");
                    }
                });
            }
        } catch (Exception e) {
            notifyStatus("AI LINK OFFLINE — checking connection");
        } finally {
            busy = false;
        }
    }

    private JSONObject request(String method, String path, JSONObject body) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(baseUrl + path).openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(8000);
        connection.setRequestProperty("Authorization", "Bearer " + token);
        connection.setRequestProperty("Accept", "application/json");
        if (body != null) {
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream out = connection.getOutputStream()) { out.write(bytes); }
        }
        int status = connection.getResponseCode();
        InputStream stream = status >= 200 && status < 300 ? connection.getInputStream() : connection.getErrorStream();
        StringBuilder text = new StringBuilder();
        if (stream != null) {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) text.append(line);
            }
        }
        connection.disconnect();
        if (status < 200 || status >= 300) throw new IllegalStateException("Controller HTTP " + status);
        return text.length() == 0 ? new JSONObject() : new JSONObject(text.toString());
    }

    private void notifyStatus(String status) {
        if (listener != null) engine.post(() -> listener.onStatus(status));
    }

    @Override public void close() {
        closed = true;
        loop.shutdownNow();
        if (dispatcher != null) dispatcher.close();
    }
}
