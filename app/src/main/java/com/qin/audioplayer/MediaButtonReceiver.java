package com.qin.audioplayer;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.view.KeyEvent;

/** Routes Android headset/Bluetooth media keys and notification buttons to the player. */
public class MediaButtonReceiver extends BroadcastReceiver {
    public static final String ACTION_PLAY_PAUSE = "com.qin.audioplayer.PLAY_PAUSE";
    public static final String ACTION_NEXT = "com.qin.audioplayer.NEXT";
    public static final String ACTION_PREVIOUS = "com.qin.audioplayer.PREVIOUS";
    public static final String ACTION_STOP = "com.qin.audioplayer.STOP";
    public static final String ACTION_PAUSE = "com.qin.audioplayer.PAUSE";

    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (Intent.ACTION_MEDIA_BUTTON.equals(action)) {
            KeyEvent event = intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
            if (event == null || event.getAction() != KeyEvent.ACTION_DOWN) return;
            switch (event.getKeyCode()) {
                case KeyEvent.KEYCODE_MEDIA_PLAY:
                case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                case KeyEvent.KEYCODE_HEADSETHOOK:
                    MainActivity.handleExternalCommand(context, ACTION_PLAY_PAUSE); break;
                case KeyEvent.KEYCODE_MEDIA_PAUSE:
                    MainActivity.handleExternalCommand(context, ACTION_PLAY_PAUSE); break;
                case KeyEvent.KEYCODE_MEDIA_NEXT:
                    MainActivity.handleExternalCommand(context, ACTION_NEXT); break;
                case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                    MainActivity.handleExternalCommand(context, ACTION_PREVIOUS); break;
                case KeyEvent.KEYCODE_MEDIA_STOP:
                    MainActivity.handleExternalCommand(context, ACTION_STOP); break;
            }
            abortBroadcast();
        } else if ("android.media.AUDIO_BECOMING_NOISY".equals(action)) {
            MainActivity.handleExternalCommand(context, ACTION_PAUSE);
        } else {
            MainActivity.handleExternalCommand(context, action);
        }
    }
}