package com.motiontrace.diary;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentValues;
import android.content.ClipData;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.provider.Settings;
import android.text.InputType;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.amap.api.location.AMapLocation;
import com.amap.api.location.AMapLocationClient;
import com.amap.api.location.AMapLocationClientOption;
import com.amap.api.location.AMapLocationListener;
import com.amap.api.maps.AMap;
import com.amap.api.maps.CameraUpdateFactory;
import com.amap.api.maps.MapsInitializer;
import com.amap.api.maps.TextureMapView;
import com.amap.api.maps.model.BitmapDescriptorFactory;
import com.amap.api.maps.model.CameraPosition;
import com.amap.api.maps.model.CircleOptions;
import com.amap.api.maps.model.LatLng;
import com.amap.api.maps.model.LatLngBounds;
import com.amap.api.maps.model.Marker;
import com.amap.api.maps.model.MarkerOptions;
import com.amap.api.maps.model.MyLocationStyle;
import com.amap.api.maps.model.PolylineOptions;
import com.amap.api.services.core.LatLonPoint;
import com.amap.api.services.core.PoiItem;
import com.amap.api.services.geocoder.GeocodeResult;
import com.amap.api.services.geocoder.GeocodeSearch;
import com.amap.api.services.geocoder.RegeocodeAddress;
import com.amap.api.services.geocoder.RegeocodeQuery;
import com.amap.api.services.geocoder.RegeocodeResult;

