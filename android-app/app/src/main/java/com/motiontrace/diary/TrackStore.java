package com.motiontrace.diary;

import android.content.Context;
import android.location.Location;
import android.net.Uri;

import com.amap.api.location.AMapLocation;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

final class TrackStore {
    private static final String STORE_FILE = "motion_tracks_v1.json";
    private static final int MAX_LOCATION_ACCURACY = 120;
    private static final double MIN_POINT_DISTANCE = 3.0;
    private static final double SLOW_MIN_POINT_DISTANCE = 8.0;
    private static final double FAST_MIN_POINT_DISTANCE = 2.0;
    private static final double MAX_SEGMENT_DISTANCE = 1000.0;

    private TrackStore() {
    }

    static synchronized DayRecord appendPoint(Context context, Location location) {
        if (!isUsable(location)) {
            return getDay(context, today());
        }
        return appendPoint(
                context,
                location.getLatitude(),
                location.getLongitude(),
                location.hasAccuracy() ? location.getAccuracy() : 0.0,
                location.hasSpeed() ? location.getSpeed() : 0.0
        );
    }

    static synchronized DayRecord appendPoint(Context context, AMapLocation location) {
        if (!isUsable(location)) {
            return getDay(context, today());
        }
        return appendPoint(context, location.getLatitude(), location.getLongitude(), location.getAccuracy(), location.getSpeed());
    }

    static synchronized DayRecord appendPoint(
            Context context,
            double latitude,
            double longitude,
            double accuracy,
            double speed
    ) {
        if (!isUsable(latitude, longitude, accuracy)) {
            return getDay(context, today());
        }

        String date = today();
        JSONObject root = readRoot(context);
        JSONObject day = root.optJSONObject(date);
        if (day == null) {
            day = createDay(date);
        }

        JSONArray points = day.optJSONArray("points");
        if (points == null) {
            points = new JSONArray();
        }

        PointRecord last = points.length() > 0 ? parsePoint(points.optJSONObject(points.length() - 1)) : null;
        long timestamp = System.currentTimeMillis();
        double minPointDistance = resolveMinPointDistance(speed);
        double gap = last == null ? 0.0 : GeoUtils.distanceMeters(
                last.latitude,
                last.longitude,
                latitude,
                longitude
        );

        try {
            if (last != null && gap < minPointDistance) {
                day.put("endTime", timestamp);
                saveDay(context, root, date, day);
                return parseDay(day);
            }

            if (last != null && gap < MAX_SEGMENT_DISTANCE) {
                day.put("distanceMeters", day.optDouble("distanceMeters", 0.0) + gap);
            }

            if (day.optLong("startTime", 0L) == 0L) {
                day.put("startTime", timestamp);
            }

            JSONObject point = new JSONObject();
            point.put("latitude", latitude);
            point.put("longitude", longitude);
            point.put("accuracy", accuracy);
            point.put("speed", speed);
            point.put("timestamp", timestamp);
            points.put(point);

            day.put("points", points);
            day.put("endTime", timestamp);
            saveDay(context, root, date, day);
        } catch (JSONException ignored) {
        }

        return parseDay(day);
    }

    static synchronized DayRecord addCheckin(
            Context context,
            String date,
            Location location,
            String note,
            List<String> photos
    ) {
        if (!isUsable(location)) {
            return getDay(context, date);
        }
        return addCheckin(context, date, location.getLatitude(), location.getLongitude(), note, photos);
    }

    static synchronized DayRecord addCheckin(
            Context context,
            String date,
            AMapLocation location,
            String note,
            List<String> photos
    ) {
        return addCheckin(context, date, location.getLatitude(), location.getLongitude(), note, photos);
    }

