package com.qin.audioplayer;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.media.RemoteControlClient;
import android.media.MediaMetadataRetriever;
import android.os.IBinder;
import android.os.PowerManager;

public class PlaybackKeepAliveService extends Service {
    public static final String ACTION_START = "com.qin.audioplayer.START_PLAYBACK";
    public static final String ACTION_PAUSE = "com.qin.audioplayer.PAUSE_PLAYBACK";
    public static final String ACTION_STOP = "com.qin.audioplayer.STOP_PLAYBACK";
    private static final int NOTIFICATION_ID = 41;
    private PowerManager.WakeLock wakeLock;
    private AudioManager audioManager;
    private boolean receiverRegistered;
    private RemoteControlClient remoteControlClient;

    public static void start(Context c, String title) { send(c, ACTION_START, title); }
    public static void pause(Context c) { send(c, ACTION_PAUSE, null); }
    public static void stop(Context c) { send(c, ACTION_STOP, null); }
    private static void send(Context c, String action, String title) {
        Intent i = new Intent(c, PlaybackKeepAliveService.class).setAction(action);
        if (title != null) i.putExtra("title", title);
        c.startService(i);
    }

    @Override public void onCreate() {
        super.onCreate();
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        try {
            audioManager.registerMediaButtonEventReceiver(new ComponentName(this, MediaButtonReceiver.class));
            receiverRegistered = true;
            Intent mediaIntent = new Intent(Intent.ACTION_MEDIA_BUTTON);
            mediaIntent.setComponent(new ComponentName(this, MediaButtonReceiver.class));
            PendingIntent mediaPendingIntent = PendingIntent.getBroadcast(this, 0, mediaIntent, PendingIntent.FLAG_UPDATE_CURRENT);
            remoteControlClient = new RemoteControlClient(mediaPendingIntent);
            remoteControlClient.setTransportControlFlags(
                    RemoteControlClient.FLAG_KEY_MEDIA_PLAY |
                    RemoteControlClient.FLAG_KEY_MEDIA_PAUSE |
                    RemoteControlClient.FLAG_KEY_MEDIA_PLAY_PAUSE |
                    RemoteControlClient.FLAG_KEY_MEDIA_STOP |
                    RemoteControlClient.FLAG_KEY_MEDIA_NEXT |
                    RemoteControlClient.FLAG_KEY_MEDIA_PREVIOUS);
            audioManager.registerRemoteControlClient(remoteControlClient);
        } catch (Exception ignored) {}
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            releaseWakeLock();
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_PAUSE.equals(action)) {
            releaseWakeLock();
            showNotification(intent == null ? "מושהה" : intent.getStringExtra("title"), false);
            return START_STICKY;
        }
        acquireWakeLock();
        showNotification(intent == null ? null : intent.getStringExtra("title"), true);
        return START_STICKY;
    }

    private void showNotification(String title, boolean playing) {
        if (remoteControlClient != null) {
            try {
                RemoteControlClient.MetadataEditor editor = remoteControlClient.editMetadata(true);
                if (title != null) editor.putString(MediaMetadataRetriever.METADATA_KEY_TITLE, title);
                editor.apply();
                remoteControlClient.setPlaybackState(playing ? RemoteControlClient.PLAYSTATE_PLAYING :
                        RemoteControlClient.PLAYSTATE_PAUSED);
            } catch (Exception ignored) {}
        }
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent content = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b = new Notification.Builder(this)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle("נגן Qin")
                .setContentText(title == null ? (playing ? "מנגן ברקע" : "מושהה") : title)
                .setContentIntent(content)
                .setOngoing(true)
                .setPriority(Notification.PRIORITY_LOW);
        b.addAction(android.R.drawable.ic_media_previous, "הקודם", command(MediaButtonReceiver.ACTION_PREVIOUS, 1));
        b.addAction(playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play,
                playing ? "השהה" : "המשך", command(MediaButtonReceiver.ACTION_PLAY_PAUSE, 2));
        b.addAction(android.R.drawable.ic_media_next, "הבא", command(MediaButtonReceiver.ACTION_NEXT, 3));
        startForeground(NOTIFICATION_ID, b.build());
    }

    private PendingIntent command(String action, int requestCode) {
        Intent i = new Intent(this, MediaButtonReceiver.class).setAction(action);
        return PendingIntent.getBroadcast(this, requestCode, i, PendingIntent.FLAG_UPDATE_CURRENT);
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
    @Override public void onDestroy() {
        releaseWakeLock();
        if (receiverRegistered) try {
            audioManager.unregisterMediaButtonEventReceiver(new ComponentName(this, MediaButtonReceiver.class));
            if (remoteControlClient != null) audioManager.unregisterRemoteControlClient(remoteControlClient);
        } catch (Exception ignored) {}
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}