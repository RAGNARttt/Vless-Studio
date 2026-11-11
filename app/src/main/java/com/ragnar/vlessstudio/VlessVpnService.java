package com.ragnar.vlessstudio;

import android.content.Intent;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.net.InetAddress;

import org.json.JSONArray;
import org.json.JSONObject;

import libXray.DialerController;
import libXray.LibXray;

public class VlessVpnService extends VpnService implements DialerController {
    private static final String TAG = "VlessVpnService";
    public static final String ACTION_START = "com.ragnar.vlessstudio.START_VPN";
    public static final String ACTION_STOP = "com.ragnar.vlessstudio.STOP_VPN";
    public static final String ACTION_STATE = "com.ragnar.vlessstudio.VPN_STATE";
    public static final String EXTRA_SHARE_LINK = "share_link";
    private static final String DNS_SERVER = "1.1.1.1";
    private static final StringBuilder diagnosticLog = new StringBuilder("VLESS Studio VPN diagnostics\\n");
    private static synchronized void diag(String value) {
        String line = new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(new java.util.Date()) + " " + value;
        diagnosticLog.append(line).append("\\n");
        if (diagnosticLog.length() > 12000) diagnosticLog.delete(0, diagnosticLog.length() - 12000);
        Log.i(TAG, value);
    }
    public static synchronized String getDiagnostics() { return diagnosticLog.toString(); }

