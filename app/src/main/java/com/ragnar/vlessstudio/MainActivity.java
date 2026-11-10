package com.ragnar.vlessstudio;

import android.app.Activity;
import android.os.Bundle;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.content.Intent;
import android.net.VpnService;
public class MainActivity extends Activity {
    private WebView webView;
private static final int VPN_REQUEST_CODE = 1001;
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        webView = new WebView(this);
        setContentView(webView);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);

        webView.setWebViewClient(new WebViewClient());
        webView.setWebChromeClient(new WebChromeClient());
        webView.loadUrl("file:///android_asset/index.html");
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
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }

private void requestVpnPermission() {
    Intent intent = VpnService.prepare(this);

    if (intent != null) {
        startActivityForResult(intent, VPN_REQUEST_CODE);
    } else {
        onActivityResult(
                VPN_REQUEST_CODE,
                RESULT_OK,
                null
        );
    }
}

@Override
protected void onActivityResult(
        int requestCode,
        int resultCode,
        Intent data
) {
    super.onActivityResult(requestCode, resultCode, data);

    if (requestCode == VPN_REQUEST_CODE) {
        if (resultCode == RESULT_OK) {
            Intent serviceIntent =
                    new Intent(this, VlessVpnService.class);

            serviceIntent.setAction(VlessVpnService.ACTION_START);

            if (android.os.Build.VERSION.SDK_INT >= 26) {
                startForegroundService(serviceIntent);
            } else {
                startService(serviceIntent);
            }
        }
    }
}
}
