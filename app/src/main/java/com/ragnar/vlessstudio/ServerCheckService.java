package com.ragnar.vlessstudio;

import android.app.Service;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.SharedPreferences;
import android.os.Build;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import libXray.LibXray;

/** Runs libXray checks in an isolated process so pingBatch cannot collide
 * with the long-running Xray core used by the VPN service. */
public class ServerCheckService extends Service {
    public static final String ACTION_CHECK = "com.ragnar.vlessstudio.CHECK_SERVERS";
    public static final String ACTION_RESULT = "com.ragnar.vlessstudio.SERVER_CHECK_RESULT";
    public static final String ACTION_FINISHED = "com.ragnar.vlessstudio.SERVER_CHECK_FINISHED";
    public static final String EXTRA_ITEMS = "items_json";
    private static final String TAG = "ServerCheckService";
    private static final String PREFS = "server_check_results";
    private static final String CHANNEL = "server_check_channel";
    private static final int NOTIFICATION_ID = 2405;
    private final java.util.concurrent.ExecutorService executor =
            java.util.concurrent.Executors.newSingleThreadExecutor();

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || !ACTION_CHECK.equals(intent.getAction())) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        startCheckForeground();
        String raw = intent.getStringExtra(EXTRA_ITEMS);
        executor.execute(() -> {
            try {
                JSONArray items = new JSONArray(raw == null ? "[]" : raw);
                for (int offset = 0; offset < items.length(); offset += 5) {
                    JSONArray configs = new JSONArray();
                    java.util.ArrayList<String> ids = new java.util.ArrayList<>();
                    int end = Math.min(offset + 5, items.length());
                    for (int i = offset; i < end; i++) {
                        JSONObject item = items.getJSONObject(i);
                        String id = item.optString("id", "");
                        String link = item.optString("link", "");
                        try {
                            JSONObject request = new JSONObject()
                                    .put("apiVersion", 3)
                                    .put("method", "convertShareLinksToXrayJson")
                                    .put("payload", new JSONObject().put("text", link));
                            JSONObject converted = new JSONObject(LibXray.invoke(request.toString()));
                            JSONObject data = converted.optJSONObject("data");
                            JSONArray outbounds = data == null ? null : data.optJSONArray("outbounds");
                            if (!converted.optBoolean("success", false) || outbounds == null || outbounds.length() == 0)
                                throw new IllegalArgumentException(converted.optString("error", "Не удалось разобрать ссылку"));
                            configs.put(new JSONObject().put("xrayJson",
                                    new JSONObject().put("outbounds", outbounds).toString()));
                            ids.add(id);
                        } catch (Exception e) {
                            publish(id, false, -1, e.getMessage());
                        }
                    }
                    if (configs.length() == 0) continue;
                    try {
                        JSONObject payload = new JSONObject()
                                .put("configs", configs)
                                .put("timeout", 6)
                                .put("url", "https://cp.cloudflare.com/");
                        JSONObject request = new JSONObject().put("apiVersion", 3)
                                .put("method", "pingBatch").put("payload", payload);
                        JSONObject response = new JSONObject(LibXray.invoke(request.toString()));
                        JSONObject data = response.optJSONObject("data");
                        JSONArray results = data == null ? null : data.optJSONArray("results");
                        if (!response.optBoolean("success", false) || results == null)
                            throw new IllegalStateException(response.optString("error", "libXray не вернул результаты pingBatch"));
                        for (int j = 0; j < ids.size(); j++) {
                            JSONObject result = results.optJSONObject(j);
                            boolean ok = result != null && result.optBoolean("success", false);
                            publish(ids.get(j), ok, ok ? result.optLong("delay", -1) : -1,
                                    result == null ? "Пустой ответ" : result.optString("error", ""));
                        }
                    } catch (Exception e) {
                        for (String id : ids) publish(id, false, -1, e.getMessage());
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Server check failed", e);
            } finally {
                Intent finished = new Intent(ACTION_FINISHED);
                finished.setPackage(getPackageName());
                sendBroadcast(finished);
                stopForeground(true);
                stopSelf(startId);
            }
        });
        return START_NOT_STICKY;
    }

    private void startCheckForeground() {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(new NotificationChannel(CHANNEL, "Проверка серверов", NotificationManager.IMPORTANCE_LOW));
        }
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        builder.setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("VLESS Studio")
                .setContentText("Проверяю серверы в фоне…")
                .setOngoing(true)
                .setOnlyAlertOnce(true);
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, builder.build(), android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        else startForeground(NOTIFICATION_ID, builder.build());
    }

    private void publish(String id, boolean ok, long delay, String error) {
        try {
            SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
            JSONObject all = new JSONObject(prefs.getString("results", "{}"));
            JSONObject item = new JSONObject().put("ok", ok).put("delay", delay).put("error", error == null ? "" : error).put("time", System.currentTimeMillis());
            all.put(id, item);
            prefs.edit().putString("results", all.toString()).apply();
        } catch (Exception ignored) {}

        Intent result = new Intent(ACTION_RESULT);
        result.setPackage(getPackageName());
        result.putExtra("id", id);
        result.putExtra("ok", ok);
        result.putExtra("delay", delay);
        result.putExtra("error", error == null ? "" : error);
        sendBroadcast(result);
    }

    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }
}
