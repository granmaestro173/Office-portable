package com.granmaestro.officeportable;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

/**
 * Foreground service that runs only while a document is being saved. It tells
 * Android the :editor process is doing work the user asked for, so switching
 * apps mid-save doesn't get the editor killed before the file is written.
 */
public class SaveService extends Service {
    private static final String CHANNEL = "saving";
    private static final int ID = 7;
    private static final String EXTRA_NAME = "name";

    static void start(Context context, String docName) {
        Intent i = new Intent(context, SaveService.class).putExtra(EXTRA_NAME, docName);
        try {
            context.startForegroundService(i);
        } catch (RuntimeException e) {
            // Not allowed right now (e.g. app already in the background): the save still
            // runs, just without the extra protection.
            Log.w("OfficePortable", "SaveService not started", e);
        }
    }

    static void stop(Context context) {
        context.stopService(new Intent(context, SaveService.class));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String name = intent != null ? intent.getStringExtra(EXTRA_NAME) : null;
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null && nm.getNotificationChannel(CHANNEL) == null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Saving documents",
                    NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        Notification n = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setContentTitle("Saving" + (name != null ? " " + name : "") + "…")
                .setProgress(0, 0, true)
                .setOngoing(true)
                .build();
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(ID, n);
            }
        } catch (RuntimeException e) {
            Log.w("OfficePortable", "startForeground failed", e);
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
