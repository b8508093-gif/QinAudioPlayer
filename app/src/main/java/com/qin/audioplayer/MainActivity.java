package com.qin.audioplayer;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;
import android.view.KeyEvent;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class MainActivity extends Activity {

    private TextView txtHeader, txtCurrentFile, txtTime, txtSpeed;
    private ListView listView;
    
    private File currentDir;
    private List<File> fileList;
    private List<String> itemNames;
    
    private MediaPlayer mediaPlayer;
    private File currentAudioFile;
    
    private float currentSpeed = 1.0f;
    private boolean isPlaying = false;
    private int currentMode = 0;
    
    private SharedPreferences prefs;
    private Handler handler = new Handler();
    private boolean isPhoneCalling = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        txtHeader = findViewById(R.id.txtHeader);
        txtCurrentFile = findViewById(R.id.txtCurrentFile);
        txtTime = findViewById(R.id.txtTime);
        txtSpeed = findViewById(R.id.txtSpeed);
        listView = findViewById(R.id.listView);

        prefs = getSharedPreferences("QinPlayerPrefs", MODE_PRIVATE);

        TelephonyManager telephonyManager = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
        telephonyManager.listen(new PhoneStateListener() {
            @Override
            public void onCallStateChanged(int state, String incomingNumber) {
                if (state == TelephonyManager.CALL_STATE_RINGING || state == TelephonyManager.CALL_STATE_OFFHOOK) {
                    isPhoneCalling = true;
                    if (isPlaying) {
                        pauseAudio();
                    }
                } else if (state == TelephonyManager.CALL_STATE_IDLE) {
                    if (isPhoneCalling) {
                        isPhoneCalling = false;
                        handler.postDelayed(() -> {
                            if (currentAudioFile != null) {
                                resumeWithSmartJump();
                            }
                        }, 1500);
                    }
                }
                super.onCallStateChanged(state, incomingNumber);
            }
        }, PhoneStateListener.LISTEN_CALL_STATE);

        File root = Environment.getExternalStorageDirectory();
        if (root.exists()) {
            currentDir = root;
        } else {
            currentDir = new File("/");
        }

        loadDirectory(currentDir);

        listView.setOnItemClickListener((parent, view, position, id) -> {
            if (currentMode == 0) {
                File selected = fileList.get(position);
                if (selected.isDirectory()) {
                    loadDirectory(selected);
                } else if (isAudioFile(selected)) {
                    playAudioFile(selected);
                }
            }
        });
    }

    private boolean isAudioFile(File file) {
        String name = file.getName().toLowerCase();
        return name.endsWith(".mp3") || name.endsWith(".wav") || name.endsWith(".m4a") || name.endsWith(".ogg") || name.endsWith(".aac");
    }

    private void loadDirectory(File dir) {
        currentMode = 0;
        txtHeader.setText("תיקייה: " + dir.getName());
        fileList = new ArrayList<>();
        itemNames = new ArrayList<>();

        if (dir.getParentFile() != null) {
            fileList.add(dir.getParentFile());
            itemNames.add("📁 .. (תיקיית האם)");
        }

        File[] files = dir.listFiles();
        if (files != null) {
            List<File> folders = new ArrayList<>();
            List<File> audios = new ArrayList<>();
            for (File f : files) {
                if (f.isDirectory()) folders.add(f);
                else if (isAudioFile(f)) audios.add(f);
            }
            Collections.sort(folders);
            Collections.sort(audios);

            for (File f : folders) {
                fileList.add(f);
                itemNames.add("📁 " + f.getName());
            }
            for (File f : audios) {
                fileList.add(f);
                itemNames.add("🎵 " + f.getName());
            }
        }

        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, itemNames);
        listView.setAdapter(adapter);
    }

    private void playAudioFile(File file) {
        currentAudioFile = file;
        txtCurrentFile.setText(file.getName());
        
        try {
            if (mediaPlayer != null) {
                mediaPlayer.release();
            }
            mediaPlayer = new MediaPlayer();
            mediaPlayer.setDataSource(file.getAbsolutePath());
            mediaPlayer.prepare();

            int savedPos = prefs.getInt(file.getAbsolutePath() + "_pos", 0);
            if (savedPos > 3000) {
                savedPos -= 3000;
            }
            if (savedPos < mediaPlayer.getDuration()) {
                mediaPlayer.seekTo(savedPos);
            }

            currentSpeed = prefs.getFloat(file.getAbsolutePath() + "_speed", 1.0f);
            txtSpeed.setText("מהירות: " + currentSpeed + "x");

            mediaPlayer.start();
            isPlaying = true;

            prefs.edit().putString("last_played", file.getAbsolutePath()).apply();

            mediaPlayer.setOnCompletionListener(mp -> {
                isPlaying = false;
                playNextInDir();
            });

        } catch (Exception e) {
            Toast.makeText(this, "שגיאה בנגינת קובץ", Toast.LENGTH_SHORT).show();
        }
    }

    private void pauseAudio() {
        if (mediaPlayer != null && mediaPlayer.isPlaying()) {
            mediaPlayer.pause();
            isPlaying = false;
            saveCurrentPosition();
        }
    }

    private void resumeWithSmartJump() {
        if (mediaPlayer != null && !isPlaying) {
            int currentPos = mediaPlayer.getCurrentPosition();
            if (currentPos > 3000) {
                mediaPlayer.seekTo(currentPos - 3000);
            }
            mediaPlayer.start();
            isPlaying = true;
        } else if (currentAudioFile != null) {
            playAudioFile(currentAudioFile);
        }
    }

    private void saveCurrentPosition() {
        if (mediaPlayer != null && currentAudioFile != null) {
            prefs.edit().putInt(currentAudioFile.getAbsolutePath() + "_pos", mediaPlayer.getCurrentPosition())
                        .putFloat(currentAudioFile.getAbsolutePath() + "_speed", currentSpeed)
                        .apply();
        }
    }

    private void playNextInDir() {
        if (fileList == null || currentAudioFile == null) return;
        for (int i = 0; i < fileList.size(); i++) {
            if (fileList.get(i).equals(currentAudioFile)) {
                for (int j = i + 1; j < fileList.size(); j++) {
                    if (isAudioFile(fileList.get(j))) {
                        playAudioFile(fileList.get(j));
                        listView.setSelection(j);
                        return;
                    }
                }
                break;
            }
        }
    }

    private void playPrevInDir() {
        if (fileList == null || currentAudioFile == null) return;
        for (int i = 0; i < fileList.size(); i++) {
            if (fileList.get(i).equals(currentAudioFile)) {
                for (int j = i - 1; j >= 0; j--) {
                    if (isAudioFile(fileList.get(j))) {
                        playAudioFile(fileList.get(j));
                        listView.setSelection(j);
                        return;
                    }
                }
                break;
            }
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_5:
            case KeyEvent.KEYCODE_DPAD_CENTER:
                if (isPlaying) {
                    pauseAudio();
                } else {
                    resumeWithSmartJump();
                }
                return true;

            case KeyEvent.KEYCODE_1:
                if (mediaPlayer != null) {
                    int pos = mediaPlayer.getCurrentPosition() - 5000;
                    mediaPlayer.seekTo(Math.max(0, pos));
                }
                return true;

            case KeyEvent.KEYCODE_3:
                if (mediaPlayer != null) {
                    int pos = mediaPlayer.getCurrentPosition() + 5000;
                    if (pos < mediaPlayer.getDuration()) mediaPlayer.seekTo(pos);
                }
                return true;

            case KeyEvent.KEYCODE_2:
                AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI);
                return true;

            case KeyEvent.KEYCODE_8:
                AudioManager amDown = (AudioManager) getSystemService(AUDIO_SERVICE);
                amDown.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI);
                return true;

            case KeyEvent.KEYCODE_7:
                if (currentSpeed > 0.5f) {
                    currentSpeed = Math.round((currentSpeed - 0.1f) * 10f) / 10f;
                    txtSpeed.setText("מהירות: " + currentSpeed + "x");
                    saveCurrentPosition();
                }
                return true;

            case KeyEvent.KEYCODE_9:
                if (currentSpeed < 2.5f) {
                    currentSpeed = Math.round((currentSpeed + 0.1f) * 10f) / 10f;
                    txtSpeed.setText("מהירות: " + currentSpeed + "x");
                    saveCurrentPosition();
                }
                return true;

            case KeyEvent.KEYCODE_0:
                currentSpeed = 1.0f;
                txtSpeed.setText("מהירות: 1.0x");
                saveCurrentPosition();
                return true;

            case KeyEvent.KEYCODE_4:
            case KeyEvent.KEYCODE_DPAD_LEFT:
                playPrevInDir();
                return true;

            case KeyEvent.KEYCODE_6:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                playNextInDir();
                return true;

            case KeyEvent.KEYCODE_POUND:
                String lastPlayedPath = prefs.getString("last_played", null);
                if (lastPlayedPath != null) {
                    File lastFile = new File(lastPlayedPath);
                    if (lastFile.exists()) {
                        playAudioFile(lastFile);
                        Toast.makeText(this, "חזרה לקובץ האחרון", Toast.LENGTH_SHORT).show();
                    }
                }
                return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        saveCurrentPosition();
        if (mediaPlayer != null) {
            mediaPlayer.release();
        }
    }
}
