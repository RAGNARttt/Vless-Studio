package com.ragnar.vlessstudio;

import android.app.Activity;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.view.WindowInsets;
import android.graphics.Insets;

public class MainActivity extends Activity {
    private static final int VPN_REQUEST_CODE = 1001;
    private WebView webView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        webView = new WebView(this);
        setContentView(webView);

        // Keep WebView content below Android status/navigation bars on modern devices.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            webView.setOnApplyWindowInsetsListener((view, windowInsets) -> {
                Insets bars = windowInsets.getInsets(WindowInsets.Type.systemBars());
                view.setPadding(0, bars.top, 0, bars.bottom);
                return windowInsets;
            });
            webView.requestApplyInsets();
        }

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
        public void requestVpnPermission() {
            runOnUiThread(() -> MainActivity.this.requestVpnPermission());
        }

        @JavascriptInterface
        public void stopVpn() {
            runOnUiThread(() -> {
                Intent serviceIntent = new Intent(MainActivity.this, VlessVpnService.class);
                serviceIntent.setAction(VlessVpnService.ACTION_STOP);
                startService(serviceIntent);
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
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
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
