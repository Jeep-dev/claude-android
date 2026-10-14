package com.jeep.gemini;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

/**
 * Runs while Gemini writes a reply. Without it Android freezes the app soon after it goes to
 * the background, the page loses its connection and the reply stops halfway.
 */
public final class ReplyService extends Service {
    private static final String CHANNEL = "reply";
    /** A reply that never reports its end does not keep the app awake for ever. */
    private static final long LIMIT_MS = 15 * 60 * 1000;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable stop = this::stopSelf;

    static void start(Context context) {
        try {
            context.startForegroundService(new Intent(context, ReplyService.class));
        } catch (RuntimeException e) {
            // Not allowed from the background (Android 12+): the reply runs as before.
        }
    }

    static void stop(Context context) {
        context.stopService(new Intent(context, ReplyService.class));
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL,
                getString(R.string.reply_channel), NotificationManager.IMPORTANCE_MIN));
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.notify_mark)
                .setContentTitle(getString(R.string.reply_running))
                .setContentIntent(open)
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(1, notification);
        }
        handler.removeCallbacks(stop);
        handler.postDelayed(stop, LIMIT_MS);
        return START_NOT_STICKY;
    }

    @Override public void onDestroy() {
        handler.removeCallbacks(stop);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
