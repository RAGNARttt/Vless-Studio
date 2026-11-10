package com.ragnar.vlessstudio;

import android.app.Activity;
import android.content.Intent;
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
        public void requestVpnPermission() {
            runOnUiThread(() -> MainActivity.this.requestVpnPermission());
        }

        @JavascriptInterface
        public void stopVpn() {
            runOnUiThread(() -> {
                Intent serviceIntent = new Intent(MainActivity.this, VlessVpnService.class);
                serviceIntent.setAction(VlessVpnService.ACTION_STOP);
                startService(serviceIntent);
                updateVpnUi(false);
            });
        }
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
        updateVpnUi(true);
    }

    private void updateVpnUi(boolean started) {
        if (webView != null) {
            String script = "setVpnUiState(" + started + ")";
            webView.post(() -> webView.evaluateJavascript(script, null));
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == VPN_REQUEST_CODE) {
            if (resultCode == RESULT_OK) {
                startVpnService();
            } else {
                updateVpnUi(false);
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
        if (webView != null) {
            webView.removeJavascriptInterface("AndroidVpnBridge");
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}
