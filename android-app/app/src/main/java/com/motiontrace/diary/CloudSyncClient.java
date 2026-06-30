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
    private static final String KEY_USERNAME = "username";
    private static final String KEY_EMAIL = "email";
    private static final String KEY_REALTIME_SYNC = "realtime_sync";

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

    String getUsername() {
        return prefs().getString(KEY_USERNAME, "");
    }

    boolean isConfigured() {
        return !getBaseUrl().isEmpty();
    }

    boolean isLoggedIn() {
        return !getToken().isEmpty();
    }

    boolean isRealtimeSyncEnabled() {
        return prefs().getBoolean(KEY_REALTIME_SYNC, false);
    }

    void setRealtimeSyncEnabled(boolean enabled) {
        prefs().edit().putBoolean(KEY_REALTIME_SYNC, enabled).apply();
    }

    String register(String username, String password, String email) throws Exception {
        JSONObject request = new JSONObject();
        request.put("username", username);
        request.put("email", email);
        request.put("password", password);
        JSONObject response = post("/auth/register", request, "");
        String token = response.optString("token", "");
        if (token.isEmpty()) {
            throw new IllegalStateException("云端登录返回异常");
        }
        saveSession(response, username, email, token);
        return token;
    }

    String login(String username, String password) throws Exception {
        JSONObject request = new JSONObject();
        request.put("username", username);
        request.put("password", password);
        JSONObject response = post("/auth/login", request, "");
        String token = response.optString("token", "");
        if (token.isEmpty()) {
            throw new IllegalStateException("云端登录返回异常");
        }
        saveSession(response, username, "", token);
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

    void uploadRealtimePoint(RealtimeTrackPoint point) throws Exception {
        if (point == null) {
            return;
        }
        JSONObject request = new JSONObject();
        request.put("date", point.date);
        request.put("tripId", point.tripId);
        request.put("tripIndex", point.tripIndex);
        request.put("pointIndex", point.pointIndex);
        request.put("timestamp", point.timestamp);
        request.put("longitude", point.longitude);
        request.put("latitude", point.latitude);
        request.put("accuracy", point.accuracy);
        request.put("speed", point.speed);
        post("/sync/track-point", request, getToken());
    }

    RealtimeTrackPoint buildRealtimePoint(TrackStore.DayRecord day, TrackStore.PointRecord point) {
        if (day == null || point == null || point.timestamp <= 0L) {
            return null;
        }
        int pointIndex = resolvePointIndex(day, point);
        int tripIndex = resolveTripIndex(day, point);
        return new RealtimeTrackPoint(
                day.date == null ? "" : day.date,
                point.tripId == null ? "" : point.tripId,
                tripIndex,
                pointIndex,
                point.timestamp,
                point.longitude,
                point.latitude,
                point.accuracy,
                point.speed
        );
    }

    String download() throws Exception {
        JSONObject response = get("/sync/download", getToken());
        return response.optString("payload", "{}");
    }

    void logout() {
        prefs().edit()
                .remove(KEY_TOKEN)
                .remove(KEY_USERNAME)
                .remove(KEY_EMAIL)
                .remove(KEY_REALTIME_SYNC)
                .apply();
    }

    private void saveSession(JSONObject response, String fallbackUsername, String fallbackEmail, String token) {
        JSONObject user = response.optJSONObject("user");
        String username = user == null ? fallbackUsername : user.optString("username", fallbackUsername);
        String email = user == null ? fallbackEmail : user.optString("email", fallbackEmail);
        prefs().edit()
                .putString(KEY_USERNAME, username == null ? "" : username)
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
        if ("username_exists".equals(error)) {
            return "用户名已存在";
        }
        if ("email_exists".equals(error)) {
            return "邮箱已注册";
        }
        if ("invalid_username".equals(error)) {
            return "用户名需为 3-32 位字母、数字、下划线、点或横线";
        }
        if ("invalid_email".equals(error)) {
            return "邮箱格式不正确";
        }
        if ("invalid_credentials".equals(error)) {
            return code == 400 ? "用户名或密码不符合要求" : "用户名或密码不正确";
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
        if ("invalid_track_point".equals(error)) {
            return "实时轨迹点异常";
        }
        if ("server_error".equals(error)) {
            return "云端服务异常";
        }
        return "云端请求失败：" + code;
    }

    private int resolvePointIndex(TrackStore.DayRecord day, TrackStore.PointRecord point) {
        for (int i = 0; i < day.points.size(); i++) {
            TrackStore.PointRecord candidate = day.points.get(i);
            if (candidate == point || samePoint(candidate, point)) {
                return i;
            }
        }
        return Math.max(0, day.points.size() - 1);
    }

    private boolean samePoint(TrackStore.PointRecord a, TrackStore.PointRecord b) {
        return a != null
                && b != null
                && a.timestamp == b.timestamp
                && Double.compare(a.latitude, b.latitude) == 0
                && Double.compare(a.longitude, b.longitude) == 0;
    }

    private int resolveTripIndex(TrackStore.DayRecord day, TrackStore.PointRecord point) {
        String tripId = point.tripId == null ? "" : point.tripId;
        if (!tripId.isEmpty()) {
            for (int i = 0; i < day.trips.size(); i++) {
                TrackStore.TripRecord trip = day.trips.get(i);
                if (trip != null && tripId.equals(trip.id)) {
                    return i + 1;
                }
            }
        }
        for (int i = 0; i < day.trips.size(); i++) {
            TrackStore.TripRecord trip = day.trips.get(i);
            if (trip == null || trip.startTime <= 0L) {
                continue;
            }
            long end = trip.endTime > 0L ? trip.endTime : Long.MAX_VALUE;
            if (point.timestamp >= trip.startTime && point.timestamp <= end) {
                return i + 1;
            }
        }
        return 0;
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

    static final class RealtimeTrackPoint {
        final String date;
        final String tripId;
        final int tripIndex;
        final int pointIndex;
        final long timestamp;
        final double longitude;
        final double latitude;
        final double accuracy;
        final double speed;

        RealtimeTrackPoint(
                String date,
                String tripId,
                int tripIndex,
                int pointIndex,
                long timestamp,
                double longitude,
                double latitude,
                double accuracy,
                double speed
        ) {
            this.date = date;
            this.tripId = tripId;
            this.tripIndex = tripIndex;
            this.pointIndex = pointIndex;
            this.timestamp = timestamp;
            this.longitude = longitude;
            this.latitude = latitude;
            this.accuracy = accuracy;
            this.speed = speed;
        }
    }
}
