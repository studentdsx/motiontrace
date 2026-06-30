package com.motiontrace.diary;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

final class CloudSyncClient {
    private static final String TAG = "MotionTrace";
    private static final String PREFS = "cloud_sync";
    private static final String KEY_TOKEN = "token";
    private static final String KEY_EMAIL = "email";

    private final Context context;

    CloudSyncClient(Context context) {
        this.context = context.getApplicationContext();
    }

    String getBaseUrl() {
        return normalizeBaseUrl(BuildConfig.CLOUD_WORKER_URL);
    }

    String getEmail() {
        return prefs().getString(KEY_EMAIL, "");
    }

    boolean isConfigured() {
        return !getBaseUrl().isEmpty();
    }

    boolean isLoggedIn() {
        return !getToken().isEmpty();
    }

    String register(String email, String password) throws Exception {
        JSONObject request = new JSONObject();
        request.put("email", email);
        request.put("password", password);
        JSONObject response = post("/auth/register", request, "");
        String token = response.optString("token", "");
        if (token.isEmpty()) {
            throw new IllegalStateException("云端登录返回异常");
        }
        saveSession(email, token);
        return token;
    }

    String login(String email, String password) throws Exception {
        JSONObject request = new JSONObject();
        request.put("email", email);
        request.put("password", password);
        JSONObject response = post("/auth/login", request, "");
        String token = response.optString("token", "");
        if (token.isEmpty()) {
            throw new IllegalStateException("云端登录返回异常");
        }
        saveSession(email, token);
        return token;
    }

    JSONObject changePassword(String currentPassword, String newPassword) throws Exception {
        JSONObject request = new JSONObject();
        request.put("currentPassword", currentPassword);
        request.put("newPassword", newPassword);
        return post("/auth/change-password", request, getToken());
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
            throw new IllegalStateException("云同步服务未配置");
        }
        URL url = new URL(baseUrl + path);
        Log.i(TAG, "cloud request: " + method + " " + url);
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
        Log.i(TAG, "cloud response: " + code + " " + connection.getURL().getPath());
        InputStream input = code >= 200 && code < 300
                ? connection.getInputStream()
                : connection.getErrorStream();
        String text = readAll(input);
        if (code < 200 || code >= 300) {
            throw new IllegalStateException(toUserMessage(code, text));
        }
        return text.isEmpty() ? new JSONObject() : new JSONObject(text);
    }

    private String toUserMessage(int code, String text) {
        String error = "";
        try {
            error = text == null || text.isEmpty() ? "" : new JSONObject(text).optString("error", "");
        } catch (Exception ignored) {
        }
        if ("email_exists".equals(error)) {
            return "邮箱已注册";
        }
        if ("invalid_credentials".equals(error)) {
            return code == 400 ? "邮箱格式或密码不符合要求" : "邮箱或密码不正确";
        }
        if ("invalid_password".equals(error)) {
            return "新密码至少 8 位，且不超过 128 位";
        }
        if ("password_unchanged".equals(error)) {
            return "新密码不能和旧密码相同";
        }
        if ("unauthorized".equals(error)) {
            return "请先登录";
        }
        if ("payload_too_large".equals(error)) {
            return "云端数据包过大";
        }
        if ("server_error".equals(error)) {
            return "云端服务异常";
        }
        return "云端请求失败：" + code;
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
