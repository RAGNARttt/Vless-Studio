
package com.ragnar.vlessstudio;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;

public class VpnNotification {

    private static final String CHANNEL_ID = "vless_vpn_channel";

    public static Notification create(Context context) {
        NotificationManager manager =
                (NotificationManager) context.getSystemService(
                        Context.NOTIFICATION_SERVICE);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "VLESS VPN",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Статус VPN-службы VLESS Studio");
            manager.createNotificationChannel(channel);

            return new Notification.Builder(context, CHANNEL_ID)
                    .setContentTitle("VLESS Studio")
                    .setContentText("VPN-служба запущена")
                    .setSmallIcon(android.R.drawable.stat_sys_warning)
                    .setOngoing(true)
                    .build();
        }

        return new Notification.Builder(context)
                .setContentTitle("VLESS Studio")
                .setContentText("VPN-служба запущена")
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setOngoing(true)
                .build();
    }
}