import java.io.FileOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public final class MainActivity extends Activity {
    private static final String TAG = "MotionTrace";
    private static final int REQUEST_FOREGROUND_LOCATION = 1001;
    private static final int REQUEST_BACKGROUND_LOCATION = 1002;
    private static final int REQUEST_NOTIFICATIONS = 1003;
    private static final int REQUEST_PICK_IMAGES = 1004;
    private static final int REQUEST_CAPTURE_IMAGE = 1005;

    private static final int ACTION_NONE = 0;
    private static final int ACTION_START_RECORDING = 1;
    private static final int ACTION_CHECKIN = 3;
    private static final int ACTION_CENTER_MAP = 4;
    private static final int TAB_TODAY = 0;
    private static final int TAB_HISTORY = 1;
    private static final int TAB_SETTINGS = 2;
    private static final String AMAP_KEY = BuildConfig.AMAP_KEY;
    private static final long AUTO_CENTER_MIN_INTERVAL_MS = 30000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refreshRunnable = new Runnable() {
        @Override
        public void run() {
            refreshUi();
            handler.postDelayed(this, 2500L);
        }
    };

    private TextView statusText;
    private TextView distanceText;
    private TextView durationText;
    private TextView tripsText;
    private TextView checkinsText;
    private TextView backgroundText;
    private TextView historyCountText;
    private TextView cloudStatusText;
    private TextView cloudAvatarText;
    private TextView cloudNameText;
    private TextView cloudMetaText;
    private LinearLayout cloudAuthActions;
    private LinearLayout cloudAccountActions;
    private LinearLayout commonAddressList;
    private Button cloudRegisterButton;
    private Button cloudLoginButton;
    private Button cloudChangePasswordButton;
    private Button cloudRealtimeButton;
    private Button cloudUploadButton;
    private Button cloudDownloadButton;
    private Button cloudLogoutButton;
    private boolean cloudTaskRunning;
    private String cloudNotice = "";
    private long cloudNoticeUntilMs;
    private Button recordButton;
    private Button checkinButton;
    private Button backgroundButton;
    private LinearLayout checkinList;
    private HorizontalScrollView checkinTimelineScroller;
    private int checkinTimelineScrollX;
    private String checkinRenderSignature = "";
    private LinearLayout historyList;
    private ScrollView todayPage;
    private ScrollView historyPage;
    private ScrollView settingsPage;
    private final TextView[] tabLabels = new TextView[3];
    private final View[] tabDots = new View[3];
    private int selectedTab = TAB_TODAY;
    private TextureMapView mapView;
    private AMap aMap;
    private String mainMapFocusKey = "";
    private boolean mainMapUserMoved;
    private boolean mainMapProgrammaticMove;
    private String selectedTodayTripId = "";
    private TextureMapView historyMapView;
    private AMap historyAMap;
    private String selectedHistoryDate;
    private String selectedHistoryTripKey;
    private boolean historyMonthInitialized;
    private final Calendar historyMonth = Calendar.getInstance(Locale.CHINA);
    private long lastAutoCenterAt;
    private boolean autoLocationPermissionAsked;

    private int pendingLocationAction = ACTION_NONE;
    private boolean startAfterNotificationPermission;
    private AlertDialog activeCheckinDialog;
    private TextView activePhotoCountText;
    private TextView activeAddressText;
    private ImageButton activeAddressButton;
    private LinearLayout activePhotoPreviewList;
    private EditText activeNoteInput;
    private AMapLocation pendingCheckinLocation;
    private ArrayList<String> pendingPhotoPaths = new ArrayList<>();
    private ArrayList<String> pendingAddressOptions = new ArrayList<>();
    private String pendingCameraPhotoPath;
    private Uri pendingCameraPhotoUri;
    private boolean pendingCameraPhotoFromMediaStore;
    private String pendingCheckinAddress = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        initAmapSdk();
        if (savedInstanceState != null) {
            pendingCameraPhotoPath = savedInstanceState.getString("pendingCameraPhotoPath");
            String cameraUri = savedInstanceState.getString("pendingCameraPhotoUri");
            pendingCameraPhotoUri = cameraUri == null ? null : Uri.parse(cameraUri);
            pendingCameraPhotoFromMediaStore = savedInstanceState.getBoolean("pendingCameraPhotoFromMediaStore", false);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            getWindow().setStatusBarColor(color("#F7F4EE"));
            getWindow().setNavigationBarColor(color("#F7F4EE"));
        }
        buildUi(savedInstanceState);
        refreshUi();
    }

    private void initAmapSdk() {
        // 高德 SDK 要先完成隐私合规确认，再设置 Key 和创建地图/定位对象。
        MapsInitializer.updatePrivacyShow(this, true, true);
        MapsInitializer.updatePrivacyAgree(this, true);
        MapsInitializer.setApiKey(AMAP_KEY);
        AMapLocationClient.updatePrivacyShow(this, true, true);
        AMapLocationClient.updatePrivacyAgree(this, true);
        AMapLocationClient.setApiKey(AMAP_KEY);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mapView != null) {
            mapView.onResume();
        }
        if (historyMapView != null) {
            historyMapView.onResume();
        }
        handler.removeCallbacks(refreshRunnable);
        handler.post(refreshRunnable);
        handler.post(new Runnable() {
            @Override
            public void run() {
                autoCenterMapOnOpen();
            }
        });
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(refreshRunnable);
        if (mapView != null) {
            mapView.onPause();
        }
        if (historyMapView != null) {
            historyMapView.onPause();
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(refreshRunnable);
        if (mapView != null) {
            mapView.onDestroy();
        }
        destroyHistoryMap();
        super.onDestroy();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (mapView != null) {
            mapView.onSaveInstanceState(outState);
        }
        if (historyMapView != null) {
            historyMapView.onSaveInstanceState(outState);
        }
        outState.putString("pendingCameraPhotoPath", pendingCameraPhotoPath);
        outState.putString("pendingCameraPhotoUri", pendingCameraPhotoUri == null ? null : pendingCameraPhotoUri.toString());
        outState.putBoolean("pendingCameraPhotoFromMediaStore", pendingCameraPhotoFromMediaStore);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_PICK_IMAGES && resultCode == RESULT_OK && data != null) {
            importPickedImages(data);
        } else if (requestCode == REQUEST_CAPTURE_IMAGE) {
            importCapturedImage(resultCode == RESULT_OK, data);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_FOREGROUND_LOCATION) {
            if (!hasForegroundLocationPermission()) {
                toast("需要定位权限");
                pendingLocationAction = ACTION_NONE;
                return;
            }
            runPendingLocationAction();
        } else if (requestCode == REQUEST_BACKGROUND_LOCATION) {
            refreshUi();
        } else if (requestCode == REQUEST_NOTIFICATIONS) {
            if (startAfterNotificationPermission) {
                startAfterNotificationPermission = false;
                startRecordingFlow();
            }
        }
    }

    private void buildUi(Bundle savedInstanceState) {
        LinearLayout shell = new LinearLayout(this);
        shell.setOrientation(LinearLayout.VERTICAL);
        shell.setBackgroundColor(color("#F7F4EE"));

        FrameLayout pageHost = new FrameLayout(this);
        shell.addView(pageHost, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
        ));

        todayPage = pageScroll();
        historyPage = pageScroll();
        settingsPage = pageScroll();
        pageHost.addView(todayPage, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));
        pageHost.addView(historyPage, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));
        pageHost.addView(settingsPage, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        buildTodayPage(savedInstanceState);
        buildHistoryPage();
        buildSettingsPage();
        shell.addView(buildTabBar(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(64)
        ));

        setContentView(shell);
        switchTab(TAB_TODAY);
    }

    private ScrollView pageScroll() {
        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(false);
        scrollView.setBackgroundColor(color("#F7F4EE"));
        return scrollView;
    }

    private LinearLayout pageRoot(ScrollView scrollView) {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(22), dp(18), dp(28));
        scrollView.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        return root;
    }

    private void buildTodayPage(Bundle savedInstanceState) {
        LinearLayout root = pageRoot(todayPage);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(header, matchWrap());

        header.addView(buildBrandView(), new LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
        ));

        statusText = text("", 13, color("#1F6F54"), Typeface.BOLD);
        statusText.setGravity(Gravity.CENTER);
        statusText.setPadding(dp(12), dp(7), dp(12), dp(7));
        statusText.setBackground(pill(color("#E5F1EA"), 999f));
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        statusParams.setMargins(dp(10), 0, 0, 0);
        header.addView(statusText, statusParams);

        FrameLayout mapFrame = new FrameLayout(this);
        mapFrame.setBackgroundColor(Color.BLACK);
        mapView = new TextureMapView(this);
        mapView.onCreate(savedInstanceState);
        mapFrame.addView(mapView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));
        mapFrame.addView(buildMapLocateButton(), mapLocateButtonParams());
        aMap = mapView.getMap();
        setupMap();
        LinearLayout.LayoutParams mapParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(460)
        );
        mapParams.setMargins(0, dp(4), 0, dp(12));
        root.addView(mapFrame, mapParams);

        root.addView(buildStatsGrid(), matchWrap());

        LinearLayout actionRow = new LinearLayout(this);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);
        actionRow.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams actionParams = matchWrap();
        actionParams.setMargins(0, dp(12), 0, 0);
        root.addView(actionRow, actionParams);

        recordButton = compactButton("开始记录", true);
        recordButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                toggleRecording();
            }
        });
        actionRow.addView(recordButton, weightedButtonParams(1f, 0, dp(5)));

        checkinButton = compactButton("沿途打卡", false);
        checkinButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                checkinFlow();
            }
        });
        actionRow.addView(checkinButton, weightedButtonParams(1f, dp(5), 0));

        TextView checkinTitle = text("今日打卡", 18, color("#25302B"), Typeface.BOLD);
        LinearLayout.LayoutParams titleParams = matchWrap();
        titleParams.setMargins(0, dp(22), 0, dp(8));
        root.addView(checkinTitle, titleParams);

        checkinList = new LinearLayout(this);
        checkinList.setOrientation(LinearLayout.VERTICAL);
        root.addView(checkinList, matchWrap());
    }

    private void buildHistoryPage() {
        LinearLayout root = pageRoot(historyPage);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.BOTTOM);
        root.addView(header, matchWrap());

        LinearLayout titleBox = new LinearLayout(this);
        titleBox.setOrientation(LinearLayout.VERTICAL);
        header.addView(titleBox, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView eyebrow = text("日历检索", 13, color("#728071"), Typeface.NORMAL);
        titleBox.addView(eyebrow);

        TextView title = text("轨迹历史", 28, color("#18231F"), Typeface.BOLD);
        titleBox.addView(title);

        TextView count = text("0 天", 13, color("#1F6F54"), Typeface.BOLD);
        count.setGravity(Gravity.CENTER);
        count.setPadding(dp(12), dp(6), dp(12), dp(6));
        count.setBackground(pill(color("#E8F2EA"), 999f));
        header.addView(count);
        historyCountText = count;

        historyList = new LinearLayout(this);
        historyList.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams listParams = matchWrap();
        listParams.setMargins(0, dp(14), 0, 0);
        root.addView(historyList, listParams);
    }

    private void buildSettingsPage() {
        LinearLayout root = pageRoot(settingsPage);

        TextView title = text("我的", 28, color("#25302B"), Typeface.BOLD);
        root.addView(title, matchWrap());

        TextView subtitle = text("账号、云同步、权限和本机数据管理。", 13, color("#6F756D"), Typeface.NORMAL);
        LinearLayout.LayoutParams subtitleParams = matchWrap();
        subtitleParams.setMargins(0, dp(4), 0, dp(16));
        root.addView(subtitle, subtitleParams);

        root.addView(buildCloudCard(), matchWrap());

        LinearLayout.LayoutParams addressParams = matchWrap();
        addressParams.setMargins(0, dp(14), 0, 0);
        root.addView(buildCommonAddressCard(), addressParams);

        LinearLayout permissionCard = new LinearLayout(this);
        permissionCard.setOrientation(LinearLayout.VERTICAL);
        permissionCard.setPadding(dp(14), dp(12), dp(14), dp(14));
        permissionCard.setBackground(cardBg());
        LinearLayout.LayoutParams permissionParams = matchWrap();
        permissionParams.setMargins(0, dp(14), 0, 0);
        root.addView(permissionCard, permissionParams);

        TextView permissionTitle = text("后台定位", 18, color("#25302B"), Typeface.BOLD);
        permissionCard.addView(permissionTitle);

        backgroundText = text("", 13, color("#6F756D"), Typeface.NORMAL);
        backgroundText.setPadding(0, dp(8), 0, dp(12));
        permissionCard.addView(backgroundText);

        backgroundButton = button("后台权限", false);
        backgroundButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                requestBackgroundLocationFlow();
            }
        });
        permissionCard.addView(backgroundButton, matchWrap());

        Button clearButton = button("清空数据", false);
        clearButton.setTextColor(color("#B94F44"));
        clearButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                confirmClearData();
            }
        });
        LinearLayout.LayoutParams clearParams = matchWrap();
        clearParams.setMargins(0, dp(14), 0, 0);
        root.addView(clearButton, clearParams);
    }

    private View buildTabBar() {
        LinearLayout tabBar = new LinearLayout(this);
        tabBar.setOrientation(LinearLayout.HORIZONTAL);
        tabBar.setGravity(Gravity.CENTER);
        tabBar.setPadding(dp(14), dp(7), dp(14), dp(7));
        tabBar.setBackground(tabBarBg());

        tabBar.addView(tabItem(TAB_TODAY, "今日"), weightedButtonParams(1f, dp(2), dp(4)));
        tabBar.addView(tabItem(TAB_HISTORY, "历史"), weightedButtonParams(1f, dp(4), dp(4)));
        tabBar.addView(tabItem(TAB_SETTINGS, "我的"), weightedButtonParams(1f, dp(4), dp(2)));
        return tabBar;
    }

    private View buildCloudCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(12), dp(14), dp(14));
        card.setBackground(cardBg());

        TextView title = text("账号与云同步", 18, color("#25302B"), Typeface.BOLD);
        card.addView(title);

        LinearLayout profileRow = new LinearLayout(this);
        profileRow.setOrientation(LinearLayout.HORIZONTAL);
        profileRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams profileParams = matchWrap();
        profileParams.setMargins(0, dp(12), 0, 0);
        card.addView(profileRow, profileParams);

        cloudAvatarText = text("未", 18, color("#1F6F54"), Typeface.BOLD);
        cloudAvatarText.setGravity(Gravity.CENTER);
        cloudAvatarText.setBackground(pill(color("#E8F2EA"), 999f));
        LinearLayout.LayoutParams avatarParams = new LinearLayout.LayoutParams(dp(50), dp(50));
        avatarParams.setMargins(0, 0, dp(12), 0);
        profileRow.addView(cloudAvatarText, avatarParams);

        LinearLayout profileTexts = new LinearLayout(this);
        profileTexts.setOrientation(LinearLayout.VERTICAL);
        profileRow.addView(profileTexts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        cloudNameText = text("未登录用户", 16, color("#25302B"), Typeface.BOLD);
        profileTexts.addView(cloudNameText);

        cloudMetaText = text("登录后可同步轨迹数据", 13, color("#6F756D"), Typeface.NORMAL);
        cloudMetaText.setPadding(0, dp(3), 0, 0);
        profileTexts.addView(cloudMetaText);

        cloudStatusText = text("", 13, color("#6F756D"), Typeface.NORMAL);
        cloudStatusText.setPadding(0, dp(10), 0, dp(2));
        card.addView(cloudStatusText);

        cloudAuthActions = new LinearLayout(this);
        cloudAuthActions.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams authParams = matchWrap();
        authParams.setMargins(0, dp(12), 0, 0);
        card.addView(cloudAuthActions, authParams);

        cloudRegisterButton = button("注册", false);
        cloudRegisterButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                registerCloudAccount();
            }
        });
        cloudAuthActions.addView(cloudRegisterButton, weightedButtonParams(1f, 0, dp(5)));

        cloudLoginButton = button("登录", true);
        cloudLoginButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                loginCloudAccount();
            }
        });
        cloudAuthActions.addView(cloudLoginButton, weightedButtonParams(1f, dp(5), 0));

        cloudAccountActions = new LinearLayout(this);
        cloudAccountActions.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams accountParams = matchWrap();
        accountParams.setMargins(0, dp(12), 0, 0);
        card.addView(cloudAccountActions, accountParams);

        cloudChangePasswordButton = button("修改密码", false);
        cloudChangePasswordButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                changeCloudPassword();
            }
        });
        cloudAccountActions.addView(cloudChangePasswordButton, matchWrap());

        cloudRealtimeButton = button("实时同步：关", false);
        cloudRealtimeButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                toggleRealtimeCloudSync();
            }
        });
        LinearLayout.LayoutParams realtimeParams = matchWrap();
        realtimeParams.setMargins(0, dp(10), 0, 0);
        cloudAccountActions.addView(cloudRealtimeButton, realtimeParams);

        LinearLayout syncRow = new LinearLayout(this);
        syncRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams syncParams = matchWrap();
        syncParams.setMargins(0, dp(10), 0, 0);
        cloudAccountActions.addView(syncRow, syncParams);

        cloudUploadButton = button("上传本机数据", false);
        cloudUploadButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                uploadCloudSnapshot();
            }
        });
        syncRow.addView(cloudUploadButton, weightedButtonParams(1f, 0, dp(5)));

        cloudDownloadButton = button("从云端恢复", false);
        cloudDownloadButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                confirmDownloadCloudSnapshot();
            }
        });
        syncRow.addView(cloudDownloadButton, weightedButtonParams(1f, dp(5), 0));

        cloudLogoutButton = button("退出登录", false);
        cloudLogoutButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                new CloudSyncClient(MainActivity.this).logout();
                refreshCloudUi();
                toast("已退出登录");
            }
        });
        LinearLayout.LayoutParams logoutParams = matchWrap();
        logoutParams.setMargins(0, dp(10), 0, 0);
        cloudAccountActions.addView(cloudLogoutButton, logoutParams);

        refreshCloudUi();
        return card;
    }

    private View buildCommonAddressCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(12), dp(14), dp(14));
        card.setBackground(cardBg());

        TextView title = text("常用地址", 18, color("#25302B"), Typeface.BOLD);
        card.addView(title);

        commonAddressList = new LinearLayout(this);
        commonAddressList.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams listParams = matchWrap();
        listParams.setMargins(0, dp(8), 0, 0);
        card.addView(commonAddressList, listParams);

        refreshCommonAddressUi();
        return card;
    }

    private View tabItem(final int index, String label) {
        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setGravity(Gravity.CENTER);
        item.setPadding(0, dp(6), 0, dp(6));
        item.setMinimumHeight(dp(48));
        item.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                switchTab(index);
            }
        });

        View dot = new View(this);
        LinearLayout.LayoutParams dotParams = new LinearLayout.LayoutParams(dp(5), dp(5));
        dotParams.setMargins(0, 0, 0, dp(6));
        item.addView(dot, dotParams);

        TextView text = text(label, 13, color("#68736B"), Typeface.NORMAL);
        text.setGravity(Gravity.CENTER);
        item.addView(text);

        tabDots[index] = dot;
        tabLabels[index] = text;
        return item;
    }

    private View buildBrandView() {
        LinearLayout brand = new LinearLayout(this);
        brand.setOrientation(LinearLayout.HORIZONTAL);
        brand.setGravity(Gravity.CENTER_VERTICAL);

        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.ic_launcher);
        logo.setScaleType(ImageView.ScaleType.CENTER_CROP);
        LinearLayout.LayoutParams logoParams = new LinearLayout.LayoutParams(dp(34), dp(34));
        brand.addView(logo, logoParams);

        TextView name = text(getString(R.string.app_name), 18, color("#25302B"), Typeface.BOLD);
        name.setSingleLine(true);
        LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        nameParams.setMargins(dp(9), 0, 0, 0);
        brand.addView(name, nameParams);

        return brand;
    }

    private View buildMapLocateButton() {
        ImageButton button = new ImageButton(this);
        button.setImageResource(R.drawable.ic_locate);
        button.setBackground(circleButtonBg());
        button.setScaleType(ImageView.ScaleType.CENTER);
        button.setPadding(dp(10), dp(10), dp(10), dp(10));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            button.setElevation(dp(3));
        }
        button.setContentDescription("定位");
        button.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                centerMapOnCurrentLocationFlow();
            }
        });
        return button;
    }

    private FrameLayout.LayoutParams mapLocateButtonParams() {
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(dp(46), dp(46));
        params.gravity = Gravity.BOTTOM | Gravity.RIGHT;
        params.setMargins(0, 0, dp(12), dp(12));
        return params;
    }

    private void switchTab(int index) {
        int previousTab = selectedTab;
        selectedTab = index;
        todayPage.setVisibility(index == TAB_TODAY ? View.VISIBLE : View.GONE);
        historyPage.setVisibility(index == TAB_HISTORY ? View.VISIBLE : View.GONE);
        settingsPage.setVisibility(index == TAB_SETTINGS ? View.VISIBLE : View.GONE);
        if (previousTab != TAB_HISTORY && index == TAB_HISTORY) {
            if (historyMapView != null) {
                historyMapView.onResume();
            }
            renderHistory();
        } else if (previousTab == TAB_HISTORY && index != TAB_HISTORY && historyMapView != null) {
            historyMapView.onPause();
        }
        updateTabs();
        refreshUi();
    }

    private void updateTabs() {
        for (int i = 0; i < tabLabels.length; i++) {
            boolean active = i == selectedTab;
            if (tabLabels[i] != null) {
                tabLabels[i].setTextColor(active ? color("#1F6F54") : color("#68736B"));
                tabLabels[i].setTypeface(Typeface.DEFAULT, active ? Typeface.BOLD : Typeface.NORMAL);
            }
            if (tabDots[i] != null) {
                tabDots[i].setBackground(pill(active ? color("#1F6F54") : Color.TRANSPARENT, 999f));
                View parent = (View) tabDots[i].getParent();
                parent.setBackground(active ? pill(color("#E8F2EA"), 8f) : pill(Color.TRANSPARENT, 8f));
            }
        }
    }

    private View buildStatsGrid() {
        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);

        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        grid.addView(row1, matchWrap());

        tripsText = addStat(row1, "今日行程", 0, dp(5));
        checkinsText = addStat(row1, "打卡", dp(5), 0);

        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams row2Params = matchWrap();
        row2Params.setMargins(0, dp(10), 0, 0);
        grid.addView(row2, row2Params);

        distanceText = addStat(row2, "当前距离", 0, dp(5));
        durationText = addStat(row2, "当前时长", dp(5), 0);

        return grid;
    }

    private TextView addStat(LinearLayout row, String label, int left, int right) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        card.setBackground(cardBg());

        TextView value = text("0", 22, color("#25302B"), Typeface.BOLD);
        TextView labelView = text(label, 12, color("#6F756D"), Typeface.NORMAL);
        card.addView(value);
        card.addView(labelView);

        row.addView(card, weightedButtonParams(1f, left, right));
        return value;
    }

    private void setupMap() {
        if (aMap == null) {
            return;
        }
        aMap.getUiSettings().setZoomControlsEnabled(false);
        aMap.getUiSettings().setZoomGesturesEnabled(true);
        aMap.getUiSettings().setScrollGesturesEnabled(true);
        aMap.getUiSettings().setRotateGesturesEnabled(true);
        aMap.getUiSettings().setTiltGesturesEnabled(true);
        aMap.getUiSettings().setMyLocationButtonEnabled(false);
        aMap.getUiSettings().setScaleControlsEnabled(true);
        aMap.moveCamera(CameraUpdateFactory.newCameraPosition(
                new CameraPosition(new LatLng(31.2304, 121.4737), 15f, 0f, 0f)
        ));

        MyLocationStyle locationStyle = new MyLocationStyle();
        locationStyle.myLocationType(MyLocationStyle.LOCATION_TYPE_LOCATION_ROTATE_NO_CENTER);
        locationStyle.interval(5000L);
        locationStyle.strokeColor(color("#661F6F54"));
        locationStyle.radiusFillColor(color("#221F6F54"));
        aMap.setMyLocationStyle(locationStyle);
        enableMainMapMyLocation();
        aMap.setOnCameraChangeListener(new AMap.OnCameraChangeListener() {
            @Override
            public void onCameraChange(CameraPosition cameraPosition) {
            }

            @Override
            public void onCameraChangeFinish(CameraPosition cameraPosition) {
                if (mainMapProgrammaticMove) {
                    mainMapProgrammaticMove = false;
                    return;
                }
                mainMapUserMoved = true;
            }
        });
    }

    private void enableMainMapMyLocation() {
        if (aMap == null || !hasForegroundLocationPermission()) {
            return;
        }
        try {
            aMap.setMyLocationEnabled(true);
        } catch (SecurityException ignored) {
        }
    }

    private void setupHistoryMap() {
        if (historyAMap == null) {
            return;
        }
        historyAMap.getUiSettings().setZoomControlsEnabled(false);
        historyAMap.getUiSettings().setMyLocationButtonEnabled(false);
        historyAMap.getUiSettings().setScaleControlsEnabled(true);
        historyAMap.moveCamera(CameraUpdateFactory.newCameraPosition(
                new CameraPosition(new LatLng(31.2304, 121.4737), 13f, 0f, 0f)
        ));
        historyAMap.setOnMarkerClickListener(new AMap.OnMarkerClickListener() {
            @Override
            public boolean onMarkerClick(Marker marker) {
                Object tag = marker.getObject();
                if (tag instanceof TrackStore.CheckinRecord) {
                    showCheckinDetail((TrackStore.CheckinRecord) tag);
                    return true;
                }
                return false;
            }
        });
    }

    private void renderMap(TrackStore.DayRecord day) {
        if (aMap == null || day == null) {
            return;
        }
        aMap.clear();
        enableMainMapMyLocation();
        boolean recording = TrackRecordingService.isRecording(this);
        TrackStore.TripRecord activeTrip = recording ? activeTodayTrip(day) : null;
        String tripFilter = recording
                ? (activeTrip == null ? "" : activeTrip.id)
                : selectedTodayTripId;
        if (tripFilter.isEmpty()) {
            mainMapFocusKey = "";
            return;
        }

        ArrayList<LatLng> route = new ArrayList<>();
        LatLngBounds.Builder boundsBuilder = LatLngBounds.builder();
        boolean hasBounds = false;

        for (TrackStore.PointRecord point : day.points) {
            if (!tripFilter.isEmpty() && !pointBelongsToTodayTrip(day, point, tripFilter)) {
                continue;
            }
            LatLng latLng = new LatLng(point.latitude, point.longitude);
            route.add(latLng);
            boundsBuilder.include(latLng);
            hasBounds = true;
        }

        if (route.size() >= 2) {
            aMap.addPolyline(new PolylineOptions()
                    .addAll(route)
                    .color(color("#1F6F54"))
                    .width(dp(5))
                    .geodesic(true));
        }

        if (!route.isEmpty()) {
            LatLng start = route.get(0);
            LatLng end = route.get(route.size() - 1);
            aMap.addMarker(new MarkerOptions()
                    .position(start)
                    .title("起点")
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_ORANGE)));
            aMap.addMarker(new MarkerOptions()
                    .position(end)
                    .title("当前位置")
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED)));
        }

        for (TrackStore.CheckinRecord checkin : day.checkins) {
            if (!tripFilter.isEmpty() && !checkinBelongsToTodayTrip(day, checkin, tripFilter)) {
                continue;
            }
            LatLng latLng = new LatLng(checkin.latitude, checkin.longitude);
            boundsBuilder.include(latLng);
            hasBounds = true;
            aMap.addMarker(new MarkerOptions()
                    .position(latLng)
                    .title(checkinTitle(checkin))
                    .snippet(TrackStore.formatClock(checkin.timestamp))
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE)));
        }

        String focusKey = mainMapFocusKey(day, tripFilter, route.size());
        if (hasBounds && !mainMapUserMoved && !focusKey.equals(mainMapFocusKey)) {
            mainMapFocusKey = focusKey;
            mainMapProgrammaticMove = true;
            aMap.animateCamera(CameraUpdateFactory.newLatLngBounds(boundsBuilder.build(), dp(42)));
        }
    }

    private boolean pointBelongsToTodayTrip(TrackStore.DayRecord day, TrackStore.PointRecord point, String tripId) {
        if (point == null || tripId == null || tripId.isEmpty()) {
            return false;
        }
        if (hasText(point.tripId)) {
            return tripId.equals(point.tripId);
        }
        TrackStore.TripRecord trip = todayTripById(day, tripId);
        return timestampBelongsToTodayTrip(trip, point.timestamp);
    }

    private boolean checkinBelongsToTodayTrip(TrackStore.DayRecord day, TrackStore.CheckinRecord checkin, String tripId) {
        if (checkin == null || tripId == null || tripId.isEmpty()) {
            return false;
        }
        if (hasText(checkin.tripId)) {
            return tripId.equals(checkin.tripId);
        }
        TrackStore.TripRecord trip = todayTripById(day, tripId);
        return timestampBelongsToTodayTrip(trip, checkin.timestamp);
    }

    private boolean timestampBelongsToTodayTrip(TrackStore.TripRecord trip, long timestamp) {
        if (trip == null || trip.startTime <= 0L || timestamp <= 0L) {
            return false;
        }
        long end = trip.endTime > 0L ? trip.endTime : System.currentTimeMillis();
        return timestamp >= trip.startTime && timestamp <= end;
    }

    private TrackStore.TripRecord todayTripById(TrackStore.DayRecord day, String tripId) {
        if (day == null || tripId == null || tripId.isEmpty()) {
            return null;
        }
        for (TrackStore.TripRecord trip : day.trips) {
            if (trip != null && tripId.equals(trip.id)) {
                return trip;
            }
        }
        return null;
    }

    private String mainMapFocusKey(TrackStore.DayRecord day, String tripId, int routeSize) {
        if (day == null) {
            return "";
        }
        return day.date + "|" + tripId + "|" + routeSize + "|" + day.checkins.size();
    }

    private void toggleRecording() {
        if (TrackRecordingService.isRecording(this)) {
            TrackStore.TripRecord activeTrip = activeTodayTrip(TrackStore.getDay(this, TrackStore.today()));
            selectedTodayTripId = activeTrip == null ? "" : activeTrip.id;
            checkinRenderSignature = "";
            mainMapUserMoved = false;
            mainMapFocusKey = "";
            Intent intent = new Intent(this, TrackRecordingService.class);
            intent.setAction(TrackRecordingService.ACTION_STOP);
            startService(intent);
            refreshUi();
        } else {
            startRecordingFlow();
        }
    }

    private void startRecordingFlow() {
        if (!ensureForegroundLocation(ACTION_START_RECORDING)) {
            return;
        }
        if (!hasNotificationPermission()) {
            startAfterNotificationPermission = true;
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_NOTIFICATIONS);
            return;
        }

        toast("正在获取行程起点");
        requestSingleLocation(new SingleLocationCallback() {
            @Override
            public void onLocation(AMapLocation location) {
                Intent intent = new Intent(MainActivity.this, TrackRecordingService.class);
                intent.setAction(TrackRecordingService.ACTION_START_WITH_FIX);
                intent.putExtra(TrackRecordingService.EXTRA_LATITUDE, location.getLatitude());
                intent.putExtra(TrackRecordingService.EXTRA_LONGITUDE, location.getLongitude());
                intent.putExtra(TrackRecordingService.EXTRA_ACCURACY, (double) location.getAccuracy());
                intent.putExtra(TrackRecordingService.EXTRA_SPEED, (double) location.getSpeed());
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(intent);
                } else {
                    startService(intent);
                }
                selectedTodayTripId = "";
                checkinRenderSignature = "";
                mainMapUserMoved = false;
                mainMapFocusKey = "";
                refreshUi();
            }
        }, false);
    }

    private void renderHistory() {
        if (historyList == null) {
            return;
        }
        destroyHistoryMap();
        historyList.removeAllViews();
        List<TrackStore.DayRecord> days = TrackStore.listDays(this);
        if (historyCountText != null) {
            historyCountText.setText(days.size() + " 天");
        }
        ensureHistorySelection(days);
        historyList.addView(historyCalendar(days));

        TrackStore.DayRecord selectedDay = findHistoryDay(days, selectedHistoryDate);
        if (selectedDay == null) {
            TextView empty = text("这天没有历史轨迹", 14, color("#6F756D"), Typeface.NORMAL);
            empty.setPadding(0, dp(18), 0, dp(8));
            historyList.addView(empty);
            return;
        }

        historyList.addView(historyTripSection(selectedDay));
    }

    private void ensureHistorySelection(List<TrackStore.DayRecord> days) {
        if (selectedHistoryDate == null || selectedHistoryDate.isEmpty()) {
            selectedHistoryDate = days.isEmpty() ? formatStoreDate(new Date()) : days.get(0).date;
            selectedHistoryTripKey = null;
        }
        if (!historyMonthInitialized) {
            setHistoryMonthToDate(selectedHistoryDate);
            historyMonthInitialized = true;
        }
    }

    private View historyCalendar(List<TrackStore.DayRecord> days) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(12), dp(12), dp(12), dp(12));
        card.setBackground(cardBg());
        LinearLayout.LayoutParams cardParams = matchWrap();
        cardParams.setMargins(0, dp(14), 0, dp(12));
        card.setLayoutParams(cardParams);

        LinearLayout monthRow = new LinearLayout(this);
        monthRow.setOrientation(LinearLayout.HORIZONTAL);
        monthRow.setGravity(Gravity.CENTER_VERTICAL);
        card.addView(monthRow, matchWrap());

        monthRow.addView(calendarNavButton("‹", -1), new LinearLayout.LayoutParams(dp(38), dp(36)));

        TextView monthTitle = text(formatHistoryMonthTitle(), 17, color("#25302B"), Typeface.BOLD);
        monthTitle.setGravity(Gravity.CENTER);
        monthRow.addView(monthTitle, new LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
        ));

        monthRow.addView(calendarNavButton("›", 1), new LinearLayout.LayoutParams(dp(38), dp(36)));

        LinearLayout weekRow = new LinearLayout(this);
        weekRow.setOrientation(LinearLayout.HORIZONTAL);
        weekRow.setPadding(0, dp(10), 0, dp(4));
        card.addView(weekRow, matchWrap());
        String[] weeks = {"一", "二", "三", "四", "五", "六", "日"};
        for (String week : weeks) {
            TextView label = text(week, 12, color("#7B847C"), Typeface.BOLD);
            label.setGravity(Gravity.CENTER);
            weekRow.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }

        Calendar first = (Calendar) historyMonth.clone();
        first.set(Calendar.DAY_OF_MONTH, 1);
        int firstOffset = (first.get(Calendar.DAY_OF_WEEK) + 5) % 7;
        int daysInMonth = first.getActualMaximum(Calendar.DAY_OF_MONTH);
        int cells = firstOffset + daysInMonth <= 35 ? 35 : 42;
        for (int rowIndex = 0; rowIndex < cells / 7; rowIndex++) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams rowParams = matchWrap();
            rowParams.setMargins(0, rowIndex == 0 ? 0 : dp(4), 0, 0);
            card.addView(row, rowParams);
            for (int column = 0; column < 7; column++) {
                int cellIndex = rowIndex * 7 + column;
                int dayOfMonth = cellIndex - firstOffset + 1;
                row.addView(historyCalendarCell(days, dayOfMonth, daysInMonth));
            }
        }
        return card;
    }

    private TextView calendarNavButton(String label, final int direction) {
        TextView button = text(label, 24, color("#25302B"), Typeface.NORMAL);
        button.setGravity(Gravity.CENTER);
        button.setBackground(outlineButtonBg());
        button.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                moveHistoryMonth(direction);
            }
        });
        return button;
    }

    private View historyCalendarCell(List<TrackStore.DayRecord> days, int dayOfMonth, int daysInMonth) {
        TextView cell = text("", 13, color("#9A8F80"), Typeface.NORMAL);
        cell.setGravity(Gravity.CENTER);
        cell.setMinHeight(dp(42));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(42), 1f);
        params.setMargins(dp(2), 0, dp(2), 0);
        cell.setLayoutParams(params);
        if (dayOfMonth < 1 || dayOfMonth > daysInMonth) {
            return cell;
        }

        final String date = formatStoreDate(
                historyMonth.get(Calendar.YEAR),
                historyMonth.get(Calendar.MONTH),
                dayOfMonth
        );
        boolean hasHistory = hasHistoryOnDate(days, date);
        boolean selected = date.equals(selectedHistoryDate);
        cell.setText(String.valueOf(dayOfMonth));
        cell.setTextColor(selected ? Color.WHITE : hasHistory ? color("#1F6F54") : color("#6F756D"));
        cell.setTypeface(Typeface.DEFAULT, hasHistory || selected ? Typeface.BOLD : Typeface.NORMAL);
        cell.setBackground(calendarCellBg(selected, hasHistory));
        cell.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                selectedHistoryDate = date;
                selectedHistoryTripKey = null;
                setHistoryMonthToDate(date);
                renderHistory();
            }
        });
        return cell;
    }

    private void moveHistoryMonth(int direction) {
        historyMonth.add(Calendar.MONTH, direction);
        selectedHistoryDate = defaultDateInHistoryMonth(TrackStore.listDays(this));
        selectedHistoryTripKey = null;
        renderHistory();
    }

    private String defaultDateInHistoryMonth(List<TrackStore.DayRecord> days) {
        for (TrackStore.DayRecord day : days) {
            if (isDateInHistoryMonth(day.date)) {
                return day.date;
            }
        }
        return formatStoreDate(historyMonth.get(Calendar.YEAR), historyMonth.get(Calendar.MONTH), 1);
    }

    private View historyTripSection(TrackStore.DayRecord day) {
        LinearLayout section = new LinearLayout(this);
        section.setOrientation(LinearLayout.VERTICAL);

        TextView title = text(day.date + " · " + weekLabel(day.date), 17, color("#25302B"), Typeface.BOLD);
        section.addView(title, matchWrap());

        List<HistoryTrip> trips = buildHistoryTrips(day);
        if (trips.isEmpty()) {
            TextView empty = text("这天没有可展示的行程", 14, color("#6F756D"), Typeface.NORMAL);
            empty.setPadding(0, dp(10), 0, dp(8));
            section.addView(empty);
            return section;
        }

        ensureSelectedHistoryTrip(trips);
        for (HistoryTrip trip : trips) {
            section.addView(historyTripCard(trip));
        }

        HistoryTrip selectedTrip = findHistoryTrip(trips, selectedHistoryTripKey);
        if (selectedTrip != null) {
            section.addView(historyMapPanel(selectedTrip));
        }
        return section;
    }

    // 历史页按行程组织轨迹点和打卡；新数据优先按 tripId，旧数据按时间段兜底。
    private List<HistoryTrip> buildHistoryTrips(TrackStore.DayRecord day) {
        List<HistoryTrip> trips = new ArrayList<>();
        for (int i = 0; i < day.trips.size(); i++) {
            TrackStore.TripRecord record = day.trips.get(i);
            trips.add(new HistoryTrip(
                    "trip_" + i + "_" + record.id,
                    "行程 " + (i + 1),
                    record.id,
                    record.startTime,
                    record.endTime,
                    false
            ));
        }
        if (trips.isEmpty() && day.tripCount > 0) {
            trips.add(new HistoryTrip("legacy_1", "行程 1", "", day.startTime, day.endTime, false));
        }

        HistoryTrip unassigned = null;
        for (TrackStore.PointRecord point : day.points) {
            HistoryTrip trip = findHistoryTripForPoint(trips, point);
            if (trip == null) {
                if (unassigned == null) {
                    unassigned = new HistoryTrip("unassigned", "未归属", "", 0L, 0L, true);
                    trips.add(unassigned);
                }
                trip = unassigned;
            }
            trip.points.add(point);
        }

        for (TrackStore.CheckinRecord checkin : day.checkins) {
            HistoryTrip trip = findHistoryTripForCheckin(trips, checkin);
            if (trip == null) {
                if (unassigned == null) {
                    unassigned = new HistoryTrip("unassigned", "未归属", "", 0L, 0L, true);
                    trips.add(unassigned);
                }
                trip = unassigned;
            }
            trip.checkins.add(checkin);
        }

        for (HistoryTrip trip : trips) {
            trip.distanceMeters = historyTripDistance(trip.points);
        }
        return trips;
    }

    private HistoryTrip findHistoryTripForPoint(List<HistoryTrip> trips, TrackStore.PointRecord point) {
        for (HistoryTrip trip : trips) {
            if (timestampBelongsToHistoryTrip(trip, point == null ? 0L : point.timestamp)) {
                return trip;
            }
        }
        return null;
    }

    private HistoryTrip findHistoryTripForCheckin(List<HistoryTrip> trips, TrackStore.CheckinRecord checkin) {
        if (checkin != null && hasText(checkin.tripId)) {
            for (HistoryTrip trip : trips) {
                if (checkin.tripId.equals(trip.tripId)) {
                    return trip;
                }
            }
        }
        for (HistoryTrip trip : trips) {
            if (timestampBelongsToHistoryTrip(trip, checkin == null ? 0L : checkin.timestamp)) {
                return trip;
            }
        }
        return null;
    }

    private boolean timestampBelongsToHistoryTrip(HistoryTrip trip, long timestamp) {
        if (trip.unassigned || timestamp <= 0L || trip.startTime <= 0L) {
            return false;
        }
        long end = trip.endTime > 0L ? trip.endTime : System.currentTimeMillis();
        return timestamp >= trip.startTime && timestamp <= end;
    }

    private double historyTripDistance(List<TrackStore.PointRecord> points) {
        double distance = 0.0;
        for (int i = 1; i < points.size(); i++) {
            TrackStore.PointRecord prev = points.get(i - 1);
            TrackStore.PointRecord next = points.get(i);
            double gap = GeoUtils.distanceMeters(prev.latitude, prev.longitude, next.latitude, next.longitude);
            if (gap < 1000.0) {
                distance += gap;
            }
        }
        return distance;
    }

    private View historyTripCard(final HistoryTrip trip) {
        boolean selected = trip.key.equals(selectedHistoryTripKey);
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(12), dp(12), dp(12), dp(12));
        card.setBackground(historySelectableBg(selected));
        LinearLayout.LayoutParams cardParams = matchWrap();
        cardParams.setMargins(0, dp(10), 0, 0);
        card.setLayoutParams(cardParams);
        card.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                selectedHistoryTripKey = trip.key;
                renderHistory();
            }
        });

        LinearLayout main = new LinearLayout(this);
        main.setOrientation(LinearLayout.HORIZONTAL);
        main.setGravity(Gravity.CENTER_VERTICAL);
        card.addView(main, matchWrap());

        LinearLayout textBox = new LinearLayout(this);
        textBox.setOrientation(LinearLayout.VERTICAL);
        main.addView(textBox, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView name = text(trip.title, 16, color("#25302B"), Typeface.BOLD);
        textBox.addView(name);

        TextView range = text(tripTimeRange(trip.startTime, trip.endTime), 13, color("#6F756D"), Typeface.NORMAL);
        range.setPadding(0, dp(4), 0, 0);
        textBox.addView(range);

        TextView state = text(selected ? "已选择" : "查看", 12, selected ? Color.WHITE : color("#1F6F54"), Typeface.BOLD);
        state.setGravity(Gravity.CENTER);
        state.setPadding(dp(9), dp(5), dp(9), dp(5));
        state.setBackground(pill(selected ? color("#1F6F54") : color("#E5F1EA"), 999f));
        main.addView(state);

        View divider = new View(this);
        divider.setBackgroundColor(color("#EEE6D9"));
        LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(1)
        );
        dividerParams.setMargins(0, dp(12), 0, dp(10));
        card.addView(divider, dividerParams);

        LinearLayout summary = new LinearLayout(this);
        summary.setOrientation(LinearLayout.HORIZONTAL);
        card.addView(summary, matchWrap());
        summary.addView(summaryItem(formatMeters(trip.distanceMeters), "距离"), weightedButtonParams(1f, 0, dp(4)));
        summary.addView(summaryItem(String.valueOf(trip.points.size()), "轨迹点"), weightedButtonParams(1f, dp(4), dp(4)));
        summary.addView(summaryItem(String.valueOf(trip.checkins.size()), "打卡"), weightedButtonParams(1f, dp(4), 0));
        return card;
    }

    private View historyMapPanel(final HistoryTrip trip) {
        FrameLayout panel = new FrameLayout(this);
        panel.setBackgroundColor(Color.BLACK);
        LinearLayout.LayoutParams params = matchWrap();
        params.height = dp(300);
        params.setMargins(0, dp(12), 0, dp(12));
        panel.setLayoutParams(params);

        final TextureMapView panelMapView = new TextureMapView(this);
        historyMapView = panelMapView;
        historyMapView.onCreate(null);
        historyMapView.onResume();
        historyAMap = historyMapView.getMap();
        setupHistoryMap();
        panel.addView(historyMapView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        TextView hint = text("点击蓝色打卡点查看照片", 12, color("#1F6F54"), Typeface.BOLD);
        hint.setPadding(dp(10), dp(6), dp(10), dp(6));
        hint.setBackground(pill(color("#FFFDF8"), 999f));
        FrameLayout.LayoutParams hintParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.LEFT | Gravity.TOP
        );
        hintParams.setMargins(dp(10), dp(10), 0, 0);
        panel.addView(hint, hintParams);

        panel.post(new Runnable() {
            @Override
            public void run() {
                if (historyMapView != panelMapView) {
                    return;
                }
                panelMapView.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        if (historyMapView == panelMapView) {
                            renderHistoryMap(trip);
                        }
                    }
                }, 250L);
            }
        });
        return panel;
    }

    private void destroyHistoryMap() {
        if (historyMapView == null) {
            historyAMap = null;
            return;
        }
        ViewGroup parent = (ViewGroup) historyMapView.getParent();
        if (parent != null) {
            parent.removeView(historyMapView);
        }
        historyMapView.onPause();
        historyMapView.onDestroy();
        historyMapView = null;
        historyAMap = null;
    }

    private void renderHistoryMap(HistoryTrip trip) {
        if (historyAMap == null || trip == null) {
            return;
        }
        historyAMap.clear();

        ArrayList<LatLng> route = new ArrayList<>();
        LatLngBounds.Builder boundsBuilder = LatLngBounds.builder();
        boolean hasBounds = false;

        for (TrackStore.PointRecord point : trip.points) {
            LatLng latLng = new LatLng(point.latitude, point.longitude);
            route.add(latLng);
            boundsBuilder.include(latLng);
            hasBounds = true;
            historyAMap.addCircle(new CircleOptions()
                    .center(latLng)
                    .radius(3.0)
                    .strokeColor(color("#AA1F6F54"))
                    .fillColor(color("#661F6F54"))
                    .strokeWidth(1f));
        }

        if (route.size() >= 2) {
            historyAMap.addPolyline(new PolylineOptions()
                    .addAll(route)
                    .color(color("#1F6F54"))
                    .width(dp(5))
                    .geodesic(true));
        }

        if (!route.isEmpty()) {
            LatLng start = route.get(0);
            LatLng end = route.get(route.size() - 1);
            historyAMap.addMarker(new MarkerOptions()
                    .position(start)
                    .title("起点")
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_ORANGE)));
            historyAMap.addMarker(new MarkerOptions()
                    .position(end)
                    .title("终点")
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED)));
        }

        for (TrackStore.CheckinRecord checkin : trip.checkins) {
            LatLng latLng = new LatLng(checkin.latitude, checkin.longitude);
            boundsBuilder.include(latLng);
            hasBounds = true;
            Marker marker = historyAMap.addMarker(new MarkerOptions()
                    .position(latLng)
                    .title(checkinTitle(checkin))
                    .snippet(TrackStore.formatClock(checkin.timestamp))
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE)));
            if (marker != null) {
                marker.setObject(checkin);
            }
        }

        if (hasBounds) {
            historyAMap.animateCamera(CameraUpdateFactory.newLatLngBounds(boundsBuilder.build(), dp(42)));
        }
    }

    private TrackStore.DayRecord findHistoryDay(List<TrackStore.DayRecord> days, String date) {
        if (date == null) {
            return null;
        }
        for (TrackStore.DayRecord day : days) {
            if (date.equals(day.date)) {
                return day;
            }
        }
        return null;
    }

    private boolean hasHistoryOnDate(List<TrackStore.DayRecord> days, String date) {
        TrackStore.DayRecord day = findHistoryDay(days, date);
        return day != null && (day.tripCount > 0 || !day.points.isEmpty() || !day.checkins.isEmpty());
    }

    private void ensureSelectedHistoryTrip(List<HistoryTrip> trips) {
        if (findHistoryTrip(trips, selectedHistoryTripKey) == null) {
            selectedHistoryTripKey = trips.isEmpty() ? null : trips.get(0).key;
        }
    }

    private HistoryTrip findHistoryTrip(List<HistoryTrip> trips, String key) {
        if (key == null) {
            return null;
        }
        for (HistoryTrip trip : trips) {
            if (key.equals(trip.key)) {
                return trip;
            }
        }
        return null;
    }

    private String formatMeters(double meters) {
        if (meters < 1000.0) {
            return Math.round(Math.max(0.0, meters)) + " m";
        }
        return String.format(Locale.CHINA, "%.2f km", meters / 1000.0);
    }

    private String formatHistoryMonthTitle() {
        return String.format(
                Locale.CHINA,
                "%d年%d月",
                historyMonth.get(Calendar.YEAR),
                historyMonth.get(Calendar.MONTH) + 1
        );
    }

    private String formatStoreDate(Date date) {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(date);
    }

    private String formatStoreDate(int year, int month, int day) {
        return String.format(Locale.CHINA, "%04d-%02d-%02d", year, month + 1, day);
    }

    private void setHistoryMonthToDate(String date) {
        Date parsed = parseStoreDate(date);
        if (parsed == null) {
            return;
        }
        historyMonth.setTime(parsed);
        historyMonth.set(Calendar.DAY_OF_MONTH, 1);
    }

    private boolean isDateInHistoryMonth(String date) {
        Date parsed = parseStoreDate(date);
        if (parsed == null) {
            return false;
        }
        Calendar calendar = Calendar.getInstance(Locale.CHINA);
        calendar.setTime(parsed);
        return calendar.get(Calendar.YEAR) == historyMonth.get(Calendar.YEAR)
                && calendar.get(Calendar.MONTH) == historyMonth.get(Calendar.MONTH);
    }

    private Date parseStoreDate(String date) {
        try {
            return date == null ? null : new SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).parse(date);
        } catch (ParseException ignored) {
            return null;
        }
    }

    private GradientDrawable calendarCellBg(boolean selected, boolean hasHistory) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setCornerRadius(dp(8));
        if (selected) {
            drawable.setColor(color("#1F6F54"));
            drawable.setStroke(dp(1), color("#1F6F54"));
        } else if (hasHistory) {
            drawable.setColor(color("#E5F1EA"));
            drawable.setStroke(dp(1), color("#B8D6C5"));
        } else {
            drawable.setColor(color("#FFFDF8"));
            drawable.setStroke(dp(1), color("#EEE6D9"));
        }
        return drawable;
    }

    private GradientDrawable historySelectableBg(boolean selected) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color("#FFFDF8"));
        drawable.setCornerRadius(dp(8));
        drawable.setStroke(dp(selected ? 2 : 1), selected ? color("#1F6F54") : color("#E2DDD2"));
        return drawable;
    }

    private View summaryItem(String value, String label) {
        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.VERTICAL);

        TextView valueView = text(value, 15, color("#25302B"), Typeface.BOLD);
        item.addView(valueView);

        TextView labelView = text(label, 12, color("#7B847C"), Typeface.NORMAL);
        labelView.setPadding(0, dp(3), 0, 0);
        item.addView(labelView);
        return item;
    }

    private void showCheckinDetail(TrackStore.CheckinRecord checkin) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(4), dp(4), dp(4), 0);

        TextView time = text(TrackStore.formatClock(checkin.timestamp), 13, color("#6F756D"), Typeface.BOLD);
        box.addView(time);

        if (hasText(checkin.address)) {
            TextView address = text(checkin.address, 15, color("#1F6F54"), Typeface.BOLD);
            address.setPadding(0, dp(8), 0, 0);
            box.addView(address);
        }

        String note = checkin.note == null || checkin.note.isEmpty() ? "没有文字备注" : checkin.note;
        TextView noteView = text(note, 15, color("#25302B"), Typeface.NORMAL);
        noteView.setPadding(0, dp(8), 0, checkin.photos.isEmpty() ? 0 : dp(10));
        box.addView(noteView);

        if (!checkin.photos.isEmpty()) {
            box.addView(photoGrid(checkin.photos));
        }

        new AlertDialog.Builder(this)
                .setTitle("打卡详情")
                .setView(box)
                .setPositiveButton("关闭", null)
                .show();
    }

    private void confirmClearData() {
        new AlertDialog.Builder(this)
                .setTitle("清空数据")
                .setMessage("确认清空本机保存的所有轨迹、打卡和照片吗？此操作无法撤销。")
                .setNegativeButton("取消", null)
                .setPositiveButton("清空", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        TrackStore.clearAll(MainActivity.this);
                        toast("已清空");
                        refreshUi();
                    }
                })
                .show();
    }

    private void centerMapOnCurrentLocationFlow() {
        if (!ensureForegroundLocation(ACTION_CENTER_MAP)) {
            return;
        }
        centerMapOnCurrentLocation(true);
    }

    private void autoCenterMapOnOpen() {
        if (aMap == null || selectedTab != TAB_TODAY || activeCheckinDialog != null) {
            return;
        }
        if (!hasForegroundLocationPermission()) {
            if (!autoLocationPermissionAsked) {
                autoLocationPermissionAsked = true;
                ensureForegroundLocation(ACTION_CENTER_MAP);
            }
            return;
        }

        long now = System.currentTimeMillis();
        if (now - lastAutoCenterAt < AUTO_CENTER_MIN_INTERVAL_MS) {
            return;
        }
        lastAutoCenterAt = now;
        centerMapOnCurrentLocation(false);
    }

    private void centerMapOnCurrentLocation(boolean showProgress) {
        requestSingleLocation(new SingleLocationCallback() {
            @Override
            public void onLocation(AMapLocation location) {
                LatLng latLng = new LatLng(location.getLatitude(), location.getLongitude());
                if (aMap != null) {
                    enableMainMapMyLocation();
                    mainMapProgrammaticMove = true;
                    aMap.animateCamera(CameraUpdateFactory.newLatLngZoom(latLng, 17f));
                }
            }
        }, showProgress);
    }

    private void checkinFlow() {
        if (!TrackRecordingService.isRecording(this)) {
            toast("开始记录后才能沿途打卡");
            return;
        }
        if (!ensureForegroundLocation(ACTION_CHECKIN)) {
            return;
        }
        requestSingleLocation(new SingleLocationCallback() {
            @Override
            public void onLocation(AMapLocation location) {
                showCheckinDialog(location);
            }
        }, true);
    }

    private void requestBackgroundLocationFlow() {
        if (!hasForegroundLocationPermission()) {
            ensureForegroundLocation(ACTION_NONE);
            return;
        }
        if (hasBackgroundLocationPermission()) {
            toast("后台定位已允许");
            return;
        }

        if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_BACKGROUND_LOCATION}, REQUEST_BACKGROUND_LOCATION);
            return;
        }

        String option = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                ? String.valueOf(getPackageManager().getBackgroundPermissionOptionLabel())
                : "始终允许";
        new AlertDialog.Builder(this)
                .setTitle("允许后台记录")
                .setMessage("在系统位置权限里选择“" + option + "”，轨迹记录在锁屏和切到后台时会更稳定。")
                .setNegativeButton("先不用", null)
                .setPositiveButton("去设置", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                        intent.setData(Uri.parse("package:" + getPackageName()));
                        startActivity(intent);
                    }
                })
                .show();
    }

    private void registerCloudAccount() {
        showCloudAuthDialog(true);
    }

    private void loginCloudAccount() {
        showCloudAuthDialog(false);
    }

    private void showCloudAuthDialog(final boolean register) {
        if (!ensureCloudConfigured()) {
            return;
        }
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(18), dp(18), dp(18), dp(14));
        content.setBackground(cardBg());

        TextView title = text(register ? "注册账号" : "登录账号", 20, color("#25302B"), Typeface.BOLD);
        content.addView(title);

        TextView subtitle = text(register ? "创建账号后即可使用云同步" : "登录后恢复云同步状态", 13, color("#6F756D"), Typeface.NORMAL);
        subtitle.setPadding(0, dp(5), 0, dp(14));
        content.addView(subtitle);

        final EditText usernameInput = input(register ? "用户名，3-32 位" : "用户名", false);
        usernameInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_NORMAL);
        final EditText passwordInput = input(register ? "密码，至少 8 位" : "密码", true);
        final EditText emailInput = input("邮箱，用于找回密码", false);
        emailInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        CloudSyncClient savedClient = new CloudSyncClient(this);
        String savedUsername = savedClient.getUsername();
        if (!savedUsername.isEmpty()) {
            usernameInput.setText(savedUsername);
        } else if (!savedClient.getEmail().isEmpty()) {
            usernameInput.setText(savedClient.getEmail());
        }
        content.addView(usernameInput, inputParams(0));
        content.addView(passwordInput, inputParams(dp(10)));
        if (register) {
            content.addView(emailInput, inputParams(dp(10)));
        }

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams actionsParams = matchWrap();
        actionsParams.setMargins(0, dp(16), 0, 0);
        content.addView(actions, actionsParams);

        Button cancelButton = button("取消", false);
        Button submitButton = button(register ? "注册" : "登录", true);
        actions.addView(cancelButton, weightedButtonParams(1f, 0, dp(5)));
        actions.addView(submitButton, weightedButtonParams(1f, dp(5), 0));

        final AlertDialog[] dialogRef = new AlertDialog[1];
        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(content)
                .create();
        dialogRef[0] = dialog;
        cancelButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (dialogRef[0] != null) {
                    dialogRef[0].dismiss();
                }
            }
        });
        submitButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                String username = inputValue(usernameInput);
                String password = inputValue(passwordInput);
                String email = register ? inputValue(emailInput) : "";
                boolean started = register
                        ? startRegisterCloudAccount(username, password, email)
                        : startLoginCloudAccount(username, password);
                if (started && dialogRef[0] != null) {
                    dialogRef[0].dismiss();
                }
            }
        });
        dialog.show();
    }

    private boolean startRegisterCloudAccount(final String username, final String password, final String email) {
        if (!ensureCloudConfigured() || !validateRegisterCredentials(username, password, email)) {
            return false;
        }
        runCloudTask("正在注册", new CloudTask() {
            @Override
            public String run() throws Exception {
                new CloudSyncClient(MainActivity.this).register(username, password, email);
                return "注册并登录成功";
            }
        });
        return true;
    }

    private boolean startLoginCloudAccount(final String username, final String password) {
        if (!ensureCloudConfigured() || !validateLoginCredentials(username, password)) {
            return false;
        }
        runCloudTask("正在登录", new CloudTask() {
            @Override
            public String run() throws Exception {
                new CloudSyncClient(MainActivity.this).login(username, password);
                return "登录成功";
            }
        });
        return true;
    }

    private void changeCloudPassword() {
        if (!ensureCloudConfigured()) {
            return;
        }
        if (!new CloudSyncClient(this).isLoggedIn()) {
            showCloudNotice("请先登录", 6000L);
            toast("请先登录");
            return;
        }
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(2), dp(8), dp(2), 0);

        final EditText currentInput = input("当前密码", true);
        final EditText newInput = input("新密码，至少 8 位", true);
        content.addView(currentInput, inputParams(0));
        content.addView(newInput, inputParams(dp(10)));

        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("修改密码")
                .setView(content)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", null)
                .create();
        dialog.setOnShowListener(new android.content.DialogInterface.OnShowListener() {
            @Override
            public void onShow(android.content.DialogInterface value) {
                Button submit = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
                submit.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        String currentPassword = inputValue(currentInput);
                        String newPassword = inputValue(newInput);
                        if (startChangeCloudPassword(currentPassword, newPassword)) {
                            dialog.dismiss();
                        }
                    }
                });
            }
        });
        dialog.show();
    }

    private boolean startChangeCloudPassword(final String currentPassword, final String newPassword) {
        if (!ensureCloudConfigured()) {
            return false;
        }
        if (currentPassword.isEmpty()) {
            showCloudNotice("请先输入当前密码", 6000L);
            toast("请先输入当前密码");
            return false;
        }
        if (newPassword.length() < 8) {
            showCloudNotice("新密码至少 8 位", 6000L);
            toast("新密码至少 8 位");
            return false;
        }
        runCloudTask("正在修改密码", new CloudTask() {
            @Override
            public String run() throws Exception {
                CloudSyncClient client = new CloudSyncClient(MainActivity.this);
                if (!client.isLoggedIn()) {
                    throw new IllegalStateException("请先登录");
                }
                client.changePassword(currentPassword, newPassword);
                return "密码已修改";
            }
        });
        return true;
    }

    private void toggleRealtimeCloudSync() {
        if (!ensureCloudConfigured()) {
            return;
        }
        CloudSyncClient client = new CloudSyncClient(this);
        if (!client.isLoggedIn()) {
            showCloudNotice("请先登录", 6000L);
            toast("请先登录");
            return;
        }
        boolean enabled = !client.isRealtimeSyncEnabled();
        client.setRealtimeSyncEnabled(enabled);
        refreshCloudUi();
        showCloudNotice(enabled ? "实时同步已开启" : "实时同步已关闭", 6000L);
        toast(enabled ? "实时同步已开启" : "实时同步已关闭");
    }

    private void uploadCloudSnapshot() {
        if (!ensureCloudConfigured()) {
            return;
        }
        runCloudTask("正在上传", new CloudTask() {
            @Override
            public String run() throws Exception {
                CloudSyncClient client = new CloudSyncClient(MainActivity.this);
                if (!client.isLoggedIn()) {
                    throw new IllegalStateException("请先登录");
                }
                client.upload(TrackStore.exportSnapshot(MainActivity.this));
                return "已上传本机轨迹数据";
            }
        });
    }

    private void confirmDownloadCloudSnapshot() {
        new AlertDialog.Builder(this)
                .setTitle("从云端恢复")
                .setMessage("这会用云端轨迹覆盖本机轨迹数据。打卡照片原图仍只保存在原手机本机，确认继续吗？")
                .setNegativeButton("取消", null)
                .setPositiveButton("恢复", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        downloadCloudSnapshot();
                    }
                })
                .show();
    }

    private void downloadCloudSnapshot() {
        if (!ensureCloudConfigured()) {
            return;
        }
        runCloudTask("正在恢复", new CloudTask() {
            @Override
            public String run() throws Exception {
                CloudSyncClient client = new CloudSyncClient(MainActivity.this);
                if (!client.isLoggedIn()) {
                    throw new IllegalStateException("请先登录");
                }
                TrackStore.importSnapshot(MainActivity.this, client.download());
                return "已从云端恢复轨迹数据";
            }
        });
    }

    private void runCloudTask(String startMessage, final CloudTask task) {
        if (cloudTaskRunning) {
            showCloudNotice("云同步任务进行中，请稍等", 5000L);
            toast("云同步任务进行中");
            return;
        }
        Log.i(TAG, "cloud task start: " + startMessage);
        setCloudBusy(true, startMessage + "...");
        toast(startMessage);
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final String message = task.run();
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            setCloudBusy(false, null);
                            refreshCloudUi();
                            refreshUi();
                            showCloudNotice(message, 6000L);
                            toast(message);
                        }
                    });
                } catch (final Exception error) {
                    Log.w(TAG, "cloud task failed: " + startMessage, error);
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            setCloudBusy(false, null);
                            refreshCloudUi();
                            String message = cloudErrorMessage(error);
                            showCloudNotice(message, 10000L);
                            toast(message);
                        }
                    });
                }
            }
        }).start();
    }

    private boolean ensureCloudConfigured() {
        if (new CloudSyncClient(this).isConfigured()) {
            return true;
        }
        showCloudNotice("云同步服务未配置，无法注册或登录", 8000L);
        toast("云同步服务未配置");
        return false;
    }

    private boolean validateLoginCredentials(String username, String password) {
        if (username.isEmpty()) {
            showCloudNotice("请先输入用户名", 6000L);
            toast("请先输入用户名");
            return false;
        }
        if (password.isEmpty()) {
            showCloudNotice("请先输入密码", 6000L);
            toast("请先输入密码");
            return false;
        }
        return true;
    }

    private boolean validateRegisterCredentials(String username, String password, String email) {
        if (!validateLoginCredentials(username, password)) {
            return false;
        }
        if (!isValidUsername(username)) {
            showCloudNotice("用户名需为 3-32 位字母、数字、下划线、点或横线", 6000L);
            toast("用户名格式不正确");
            return false;
        }
        if (password.length() < 8) {
            showCloudNotice("密码至少 8 位", 6000L);
            toast("密码至少 8 位");
            return false;
        }
        if (email.isEmpty()) {
            showCloudNotice("请先输入邮箱", 6000L);
            toast("请先输入邮箱");
            return false;
        }
        if (!email.contains("@")) {
            showCloudNotice("邮箱格式不正确", 6000L);
            toast("邮箱格式不正确");
            return false;
        }
        return true;
    }

    private boolean isValidUsername(String username) {
        return username != null && username.matches("[A-Za-z0-9_.-]{3,32}");
    }

    private String cloudErrorMessage(Exception error) {
        String message = error.getMessage();
        if (message == null || message.trim().isEmpty()) {
            return "云同步失败，请检查网络或服务端配置";
        }
        return message;
    }

    private void setCloudBusy(boolean busy, String status) {
        cloudTaskRunning = busy;
        if (busy) {
            cloudNotice = "";
            cloudNoticeUntilMs = 0L;
        }
        setCloudButtonsEnabled(!busy);
        if (status != null) {
            setCloudStatus(status);
        }
    }

    private void showCloudNotice(String message, long durationMs) {
        cloudNotice = message == null ? "" : message;
        cloudNoticeUntilMs = cloudNotice.isEmpty() ? 0L : System.currentTimeMillis() + durationMs;
        setCloudStatus(cloudNotice);
    }

    private void setCloudStatus(String message) {
        if (cloudStatusText != null) {
            cloudStatusText.setText(message == null ? "" : message);
        }
    }

    private void setCloudButtonsEnabled(boolean enabled) {
        setCloudButtonEnabled(cloudRegisterButton, enabled);
        setCloudButtonEnabled(cloudLoginButton, enabled);
        setCloudButtonEnabled(cloudChangePasswordButton, enabled);
        setCloudButtonEnabled(cloudRealtimeButton, enabled);
        setCloudButtonEnabled(cloudUploadButton, enabled);
        setCloudButtonEnabled(cloudDownloadButton, enabled);
        setCloudButtonEnabled(cloudLogoutButton, enabled);
    }

    private void setCloudButtonEnabled(Button button, boolean enabled) {
        if (button == null) {
            return;
        }
        button.setEnabled(enabled);
        button.setAlpha(enabled ? 1f : 0.45f);
    }

    private void refreshCloudUi() {
        if (cloudStatusText == null) {
            return;
        }
        if (cloudTaskRunning) {
            return;
        }
        CloudSyncClient client = new CloudSyncClient(this);
        boolean configured = client.isConfigured();
        boolean loggedIn = client.isLoggedIn();
        setCloudButtonEnabled(cloudRegisterButton, configured);
        setCloudButtonEnabled(cloudLoginButton, configured);
        setCloudButtonEnabled(cloudChangePasswordButton, configured && loggedIn);
        setCloudButtonEnabled(cloudRealtimeButton, configured && loggedIn);
        setCloudButtonEnabled(cloudUploadButton, configured && loggedIn);
        setCloudButtonEnabled(cloudDownloadButton, configured && loggedIn);
        setCloudButtonEnabled(cloudLogoutButton, configured && loggedIn);
        setCloudSectionVisible(cloudAuthActions, configured && !loggedIn);
        setCloudSectionVisible(cloudAccountActions, configured && loggedIn);
        refreshCloudProfile(client, configured, loggedIn);
        refreshRealtimeCloudButton(client, configured && loggedIn);
        if (!cloudNotice.isEmpty()) {
            if (System.currentTimeMillis() < cloudNoticeUntilMs) {
                setCloudStatus(cloudNotice);
                return;
            }
            cloudNotice = "";
            cloudNoticeUntilMs = 0L;
        }
        if (!configured) {
            cloudStatusText.setText("云同步服务未配置，当前只使用本机数据。");
        } else if (loggedIn) {
            cloudStatusText.setText("已登录：" + cloudDisplayName(client.getUsername(), client.getEmail())
                    + "。实时同步：" + (client.isRealtimeSyncEnabled() ? "开" : "关")
                    + "。照片仍保留本机。");
        } else {
            cloudStatusText.setText("未登录。可选云同步会上传轨迹、行程和打卡文字，照片仍保留本机。");
        }
    }

    private void refreshRealtimeCloudButton(CloudSyncClient client, boolean enabled) {
        if (cloudRealtimeButton == null) {
            return;
        }
        boolean realtimeEnabled = enabled && client.isRealtimeSyncEnabled();
        cloudRealtimeButton.setText(realtimeEnabled ? "实时同步：开" : "实时同步：关");
        cloudRealtimeButton.setBackground(realtimeEnabled ? pill(color("#1F6F54"), 10f) : outlineButtonBg());
        cloudRealtimeButton.setTextColor(realtimeEnabled ? Color.WHITE : color("#1F6F54"));
    }

    private void refreshCloudProfile(CloudSyncClient client, boolean configured, boolean loggedIn) {
        if (cloudNameText == null || cloudMetaText == null || cloudAvatarText == null) {
            return;
        }
        if (!configured) {
            cloudNameText.setText("本机模式");
            cloudMetaText.setText("云同步服务未配置");
            cloudAvatarText.setText("本");
            cloudAvatarText.setTextColor(color("#6F756D"));
            cloudAvatarText.setBackground(pill(color("#ECE7DD"), 999f));
            return;
        }
        if (!loggedIn) {
            cloudNameText.setText("未登录用户");
            cloudMetaText.setText("登录后可同步轨迹数据");
            cloudAvatarText.setText("未");
            cloudAvatarText.setTextColor(color("#1F6F54"));
            cloudAvatarText.setBackground(pill(color("#E8F2EA"), 999f));
            return;
        }
        String username = client.getUsername();
        String email = client.getEmail();
        String displayName = cloudDisplayName(username, email);
        cloudNameText.setText(displayName);
        cloudMetaText.setText(email.isEmpty() ? "邮箱未保存" : email);
        cloudAvatarText.setText(cloudAvatarLabel(displayName));
        cloudAvatarText.setTextColor(Color.WHITE);
        cloudAvatarText.setBackground(pill(color("#1F6F54"), 999f));
    }

    private String cloudDisplayName(String username, String email) {
        String name = username == null ? "" : username.trim();
        if (!name.isEmpty()) {
            return name;
        }
        String value = email == null ? "" : email.trim();
        int at = value.indexOf("@");
        return at > 0 ? value.substring(0, at) : (value.isEmpty() ? "云同步用户" : value);
    }

    private String cloudAvatarLabel(String displayName) {
        String value = displayName == null ? "" : displayName.trim();
        if (value.isEmpty()) {
            return "云";
        }
        return value.substring(0, 1).toUpperCase(Locale.CHINA);
    }

    private void setCloudSectionVisible(View view, boolean visible) {
        if (view != null) {
            view.setVisibility(visible ? View.VISIBLE : View.GONE);
        }
    }

    private void refreshCommonAddressUi() {
        if (commonAddressList == null) {
            return;
        }
        commonAddressList.removeAllViews();
        List<TrackStore.AddressStat> stats = TrackStore.listCommonAddresses(this, 5);
        if (stats.isEmpty()) {
            TextView empty = text("暂无常用地址", 14, color("#6F756D"), Typeface.NORMAL);
            empty.setPadding(0, dp(4), 0, 0);
            commonAddressList.addView(empty);
            return;
        }
        for (TrackStore.AddressStat stat : stats) {
            commonAddressList.addView(commonAddressRow(stat));
        }
    }

    private View commonAddressRow(final TrackStore.AddressStat stat) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(7), 0, dp(7));

        LinearLayout textBox = new LinearLayout(this);
        textBox.setOrientation(LinearLayout.VERTICAL);
        row.addView(textBox, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView address = text(stat.address, 15, color("#25302B"), Typeface.BOLD);
        textBox.addView(address);

        TextView count = text(stat.count + " 次打卡", 12, color("#6F756D"), Typeface.NORMAL);
        count.setPadding(0, dp(3), 0, 0);
        textBox.addView(count);

        Button deleteButton = compactButton("删除", false);
        deleteButton.setTextSize(12);
        deleteButton.setPadding(dp(9), 0, dp(9), 0);
        deleteButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                TrackStore.deleteCommonAddress(MainActivity.this, stat.address);
                refreshCommonAddressUi();
                toast("已从常用地址移除");
            }
        });
        LinearLayout.LayoutParams deleteParams = new LinearLayout.LayoutParams(dp(58), dp(34));
        deleteParams.setMargins(dp(10), 0, 0, 0);
        row.addView(deleteButton, deleteParams);
        return row;
    }

    private boolean ensureForegroundLocation(int action) {
        if (hasForegroundLocationPermission()) {
            return true;
        }
        pendingLocationAction = action;
        requestPermissions(new String[]{
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
        }, REQUEST_FOREGROUND_LOCATION);
        return false;
    }

    private void runPendingLocationAction() {
        int action = pendingLocationAction;
        pendingLocationAction = ACTION_NONE;
        if (action == ACTION_START_RECORDING) {
            startRecordingFlow();
        } else if (action == ACTION_CHECKIN) {
            checkinFlow();
        } else if (action == ACTION_CENTER_MAP) {
            centerMapOnCurrentLocationFlow();
        } else {
            refreshUi();
        }
    }

    private void requestSingleLocation(final SingleLocationCallback callback, boolean showProgress) {
        try {
            final AMapLocationClient client = new AMapLocationClient(getApplicationContext());
            AMapLocationClientOption option = new AMapLocationClientOption();
            option.setLocationMode(AMapLocationClientOption.AMapLocationMode.Hight_Accuracy);
            option.setGpsFirst(true);
            option.setOnceLocationLatest(true);
            option.setNeedAddress(false);
            option.setLocationCacheEnable(false);
            option.setHttpTimeOut(10000L);
            client.setLocationOption(option);
            client.setLocationListener(new AMapLocationListener() {
                @Override
                public void onLocationChanged(AMapLocation location) {
                    client.stopLocation();
                    client.onDestroy();
                    if (location != null && location.getErrorCode() == 0) {
                        callback.onLocation(location);
                    } else {
                        showLocationFailure(location);
                    }
                }
            });
            if (showProgress) {
                toast("正在定位");
            }
            client.startLocation();
        } catch (Exception e) {
            Log.w(TAG, "single location startup failed", e);
            toastLong("无法获取定位服务：" + cleanText(e.getMessage()));
        }
    }

    private void showLocationFailure(AMapLocation location) {
        int code = location == null ? -1 : location.getErrorCode();
        String info = location == null ? "无定位结果" : cleanText(location.getErrorInfo());
        String detail = location == null ? "" : cleanText(location.getLocationDetail());
        Log.w(TAG, "single location failed: code=" + code + ", info=" + info + ", detail=" + detail);
        toastLong("定位失败：" + code + (info.isEmpty() ? "" : " " + info));
    }

    private void showCheckinDialog(AMapLocation location) {
        pendingCheckinLocation = location;
        pendingPhotoPaths = new ArrayList<>();
        pendingAddressOptions = new ArrayList<>();
        pendingCheckinAddress = "";

        ScrollView dialogScroll = new ScrollView(this);
        dialogScroll.setFillViewport(false);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(18), dp(8), dp(18), dp(6));
        dialogScroll.addView(box, matchWrap());

        LinearLayout addressRow = new LinearLayout(this);
        addressRow.setOrientation(LinearLayout.HORIZONTAL);
        addressRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams addressRowParams = matchWrap();
        addressRowParams.setMargins(0, dp(2), 0, dp(8));
        box.addView(addressRow, addressRowParams);

        activeAddressText = text("点击右侧图标选择地名，或手动输入", 13, color("#6F756D"), Typeface.NORMAL);
        activeAddressText.setSingleLine(false);
        addressRow.addView(activeAddressText, new LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
        ));

        activeAddressButton = new ImageButton(this);
        activeAddressButton.setImageResource(R.drawable.ic_map_pin);
        activeAddressButton.setBackground(outlineButtonBg());
        activeAddressButton.setScaleType(ImageView.ScaleType.CENTER);
        activeAddressButton.setPadding(dp(9), dp(9), dp(9), dp(9));
        activeAddressButton.setContentDescription("选择地名");
        activeAddressButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                resolvePendingCheckinAddress();
            }
        });
        LinearLayout.LayoutParams mapButtonParams = new LinearLayout.LayoutParams(dp(44), dp(44));
        mapButtonParams.setMargins(dp(8), 0, 0, 0);
        addressRow.addView(activeAddressButton, mapButtonParams);

        activeNoteInput = new EditText(this);
        activeNoteInput.setHint("写一句备注");
        activeNoteInput.setTextSize(15);
        activeNoteInput.setTextColor(color("#25302B"));
        activeNoteInput.setHintTextColor(color("#9A8F80"));
        activeNoteInput.setGravity(Gravity.TOP | Gravity.LEFT);
        activeNoteInput.setMinLines(2);
        activeNoteInput.setMaxLines(4);
        activeNoteInput.setPadding(dp(12), dp(9), dp(12), dp(9));
        activeNoteInput.setBackground(outlineButtonBg());
        activeNoteInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        box.addView(activeNoteInput, matchWrap());

        LinearLayout photoRow = new LinearLayout(this);
        photoRow.setOrientation(LinearLayout.HORIZONTAL);
        photoRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams photoRowParams = matchWrap();
        photoRowParams.setMargins(0, dp(12), 0, 0);
        box.addView(photoRow, photoRowParams);

        Button cameraButton = compactButton("拍照", true);
        cameraButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                openCamera();
            }
        });
        photoRow.addView(cameraButton, weightedButtonParams(1f, 0, dp(5)));

        Button pickButton = compactButton("选照片", false);
        pickButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                openImagePicker();
            }
        });
        photoRow.addView(pickButton, weightedButtonParams(1f, dp(5), dp(8)));

        activePhotoCountText = text("0/9", 14, color("#6F756D"), Typeface.NORMAL);
        photoRow.addView(activePhotoCountText);

        activePhotoPreviewList = new LinearLayout(this);
        activePhotoPreviewList.setOrientation(LinearLayout.VERTICAL);
        activePhotoPreviewList.setVisibility(View.GONE);
        LinearLayout.LayoutParams previewParams = matchWrap();
        previewParams.setMargins(0, dp(12), 0, 0);
        box.addView(activePhotoPreviewList, previewParams);

        activeCheckinDialog = new AlertDialog.Builder(this)
                .setCustomTitle(buildCheckinDialogTitle(location))
                .setView(dialogScroll)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存打卡", null)
                .create();
        activeCheckinDialog.setOnShowListener(new android.content.DialogInterface.OnShowListener() {
            @Override
            public void onShow(android.content.DialogInterface dialog) {
                activeCheckinDialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(color("#6F756D"));
                activeCheckinDialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(color("#1F6F54"));
                activeCheckinDialog
                        .getButton(AlertDialog.BUTTON_POSITIVE)
                        .setOnClickListener(new View.OnClickListener() {
                            @Override
                            public void onClick(View view) {
                                savePendingCheckin();
                            }
                        });
            }
        });
        activeCheckinDialog.setOnDismissListener(new android.content.DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(android.content.DialogInterface dialog) {
                activeCheckinDialog = null;
                activePhotoCountText = null;
                activeAddressText = null;
                activeAddressButton = null;
                activePhotoPreviewList = null;
                activeNoteInput = null;
                pendingCheckinLocation = null;
                pendingPhotoPaths = new ArrayList<>();
                pendingAddressOptions = new ArrayList<>();
                pendingCheckinAddress = "";
            }
        });
        activeCheckinDialog.show();
    }

    private View buildCheckinDialogTitle(AMapLocation location) {
        LinearLayout title = new LinearLayout(this);
        title.setOrientation(LinearLayout.HORIZONTAL);
        title.setGravity(Gravity.CENTER_VERTICAL);
        title.setPadding(dp(22), dp(18), dp(22), dp(6));

        TextView name = text("保存打卡", 24, color("#25302B"), Typeface.NORMAL);
        title.addView(name);

        TextView coordinate = text(formatLatLng(location), 14, color("#6F756D"), Typeface.NORMAL);
        coordinate.setSingleLine(true);
        LinearLayout.LayoutParams coordinateParams = new LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
        );
        coordinateParams.setMargins(dp(10), dp(3), 0, 0);
        title.addView(coordinate, coordinateParams);
        return title;
    }

    private void resolvePendingCheckinAddress() {
        if (pendingCheckinLocation == null) {
            toast("没有可用位置");
            return;
        }
        if (!pendingAddressOptions.isEmpty()) {
            showAddressPickerDialog();
            return;
        }
        if (activeAddressButton != null) {
            activeAddressButton.setEnabled(false);
            activeAddressButton.setAlpha(0.45f);
        }
        if (activeAddressText != null) {
            activeAddressText.setText("正在获取地名");
        }

        try {
            GeocodeSearch search = new GeocodeSearch(this);
            search.setOnGeocodeSearchListener(new GeocodeSearch.OnGeocodeSearchListener() {
                @Override
                public void onRegeocodeSearched(final RegeocodeResult result, final int code) {
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            handleResolvedAddress(result, code);
                        }
                    });
                }

                @Override
                public void onGeocodeSearched(GeocodeResult result, int code) {
                }
            });
            LatLonPoint point = new LatLonPoint(
                    pendingCheckinLocation.getLatitude(),
                    pendingCheckinLocation.getLongitude()
            );
            RegeocodeQuery query = new RegeocodeQuery(point, 300f, GeocodeSearch.AMAP);
            search.getFromLocationAsyn(query);
        } catch (Exception ignored) {
            resetAddressButton();
            if (activeAddressText != null) {
                activeAddressText.setText("未获取地名");
            }
            toast("无法获取地名");
        }
    }

    private void handleResolvedAddress(RegeocodeResult result, int code) {
        resetAddressButton();
        if (activeCheckinDialog == null) {
            return;
        }
        ArrayList<String> addresses = code == 1000 && result != null
                ? collectRegeocodeAddressOptions(result.getRegeocodeAddress())
                : new ArrayList<String>();
        if (addresses.isEmpty()) {
            pendingCheckinAddress = "";
            pendingAddressOptions = new ArrayList<>();
            if (activeAddressText != null) {
                activeAddressText.setText("未获取地名，可手动输入");
            }
            showAddressPickerDialog();
            return;
        }
        pendingAddressOptions = addresses;
        showAddressPickerDialog();
    }

    private void resetAddressButton() {
        if (activeAddressButton != null) {
            activeAddressButton.setEnabled(true);
            activeAddressButton.setAlpha(1f);
        }
    }

    private ArrayList<String> collectRegeocodeAddressOptions(RegeocodeAddress address) {
        ArrayList<String> options = new ArrayList<>();
        if (address == null) {
            return options;
        }
        List<PoiItem> pois = address.getPois();
        if (pois != null) {
            for (PoiItem poi : pois) {
                if (options.size() >= 8) {
                    break;
                }
                if (poi != null) {
                    addAddressOption(options, poi.getTitle());
                    addAddressOption(options, joinAddressParts(poi.getTitle(), invokeString(poi, "getSnippet")));
                }
            }
        }
        addReflectListAddressOptions(options, address, "getAois", "getAoiName");
        addReflectListAddressOptions(options, address, "getAois", "getName");
        Object streetNumber = invokeNoArg(address, "getStreetNumber");
        addAddressOption(options, joinAddressParts(invokeString(streetNumber, "getStreet"), invokeString(streetNumber, "getNumber")));
        addAddressOption(options, joinAddressParts(
                invokeString(address, "getDistrict"),
                invokeString(address, "getTownship"),
                invokeString(address, "getNeighborhood")
        ));
        addAddressOption(options, joinAddressParts(
                invokeString(address, "getTownship"),
                invokeString(address, "getBuilding")
        ));
        addAddressOption(options, address.getFormatAddress());
        return options;
    }

    private void addAddressOption(ArrayList<String> options, String value) {
        String cleaned = cleanText(value);
        if (!cleaned.isEmpty() && !options.contains(cleaned)) {
            options.add(cleaned);
        }
    }

    private void addReflectListAddressOptions(ArrayList<String> options, Object target, String listMethod, String itemMethod) {
        Object value = invokeNoArg(target, listMethod);
        if (!(value instanceof List<?>)) {
            return;
        }
        for (Object item : (List<?>) value) {
            if (options.size() >= 12) {
                break;
            }
            addAddressOption(options, invokeString(item, itemMethod));
        }
    }

    private String joinAddressParts(String first, String second) {
        String a = cleanText(first);
        String b = cleanText(second);
        if (a.isEmpty()) {
            return b;
        }
        if (b.isEmpty() || a.equals(b) || a.contains(b)) {
            return a;
        }
        return a + " " + b;
    }

    private String joinAddressParts(String first, String second, String third) {
        return joinAddressParts(joinAddressParts(first, second), third);
    }

    private String invokeString(Object target, String methodName) {
        Object value = invokeNoArg(target, methodName);
        return value == null ? "" : String.valueOf(value);
    }

    private Object invokeNoArg(Object target, String methodName) {
        if (target == null) {
            return null;
        }
        try {
            java.lang.reflect.Method method = target.getClass().getMethod(methodName);
            return method.invoke(target);
        } catch (Exception ignored) {
            return null;
        }
    }

    private void showAddressPickerDialog() {
        if (activeCheckinDialog == null) {
            return;
        }
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(4), dp(8), dp(4), 0);

        String initial = pendingCheckinAddress;
        if (initial == null || initial.isEmpty()) {
            initial = pendingAddressOptions.isEmpty() ? "" : pendingAddressOptions.get(0);
        }

        TextView pickerLabel = text("候选地址 / 手动输入", 13, color("#6F756D"), Typeface.BOLD);
        content.addView(pickerLabel);

        final AutoCompleteTextView addressInput = new AutoCompleteTextView(this);
        addressInput.setHint("输入关键词筛选地名");
        addressInput.setTextSize(15);
        addressInput.setSingleLine(true);
        addressInput.setTextColor(color("#25302B"));
        addressInput.setHintTextColor(color("#9A8F80"));
        addressInput.setPadding(dp(12), dp(9), dp(12), dp(9));
        addressInput.setBackground(outlineButtonBg());
        addressInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        addressInput.setThreshold(0);
        if (!pendingAddressOptions.isEmpty()) {
            ArrayAdapter<String> adapter = new ArrayAdapter<>(
                    this,
                    android.R.layout.simple_dropdown_item_1line,
                    pendingAddressOptions
            );
            addressInput.setAdapter(adapter);
            addressInput.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    addressInput.showDropDown();
                }
            });
            addressInput.setOnFocusChangeListener(new View.OnFocusChangeListener() {
                @Override
                public void onFocusChange(View view, boolean hasFocus) {
                    if (hasFocus) {
                        addressInput.showDropDown();
                    }
                }
            });
        } else {
            TextView empty = text("没有获取到附近地址，可以手动填写。", 13, color("#6F756D"), Typeface.NORMAL);
            empty.setPadding(0, dp(8), 0, 0);
            content.addView(empty);
        }
        addressInput.setText(initial);
        addressInput.setSelection(addressInput.getText().length());
        LinearLayout.LayoutParams inputParams = matchWrap();
        inputParams.setMargins(0, dp(6), 0, 0);
        content.addView(addressInput, inputParams);

        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("选择地名")
                .setView(content)
                .setNegativeButton("取消", null)
                .setPositiveButton("确定", null)
                .create();
        dialog.setOnShowListener(new android.content.DialogInterface.OnShowListener() {
            @Override
            public void onShow(android.content.DialogInterface value) {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(color("#1F6F54"));
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(color("#6F756D"));
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        String address = inputValue(addressInput);
                        if (address.isEmpty()) {
                            pendingCheckinAddress = "";
                            if (activeAddressText != null) {
                                activeAddressText.setText("未填写地名");
                            }
                        } else {
                            pendingCheckinAddress = address;
                            addAddressOption(pendingAddressOptions, address);
                            if (activeAddressText != null) {
                                activeAddressText.setText(address);
                            }
                        }
                        dialog.dismiss();
                    }
                });
            }
        });
        dialog.show();
        if (!pendingAddressOptions.isEmpty()) {
            addressInput.post(new Runnable() {
                @Override
                public void run() {
                    addressInput.showDropDown();
                }
            });
        }
    }

    private String formatLatLng(AMapLocation location) {
        if (location == null) {
            return "";
        }
        return String.format(Locale.CHINA, "%.5f, %.5f", location.getLatitude(), location.getLongitude());
    }

    private String cleanText(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ");
    }

    private String checkinTitle(TrackStore.CheckinRecord checkin) {
        if (checkin == null) {
            return "打卡";
        }
        if (hasText(checkin.address)) {
            return checkin.address;
        }
        if (hasText(checkin.note)) {
            return checkin.note;
        }
        return "打卡";
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private void openImagePicker() {
        if (pendingPhotoPaths.size() >= 9) {
            toast("最多 9 张照片");
            return;
        }
        int remaining = remainingPhotoSlots();
        Intent intent;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent = new Intent(MediaStore.ACTION_PICK_IMAGES);
            intent.setType("image/*");
            int maxPick = Math.min(remaining, MediaStore.getPickImagesMaxLimit());
            if (maxPick > 1) {
                intent.putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, maxPick);
            }
        } else {
            intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("image/*");
            intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        }
        startActivityForResult(Intent.createChooser(intent, "选择照片"), REQUEST_PICK_IMAGES);
    }

    private void openCamera() {
        if (pendingPhotoPaths.size() >= 9) {
            toast("最多 9 张照片");
            return;
        }
        File photo = null;
        Uri outputUri = null;
        boolean fromMediaStore = false;
        try {
            Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10+ 上部分系统相机不会稳定写入外部传入的 URI，直接使用回传缩略图更兼容。
                pendingCameraPhotoPath = null;
                pendingCameraPhotoUri = null;
                pendingCameraPhotoFromMediaStore = false;
                startActivityForResult(intent, REQUEST_CAPTURE_IMAGE);
                return;
            }
            photo = CheckinPhotoProvider.createPhotoFile(this);
            outputUri = CheckinPhotoProvider.uriFor(this, photo);
            intent.putExtra(MediaStore.EXTRA_OUTPUT, outputUri);
            intent.setClipData(ClipData.newUri(getContentResolver(), "checkin_photo", outputUri));
            intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            grantCameraUriPermissions(intent, outputUri);
            pendingCameraPhotoPath = photo == null ? null : photo.getAbsolutePath();
            pendingCameraPhotoUri = outputUri;
            pendingCameraPhotoFromMediaStore = fromMediaStore;
            startActivityForResult(intent, REQUEST_CAPTURE_IMAGE);
        } catch (Exception e) {
            Log.w(TAG, "camera startup failed", e);
            deleteCameraOutput(outputUri, photo == null ? null : photo.getAbsolutePath(), fromMediaStore);
            pendingCameraPhotoPath = null;
            pendingCameraPhotoUri = null;
            pendingCameraPhotoFromMediaStore = false;
            toast("无法打开相机");
        }
    }

    private Uri createCameraImageUri() {
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, "checkin_" + System.currentTimeMillis() + ".jpg");
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
        values.put(MediaStore.Images.Media.DATE_TAKEN, System.currentTimeMillis());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/MotionTrace");
            values.put(MediaStore.Images.Media.IS_PENDING, 1);
        }
        Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
        if (uri == null) {
            throw new IllegalStateException("Cannot create camera image uri");
        }
        return uri;
    }

    private void grantCameraUriPermissions(Intent intent, Uri outputUri) {
        List<ResolveInfo> handlers = getPackageManager().queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY);
        for (ResolveInfo handler : handlers) {
            if (handler.activityInfo != null && handler.activityInfo.packageName != null) {
                grantUriPermission(
                        handler.activityInfo.packageName,
                        outputUri,
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION
                );
            }
        }
    }

    private void importPickedImages(Intent data) {
        if (activeCheckinDialog == null) {
            return;
        }
        List<Uri> uris = new ArrayList<>();
        ClipData clipData = data.getClipData();
        if (clipData != null) {
            for (int i = 0; i < clipData.getItemCount(); i++) {
                uris.add(clipData.getItemAt(i).getUri());
            }
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }

        int imported = 0;
        for (Uri uri : uris) {
            if (pendingPhotoPaths.size() >= 9) {
                break;
            }
            try {
                pendingPhotoPaths.add(TrackStore.saveImage(this, uri));
                imported++;
            } catch (Exception e) {
                Log.w(TAG, "picked image import failed: " + uri, e);
            }
        }
        updateActivePhotoCount();
        toast(imported > 0 ? "已添加 " + imported + " 张照片" : "照片读取失败");
    }

    // 系统相机返回差异较大：优先读取输出 URI/文件，必要时再使用回传缩略图兜底。
    private void importCapturedImage(boolean captured, Intent data) {
        String path = pendingCameraPhotoPath;
        Uri uri = pendingCameraPhotoUri;
        boolean fromMediaStore = pendingCameraPhotoFromMediaStore;
        pendingCameraPhotoPath = null;
        pendingCameraPhotoUri = null;
        pendingCameraPhotoFromMediaStore = false;
        if (activeCheckinDialog == null || pendingPhotoPaths.size() >= 9) {
            deleteCameraOutput(uri, path, fromMediaStore);
            return;
        }

        String importedPath = importCameraOutput(uri, path, fromMediaStore);
        if (importedPath == null) {
            importedPath = saveCameraThumbnail(data);
        }

        if (importedPath != null) {
            pendingPhotoPaths.add(importedPath);
            updateActivePhotoCount();
            toast("已添加照片");
        } else {
            toast(captured ? "照片保存失败" : "未拍摄照片");
        }
        if (fromMediaStore) {
            deleteCameraOutput(uri, null, true);
        } else if (importedPath == null) {
            deleteCameraOutput(null, path, false);
        }
    }

    private String importCameraOutput(Uri uri, String path, boolean fromMediaStore) {
        try {
            if (fromMediaStore && uri != null && uriHasContent(uri)) {
                return TrackStore.saveImage(this, uri);
            }
            if (!fromMediaStore && path != null) {
                File photo = new File(path);
                if (photo.exists() && photo.length() > 0) {
                    return path;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "camera image import failed", e);
        }
        return null;
    }

    private boolean uriHasContent(Uri uri) {
        if (uri == null) {
            return false;
        }
        try (InputStream input = getContentResolver().openInputStream(uri)) {
            return input != null && input.read() != -1;
        } catch (Exception e) {
            Log.w(TAG, "camera uri check failed: " + uri, e);
            return false;
        }
    }

    private String saveCameraThumbnail(Intent data) {
        if (data == null || data.getExtras() == null) {
            return null;
        }
        Object value = data.getExtras().get("data");
        if (!(value instanceof Bitmap)) {
            return null;
        }
        File dir = new File(getFilesDir(), "checkin_photos");
        if (!dir.exists() && !dir.mkdirs()) {
            return null;
        }
        File outFile = new File(dir, "camera_thumb_" + System.currentTimeMillis() + ".jpg");
        try (OutputStream output = new FileOutputStream(outFile)) {
            Bitmap bitmap = (Bitmap) value;
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 92, output)) {
                if (outFile.exists()) {
                    outFile.delete();
                }
                return null;
            }
            return outFile.getAbsolutePath();
        } catch (Exception e) {
            Log.w(TAG, "camera thumbnail save failed", e);
            if (outFile.exists()) {
                outFile.delete();
            }
            return null;
        }
    }

    private void deleteCameraOutput(Uri uri, String path, boolean fromMediaStore) {
        if (fromMediaStore && uri != null) {
            try {
                getContentResolver().delete(uri, null, null);
            } catch (Exception e) {
                Log.w(TAG, "camera media cleanup failed: " + uri, e);
            }
        }
        if (!fromMediaStore && path != null) {
            File photo = new File(path);
            if (photo.exists() && !photo.delete()) {
                Log.w(TAG, "camera file cleanup failed: " + path);
            }
        }
    }

    private void updateActivePhotoCount() {
        if (activePhotoCountText != null) {
            activePhotoCountText.setText(pendingPhotoPaths.size() + "/9");
        }
        updateActivePhotoPreview();
    }

    private void updateActivePhotoPreview() {
        if (activePhotoPreviewList == null) {
            return;
        }
        activePhotoPreviewList.removeAllViews();
        if (pendingPhotoPaths.isEmpty()) {
            activePhotoPreviewList.setVisibility(View.GONE);
            return;
        }
        activePhotoPreviewList.setVisibility(View.VISIBLE);
        LinearLayout row = null;
        for (int i = 0; i < pendingPhotoPaths.size(); i++) {
            if (i % 3 == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams rowParams = matchWrap();
                rowParams.setMargins(0, 0, 0, dp(8));
                activePhotoPreviewList.addView(row, rowParams);
            }
            if (row != null) {
                row.addView(pendingPhotoThumb(i, i % 3));
            }
        }
        int remainder = pendingPhotoPaths.size() % 3;
        if (row != null && remainder != 0) {
            for (int column = remainder; column < 3; column++) {
                View spacer = new View(this);
                spacer.setLayoutParams(photoGridCellParams(column));
                row.addView(spacer);
            }
        }
    }

    private View pendingPhotoThumb(final int index, int column) {
        FrameLayout frame = new FrameLayout(this);
        frame.setLayoutParams(photoGridCellParams(column));
        frame.setBackground(cardBg());
        frame.setPadding(dp(2), dp(2), dp(2), dp(2));

        final String path = pendingPhotoPaths.get(index);
        ImageView image = new ImageView(this);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        image.setBackgroundColor(color("#ECE7DD"));
        image.setImageURI(Uri.fromFile(new File(path)));
        image.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                previewPhoto(path);
            }
        });
        frame.addView(image, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        TextView remove = text("X", 10, Color.WHITE, Typeface.BOLD);
        remove.setGravity(Gravity.CENTER);
        remove.setBackground(pill(color("#B94F44"), 999f));
        remove.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                removePendingPhoto(index);
            }
        });
        FrameLayout.LayoutParams removeParams = new FrameLayout.LayoutParams(dp(20), dp(20), Gravity.RIGHT | Gravity.TOP);
        frame.addView(remove, removeParams);
        return frame;
    }

    private LinearLayout.LayoutParams photoGridCellParams(int column) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(82), 1f);
        params.setMargins(column == 0 ? 0 : dp(4), 0, column == 2 ? 0 : dp(4), 0);
        return params;
    }

    private void removePendingPhoto(int index) {
        if (index < 0 || index >= pendingPhotoPaths.size()) {
            return;
        }
        pendingPhotoPaths.remove(index);
        updateActivePhotoCount();
    }

    private int remainingPhotoSlots() {
        return Math.max(0, 9 - pendingPhotoPaths.size());
    }

    private void savePendingCheckin() {
        if (pendingCheckinLocation == null) {
            return;
        }
        String note = activeNoteInput == null ? "" : activeNoteInput.getText().toString().trim();
        TrackStore.addCheckin(this, TrackStore.today(), pendingCheckinLocation, note, pendingCheckinAddress, pendingPhotoPaths);
        if (activeCheckinDialog != null) {
            activeCheckinDialog.dismiss();
        }
        toast("已保存打卡");
        refreshUi();
    }

    private void refreshUi() {
        TrackStore.DayRecord day = TrackStore.getDay(this, TrackStore.today());
        boolean recording = TrackRecordingService.isRecording(this);
        if (recording && !selectedTodayTripId.isEmpty()) {
            selectedTodayTripId = "";
            checkinRenderSignature = "";
        }
        TrackStore.Stats stats = TrackStore.buildStats(day, recording);

        statusText.setText(recording ? "记录中" : "未记录");
        statusText.setTextColor(recording ? color("#1F6F54") : color("#6F756D"));
        statusText.setBackground(pill(recording ? color("#E5F1EA") : color("#ECE7DD"), 999f));

        distanceText.setText(stats.distance);
        durationText.setText(stats.duration);
        tripsText.setText(stats.trips);
        checkinsText.setText(stats.checkins);
        recordButton.setText(recording ? "停止记录" : "开始记录");
        recordButton.setBackground(recording ? pill(color("#B94F44"), 10f) : pill(color("#1F6F54"), 10f));
        if (checkinButton != null) {
            checkinButton.setEnabled(recording);
            checkinButton.setAlpha(recording ? 1f : 0.45f);
            checkinButton.setText(recording ? "沿途打卡" : "先开始记录");
        }
        renderMap(day);

        if (hasBackgroundLocationPermission()) {
            if (backgroundText != null) {
                backgroundText.setText("后台定位：已允许");
            }
            if (backgroundButton != null) {
                backgroundButton.setText("后台已允许");
            }
        } else {
            if (backgroundText != null) {
                backgroundText.setText("后台定位：前台服务已可锁屏记录；授予“始终允许”后，从后台恢复会更稳。");
            }
            if (backgroundButton != null) {
                backgroundButton.setText("后台权限");
            }
        }

        renderCheckins(day);
        if (selectedTab == TAB_SETTINGS) {
            refreshCloudUi();
            refreshCommonAddressUi();
        }
    }

    private void renderCheckins(TrackStore.DayRecord day) {
        if (checkinTimelineScroller != null) {
            checkinTimelineScrollX = checkinTimelineScroller.getScrollX();
        }
        List<CheckinTripGroup> groups = buildCheckinTripGroups(day);
        boolean recording = TrackRecordingService.isRecording(this);
        String signature = checkinRenderSignature(groups);
        if (signature.equals(checkinRenderSignature) && checkinList.getChildCount() > 0) {
            return;
        }
        checkinRenderSignature = signature;
        checkinList.removeAllViews();
        if (groups.isEmpty()) {
            checkinTimelineScroller = null;
            TextView empty = text("今天还没有行程和打卡", 14, color("#6F756D"), Typeface.NORMAL);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, dp(14), 0, dp(14));
            empty.setBackground(pill(color("#F0EDE5"), 8f));
            checkinList.addView(empty);
            return;
        }

        final HorizontalScrollView scroller = new HorizontalScrollView(this);
        checkinTimelineScroller = scroller;
        scroller.setHorizontalScrollBarEnabled(false);
        scroller.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, 0, dp(2), 0);
        scroller.addView(row, new HorizontalScrollView.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        int cardWidth = Math.max(dp(266), getResources().getDisplayMetrics().widthPixels - dp(60));
        for (int i = 0; i < groups.size(); i++) {
            row.addView(checkinTripCard(groups.get(i), cardWidth, i == groups.size() - 1, recording));
        }

        checkinList.addView(scroller, matchWrap());
        final int restoreScrollX = checkinTimelineScrollX;
        scroller.post(new Runnable() {
            @Override
            public void run() {
                scroller.scrollTo(restoreScrollX, 0);
            }
        });
    }

    private String checkinRenderSignature(List<CheckinTripGroup> groups) {
        StringBuilder builder = new StringBuilder();
        builder.append(groups.size());
        for (CheckinTripGroup group : groups) {
            builder.append('|')
                    .append(group.tripId)
                    .append(':')
                    .append(group.startTime)
                    .append(':')
                    .append(group.endTime)
                    .append(':')
                    .append(group.checkins.size())
                    .append(':')
                    .append(group.tripId.equals(selectedTodayTripId) ? "selected" : "");
            for (TrackStore.CheckinRecord checkin : group.checkins) {
                builder.append('#')
                        .append(checkin.id)
                        .append('@')
                        .append(checkin.timestamp)
                        .append('@')
                        .append(checkin.photos.size());
            }
        }
        return builder.toString();
    }

    // 按行程时间段归类打卡；未处于任何行程内的打卡单独展示，避免历史数据丢失。
    private List<CheckinTripGroup> buildCheckinTripGroups(TrackStore.DayRecord day) {
        List<CheckinTripGroup> groups = new ArrayList<>();
        if (day == null) {
            return groups;
        }

        for (int i = 0; i < day.trips.size(); i++) {
            TrackStore.TripRecord trip = day.trips.get(i);
            groups.add(new CheckinTripGroup(
                    "行程 " + (i + 1),
                    tripTimeRange(trip.startTime, trip.endTime),
                    trip.id,
                    trip.startTime,
                    trip.endTime,
                    false
            ));
        }

        if (groups.isEmpty() && day.tripCount > 0) {
            groups.add(new CheckinTripGroup(
                    "行程 1",
                    tripTimeRange(day.startTime, day.endTime),
                    "",
                    day.startTime,
                    day.endTime,
                    false
            ));
        }

        CheckinTripGroup unassigned = null;
        for (TrackStore.CheckinRecord checkin : day.checkins) {
            CheckinTripGroup group = findCheckinTripGroup(groups, checkin);
            if (group == null) {
                if (unassigned == null) {
                    unassigned = new CheckinTripGroup("未归属", "不在行程时间内", "", 0L, 0L, true);
                    groups.add(unassigned);
                }
                group = unassigned;
            }
            group.checkins.add(checkin);
        }

        return groups;
    }

    private TrackStore.TripRecord activeTodayTrip(TrackStore.DayRecord day) {
        if (day == null) {
            return null;
        }
        for (int i = day.trips.size() - 1; i >= 0; i--) {
            TrackStore.TripRecord trip = day.trips.get(i);
            if (trip != null && trip.startTime > 0L && trip.endTime == 0L) {
                return trip;
            }
        }
        return null;
    }

    private CheckinTripGroup findCheckinTripGroup(List<CheckinTripGroup> groups, TrackStore.CheckinRecord checkin) {
        if (checkin != null && hasText(checkin.tripId)) {
            for (CheckinTripGroup group : groups) {
                if (checkin.tripId.equals(group.tripId)) {
                    return group;
                }
            }
        }
        for (CheckinTripGroup group : groups) {
            if (checkinBelongsToTrip(group, checkin)) {
                return group;
            }
        }
        return null;
    }

    private boolean checkinBelongsToTrip(CheckinTripGroup group, TrackStore.CheckinRecord checkin) {
        if (group.unassigned || checkin == null || group.startTime <= 0L || checkin.timestamp <= 0L) {
            return false;
        }
        long end = group.endTime > 0L ? group.endTime : System.currentTimeMillis();
        return checkin.timestamp >= group.startTime && checkin.timestamp <= end;
    }

    private String tripTimeRange(long startTime, long endTime) {
        String start = TrackStore.formatClock(startTime);
        String end = endTime > 0L ? TrackStore.formatClock(endTime) : "进行中";
        return start + " - " + end;
    }

    private View checkinTripCard(final CheckinTripGroup group, int width, boolean last, boolean recording) {
        boolean selected = !recording && hasText(group.tripId) && group.tripId.equals(selectedTodayTripId);
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setMinimumHeight(dp(150));
        card.setPadding(dp(14), dp(13), dp(14), dp(13));
        card.setBackground(pill(selected ? color("#E8F2EA") : color("#FFFDF8"), 8f));
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                width,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        cardParams.setMargins(0, 0, last ? 0 : dp(10), dp(2));
        card.setLayoutParams(cardParams);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(8), dp(7), dp(8), dp(7));
        header.setBackground(pill(selected ? color("#D9ECDE") : color("#F7F4EE"), 8f));
        if (!recording && hasText(group.tripId)) {
            header.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    selectedTodayTripId = group.tripId.equals(selectedTodayTripId) ? "" : group.tripId;
                    checkinRenderSignature = "";
                    mainMapUserMoved = false;
                    mainMapFocusKey = "";
                    refreshUi();
                }
            });
        } else {
            header.setAlpha(recording ? 0.78f : 1f);
        }
        card.addView(header, matchWrap());

        TextView title = text(group.title, 16, selected ? color("#1F6F54") : color("#25302B"), Typeface.BOLD);
        header.addView(title, new LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
        ));

        TextView count = text(group.checkins.size() + " 次打卡", 12, color("#1F6F54"), Typeface.BOLD);
        count.setGravity(Gravity.CENTER);
        count.setPadding(dp(10), dp(4), dp(10), dp(4));
        count.setBackground(pill(color("#E5F1EA"), 999f));
        header.addView(count);

        if (selected) {
            TextView selectedBadge = text("已选", 12, color("#FFFFFF"), Typeface.BOLD);
            selectedBadge.setGravity(Gravity.CENTER);
            selectedBadge.setPadding(dp(8), dp(4), dp(8), dp(4));
            selectedBadge.setBackground(pill(color("#1F6F54"), 999f));
            LinearLayout.LayoutParams selectedParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            );
            selectedParams.setMargins(dp(8), 0, 0, 0);
            header.addView(selectedBadge, selectedParams);
        }

        TextView range = text(group.subtitle, 12, color("#6F756D"), Typeface.NORMAL);
        range.setPadding(0, dp(5), 0, dp(12));
        card.addView(range);

        if (group.checkins.isEmpty()) {
            TextView empty = text("本行程还没有打卡", 13, color("#9A8F80"), Typeface.NORMAL);
            empty.setGravity(Gravity.CENTER_VERTICAL);
            empty.setPadding(dp(12), 0, dp(12), 0);
            empty.setMinHeight(dp(58));
            empty.setBackground(pill(color("#F7F4EE"), 8f));
            card.addView(empty);
            return card;
        }

        for (int i = 0; i < group.checkins.size(); i++) {
            card.addView(checkinTimelineItem(group.checkins.get(i), i == group.checkins.size() - 1));
        }

        return card;
    }

    private View checkinTimelineItem(final TrackStore.CheckinRecord checkin, boolean last) {
        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.HORIZONTAL);
        item.setPadding(0, 0, 0, last ? 0 : dp(12));
        item.setMinimumHeight(dp(64));
        item.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                showCheckinDetail(checkin);
            }
        });

        FrameLayout rail = new FrameLayout(this);
        if (!last) {
            View line = new View(this);
            line.setBackgroundColor(color("#E3DDD0"));
            FrameLayout.LayoutParams lineParams = new FrameLayout.LayoutParams(
                    dp(2),
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    Gravity.TOP | Gravity.CENTER_HORIZONTAL
            );
            lineParams.topMargin = dp(13);
            rail.addView(line, lineParams);
        }
        View dot = new View(this);
        dot.setBackground(pill(color("#1F6F54"), 999f));
        FrameLayout.LayoutParams dotParams = new FrameLayout.LayoutParams(
                dp(9),
                dp(9),
                Gravity.TOP | Gravity.CENTER_HORIZONTAL
        );
        dotParams.topMargin = dp(5);
        rail.addView(dot, dotParams);
        item.addView(rail, new LinearLayout.LayoutParams(dp(26), ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        item.addView(content, new LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
        ));

        TextView time = text(TrackStore.formatClock(checkin.timestamp), 13, color("#6F756D"), Typeface.BOLD);
        content.addView(time);

        if (hasText(checkin.address)) {
            TextView address = text(checkin.address, 14, color("#1F6F54"), Typeface.BOLD);
            address.setPadding(0, dp(6), 0, 0);
            content.addView(address);
        }

        String note = checkin.note == null || checkin.note.isEmpty() ? "没有文字备注" : checkin.note;
        TextView noteView = text(note, 15, color("#25302B"), Typeface.NORMAL);
        noteView.setPadding(0, dp(6), 0, checkin.photos.isEmpty() ? 0 : dp(8));
        content.addView(noteView);

        if (!checkin.photos.isEmpty()) {
            content.addView(photoGrid(checkin.photos));
        }

        return item;
    }

    private View photoGrid(List<String> paths) {
        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        LinearLayout row = null;
        for (int i = 0; i < paths.size(); i++) {
            if (i % 3 == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams rowParams = matchWrap();
                rowParams.setMargins(0, 0, 0, dp(8));
                grid.addView(row, rowParams);
            }
            if (row != null) {
                row.addView(photoThumb(paths.get(i), i % 3));
            }
        }
        int remainder = paths.size() % 3;
        if (row != null && remainder != 0) {
            for (int column = remainder; column < 3; column++) {
                View spacer = new View(this);
                spacer.setLayoutParams(photoGridCellParams(column));
                row.addView(spacer);
            }
        }
        return grid;
    }

    private View photoThumb(final String path, int column) {
        FrameLayout frame = new FrameLayout(this);
        frame.setLayoutParams(photoGridCellParams(column));
        frame.setBackground(cardBg());
        frame.setPadding(dp(2), dp(2), dp(2), dp(2));

        ImageView image = new ImageView(this);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        image.setBackgroundColor(color("#ECE7DD"));
        image.setImageURI(Uri.fromFile(new File(path)));
        image.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                previewPhoto(path);
            }
        });
        frame.addView(image, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));
        return frame;
    }

    private void previewPhoto(String path) {
        ImageView image = new ImageView(this);
        image.setAdjustViewBounds(true);
        image.setImageURI(Uri.fromFile(new File(path)));
        FrameLayout frame = new FrameLayout(this);
        frame.setPadding(dp(10), dp(10), dp(10), dp(10));
        frame.addView(image, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
        ));
        new AlertDialog.Builder(this)
                .setView(frame)
                .setPositiveButton("关闭", null)
                .show();
    }

    private boolean hasForegroundLocationPermission() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasFineLocationPermission() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasBackgroundLocationPermission() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
                || checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasNotificationPermission() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
    }

    private TextView text(String value, int sp, int color, int style) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        view.setTypeface(Typeface.DEFAULT, style);
        view.setIncludeFontPadding(true);
        return view;
    }

    private Button button(String label, boolean primary) {
        Button button = new Button(this);
        button.setAllCaps(false);
        button.setText(label);
        button.setTextSize(15);
        button.setTextColor(primary ? Color.WHITE : color("#25302B"));
        button.setBackground(primary ? pill(color("#1F6F54"), 10f) : outlineButtonBg());
        button.setMinHeight(dp(48));
        return button;
    }

    private Button compactButton(String label, boolean primary) {
        Button button = button(label, primary);
        button.setTextSize(14);
        button.setMinHeight(dp(38));
        button.setMinimumHeight(dp(38));
        button.setPadding(dp(12), 0, dp(12), 0);
        return button;
    }

    private EditText input(String hint, boolean password) {
        EditText input = new EditText(this);
        input.setHint(hint);
        input.setTextSize(14);
        input.setSingleLine(true);
        input.setPadding(dp(12), 0, dp(12), 0);
        input.setMinHeight(dp(46));
        input.setTextColor(color("#25302B"));
        input.setHintTextColor(color("#9A8F80"));
        input.setBackground(outlineButtonBg());
        input.setInputType(password
                ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD
                : InputType.TYPE_CLASS_TEXT);
        return input;
    }

    private LinearLayout.LayoutParams inputParams(int top) {
        LinearLayout.LayoutParams params = matchWrap();
        params.setMargins(0, top, 0, 0);
        return params;
    }

    private String inputValue(EditText input) {
        return input == null ? "" : input.getText().toString().trim();
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
    }

    private LinearLayout.LayoutParams weightedButtonParams(float weight, int left, int right) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight);
        params.setMargins(left, 0, right, 0);
        return params;
    }

    private GradientDrawable cardBg() {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color("#FFFDF8"));
        drawable.setCornerRadius(dp(8));
        drawable.setStroke(dp(1), color("#E2DDD2"));
        return drawable;
    }

    private GradientDrawable tabBarBg() {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color("#FFFDF8"));
        drawable.setStroke(dp(1), color("#EBE3D5"));
        return drawable;
    }

    private GradientDrawable outlineButtonBg() {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color("#FFFDF8"));
        drawable.setCornerRadius(dp(10));
        drawable.setStroke(dp(1), color("#D8D0C0"));
        return drawable;
    }

    private GradientDrawable circleButtonBg() {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.OVAL);
        drawable.setColor(color("#FFFDF8"));
        drawable.setStroke(dp(1), color("#D8D0C0"));
        return drawable;
    }

    private GradientDrawable pill(int color, float radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        return drawable;
    }

    private int color(String hex) {
        return Color.parseColor(hex);
    }

    private String weekLabel(String date) {
        try {
            Date parsed = new SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).parse(date);
            if (parsed == null) {
                return "";
            }
            Calendar calendar = Calendar.getInstance(Locale.CHINA);
            calendar.setTime(parsed);
            String[] weeks = {"周日", "周一", "周二", "周三", "周四", "周五", "周六"};
            return weeks[calendar.get(Calendar.DAY_OF_WEEK) - 1];
        } catch (ParseException ignored) {
            return "";
        }
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private void toastLong(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    private static final class HistoryTrip {
        final String key;
        final String title;
        final String tripId;
        final long startTime;
        final long endTime;
        final boolean unassigned;
        double distanceMeters;
        final List<TrackStore.PointRecord> points = new ArrayList<>();
        final List<TrackStore.CheckinRecord> checkins = new ArrayList<>();

        HistoryTrip(String key, String title, String tripId, long startTime, long endTime, boolean unassigned) {
            this.key = key;
            this.title = title;
            this.tripId = tripId;
            this.startTime = startTime;
            this.endTime = endTime;
            this.unassigned = unassigned;
        }
    }

    private static final class CheckinTripGroup {
        final String title;
        final String subtitle;
        final String tripId;
        final long startTime;
        final long endTime;
        final boolean unassigned;
        final List<TrackStore.CheckinRecord> checkins = new ArrayList<>();

        CheckinTripGroup(String title, String subtitle, String tripId, long startTime, long endTime, boolean unassigned) {
            this.title = title;
            this.subtitle = subtitle;
            this.tripId = tripId;
            this.startTime = startTime;
            this.endTime = endTime;
            this.unassigned = unassigned;
        }
    }

    private interface SingleLocationCallback {
        void onLocation(AMapLocation location);
    }

    private interface CloudTask {
        String run() throws Exception;
    }
}