    private ParcelFileDescriptor vpnInterface;
    private android.net.ConnectivityManager connectivityManager;
    private android.net.ConnectivityManager.NetworkCallback physicalNetworkCallback;
    private final java.util.concurrent.ConcurrentHashMap<android.net.Network, android.net.NetworkCapabilities> physicalNetworks = new java.util.concurrent.ConcurrentHashMap<>();
    private volatile boolean starting;
    private volatile boolean connected;
    private final java.util.concurrent.atomic.AtomicLong protectCalls = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong protectSuccess = new java.util.concurrent.atomic.AtomicLong();

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
            // Capture Android's underlying network before establishing the VPN.
            android.net.ConnectivityManager cm = (android.net.ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            android.net.Network underlying = cm == null ? null : cm.getActiveNetwork();
            diag("Старт VPN; сеть Android=" + (underlying == null ? "не определена" : underlying.toString()));
            Builder builder = new Builder()
                    .setSession("VLESS Studio")
                    .setMtu(1400)
                    .addAddress("10.64.0.1", 32)
                    .addRoute("0.0.0.0", 0)
                    .addDnsServer(DNS_SERVER);

            // Match Happ's excluded routes: keep private/LAN and link-local
            // destinations outside the VPN instead of forcing them through Xray.
            if (Build.VERSION.SDK_INT >= 33) {
                builder.excludeRoute(new android.net.IpPrefix(InetAddress.getByName("10.0.0.0"), 8));
                builder.excludeRoute(new android.net.IpPrefix(InetAddress.getByName("172.16.0.0"), 12));
                builder.excludeRoute(new android.net.IpPrefix(InetAddress.getByName("192.168.0.0"), 16));
                builder.excludeRoute(new android.net.IpPrefix(InetAddress.getByName("169.254.0.0"), 16));
                builder.excludeRoute(new android.net.IpPrefix(InetAddress.getByName("224.0.0.0"), 4));
                builder.excludeRoute(new android.net.IpPrefix(InetAddress.getByName("255.255.255.255"), 32));
            }

            java.util.Set<String> allowedApps = getSharedPreferences("vpn_settings", MODE_PRIVATE)
                    .getStringSet("allowed_apps", java.util.Collections.emptySet());
            if (allowedApps != null && !allowedApps.isEmpty()) {
                for (String packageName : allowedApps) {
                    try { builder.addAllowedApplication(packageName); }
                    catch (android.content.pm.PackageManager.NameNotFoundException e) {
                        Log.w(TAG, "Selected app is no longer installed: " + packageName);
                    }
                }
            }

            // Restore the dual-stack routes used by the previously working build.
            builder.addAddress("fd64:6c65:7373::1", 128);
            builder.addRoute("::", 0);
            builder.addDnsServer("2606:4700:4700::1111");

            // Explicitly tell Android which physical network carries VPN sockets.
            if (underlying != null) builder.setUnderlyingNetworks(new android.net.Network[]{underlying});
            // Establish the dual-stack Android VPN interface.
            vpnInterface = builder.establish();
            if (vpnInterface == null) {
                throw new IllegalStateException("Android не создал VPN-интерфейс");
            }
            registerPhysicalNetworkMonitor(cm, underlying);

            diag("TUN создан; fd=" + vpnInterface.getFd()
                    + ", allowedApps=" + (allowedApps == null ? 0 : allowedApps.size()));
            LibXray.registerDialerController(this);
            LibXray.setDNS(this, DNS_SERVER + ":53");
            Log.i(TAG, "libXray dialer registered; remote DNS=" + DNS_SERVER + ":53");

            JSONObject converted = invoke("convertShareLinksToXrayJson",
                    new JSONObject().put("text", shareLink));
            JSONObject conversionData = converted.optJSONObject("data");
            if (conversionData == null) {
                throw new IllegalStateException("Конвертер libXray вернул ответ без data: " + converted.toString());
            }
            JSONArray outbounds = conversionData.optJSONArray("outbounds");
            if (outbounds == null || outbounds.length() == 0) {
                throw new IllegalStateException("В ссылке не найден рабочий outbound");
            }

            JSONObject proxy = outbounds.getJSONObject(0);
            JSONObject streamSettings = proxy.optJSONObject("streamSettings");
            diag("Outbound: protocol=" + proxy.optString("protocol")
                    + ", network=" + (streamSettings == null ? "default" : streamSettings.optString("network"))
                    + ", security=" + (streamSettings == null ? "default" : streamSettings.optString("security"))
                    + ", outboundCount=" + outbounds.length());
            String proxyTag = proxy.optString("tag", "");
            if (proxyTag.isEmpty()) {
                proxyTag = "proxy";
                proxy.put("tag", proxyTag);
            }

            JSONObject tunSettings = new JSONObject()
                    .put("name", "vlessstudio")
                    .put("mtu", 1400);
            Log.i(TAG, "Xray TUN configured without netlink interface auto-detection; protected sockets use VpnService.protectFd");
            JSONObject tunInbound = new JSONObject()
                    .put("tag", "tun-in")
                    .put("port", 0)
                    .put("protocol", "tun")
                    .put("settings", tunSettings);

            // Happ's logs show DNS packets (1.1.1.1:53/UDP) going through
            // the selected proxy outbound. Match that behaviour instead of
            // intercepting DNS with a separate DNS outbound.
            JSONArray routingRules = new JSONArray()
                    .put(new JSONObject()
                            .put("type", "field")
                            .put("inboundTag", new JSONArray().put("tun-in"))
                            .put("outboundTag", proxyTag));

            JSONObject config = new JSONObject()
                    .put("log", new JSONObject().put("loglevel", "debug"))
                    .put("env", new JSONObject().put("xray.tun.fd", String.valueOf(vpnInterface.getFd())))
                    .put("dns", new JSONObject().put("servers",
                            new JSONArray().put(DNS_SERVER).put("8.8.8.8")))
                    .put("inbounds", new JSONArray().put(tunInbound))
                    .put("outbounds", outbounds)
                    .put("routing", new JSONObject().put("rules", routingRules));

            Log.i(TAG, "Starting Xray with dual-stack TUN MTU 1400; Android manages routes and protected proxy sockets; fd=" + vpnInterface.getFd());
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
            diag("Xray запущен; protectFd=" + protectCalls.get() + ", успешно=" + protectSuccess.get());
            starting = false;
            publishState(true, "VPN запущен · проверяем передачу трафика");
            Log.i(TAG, "Xray TUN started; protectFd calls=" + protectCalls.get() + ", success=" + protectSuccess.get() + "; core startup does not prove internet reachability");
        } catch (Exception e) {
            diag("ОШИБКА запуска: " + Log.getStackTraceString(e));
            Log.e(TAG, "VPN startup failed", e);
            cleanupTunnel();
            starting = false;
            connected = false;
            publishState(false, e.getMessage() == null ? "Не удалось запустить VPN" : e.getMessage());
            stopForeground(true);
            stopSelf(startId);
        }
    }


    // Keep Xray's protected sockets bound to the currently available physical
    // network. This avoids pinning the VPN to a stale Wi-Fi/cellular network.
    private void registerPhysicalNetworkMonitor(android.net.ConnectivityManager cm, android.net.Network initial) {
        if (cm == null) return;
        connectivityManager = cm;
        android.net.NetworkRequest request = new android.net.NetworkRequest.Builder()
                .addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build();
        physicalNetworkCallback = new android.net.ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(android.net.Network network) {
                refreshPhysicalNetwork(network, cm);
            }
            @Override public void onCapabilitiesChanged(android.net.Network network, android.net.NetworkCapabilities caps) {
                physicalNetworks.put(network, caps);
                applyBestUnderlyingNetwork();
            }
            @Override public void onLost(android.net.Network network) {
                physicalNetworks.remove(network);
                applyBestUnderlyingNetwork();
            }
        };
        try {
            for (android.net.Network network : cm.getAllNetworks()) refreshPhysicalNetwork(network, cm);
            cm.registerNetworkCallback(request, physicalNetworkCallback);
            if (initial != null && !physicalNetworks.containsKey(initial)) refreshPhysicalNetwork(initial, cm);
            applyBestUnderlyingNetwork();
            diag("Монитор физических сетей запущен");
        } catch (Exception e) {
            diag("Не удалось включить монитор сети: " + e.getMessage());
        }
    }

    private void refreshPhysicalNetwork(android.net.Network network, android.net.ConnectivityManager cm) {
        try {
            android.net.NetworkCapabilities caps = cm.getNetworkCapabilities(network);
            if (caps != null
                    && caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    && caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) {
                physicalNetworks.put(network, caps);
                applyBestUnderlyingNetwork();
            }
        } catch (Exception e) {
            Log.w(TAG, "Physical network refresh failed", e);
        }
    }

    private synchronized void applyBestUnderlyingNetwork() {
        if (vpnInterface == null || connectivityManager == null) return;
        android.net.Network best = null;
        int bestScore = -1;
        for (java.util.Map.Entry<android.net.Network, android.net.NetworkCapabilities> entry : physicalNetworks.entrySet()) {
            android.net.NetworkCapabilities caps = entry.getValue();
            int score = caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED) ? 10 : 0;
            if (caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)) score += 3;
            if (caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR)) score += 2;
            if (score > bestScore) { bestScore = score; best = entry.getKey(); }
        }
        try {
            setUnderlyingNetworks(best == null ? new android.net.Network[0] : new android.net.Network[]{best});
            diag("Физическая сеть VPN обновлена: " + (best == null ? "нет" : best.toString()));
        } catch (Exception e) {
            diag("Ошибка смены физической сети: " + e.getMessage());
        }
    }

    private void unregisterPhysicalNetworkMonitor() {
        try {
            if (connectivityManager != null && physicalNetworkCallback != null) {
                connectivityManager.unregisterNetworkCallback(physicalNetworkCallback);
            }
        } catch (Exception ignored) {}
        physicalNetworkCallback = null;
        connectivityManager = null;
        physicalNetworks.clear();
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
    public boolean protectFd(long fd) {
        long call = protectCalls.incrementAndGet();
        boolean ok = protect((int) fd);
        if (ok) protectSuccess.incrementAndGet();
        diag("protectFd вызов=" + call + " fd=" + fd + " результат=" + ok + "; успешно всего=" + protectSuccess.get());
        return ok;
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
        unregisterPhysicalNetworkMonitor();
        diag("Очистка TUN; protectFd вызовов=" + protectCalls.get() + ", успешно=" + protectSuccess.get());
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
