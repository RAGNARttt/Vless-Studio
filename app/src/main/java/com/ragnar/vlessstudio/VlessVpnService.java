package com.ragnar.vlessstudio;

import android.content.Intent;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import go.libXray.DialerController;
import go.libXray.LibXray;

public class VlessVpnService extends VpnService implements DialerController {
    private static final String TAG = "VlessVpnService";
    public static final String ACTION_START = "com.ragnar.vlessstudio.START_VPN";
    public static final String ACTION_STOP = "com.ragnar.vlessstudio.STOP_VPN";
    public static final String ACTION_STATE = "com.ragnar.vlessstudio.VPN_STATE";
    public static final String EXTRA_SHARE_LINK = "share_link";
    private static final String DNS_SERVER = "1.1.1.1";

    private ParcelFileDescriptor vpnInterface;
    private volatile boolean starting;
    private volatile boolean connected;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        if (ACTION_STOP.equals(intent.getAction())) {
            new Thread(this::stopVpn, "vless-stop").start();
            return START_NOT_STICKY;
        }
        if (ACTION_START.equals(intent.getAction())) {
            String link = intent.getStringExtra(EXTRA_SHARE_LINK);
            if (link == null || link.trim().isEmpty()) {
                publishState(false, "Сначала выбери сервер");
                stopSelf(startId);
                return START_NOT_STICKY;
            }
            if (connected || starting) return START_NOT_STICKY;
            starting = true;
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(1001, VpnNotification.create(this), android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(1001, VpnNotification.create(this));
            }
            new Thread(() -> startVpn(link, startId), "vless-start").start();
        }
        return START_NOT_STICKY;
    }

    private void startVpn(String shareLink, int startId) {
        try {
            Builder builder = new Builder()
                    .setSession("VLESS Studio")
                    .setMtu(1500)
                    .addAddress("10.64.0.1", 32)
                    .addRoute("0.0.0.0", 0)
                    .addDnsServer(DNS_SERVER);

            // Carry IPv6 through the same tunnel to avoid leaking IPv6 outside the VPN.
            builder.addAddress("fd64:6c65:7373::1", 128);
            builder.addRoute("::", 0);
            builder.addDnsServer("2606:4700:4700::1111");

            vpnInterface = builder.establish();
            if (vpnInterface == null) {
                throw new IllegalStateException("Android не создал VPN-интерфейс");
            }

            LibXray.registerDialerController(this);
            LibXray.setDNS(this, DNS_SERVER + ":53");

            JSONObject converted = invoke("convertShareLinksToXrayJson",
                    new JSONObject().put("text", shareLink));
            JSONObject data = converted.optJSONObject("data");
            if (data == null) throw new IllegalStateException("Конвертер не вернул конфигурацию Xray");
            JSONArray outbounds = data.optJSONArray("outbounds");
            if (outbounds == null || outbounds.length() == 0) {
                throw new IllegalStateException("В ссылке не найден рабочий outbound");
            }

            JSONObject proxy = outbounds.getJSONObject(0);
            String proxyTag = proxy.optString("tag", "");
            if (proxyTag.isEmpty()) {
                proxyTag = "proxy";
                proxy.put("tag", proxyTag);
            }

            JSONObject tunSettings = new JSONObject()
                    .put("name", "vlessstudio")
                    .put("mtu", 1500);
            JSONObject tunInbound = new JSONObject()
                    .put("tag", "tun-in")
                    .put("port", 0)
                    .put("protocol", "tun")
                    .put("settings", tunSettings);

            JSONObject config = new JSONObject()
                    .put("log", new JSONObject().put("loglevel", "warning"))
                    .put("env", new JSONObject().put("xray.tun.fd", String.valueOf(vpnInterface.getFd())))
                    .put("dns", new JSONObject().put("servers",
                            new JSONArray().put(DNS_SERVER).put("8.8.8.8")))
                    .put("inbounds", new JSONArray().put(tunInbound))
                    .put("outbounds", outbounds)
                    .put("routing", new JSONObject().put("rules",
                            new JSONArray().put(new JSONObject()
                                    .put("type", "field")
                                    .put("inboundTag", new JSONArray().put("tun-in"))
                                    .put("outboundTag", proxyTag))));

            JSONObject response = invoke("runXray",
                    new JSONObject().put("xrayJson", config.toString()));
            if (!response.optBoolean("success", false)) {
                throw new IllegalStateException(response.optString("error", "Xray не смог запуститься"));
            }

            JSONObject state = invoke("getXrayState", new JSONObject());
            JSONObject stateData = state.optJSONObject("data");
            if (!state.optBoolean("success", false) || stateData == null || !stateData.optBoolean("running")) {
                throw new IllegalStateException("Xray не подтвердил запуск ядра");
            }

            connected = true;
            starting = false;
            publishState(true, "VPN подключён · Xray передаёт трафик");
            Log.i(TAG, "Xray TUN connected");
        } catch (Exception e) {
            Log.e(TAG, "VPN startup failed", e);
            cleanupTunnel();
            starting = false;
            connected = false;
            publishState(false, e.getMessage() == null ? "Не удалось запустить VPN" : e.getMessage());
            stopForeground(true);
            stopSelf(startId);
        }
    }

    private JSONObject invoke(String method, JSONObject payload) throws Exception {
        JSONObject request = new JSONObject()
                .put("apiVersion", 3)
                .put("method", method)
                .put("payload", payload);
        String raw = LibXray.invoke(request.toString());
        JSONObject response = new JSONObject(raw);
        if (!response.optBoolean("success", false)) {
            throw new IllegalStateException(response.optString("error", "Ошибка libXray"));
        }
        return response;
    }

    @Override
    public boolean protectFd(int fd) {
        return protect(fd);
    }

    private void stopVpn() {
        try {
            if (connected || starting) {
                try {
                    invoke("stopXray", new JSONObject());
                } catch (Exception e) {
                    Log.w(TAG, "Xray stop returned an error", e);
                }
            }
        } finally {
            cleanupTunnel();
            connected = false;
            starting = false;
            stopForeground(true);
            publishState(false, "VPN отключён");
            stopSelf();
        }
    }

    private void cleanupTunnel() {
        try { LibXray.resetDNS(); } catch (Exception e) { Log.w(TAG, "DNS reset failed", e); }
        try {
            if (vpnInterface != null) {
                vpnInterface.close();
                vpnInterface = null;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error closing VPN interface", e);
        }
    }

    private void publishState(boolean isConnected, String message) {
        Intent state = new Intent(ACTION_STATE);
        state.setPackage(getPackageName());
        state.putExtra("connected", isConnected);
        state.putExtra("message", message);
        sendBroadcast(state);
    }

    @Override
    public void onRevoke() {
        new Thread(this::stopVpn, "vless-revoke").start();
        super.onRevoke();
    }

    @Override
    public void onDestroy() {
        if (connected || starting) stopVpn();
        else cleanupTunnel();
        super.onDestroy();
    }
}
