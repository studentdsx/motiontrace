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

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class TrackRecordingService extends Service implements AMapLocationListener {
    private static final String TAG = "MotionTraceService";
    static final String ACTION_START = "com.motiontrace.diary.action.START";
    static final String ACTION_START_WITH_FIX = "com.motiontrace.diary.action.START_WITH_FIX";
    static final String ACTION_STOP = "com.motiontrace.diary.action.STOP";
    static final String ACTION_TRACK_UPDATED = "com.motiontrace.diary.action.TRACK_UPDATED";
    static final String EXTRA_LATITUDE = "latitude";
    static final String EXTRA_LONGITUDE = "longitude";
    static final String EXTRA_ACCURACY = "accuracy";
    static final String EXTRA_SPEED = "speed";

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
    private final ExecutorService realtimeSyncExecutor = Executors.newSingleThreadExecutor();
    private boolean updatesStarted;
    private long currentIntervalMs = SLOW_INTERVAL_MS;
    private String lastRealtimeQueuedKey = "";
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

        startTracking(intent);
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        removeUpdates();
        TrackStore.finishActiveTrip(this);
        setRecording(false);
        serviceAlive = false;
        realtimeSyncExecutor.shutdownNow();
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
        TrackStore.DayRecord day = TrackStore.appendPoint(this, location);
        maybeUploadRealtimePoint(day);
        adjustLocationInterval(location);
        updateNotification();
        Intent update = new Intent(ACTION_TRACK_UPDATED);
        update.setPackage(getPackageName());
        sendBroadcast(update);
    }

    private void startTracking(Intent intent) {
        if (!hasForegroundLocationPermission()) {
            stopTracking();
            return;
        }

        setRecording(true);
        lastRealtimeQueuedKey = "";
        TrackStore.startTrip(this);
        appendInitialFix(intent);
        startForegroundCompat(buildNotification());

        if (!updatesStarted) {
            startAmapLocation();
            updatesStarted = true;
        }
        updateNotification();
    }

    // 新行程启动时优先写入刚获取的实时定位点，避免连续定位先返回上次缓存点。
    private void appendInitialFix(Intent intent) {
        if (intent == null || !ACTION_START_WITH_FIX.equals(intent.getAction())) {
            return;
        }
        if (!intent.hasExtra(EXTRA_LATITUDE) || !intent.hasExtra(EXTRA_LONGITUDE)) {
            return;
        }
        double latitude = intent.getDoubleExtra(EXTRA_LATITUDE, 0.0);
        double longitude = intent.getDoubleExtra(EXTRA_LONGITUDE, 0.0);
        double accuracy = intent.getDoubleExtra(EXTRA_ACCURACY, 0.0);
        double speed = intent.getDoubleExtra(EXTRA_SPEED, 0.0);
        TrackStore.DayRecord day = TrackStore.appendPoint(this, latitude, longitude, accuracy, speed);
        maybeUploadRealtimePoint(day);
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
            option.setLocationCacheEnable(false);
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
        option.setLocationCacheEnable(false);
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

    private void maybeUploadRealtimePoint(TrackStore.DayRecord day) {
        if (day == null || day.points.isEmpty()) {
            return;
        }
        CloudSyncClient client = new CloudSyncClient(this);
        if (!client.isConfigured() || !client.isLoggedIn() || !client.isRealtimeSyncEnabled()) {
            return;
        }

        TrackStore.PointRecord latest = day.points.get(day.points.size() - 1);
        final CloudSyncClient.RealtimeTrackPoint payload = client.buildRealtimePoint(day, latest);
        if (payload == null) {
            return;
        }
        String key = payload.date + "|" + payload.tripId + "|" + payload.pointIndex + "|" + payload.timestamp;
        if (key.equals(lastRealtimeQueuedKey)) {
            return;
        }
        lastRealtimeQueuedKey = key;
        realtimeSyncExecutor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    new CloudSyncClient(TrackRecordingService.this).uploadRealtimePoint(payload);
                } catch (Exception error) {
                    Log.w(TAG, "realtime track sync failed", error);
                }
            }
        });
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
