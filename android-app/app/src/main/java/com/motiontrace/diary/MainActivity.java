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
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
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
    private LinearLayout commonAddressList;
    private EditText cloudEmailInput;
    private EditText cloudPasswordInput;
    private EditText cloudNewPasswordInput;
    private Button cloudRegisterButton;
    private Button cloudLoginButton;
    private Button cloudChangePasswordButton;
    private Button cloudUploadButton;
    private Button cloudDownloadButton;
    private Button cloudLogoutButton;
    private boolean cloudTaskRunning;
    private String cloudNotice = "";
    private long cloudNoticeUntilMs;
    private Button recordButton;
    private Button backgroundButton;
    private LinearLayout checkinList;
    private LinearLayout historyList;
    private ScrollView todayPage;
    private ScrollView historyPage;
    private ScrollView settingsPage;
    private final TextView[] tabLabels = new TextView[3];
    private final View[] tabDots = new View[3];
    private int selectedTab = TAB_TODAY;
    private TextureMapView mapView;
    private AMap aMap;
    private TextureMapView historyMapView;
    private AMap historyAMap;
    private String expandedHistoryDate;
    private long lastAutoCenterAt;
    private boolean autoLocationPermissionAsked;

    private int pendingLocationAction = ACTION_NONE;
    private boolean startAfterNotificationPermission;
    private AlertDialog activeCheckinDialog;
    private TextView activePhotoCountText;
    private TextView activeAddressText;
    private ImageButton activeAddressButton;
    private Spinner activeAddressSpinner;
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
        if (historyMapView != null) {
            historyMapView.onDestroy();
        }
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

        Button checkinButton = compactButton("沿途打卡", false);
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

        TextView eyebrow = text("按天归档", 13, color("#728071"), Typeface.NORMAL);
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

        historyMapView = new TextureMapView(this);
        historyMapView.onCreate(null);
        historyAMap = historyMapView.getMap();
        setupHistoryMap();
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

        TextView title = text("云同步", 18, color("#25302B"), Typeface.BOLD);
        card.addView(title);

        cloudStatusText = text("", 13, color("#6F756D"), Typeface.NORMAL);
        cloudStatusText.setPadding(0, dp(8), 0, dp(10));
        card.addView(cloudStatusText);

        cloudEmailInput = input("邮箱", false);
        cloudPasswordInput = input("密码，登录或改密时填写", true);
        cloudNewPasswordInput = input("新密码，改密码时填写", true);
        card.addView(cloudEmailInput, inputParams(0));
        card.addView(cloudPasswordInput, inputParams(dp(8)));
        card.addView(cloudNewPasswordInput, inputParams(dp(8)));

        LinearLayout authRow = new LinearLayout(this);
        authRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams authParams = matchWrap();
        authParams.setMargins(0, dp(10), 0, 0);
        card.addView(authRow, authParams);

        cloudRegisterButton = button("注册", false);
        cloudRegisterButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                registerCloudAccount();
            }
        });
        authRow.addView(cloudRegisterButton, weightedButtonParams(1f, 0, dp(5)));

        cloudLoginButton = button("登录", true);
        cloudLoginButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                loginCloudAccount();
            }
        });
        authRow.addView(cloudLoginButton, weightedButtonParams(1f, dp(5), 0));

        cloudChangePasswordButton = button("修改密码", false);
        cloudChangePasswordButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                changeCloudPassword();
            }
        });
        LinearLayout.LayoutParams passwordParams = matchWrap();
        passwordParams.setMargins(0, dp(10), 0, 0);
        card.addView(cloudChangePasswordButton, passwordParams);

        LinearLayout syncRow = new LinearLayout(this);
        syncRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams syncParams = matchWrap();
        syncParams.setMargins(0, dp(10), 0, 0);
        card.addView(syncRow, syncParams);

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
        card.addView(cloudLogoutButton, logoutParams);

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
        selectedTab = index;
        todayPage.setVisibility(index == TAB_TODAY ? View.VISIBLE : View.GONE);
        historyPage.setVisibility(index == TAB_HISTORY ? View.VISIBLE : View.GONE);
        settingsPage.setVisibility(index == TAB_SETTINGS ? View.VISIBLE : View.GONE);
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

        distanceText = addStat(row1, "距离", 0, dp(5));
        durationText = addStat(row1, "时长", dp(5), 0);

        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams row2Params = matchWrap();
        row2Params.setMargins(0, dp(10), 0, 0);
        grid.addView(row2, row2Params);

        tripsText = addStat(row2, "今日行程", 0, dp(5));
        checkinsText = addStat(row2, "打卡", dp(5), 0);

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

        ArrayList<LatLng> route = new ArrayList<>();
        LatLngBounds.Builder boundsBuilder = LatLngBounds.builder();
        boolean hasBounds = false;

        for (TrackStore.PointRecord point : day.points) {
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
            LatLng latLng = new LatLng(checkin.latitude, checkin.longitude);
            boundsBuilder.include(latLng);
            hasBounds = true;
            aMap.addMarker(new MarkerOptions()
                    .position(latLng)
                    .title(checkinTitle(checkin))
                    .snippet(TrackStore.formatClock(checkin.timestamp))
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE)));
        }

        if (hasBounds) {
            aMap.animateCamera(CameraUpdateFactory.newLatLngBounds(boundsBuilder.build(), dp(42)));
        }
    }

    private void toggleRecording() {
        if (TrackRecordingService.isRecording(this)) {
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

        Intent intent = new Intent(this, TrackRecordingService.class);
        intent.setAction(TrackRecordingService.ACTION_START);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
        refreshUi();
    }

    private void renderHistory() {
        if (historyList == null) {
            return;
        }
        historyList.removeAllViews();
        List<TrackStore.DayRecord> days = TrackStore.listDays(this);
        if (historyCountText != null) {
            historyCountText.setText(days.size() + " 天");
        }
        if (days.isEmpty()) {
            TextView empty = text(
                    "还没有保存过轨迹。回到今日页打开记录开关，第一条路线就会出现在这里。",
                    14,
                    color("#6F756D"),
                    Typeface.NORMAL
            );
            empty.setPadding(0, dp(40), 0, dp(8));
            historyList.addView(empty);
            return;
        }

        for (TrackStore.DayRecord day : days) {
            historyList.addView(historyCard(day));
            if (day.date.equals(expandedHistoryDate)) {
                historyList.addView(historyMapPanel(day));
            }
        }
    }

    private View historyCard(TrackStore.DayRecord day) {
        TrackStore.Stats stats = TrackStore.buildStats(day, false);
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(12), dp(12), dp(12), dp(12));
        card.setBackground(cardBg());
        LinearLayout.LayoutParams cardParams = matchWrap();
        cardParams.setMargins(0, 0, 0, dp(9));
        card.setLayoutParams(cardParams);
        card.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                toggleHistoryMap(day.date);
            }
        });

        LinearLayout main = new LinearLayout(this);
        main.setOrientation(LinearLayout.HORIZONTAL);
        main.setGravity(Gravity.CENTER_VERTICAL);
        card.addView(main, matchWrap());

        LinearLayout textBox = new LinearLayout(this);
        textBox.setOrientation(LinearLayout.VERTICAL);
        main.addView(textBox, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView date = text(day.date, 17, color("#1E2925"), Typeface.BOLD);
        textBox.addView(date);

        TextView week = text(
                weekLabel(day.date) + " · " + day.points.size() + " 个轨迹点",
                13,
                color("#778178"),
                Typeface.NORMAL
        );
        week.setPadding(0, dp(4), 0, 0);
        textBox.addView(week);

        TextView arrow = text(day.date.equals(expandedHistoryDate) ? "收起" : "›", 15, color("#9A8F80"), Typeface.NORMAL);
        arrow.setGravity(Gravity.CENTER);
        main.addView(arrow, new LinearLayout.LayoutParams(dp(24), ViewGroup.LayoutParams.WRAP_CONTENT));

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

        summary.addView(summaryItem(stats.distance, "距离"), weightedButtonParams(1f, 0, dp(4)));
        summary.addView(summaryItem(stats.duration, "时长"), weightedButtonParams(1f, dp(4), dp(4)));
        summary.addView(summaryItem(stats.checkins, "打卡"), weightedButtonParams(1f, dp(4), 0));
        return card;
    }

    private View historyMapPanel(final TrackStore.DayRecord day) {
        FrameLayout panel = new FrameLayout(this);
        panel.setBackgroundColor(Color.BLACK);
        LinearLayout.LayoutParams params = matchWrap();
        params.height = dp(300);
        params.setMargins(0, 0, 0, dp(12));
        panel.setLayoutParams(params);

        ViewGroup parent = (ViewGroup) historyMapView.getParent();
        if (parent != null) {
            parent.removeView(historyMapView);
        }
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
                renderHistoryMap(day);
            }
        });
        return panel;
    }

    private void toggleHistoryMap(String date) {
        expandedHistoryDate = date.equals(expandedHistoryDate) ? null : date;
        renderHistory();
    }

    private void renderHistoryMap(TrackStore.DayRecord day) {
        if (historyAMap == null || day == null) {
            return;
        }
        historyAMap.clear();

        ArrayList<LatLng> route = new ArrayList<>();
        LatLngBounds.Builder boundsBuilder = LatLngBounds.builder();
        boolean hasBounds = false;

        for (TrackStore.PointRecord point : day.points) {
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

        for (TrackStore.CheckinRecord checkin : day.checkins) {
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
            HorizontalScrollView scroll = new HorizontalScrollView(this);
            scroll.setHorizontalScrollBarEnabled(false);
            LinearLayout photos = new LinearLayout(this);
            photos.setOrientation(LinearLayout.HORIZONTAL);
            scroll.addView(photos);
            for (String path : checkin.photos) {
                photos.addView(photoThumb(path));
            }
            box.addView(scroll);
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
                    aMap.animateCamera(CameraUpdateFactory.newLatLngZoom(latLng, 17f));
                }
            }
        }, showProgress);
    }

    private void checkinFlow() {
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
        final String email = inputValue(cloudEmailInput);
        final String password = inputValue(cloudPasswordInput);
        if (!ensureCloudConfigured() || !validateCloudCredentials(email, password, true)) {
            return;
        }
        runCloudTask("正在注册", new CloudTask() {
            @Override
            public String run() throws Exception {
                new CloudSyncClient(MainActivity.this).register(email, password);
                return "注册并登录成功";
            }
        });
    }

    private void loginCloudAccount() {
        final String email = inputValue(cloudEmailInput);
        final String password = inputValue(cloudPasswordInput);
        if (!ensureCloudConfigured() || !validateCloudCredentials(email, password, false)) {
            return;
        }
        runCloudTask("正在登录", new CloudTask() {
            @Override
            public String run() throws Exception {
                new CloudSyncClient(MainActivity.this).login(email, password);
                return "登录成功";
            }
        });
    }

    private void changeCloudPassword() {
        final String currentPassword = inputValue(cloudPasswordInput);
        final String newPassword = inputValue(cloudNewPasswordInput);
        if (!ensureCloudConfigured()) {
            return;
        }
        if (currentPassword.isEmpty()) {
            showCloudNotice("请先输入当前密码", 6000L);
            toast("请先输入当前密码");
            return;
        }
        if (newPassword.length() < 8) {
            showCloudNotice("新密码至少 8 位", 6000L);
            toast("新密码至少 8 位");
            return;
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
                            if ("注册并登录成功".equals(message)
                                    || "登录成功".equals(message)
                                    || "密码已修改".equals(message)) {
                                clearCloudPasswordInputs();
                            }
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

    private boolean validateCloudCredentials(String email, String password, boolean requireNewPassword) {
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
        if (password.isEmpty()) {
            showCloudNotice("请先输入密码", 6000L);
            toast("请先输入密码");
            return false;
        }
        if (requireNewPassword && password.length() < 8) {
            showCloudNotice("密码至少 8 位", 6000L);
            toast("密码至少 8 位");
            return false;
        }
        return true;
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

    private void clearCloudPasswordInputs() {
        if (cloudPasswordInput != null) {
            cloudPasswordInput.setText("");
        }
        if (cloudNewPasswordInput != null) {
            cloudNewPasswordInput.setText("");
        }
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
        setCloudButtonEnabled(cloudUploadButton, configured && loggedIn);
        setCloudButtonEnabled(cloudDownloadButton, configured && loggedIn);
        setCloudButtonEnabled(cloudLogoutButton, configured && loggedIn);
        if (cloudEmailInput != null && cloudEmailInput.getText().length() == 0) {
            cloudEmailInput.setText(client.getEmail());
        }
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
            cloudStatusText.setText("已登录：" + client.getEmail() + "。当前同步不上传照片原图。");
        } else {
            cloudStatusText.setText("未登录。可选云同步会上传轨迹、行程和打卡文字，照片仍保留本机。");
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

    private View commonAddressRow(TrackStore.AddressStat stat) {
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

        TextView badge = text("常用", 12, color("#1F6F54"), Typeface.BOLD);
        badge.setGravity(Gravity.CENTER);
        badge.setPadding(dp(9), dp(5), dp(9), dp(5));
        badge.setBackground(pill(color("#E8F2EA"), 999f));
        row.addView(badge);
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

        activeAddressText = text("未获取地名", 13, color("#6F756D"), Typeface.NORMAL);
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
        activeAddressButton.setContentDescription("获取地名");
        activeAddressButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                resolvePendingCheckinAddress();
            }
        });
        LinearLayout.LayoutParams mapButtonParams = new LinearLayout.LayoutParams(dp(44), dp(44));
        mapButtonParams.setMargins(dp(8), 0, 0, 0);
        addressRow.addView(activeAddressButton, mapButtonParams);

        activeAddressSpinner = new Spinner(this);
        activeAddressSpinner.setVisibility(View.GONE);
        activeAddressSpinner.setBackground(outlineButtonBg());
        LinearLayout.LayoutParams spinnerParams = matchWrap();
        spinnerParams.setMargins(0, 0, 0, dp(10));
        box.addView(activeAddressSpinner, spinnerParams);

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
                activeAddressSpinner = null;
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
            updateAddressDropdown();
            if (activeAddressText != null) {
                activeAddressText.setText("未获取地名");
            }
            toast("没有获取到地名");
            return;
        }
        pendingAddressOptions = addresses;
        pendingCheckinAddress = addresses.get(0);
        if (activeAddressText != null) {
            activeAddressText.setText(pendingCheckinAddress);
        }
        updateAddressDropdown();
        toast(addresses.size() > 1 ? "已获取地名，可下拉选择" : "已获取地名");
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
                }
            }
        }
        addAddressOption(options, address.getFormatAddress());
        return options;
    }

    private void addAddressOption(ArrayList<String> options, String value) {
        String cleaned = cleanText(value);
        if (!cleaned.isEmpty() && !options.contains(cleaned)) {
            options.add(cleaned);
        }
    }

    private void updateAddressDropdown() {
        if (activeAddressSpinner == null) {
            return;
        }
        if (pendingAddressOptions.isEmpty()) {
            activeAddressSpinner.setVisibility(View.GONE);
            activeAddressSpinner.setAdapter(null);
            return;
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this,
                android.R.layout.simple_spinner_item,
                pendingAddressOptions
        );
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        activeAddressSpinner.setAdapter(adapter);
        activeAddressSpinner.setVisibility(pendingAddressOptions.size() > 1 ? View.VISIBLE : View.GONE);
        activeAddressSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position >= 0 && position < pendingAddressOptions.size()) {
                    pendingCheckinAddress = pendingAddressOptions.get(position);
                    if (activeAddressText != null) {
                        activeAddressText.setText(pendingCheckinAddress);
                    }
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
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

        renderCheckins(day.checkins);
        if (selectedTab == TAB_HISTORY) {
            renderHistory();
        }
        if (selectedTab == TAB_SETTINGS) {
            refreshCloudUi();
            refreshCommonAddressUi();
        }
    }

    private void renderCheckins(List<TrackStore.CheckinRecord> checkins) {
        checkinList.removeAllViews();
        if (checkins.isEmpty()) {
            TextView empty = text("今天还没有打卡", 14, color("#6F756D"), Typeface.NORMAL);
            empty.setPadding(0, dp(8), 0, dp(8));
            checkinList.addView(empty);
            return;
        }

        for (TrackStore.CheckinRecord checkin : checkins) {
            checkinList.addView(checkinCard(checkin));
        }
    }

    private View checkinCard(TrackStore.CheckinRecord checkin) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        card.setBackground(cardBg());
        LinearLayout.LayoutParams cardParams = matchWrap();
        cardParams.setMargins(0, 0, 0, dp(10));
        card.setLayoutParams(cardParams);

        TextView time = text(TrackStore.formatClock(checkin.timestamp), 13, color("#6F756D"), Typeface.BOLD);
        card.addView(time);

        if (hasText(checkin.address)) {
            TextView address = text(checkin.address, 14, color("#1F6F54"), Typeface.BOLD);
            address.setPadding(0, dp(6), 0, 0);
            card.addView(address);
        }

        String note = checkin.note == null || checkin.note.isEmpty() ? "没有文字备注" : checkin.note;
        TextView noteView = text(note, 15, color("#25302B"), Typeface.NORMAL);
        noteView.setPadding(0, dp(6), 0, checkin.photos.isEmpty() ? 0 : dp(8));
        card.addView(noteView);

        if (!checkin.photos.isEmpty()) {
            HorizontalScrollView scroll = new HorizontalScrollView(this);
            scroll.setHorizontalScrollBarEnabled(false);
            LinearLayout photos = new LinearLayout(this);
            photos.setOrientation(LinearLayout.HORIZONTAL);
            scroll.addView(photos);
            for (String path : checkin.photos) {
                photos.addView(photoThumb(path));
            }
            card.addView(scroll);
        }

        return card;
    }

    private View photoThumb(final String path) {
        ImageView image = new ImageView(this);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(72), dp(72));
        params.setMargins(0, 0, dp(8), 0);
        image.setLayoutParams(params);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        image.setBackgroundColor(color("#ECE7DD"));
        image.setImageURI(Uri.fromFile(new File(path)));
        image.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                previewPhoto(path);
            }
        });
        return image;
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

    private interface SingleLocationCallback {
        void onLocation(AMapLocation location);
    }

    private interface CloudTask {
        String run() throws Exception;
    }
}
