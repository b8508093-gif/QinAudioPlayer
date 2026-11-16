package com.qin.audioplayer;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

/** Keeps the playback process in the foreground while audio is playing (Android 4.4+). */
public class PlaybackKeepAliveService extends Service {
    public static final String ACTION_START = "com.qin.audioplayer.START_PLAYBACK";
    public static final String ACTION_STOP = "com.qin.audioplayer.STOP_PLAYBACK";
    private static final int NOTIFICATION_ID = 41;
    private PowerManager.WakeLock wakeLock;

    public static void start(Context context, String title) {
        Intent i = new Intent(context, PlaybackKeepAliveService.class);
        i.setAction(ACTION_START);
        i.putExtra("title", title == null ? "ניגון מוזיקה" : title);
        context.startService(i);
    }

    public static void stop(Context context) {
        Intent i = new Intent(context, PlaybackKeepAliveService.class);
        i.setAction(ACTION_STOP);
        context.startService(i);
    }

    @Override public void onCreate() { super.onCreate(); }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            releaseWakeLock();
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        String title = intent == null ? "ניגון מוזיקה" : intent.getStringExtra("title");
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pending = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT);
        Notification notification = new Notification.Builder(this)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle("נגן Qin")
                .setContentText(title == null ? "מנגן ברקע" : title)
                .setContentIntent(pending)
                .setOngoing(true)
                .build();
        startForeground(NOTIFICATION_ID, notification);
        acquireWakeLock();
        return START_STICKY;
    }

    private void acquireWakeLock() {
        try {
            if (wakeLock == null) {
                PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "QinAudioPlayer:Playback");
                wakeLock.setReferenceCounted(false);
            }
            if (!wakeLock.isHeld()) wakeLock.acquire();
        } catch (Exception ignored) {}
    }

    private void releaseWakeLock() {
        try { if (wakeLock != null && wakeLock.isHeld()) wakeLock.release(); } catch (Exception ignored) {}
    }

    @Override public void onDestroy() { releaseWakeLock(); super.onDestroy(); }
    @Override public IBinder onBind(Intent intent) { return null; }
}