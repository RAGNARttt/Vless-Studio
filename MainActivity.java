Работаем над Android-проектом VLESS Studio в репозитории RAGNARttt/Vless-Studio.

Нужно реализовать первый этап интеграции Android VPNService.

Задачи:

1. Изучи текущую структуру проекта, MainActivity.java, AndroidManifest.xml и app/build.gradle.
2. Создай класс VlessVpnService.java, наследующий android.net.VpnService.
3. Зарегистрируй VPN-службу в AndroidManifest.xml с правильными разрешениями и настройками foreground service для target SDK проекта.
4. Добавь в MainActivity запрос системного разрешения через VpnService.prepare().
5. Добавь управление запуском и остановкой VPN-службы.
6. Добавь в существующий WebView интерфейс две кнопки: «Подключить VPN» и «Отключить VPN».
7. Добавь уведомление Android о работе VPN-службы.
8. Обеспечь корректное освобождение ресурсов при остановке службы.

ВАЖНО:

- Пока эхавьвьзапускомавьнтерфейсйсснтерфейс

import android.app.Activity;
import android.os.Bundle;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

public class MainActivity extends Activity {
    private WebView webView;

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
}
