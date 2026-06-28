package com.motiontrace.diary;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

final class CloudSyncClient {
    private static final String PREFS = "cloud_sync";
    private static final String KEY_BASE_URL = "base_url";
    private static final String KEY_TOKEN = "token";
    private static final String KEY_EMAIL = "email";

    private final Context context;

    CloudSyncClient(Context context) {
        this.context = context.getApplicationContext();
    }

    String getBaseUrl() {
        return prefs().getString(KEY_BASE_URL, "");
    }

    String getEmail() {
        return prefs().getString(KEY_EMAIL, "");
    }

    boolean isLoggedIn() {
        return !getToken().isEmpty();
    }

    void saveBaseUrl(String baseUrl) {
        prefs().edit().putString(KEY_BASE_URL, normalizeBaseUrl(baseUrl)).apply();
    }

    String register(String baseUrl, String email, String password) throws Exception {
        saveBaseUrl(baseUrl);
        JSONObject request = new JSONObject();
        request.put("email", email);
        request.put("password", password);
        JSONObject response = post("/auth/register", request, "");
        saveSession(email, response.optString("token", ""));
        return response.optString("token", "");
    }

    String login(String baseUrl, String email, String password) throws Exception {
        saveBaseUrl(baseUrl);
        JSONObject request = new JSONObject();
        request.put("email", email);
        request.put("password", password);
        JSONObject response = post("/auth/login", request, "");
        saveSession(email, response.optString("token", ""));
        return response.optString("token", "");
    }

    JSONObject upload(String payload) throws Exception {
        JSONObject request = new JSONObject();
        request.put("payload", payload == null ? "{}" : payload);
        return post("/sync/upload", request, getToken());
    }

    String download() throws Exception {
        JSONObject response = get("/sync/download", getToken());
        return response.optString("payload", "{}");
    }

    void logout() {
        prefs().edit()
                .remove(KEY_TOKEN)
                .remove(KEY_EMAIL)
                .apply();
    }

    private void saveSession(String email, String token) {
        prefs().edit()
                .putString(KEY_EMAIL, email)
                .putString(KEY_TOKEN, token)
                .apply();
    }

    private String getToken() {
        return prefs().getString(KEY_TOKEN, "");
    }

    private JSONObject get(String path, String token) throws Exception {
        HttpURLConnection connection = open(path, "GET", token);
        return readResponse(connection);
    }

    private JSONObject post(String path, JSONObject body, String token) throws Exception {
        HttpURLConnection connection = open(path, "POST", token);
        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        connection.setDoOutput(true);
        connection.setFixedLengthStreamingMode(bytes.length);
        try (OutputStream output = connection.getOutputStream()) {
            output.write(bytes);
        }
        return readResponse(connection);
    }

    private HttpURLConnection open(String path, String method, String token) throws Exception {
        String baseUrl = getBaseUrl();
        if (baseUrl.isEmpty()) {
            throw new IllegalStateException("请先填写云同步地址");
        }
        URL url = new URL(baseUrl + path);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(12000);
        connection.setReadTimeout(20000);
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        connection.setRequestProperty("Accept", "application/json");
        if (token != null && !token.isEmpty()) {
            connection.setRequestProperty("Authorization", "Bearer " + token);
        }
        return connection;
    }

    private JSONObject readResponse(HttpURLConnection connection) throws Exception {
        int code = connection.getResponseCode();
        InputStream input = code >= 200 && code < 300
                ? connection.getInputStream()
                : connection.getErrorStream();
        String text = readAll(input);
        if (code < 200 || code >= 300) {
            throw new IllegalStateException("云端请求失败：" + code + " " + text);
        }
        return text.isEmpty() ? new JSONObject() : new JSONObject(text);
    }

    private String readAll(InputStream input) throws Exception {
        if (input == null) {
            return "";
        }
        try (InputStream source = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = source.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private String normalizeBaseUrl(String value) {
        String url = value == null ? "" : value.trim();
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        return url;
    }

    private SharedPreferences prefs() {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