    static synchronized DayRecord addCheckin(
            Context context,
            String date,
            double latitude,
            double longitude,
            String note,
            List<String> photos
    ) {
        JSONObject root = readRoot(context);
        JSONObject day = root.optJSONObject(date);
        if (day == null) {
            day = createDay(date);
        }

        long timestamp = System.currentTimeMillis();
        JSONArray current = day.optJSONArray("checkins");
        JSONArray next = new JSONArray();

        try {
            JSONObject checkin = new JSONObject();
            checkin.put("id", "checkin_" + timestamp);
            checkin.put("latitude", latitude);
            checkin.put("longitude", longitude);
            checkin.put("timestamp", timestamp);
            checkin.put("note", note == null ? "" : note);

            JSONArray photoArray = new JSONArray();
            if (photos != null) {
                for (String path : photos) {
                    photoArray.put(path);
                }
            }
            checkin.put("photos", photoArray);

            next.put(checkin);
            if (current != null) {
                for (int i = 0; i < current.length(); i++) {
                    next.put(current.optJSONObject(i));
                }
            }

            if (day.optLong("startTime", 0L) == 0L) {
                day.put("startTime", timestamp);
            }
            day.put("endTime", Math.max(day.optLong("endTime", 0L), timestamp));
            day.put("checkins", next);
            saveDay(context, root, date, day);
        } catch (JSONException ignored) {
        }

        return parseDay(day);
    }

    static synchronized void startTrip(Context context) {
        String date = today();
        JSONObject root = readRoot(context);
        JSONObject day = root.optJSONObject(date);
        if (day == null) {
            day = createDay(date);
        }

        long timestamp = System.currentTimeMillis();
        try {
            JSONArray trips = day.optJSONArray("trips");
            if (trips == null) {
                trips = new JSONArray();
            }
            if (activeTrip(trips) == null) {
                JSONObject trip = new JSONObject();
                trip.put("id", "trip_" + timestamp);
                trip.put("startTime", timestamp);
                trip.put("endTime", 0L);
                trips.put(trip);
                day.put("trips", trips);
            }
            if (day.optLong("startTime", 0L) == 0L) {
                day.put("startTime", timestamp);
            }
            day.put("endTime", Math.max(day.optLong("endTime", 0L), timestamp));
            saveDay(context, root, date, day);
        } catch (JSONException ignored) {
        }
    }

    static synchronized void finishActiveTrip(Context context) {
        String date = today();
        JSONObject root = readRoot(context);
        JSONObject day = root.optJSONObject(date);
        if (day == null) {
            return;
        }

        JSONArray trips = day.optJSONArray("trips");
        if (trips == null) {
            return;
        }

        JSONObject active = activeTrip(trips);
        if (active == null) {
            return;
        }

        long timestamp = System.currentTimeMillis();
        try {
            active.put("endTime", timestamp);
            day.put("trips", trips);
            day.put("endTime", Math.max(day.optLong("endTime", 0L), timestamp));
            saveDay(context, root, date, day);
        } catch (JSONException ignored) {
        }
    }

    static synchronized DayRecord getDay(Context context, String date) {
        JSONObject day = readRoot(context).optJSONObject(date);
        if (day == null) {
            day = createDay(date);
        }
        return parseDay(day);
    }

    static synchronized List<DayRecord> listDays(Context context) {
        JSONObject root = readRoot(context);
        List<DayRecord> days = new ArrayList<>();
        Iterator<String> keys = root.keys();
        while (keys.hasNext()) {
            JSONObject day = root.optJSONObject(keys.next());
            if (day != null) {
                days.add(parseDay(day));
            }
        }
        Collections.sort(days, new Comparator<DayRecord>() {
            @Override
            public int compare(DayRecord a, DayRecord b) {
                return b.date.compareTo(a.date);
            }
        });
        return days;
    }

    static synchronized String exportSnapshot(Context context) {
        return readRoot(context).toString();
    }

    static synchronized void importSnapshot(Context context, String payload) throws JSONException {
        JSONObject imported = payload == null || payload.trim().isEmpty()
                ? new JSONObject()
                : new JSONObject(payload);
        writeRoot(context, imported);
    }

    static synchronized void clearAll(Context context) {
        File store = new File(context.getFilesDir(), STORE_FILE);
        if (store.exists()) {
            store.delete();
        }
        deleteRecursively(new File(context.getFilesDir(), "checkin_photos"));
    }

