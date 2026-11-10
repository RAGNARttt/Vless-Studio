
package com.ragnar.vlessstudio;

import android.net.VpnService;
import android.content.Intent;
import android.os.ParcelFileDescriptor;
import android.util.Log;

public class VlessVpnService extends VpnService {

    private static final String TAG = "VlessVpnService";

    public static final String ACTION_START =
            "com.ragnar.vlessstudio.START_VPN";

    public static final String ACTION_STOP =
            "com.ragnar.vlessstudio.STOP_VPN";

    private ParcelFileDescriptor vpnInterface;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        if (ACTION_STOP.equals(intent.getAction())) {
            stopVpn();
            stopSelf();
            return START_NOT_STICKY;
        }

        if (ACTION_START.equals(intent.getAction())) {
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                startForeground(
                        1001,
                        VpnNotification.create(this),
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                );
            } else {
                startForeground(1001, VpnNotification.create(this));
            }

            Log.i(TAG, "VPN service started. Tunnel is not connected yet.");
        }

        return START_NOT_STICKY;
    }

    private void stopVpn() {
        try {
            if (vpnInterface != null) {
                vpnInterface.close();
                vpnInterface = null;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error closing VPN interface", e);
        }

        stopForeground(true);
    }

    @Override
    public void onDestroy() {
        stopVpn();
        super.onDestroy();
    }
}
