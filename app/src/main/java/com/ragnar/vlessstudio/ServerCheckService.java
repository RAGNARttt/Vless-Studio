package com.ragnar.vlessstudio;

import android.app.Service;
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
    public static final String EXTRA_ITEMS = "items_json";
    private static final String TAG = "ServerCheckService";
    private final java.util.concurrent.ExecutorService executor =
            java.util.concurrent.Executors.newSingleThreadExecutor();

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || !ACTION_CHECK.equals(intent.getAction())) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }
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
                                .put("timeout", 10)
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
                stopSelf(startId);
            }
        });
        return START_NOT_STICKY;
    }

    private void publish(String id, boolean ok, long delay, String error) {
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