    static synchronized String saveImage(Context context, Uri uri) throws Exception {
        File dir = new File(context.getFilesDir(), "checkin_photos");
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IllegalStateException("Cannot create photo directory");
        }

        File outFile = new File(dir, "photo_" + System.currentTimeMillis() + "_" + Math.abs(uri.hashCode()) + ".jpg");
        try (InputStream input = context.getContentResolver().openInputStream(uri);
             FileOutputStream output = new FileOutputStream(outFile)) {
            if (input == null) {
                throw new IllegalArgumentException("Cannot open image");
            }
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
        }
        return outFile.getAbsolutePath();
    }

    static String today() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(new Date());
    }

    static String weekLabel(Date date) {
        return new SimpleDateFormat("EEEE", Locale.CHINA).format(date);
    }

    static Stats buildStats(DayRecord day, boolean recording) {
        long end = recording && day.startTime > 0L ? System.currentTimeMillis() : day.endTime;
        long duration = day.startTime > 0L && end > day.startTime ? end - day.startTime : 0L;
        return new Stats(
                formatDistance(day.distanceMeters),
                formatDuration(duration),
                String.valueOf(day.tripCount),
                String.valueOf(day.checkins.size())
        );
    }

    static String formatClock(long timestamp) {
        if (timestamp <= 0L) {
            return "--:--";
        }
        return new SimpleDateFormat("HH:mm", Locale.CHINA).format(new Date(timestamp));
    }

    private static boolean isUsable(Location location) {
        return location != null
                && isUsable(
                location.getLatitude(),
                location.getLongitude(),
                location.hasAccuracy() ? location.getAccuracy() : 0.0
        );
    }

    private static boolean isUsable(AMapLocation location) {
        return location != null
                && location.getErrorCode() == 0
                && isUsable(location.getLatitude(), location.getLongitude(), location.getAccuracy());
    }

    private static boolean isUsable(double latitude, double longitude, double accuracy) {
        return latitude >= -90.0
                && latitude <= 90.0
                && longitude >= -180.0
                && longitude <= 180.0
                && (accuracy <= 0.0 || accuracy <= MAX_LOCATION_ACCURACY);
    }

    private static double resolveMinPointDistance(double speed) {
        if (speed >= 8.0) {
            return FAST_MIN_POINT_DISTANCE;
        }
        if (speed <= 0.8) {
            return SLOW_MIN_POINT_DISTANCE;
        }
        return MIN_POINT_DISTANCE;
    }

    private static JSONObject createDay(String date) {
        JSONObject day = new JSONObject();
        try {
            day.put("date", date);
            day.put("points", new JSONArray());
            day.put("checkins", new JSONArray());
            day.put("trips", new JSONArray());
            day.put("distanceMeters", 0.0);
            day.put("startTime", 0L);
            day.put("endTime", 0L);
            day.put("updatedAt", System.currentTimeMillis());
        } catch (JSONException ignored) {
        }
        return day;
    }

    private static JSONObject activeTrip(JSONArray trips) {
        for (int i = trips.length() - 1; i >= 0; i--) {
            JSONObject trip = trips.optJSONObject(i);
            if (trip != null && trip.optLong("endTime", 0L) == 0L) {
                return trip;
            }
        }
        return null;
    }

    private static void saveDay(Context context, JSONObject root, String date, JSONObject day) throws JSONException {
        day.put("updatedAt", System.currentTimeMillis());
        root.put(date, day);
        writeRoot(context, root);
    }

    private static JSONObject readRoot(Context context) {
        File file = new File(context.getFilesDir(), STORE_FILE);
        if (!file.exists()) {
            return new JSONObject();
        }

        try (InputStream input = new BufferedInputStream(new FileInputStream(file));
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            return new JSONObject(new String(output.toByteArray(), StandardCharsets.UTF_8));
        } catch (Exception ignored) {
            return new JSONObject();
        }
    }

    private static void writeRoot(Context context, JSONObject root) {
        try (FileOutputStream output = context.openFileOutput(STORE_FILE, Context.MODE_PRIVATE)) {
            output.write(root.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child);
                }
            }
        }
        file.delete();
    }

    private static DayRecord parseDay(JSONObject day) {
        DayRecord record = new DayRecord();
        record.date = day.optString("date", today());
        record.distanceMeters = day.optDouble("distanceMeters", 0.0);
        record.startTime = day.optLong("startTime", 0L);
        record.endTime = day.optLong("endTime", 0L);
        record.tripCount = parseTripCount(day);

        JSONArray points = day.optJSONArray("points");
        if (points != null) {
            for (int i = 0; i < points.length(); i++) {
                PointRecord point = parsePoint(points.optJSONObject(i));
                if (point != null) {
                    record.points.add(point);
                }
            }
        }

        JSONArray checkins = day.optJSONArray("checkins");
        if (checkins != null) {
            for (int i = 0; i < checkins.length(); i++) {
                CheckinRecord checkin = parseCheckin(checkins.optJSONObject(i));
                if (checkin != null) {
                    record.checkins.add(checkin);
                }
            }
        }

        return record;
    }

    private static int parseTripCount(JSONObject day) {
        JSONArray trips = day.optJSONArray("trips");
        if (trips != null) {
            return trips.length();
        }
        JSONArray points = day.optJSONArray("points");
        return points != null && points.length() > 0 ? 1 : 0;
    }

    private static PointRecord parsePoint(JSONObject object) {
        if (object == null) {
            return null;
        }
        PointRecord point = new PointRecord();
        point.latitude = object.optDouble("latitude", 0.0);
        point.longitude = object.optDouble("longitude", 0.0);
        point.accuracy = object.optDouble("accuracy", 0.0);
        point.speed = object.optDouble("speed", 0.0);
        point.timestamp = object.optLong("timestamp", 0L);
        return point;
    }

    private static CheckinRecord parseCheckin(JSONObject object) {
        if (object == null) {
            return null;
        }
        CheckinRecord checkin = new CheckinRecord();
        checkin.id = object.optString("id", "");
        checkin.latitude = object.optDouble("latitude", 0.0);
        checkin.longitude = object.optDouble("longitude", 0.0);
        checkin.timestamp = object.optLong("timestamp", 0L);
        checkin.note = object.optString("note", "");
        JSONArray photos = object.optJSONArray("photos");
        if (photos != null) {
            for (int i = 0; i < photos.length(); i++) {
                String path = photos.optString(i, "");
                if (!path.isEmpty()) {
                    checkin.photos.add(path);
                }
            }
        }
        return checkin;
    }

    private static String formatDistance(double meters) {
        if (meters < 1000.0) {
            return Math.round(meters) + " m";
        }
        return String.format(Locale.CHINA, "%.2f km", meters / 1000.0);
    }

    private static String formatDuration(long millis) {
        long totalMinutes = Math.max(0L, millis / 60000L);
        long hours = totalMinutes / 60L;
        long minutes = totalMinutes % 60L;
        if (hours > 0L) {
            return hours + "小时" + minutes + "分";
        }
        return minutes + "分";
    }

    static final class DayRecord {
        String date;
        double distanceMeters;
        long startTime;
        long endTime;
        int tripCount;
        final List<PointRecord> points = new ArrayList<>();
        final List<CheckinRecord> checkins = new ArrayList<>();
    }

    static final class PointRecord {
        double latitude;
        double longitude;
        double accuracy;
        double speed;
        long timestamp;
    }

    static final class CheckinRecord {
        String id;
        double latitude;
        double longitude;
        long timestamp;
        String note;
        final List<String> photos = new ArrayList<>();
    }

    static final class Stats {
        final String distance;
        final String duration;
        final String trips;
        final String checkins;

        Stats(String distance, String duration, String trips, String checkins) {
            this.distance = distance;
            this.duration = duration;
            this.trips = trips;
            this.checkins = checkins;
        }
    }
}
