package com.ragnar.vlessstudio;

import android.app.Activity;
import android.content.Intent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;
import android.content.ClipboardManager;
import android.content.ClipData;
import android.graphics.Color;
import android.graphics.Insets;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

public class MainActivity extends Activity {
    private static final int VPN_REQUEST_CODE = 1001;
    private FrameLayout root;
    private WebView webView;
    private String pendingShareLink = "";
    private boolean receiverRegistered = false;

    private final BroadcastReceiver vpnStateReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (VlessVpnService.ACTION_STATE.equals(intent.getAction())) {
                boolean connected = intent.getBooleanExtra("connected", false);
                String message = intent.getStringExtra("message");
                updateVpnUi(connected, message == null ? "" : message);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.rgb(8, 11, 22));
        webView = new WebView(this);
        root.addView(webView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));
        setContentView(root);

        configureSystemBars();
        configureWebView();
        registerVpnReceiver();
    }

    private void configureSystemBars() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Window window = getWindow();
            window.setDecorFitsSystemWindows(false);
            window.setStatusBarColor(Color.TRANSPARENT);
            window.setNavigationBarColor(Color.TRANSPARENT);
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                window.setStatusBarContrastEnforced(false);
                window.setNavigationBarContrastEnforced(false);
            }

            // Apply the real system-bar insets to the WebView's bounds, not its
            // internal padding. This prevents HTML header/footer from drawing
            // underneath the status and navigation bars on Android 11+.
            root.setOnApplyWindowInsetsListener((view, insets) -> {
                Insets bars = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout()
                );
                FrameLayout.LayoutParams lp =
                        (FrameLayout.LayoutParams) webView.getLayoutParams();
                lp.setMargins(bars.left, bars.top, bars.right, bars.bottom);
                webView.setLayoutParams(lp);
                return WindowInsets.CONSUMED;
            });
            root.requestApplyInsets();
        } else {
            // Older Android versions use the platform's normal decor fitting.
            getWindow().setStatusBarColor(Color.rgb(8, 11, 22));
            getWindow().setNavigationBarColor(Color.rgb(8, 11, 22));
        }
    }

    private void configureWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);

        webView.addJavascriptInterface(new AndroidVpnBridge(), "AndroidVpnBridge");
        webView.setWebViewClient(new WebViewClient());
        webView.setWebChromeClient(new WebChromeClient());
        webView.loadUrl("file:///android_asset/index.html");
    }

    private final class AndroidVpnBridge {
        @JavascriptInterface
        public String getClipboardText() {
            try {
                ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                if (clipboard != null && clipboard.hasPrimaryClip()) {
                    ClipData clip = clipboard.getPrimaryClip();
                    if (clip != null && clip.getItemCount() > 0) {
                        CharSequence value = clip.getItemAt(0).coerceToText(MainActivity.this);
                        return value == null ? "" : value.toString();
                    }
                }
            } catch (Exception e) {
                android.util.Log.e("VLESSStudio", "Clipboard read failed", e);
            }
            return "";
        }

        @JavascriptInterface
        public void checkTcpAsync(String host, int port, String serverId) {
            new Thread(() -> {
                boolean ok = false;
                java.net.Socket socket = new java.net.Socket();
                try {
                    if (host != null && !host.trim().isEmpty() && port > 0 && port <= 65535) {
                        socket.connect(new java.net.InetSocketAddress(host.trim(), port), 2500);
                        ok = true;
                    }
                } catch (Exception ignored) {
                } finally {
                    try { socket.close(); } catch (Exception ignored) {}
                }
                final boolean result = ok;
                runOnUiThread(() -> {
                    if (webView != null) {
                        String safeId = org.json.JSONObject.quote(serverId == null ? "" : serverId);
                        webView.evaluateJavascript("onTcpCheckResult(" + safeId + "," + result + ")", null);
                    }
                });
            }, "vless-tcp-check").start();
        }

        @JavascriptInterface
        public void showAppPicker() {
            runOnUiThread(() -> MainActivity.this.showVpnAppPicker());
        }

        @JavascriptInterface
        public void requestVpnPermission(String shareLink) {
            runOnUiThread(() -> {
                pendingShareLink = shareLink == null ? "" : shareLink;
                MainActivity.this.requestVpnPermission();
            });
        }

        @JavascriptInterface
        public void stopVpn() {
            runOnUiThread(() -> {
                Intent serviceIntent = new Intent(MainActivity.this, VlessVpnService.class);
                serviceIntent.setAction(VlessVpnService.ACTION_STOP);
                startService(serviceIntent);
                updateVpnUi(false, "VPN отключён");
            });
        }
    }

    private void showVpnAppPicker() {
        try {
            android.content.pm.PackageManager pm = getPackageManager();
            java.util.List<android.content.pm.ApplicationInfo> installed =
                    pm.getInstalledApplications(0);
            java.util.List<android.content.pm.ApplicationInfo> apps = new java.util.ArrayList<>();
            for (android.content.pm.ApplicationInfo app : installed) {
                if (!app.packageName.equals(getPackageName())) apps.add(app);
            }
            java.util.Collections.sort(apps, (a, b) -> {
                boolean aSystem = (a.flags & android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0;
                boolean bSystem = (b.flags & android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0;
                if (aSystem != bSystem) return aSystem ? 1 : -1;
                return String.CASE_INSENSITIVE_ORDER.compare(String.valueOf(a.loadLabel(pm)), String.valueOf(b.loadLabel(pm)));
            });
            java.util.List<String> labels = new java.util.ArrayList<>();
            java.util.List<String> packages = new java.util.ArrayList<>();
            for (android.content.pm.ApplicationInfo app : apps) {
                String label = String.valueOf(app.loadLabel(pm));
                labels.add(label + "\n" + app.packageName);
                packages.add(app.packageName);
            }
            android.content.SharedPreferences prefs = getSharedPreferences("vpn_settings", MODE_PRIVATE);
            java.util.Set<String> saved = new java.util.HashSet<>(
                    prefs.getStringSet("allowed_apps", java.util.Collections.emptySet()));
            boolean[] checked = new boolean[packages.size()];
            for (int i = 0; i < packages.size(); i++) checked[i] = saved.contains(packages.get(i));
            String[] labelArray = labels.toArray(new String[0]);
            android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(this)
                    .setTitle("Приложения через VPN (" + apps.size() + ")")
                    .setMultiChoiceItems(labelArray, checked, (d, which, isChecked) -> checked[which] = isChecked)
                    .setNegativeButton("Отмена", null)
                    .setNeutralButton("Все приложения", (d, which) -> {
                        prefs.edit().remove("allowed_apps").apply();
                        notifyAppSelectionSaved(0);
                    })
                    .setPositiveButton("Сохранить", (d, which) -> {
                        java.util.Set<String> selected = new java.util.HashSet<>();
                        for (int i = 0; i < checked.length; i++) if (checked[i]) selected.add(packages.get(i));
                        prefs.edit().putStringSet("allowed_apps", selected).apply();
                        notifyAppSelectionSaved(selected.size());
                    }).create();
            dialog.show();
            if (apps.isEmpty()) {
                android.widget.Toast.makeText(this,
                        "Android не вернул список приложений. Проверь разрешение QUERY_ALL_PACKAGES.",
                        android.widget.Toast.LENGTH_LONG).show();
            }
        } catch (Exception e) {
            android.util.Log.e("VLESSStudio", "Cannot show app picker", e);
            android.widget.Toast.makeText(this, "Не удалось открыть список приложений: " + e.getMessage(),
                    android.widget.Toast.LENGTH_LONG).show();
        }
    }

    private void notifyAppSelectionSaved(int count) {
        String script = "onAppSelectionSaved(" + count + ")";
        if (webView != null) webView.post(() -> webView.evaluateJavascript(script, null));
        android.widget.Toast.makeText(this,
                count == 0 ? "Через VPN: все приложения" : "Через VPN выбрано приложений: " + count + ". Переподключи VPN для применения.",
                android.widget.Toast.LENGTH_LONG).show();
    }

    private void requestVpnPermission() {
        Intent permissionIntent = VpnService.prepare(this);
        if (permissionIntent != null) {
            startActivityForResult(permissionIntent, VPN_REQUEST_CODE);
        } else {
            startVpnService();
        }
    }

    private void startVpnService() {
        Intent serviceIntent = new Intent(this, VlessVpnService.class);
        serviceIntent.setAction(VlessVpnService.ACTION_START);
        serviceIntent.putExtra(VlessVpnService.EXTRA_SHARE_LINK, pendingShareLink);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
        updateVpnUi(false, "Подключаемся к серверу…");
    }

    private void updateVpnUi(boolean started, String message) {
        if (webView != null) {
            String safeMessage = org.json.JSONObject.quote(message == null ? "" : message);
            String script = "setVpnUiState(" + started + "," + safeMessage + ")";
            webView.post(() -> webView.evaluateJavascript(script, null));
        }
    }

    private void registerVpnReceiver() {
        if (receiverRegistered) return;
        IntentFilter filter = new IntentFilter(VlessVpnService.ACTION_STATE);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(vpnStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(vpnStateReceiver, filter);
        receiverRegistered = true;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == VPN_REQUEST_CODE) {
            if (resultCode == RESULT_OK) {
                startVpnService();
            } else {
                updateVpnUi(false, "Разрешение VPN не выдано");
            }
        }
    }

    @Override
    public void onBackPressed() {
        if (webView != null) {
            webView.evaluateJavascript("handleAndroidBack()", value -> {
                if ("false".equals(value) || value == null) {
                    MainActivity.super.onBackPressed();
                }
            });
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        if (receiverRegistered) {
            try { unregisterReceiver(vpnStateReceiver); } catch (Exception ignored) {}
            receiverRegistered = false;
        }
        if (webView != null) {
            webView.removeJavascriptInterface("AndroidVpnBridge");
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}
