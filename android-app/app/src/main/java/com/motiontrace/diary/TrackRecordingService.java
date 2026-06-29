package com.motiontrace.diary;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import com.amap.api.location.AMapLocation;
import com.amap.api.location.AMapLocationClient;
import com.amap.api.location.AMapLocationClientOption;
import com.amap.api.location.AMapLocationListener;
import com.amap.api.maps.MapsInitializer;

public final class TrackRecordingService extends Service implements AMapLocationListener {
    private static final String TAG = "MotionTraceService";
    static final String ACTION_START = "com.motiontrace.diary.action.START";
    static final String ACTION_STOP = "com.motiontrace.diary.action.STOP";
    static final String ACTION_TRACK_UPDATED = "com.motiontrace.diary.action.TRACK_UPDATED";

    private static final String PREFS = "recording_state";
    private static final String KEY_RECORDING = "recording";
    private static final String CHANNEL_ID = "track_recording";
    private static final int NOTIFICATION_ID = 1717;
    private static final long FAST_INTERVAL_MS = 1000L;
    private static final long ACTIVE_INTERVAL_MS = 2000L;
    private static final long NORMAL_INTERVAL_MS = 5000L;
    private static final long SLOW_INTERVAL_MS = 10000L;
    private static final float ACTIVE_SPEED_MPS = 3.0f;
    private static final float FAST_SPEED_MPS = 8.0f;
    private static final float SLOW_SPEED_MPS = 0.8f;
    private static final String AMAP_KEY = BuildConfig.AMAP_KEY;

    private AMapLocationClient locationClient;
    private boolean updatesStarted;
    private long currentIntervalMs = SLOW_INTERVAL_MS;
    private static volatile boolean serviceAlive;

    static boolean isRecording(Context context) {
        return serviceAlive && context.getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_RECORDING, false);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        serviceAlive = true;
        MapsInitializer.updatePrivacyShow(this, true, true);
        MapsInitializer.updatePrivacyAgree(this, true);
        MapsInitializer.setApiKey(AMAP_KEY);
        AMapLocationClient.updatePrivacyShow(this, true, true);
        AMapLocationClient.updatePrivacyAgree(this, true);
        AMapLocationClient.setApiKey(AMAP_KEY);
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopTracking();
            return START_NOT_STICKY;
        }

        startTracking();
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        removeUpdates();
        TrackStore.finishActiveTrip(this);
        setRecording(false);
        serviceAlive = false;
        super.onDestroy();
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        stopTracking();
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onLocationChanged(AMapLocation location) {
        if (location == null || location.getErrorCode() != 0) {
            logLocationFailure(location);
            adjustLocationInterval(location);
            updateNotification();
            return;
        }
        TrackStore.appendPoint(this, location);
        adjustLocationInterval(location);
        updateNotification();
        Intent update = new Intent(ACTION_TRACK_UPDATED);
        update.setPackage(getPackageName());
        sendBroadcast(update);
    }

    private void startTracking() {
        if (!hasForegroundLocationPermission()) {
            stopTracking();
            return;
        }

        setRecording(true);
        TrackStore.startTrip(this);
        startForegroundCompat(buildNotification());

        if (!updatesStarted) {
            startAmapLocation();
            updatesStarted = true;
        }
        updateNotification();
    }

    private void stopTracking() {
        removeUpdates();
        TrackStore.finishActiveTrip(this);
        setRecording(false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } else {
            stopForeground(true);
        }
        stopSelf();
    }

    private void startAmapLocation() {
        try {
            if (locationClient == null) {
                locationClient = new AMapLocationClient(getApplicationContext());
                locationClient.setLocationListener(this);
            }
            AMapLocationClientOption option = new AMapLocationClientOption();
            option.setLocationMode(AMapLocationClientOption.AMapLocationMode.Hight_Accuracy);
            option.setGpsFirst(true);
            option.setInterval(currentIntervalMs);
            option.setNeedAddress(false);
            option.setOnceLocation(false);
            option.setLocationCacheEnable(true);
            locationClient.setLocationOption(option);
            locationClient.startLocation();
        } catch (Exception e) {
            Log.w(TAG, "track location startup failed", e);
        }
    }

    private void logLocationFailure(AMapLocation location) {
        int code = location == null ? -1 : location.getErrorCode();
        String info = location == null ? "no location result" : String.valueOf(location.getErrorInfo());
        String detail = location == null ? "" : String.valueOf(location.getLocationDetail());
        Log.w(TAG, "track location failed: code=" + code + ", info=" + info + ", detail=" + detail);
    }

    private void adjustLocationInterval(AMapLocation location) {
        long nextInterval = resolveInterval(location);
        if (nextInterval == currentIntervalMs || locationClient == null) {
            return;
        }
        currentIntervalMs = nextInterval;
        AMapLocationClientOption option = new AMapLocationClientOption();
        option.setLocationMode(AMapLocationClientOption.AMapLocationMode.Hight_Accuracy);
        option.setGpsFirst(true);
        option.setInterval(currentIntervalMs);
        option.setNeedAddress(false);
        option.setOnceLocation(false);
        option.setLocationCacheEnable(true);
        locationClient.setLocationOption(option);
    }

    private long resolveInterval(AMapLocation location) {
        if (location == null || location.getErrorCode() != 0) {
            return SLOW_INTERVAL_MS;
        }
        float speed = Math.max(0f, location.getSpeed());
        if (speed >= FAST_SPEED_MPS) {
            return FAST_INTERVAL_MS;
        }
        if (speed >= ACTIVE_SPEED_MPS) {
            return ACTIVE_INTERVAL_MS;
        }
        if (speed <= SLOW_SPEED_MPS) {
            return SLOW_INTERVAL_MS;
        }
        return NORMAL_INTERVAL_MS;
    }

    private void removeUpdates() {
        if (locationClient != null) {
            locationClient.stopLocation();
            locationClient.onDestroy();
            locationClient = null;
        }
        updatesStarted = false;
    }

    private void startForegroundCompat(Notification notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void updateNotification() {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID, buildNotification());
        }
    }

    private Notification buildNotification() {
        Intent openIntent = new Intent(this, MainActivity.class);
        openIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent openPendingIntent = PendingIntent.getActivity(
                this,
                0,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        Intent stopIntent = new Intent(this, TrackRecordingService.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent stopPendingIntent = PendingIntent.getService(
                this,
                1,
                stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        TrackStore.DayRecord day = TrackStore.getDay(this, TrackStore.today());
        TrackStore.Stats stats = TrackStore.buildStats(day, true);
        String text = stats.distance + " · " + stats.duration + " · "
                + stats.trips + "次行程 · " + (currentIntervalMs / 1000L) + "s采样";

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);

        builder.setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("轨迹日记正在记录")
                .setContentText(text)
                .setContentIntent(openPendingIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .addAction(R.drawable.ic_notification, "停止", stopPendingIntent);

        return builder.build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "轨迹记录",
                NotificationManager.IMPORTANCE_LOW
        );
        channel.setDescription("显示持续轨迹记录状态");
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.createNotificationChannel(channel);
        }
    }

    private boolean hasForegroundLocationPermission() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void setRecording(boolean recording) {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        prefs.edit().putBoolean(KEY_RECORDING, recording).apply();
    }
}
