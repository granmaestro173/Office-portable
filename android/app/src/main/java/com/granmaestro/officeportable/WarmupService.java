package com.granmaestro.officeportable;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

/**
 * Started by the home screen. Runs in the :editor process, so starting it also
 * starts that process, loads the WebView and boots one editor shell in the
 * background ({@link WebViewPool#prepareStandby}). Stops itself immediately;
 * the standby WebView lives on in the process until used, expired or trimmed.
 */
public class WarmupService extends Service {
    static void warm(Context context) {
        try {
            context.startService(new Intent(context, WarmupService.class));
        } catch (RuntimeException e) {
            Log.w("OfficePortable", "warm-up skipped", e);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        WebViewPool.prepareStandby(this);
        stopSelf(startId);
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
