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

import libXray.LibXray;

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
            } else if (ServerCheckService.ACTION_RESULT.equals(intent.getAction())) {
                String id = intent.getStringExtra("id");
                boolean ok = intent.getBooleanExtra("ok", false);
                long delay = intent.getLongExtra("delay", -1);
                String error = intent.getStringExtra("error");
                if (webView != null) {
                    webView.evaluateJavascript("onVlessCheckResult("
                            + org.json.JSONObject.quote(id == null ? "" : id) + ","
                            + ok + "," + delay + ","
                            + org.json.JSONObject.quote(error == null ? "" : error) + ")", null);
                }
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
        public void showDiagnostics() {
            runOnUiThread(() -> {
                String report = VlessVpnService.getDiagnostics();
                new android.app.AlertDialog.Builder(MainActivity.this)
                        .setTitle("Диагностика VPN")
                        .setMessage(report)
                        .setPositiveButton("Копировать", (dialog, which) -> {
                            ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                            if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText("VPN diagnostics", report));
                            android.widget.Toast.makeText(MainActivity.this, "Диагностика скопирована", android.widget.Toast.LENGTH_SHORT).show();
                        })
                        .setNegativeButton("Закрыть", null)
                        .show();
            });
        }

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
        public String fetchSubscription(String url) {
            java.net.HttpURLConnection connection = null;
            try {
                if (url == null || !(url.startsWith("https://") || url.startsWith("http://"))) {
                    throw new IllegalArgumentException("Разрешены только HTTP/HTTPS ссылки");
                }
                java.net.URL parsed = new java.net.URL(url);
                connection = (java.net.HttpURLConnection) parsed.openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(12000);
                connection.setReadTimeout(20000);
                connection.setInstanceFollowRedirects(true);
                connection.setRequestProperty("User-Agent", "VLESS-Studio/1.0");
                connection.setRequestProperty("Accept", "text/plain, application/octet-stream, */*");
                int status = connection.getResponseCode();
                java.io.InputStream input = status >= 200 && status < 400
                        ? connection.getInputStream() : connection.getErrorStream();
                if (input == null) throw new java.io.IOException("HTTP " + status);
                java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int total = 0, count;
                while ((count = input.read(buffer)) != -1) {
                    total += count;
                    if (total > 2 * 1024 * 1024) throw new java.io.IOException("Подписка превышает 2 МБ");
                    output.write(buffer, 0, count);
                }
                input.close();
                if (status < 200 || status >= 300) throw new java.io.IOException("HTTP " + status);
                org.json.JSONObject result = new org.json.JSONObject();
                result.put("success", true);
                result.put("body", new String(output.toByteArray(), java.nio.charset.StandardCharsets.UTF_8));
                return result.toString();
            } catch (Exception e) {
                try {
                    return new org.json.JSONObject().put("success", false)
                            .put("error", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()).toString();
                } catch (org.json.JSONException ignored) { return "{}"; }
            } finally {
                if (connection != null) connection.disconnect();
            }
        }

        @JavascriptInterface
        public void checkVlessBatchAsync(String itemsJson) {
            Intent intent = new Intent(MainActivity.this, ServerCheckService.class);
            intent.setAction(ServerCheckService.ACTION_CHECK);
            intent.putExtra(ServerCheckService.EXTRA_ITEMS, itemsJson == null ? "[]" : itemsJson);
            startService(intent);
        }

        @JavascriptInterface
        public void checkTcpAsync(String host, int port, String serverId) {
            new Thread(() -> {
                boolean ok = false;
                long latencyMs = -1;
                java.net.Socket socket = new java.net.Socket();
                try {
                    if (host != null && !host.trim().isEmpty() && port > 0 && port <= 65535) {
                        long startedAt = android.os.SystemClock.elapsedRealtime();
                        socket.connect(new java.net.InetSocketAddress(host.trim(), port), 3500);
                        latencyMs = android.os.SystemClock.elapsedRealtime() - startedAt;
                        ok = true;
                    }
                } catch (Exception ignored) {
                } finally {
                    try { socket.close(); } catch (Exception ignored) {}
                }
                final boolean result = ok;
                final long latency = latencyMs;
                runOnUiThread(() -> {
                    if (webView != null) {
                        String safeId = org.json.JSONObject.quote(serverId == null ? "" : serverId);
                        webView.evaluateJavascript("onTcpCheckResult(" + safeId + "," + result + "," + latency + ")", null);
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

            android.content.SharedPreferences prefs = getSharedPreferences("vpn_settings", MODE_PRIVATE);
            java.util.Set<String> saved = new java.util.HashSet<>(
                    prefs.getStringSet("allowed_apps", java.util.Collections.emptySet()));
            boolean[] checked = new boolean[apps.size()];
            for (int i = 0; i < apps.size(); i++) checked[i] = saved.contains(apps.get(i).packageName);

            android.widget.ListView list = new android.widget.ListView(this);
            list.setChoiceMode(android.widget.ListView.CHOICE_MODE_NONE);
            list.setDividerHeight(1);
            list.setAdapter(new android.widget.BaseAdapter() {
                @Override public int getCount() { return apps.size(); }
                @Override public Object getItem(int position) { return apps.get(position); }
                @Override public long getItemId(int position) { return position; }
                @Override public android.view.View getView(int position, android.view.View convertView, android.view.ViewGroup parent) {
                    android.widget.LinearLayout row;
                    android.widget.ImageView icon;
                    android.widget.LinearLayout labels;
                    android.widget.TextView title;
                    android.widget.TextView pkg;
                    android.widget.CheckBox check;
                    if (convertView instanceof android.widget.LinearLayout) {
                        row = (android.widget.LinearLayout) convertView;
                        icon = (android.widget.ImageView) row.getChildAt(0);
                        labels = (android.widget.LinearLayout) row.getChildAt(1);
                        check = (android.widget.CheckBox) row.getChildAt(2);
                        title = (android.widget.TextView) labels.getChildAt(0);
                        pkg = (android.widget.TextView) labels.getChildAt(1);
                    } else {
                        row = new android.widget.LinearLayout(MainActivity.this);
                        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
                        row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
                        int pad = (int)(12 * getResources().getDisplayMetrics().density);
                        row.setPadding(pad, pad / 2, pad, pad / 2);
                        icon = new android.widget.ImageView(MainActivity.this);
                        android.widget.LinearLayout.LayoutParams ip = new android.widget.LinearLayout.LayoutParams(
                                (int)(40 * getResources().getDisplayMetrics().density),
                                (int)(40 * getResources().getDisplayMetrics().density));
                        ip.setMargins(0, 0, pad, 0);
                        row.addView(icon, ip);
                        labels = new android.widget.LinearLayout(MainActivity.this);
                        labels.setOrientation(android.widget.LinearLayout.VERTICAL);
                        row.addView(labels, new android.widget.LinearLayout.LayoutParams(0,
                                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1));
                        title = new android.widget.TextView(MainActivity.this);
                        title.setTextSize(16);
                        title.setTextColor(android.graphics.Color.WHITE);
                        labels.addView(title);
                        pkg = new android.widget.TextView(MainActivity.this);
                        pkg.setTextSize(11);
                        pkg.setTextColor(0xFF9CA3AF);
                        labels.addView(pkg);
                        check = new android.widget.CheckBox(MainActivity.this);
                        row.addView(check);
                    }
                    android.content.pm.ApplicationInfo app = apps.get(position);
                    try { icon.setImageDrawable(app.loadIcon(pm)); }
                    catch (Exception ignored) { icon.setImageResource(android.R.drawable.sym_def_app_icon); }
                    title.setText(app.loadLabel(pm));
                    pkg.setText(app.packageName);
                    check.setOnCheckedChangeListener(null);
                    check.setChecked(checked[position]);
                    check.setOnCheckedChangeListener((button, value) -> checked[position] = value);
                    row.setOnClickListener(v -> check.setChecked(!check.isChecked()));
                    return row;
                }
            });

            int maxHeight = (int)(getResources().getDisplayMetrics().heightPixels * 0.62f);
            android.widget.FrameLayout frame = new android.widget.FrameLayout(this);
            frame.addView(list, new android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT, maxHeight));
            new android.app.AlertDialog.Builder(this)
                    .setTitle("Приложения через VPN (" + apps.size() + ")")
                    .setView(frame)
                    .setNegativeButton("Отмена", null)
                    .setNeutralButton("Все приложения", (d, which) -> {
                        prefs.edit().remove("allowed_apps").apply();
                        notifyAppSelectionSaved(0);
                    })
                    .setPositiveButton("Сохранить", (d, which) -> {
                        java.util.Set<String> selected = new java.util.HashSet<>();
                        for (int i = 0; i < checked.length; i++) if (checked[i]) selected.add(apps.get(i).packageName);
                        prefs.edit().putStringSet("allowed_apps", selected).apply();
                        notifyAppSelectionSaved(selected.size());
                    }).show();
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
        IntentFilter filter = new IntentFilter();
        filter.addAction(VlessVpnService.ACTION_STATE);
        filter.addAction(ServerCheckService.ACTION_RESULT);
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
