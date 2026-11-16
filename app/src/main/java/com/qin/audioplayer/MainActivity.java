package com.qin.audioplayer;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;
import android.view.KeyEvent;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class MainActivity extends Activity {

    private TextView txtHeader, txtCurrentFile, txtTime, txtSpeed;
    private ListView listView;
    private ProgressBar progressBar;
    private int screenPage = 0;
    private List<File> historyFiles = new ArrayList<>();
    
    private File currentDir;
    private List<File> fileList;
    private List<String> itemNames;
    
    private SonicAudioPlayer audioPlayer;
    private int currentDurationMs;
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
        progressBar = findViewById(R.id.progressBar);

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

        loadStorageRoots();

        listView.setOnItemClickListener((parent, view, position, id) -> {
            if (screenPage == 2) {\n                if (position < historyFiles.size()) {\n                    File recent = historyFiles.get(position);\n                    if (recent.exists() && isAudioFile(recent)) playAudioFile(recent);\n                }\n            } else if (screenPage == 0) {
                File selected = fileList.get(position);
                if (selected.isDirectory()) {
                    loadDirectory(selected);
                } else if (isAudioFile(selected)) {
                    playAudioFile(selected);
                }
            }
        });
    }

    private boolean isSystemFolder(File f) {\n        String n = f.getName();\n        return n.equalsIgnoreCase("Android") || n.equalsIgnoreCase("LOST.DIR") || n.equalsIgnoreCase("System Volume Information") || n.equalsIgnoreCase("$RECYCLE.BIN");\n    }\n\n    private void loadStorageRoots() {\n        screenPage = 0; currentMode = 0; currentDir = null;\n        fileList = new ArrayList<>(); itemNames = new ArrayList<>();\n        File primary = Environment.getExternalStorageDirectory();\n        if (primary != null && primary.exists()) { fileList.add(primary); itemNames.add("מכשיר – " + primary.getName()); }\n        File storage = new File("/storage"); File[] roots = storage.listFiles();\n        if (roots != null) for (File r : roots) {\n            if (!r.isDirectory() || !r.canRead() || r.getName().equals("emulated") || r.getName().equals("self") || r.getName().startsWith(".")) continue;\n            if (primary != null && r.getAbsolutePath().equals(primary.getAbsolutePath())) continue;\n            fileList.add(r); itemNames.add("כרטיס זיכרון – " + r.getName());\n        }\n        txtHeader.setText("בחר אחסון"); listView.setVisibility(android.view.View.VISIBLE); progressBar.setVisibility(android.view.View.GONE);\n        listView.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, itemNames));\n        if (!itemNames.isEmpty()) listView.setSelection(0); listView.requestFocus();\n    }\n\n    private void showPlayerPage() {\n        screenPage = 1; listView.setVisibility(android.view.View.GONE); progressBar.setVisibility(android.view.View.VISIBLE);\n        txtHeader.setText("נגן");\n        txtCurrentFile.setText(currentAudioFile == null ? "לא נבחר שיר" : currentAudioFile.getName());\n        txtSpeed.setText("מהירות: " + currentSpeed + "x");\n    }\n\n    private void showHistoryPage() {\n        screenPage = 2; listView.setVisibility(android.view.View.VISIBLE); progressBar.setVisibility(android.view.View.GONE);\n        historyFiles.clear(); itemNames = new ArrayList<>();\n        String raw = prefs.getString("history_paths", "");\n        for (String path : raw.split("\\n")) { if (path.length() > 0) { File f = new File(path); if (f.exists()) { historyFiles.add(f); itemNames.add(f.getName() + " – " + f.getParent()); } } }\n        fileList = new ArrayList<>(historyFiles); txtHeader.setText("היסטוריה – מיקומים אחרונים");\n        listView.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, itemNames));\n        if (!itemNames.isEmpty()) listView.setSelection(0); listView.requestFocus();\n    }\n\n    private void rememberInHistory(File f) {\n        String old = prefs.getString("history_paths", ""); String path = f.getAbsolutePath();\n        ArrayList<String> paths = new ArrayList<>(); paths.add(path);\n        for (String p : old.split("\\n")) if (p.length() > 0 && !p.equals(path) && paths.size() < 30) paths.add(p);\n        StringBuilder b = new StringBuilder(); for (String p : paths) { if (b.length() > 0) b.append("\\n"); b.append(p); }\n        prefs.edit().putString("history_paths", b.toString()).apply();\n    }\n\n    private boolean isAudioFile(File file) {
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
                if (f.isDirectory() && !f.getName().startsWith(".") && !isSystemFolder(f)) folders.add(f);
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
        if (!itemNames.isEmpty()) listView.setSelection(0);
        listView.requestFocus();
    }

    private void playAudioFile(File file) {
        currentAudioFile = file;
        currentDurationMs = 0;
        txtTime.setText("00:00 / --:--");
        txtCurrentFile.setText(file.getName());
        try {
            if (audioPlayer != null) audioPlayer.stop();
            currentSpeed = prefs.getFloat(file.getAbsolutePath() + "_speed", currentSpeed);
            currentSpeed = Math.max(0.5f, Math.min(5.0f, currentSpeed));
            txtSpeed.setText("מהירות: " + currentSpeed + "x");
            int savedPos = prefs.getInt(file.getAbsolutePath() + "_pos", 0);
            audioPlayer = new SonicAudioPlayer(file.getAbsolutePath(), currentSpeed,
                    new SonicAudioPlayer.Listener() {
                @Override public void onProgress(int positionMs) {
                    txtTime.setText(formatTime(positionMs) + " / " + formatTime(currentDurationMs));
                }
                @Override public void onDuration(int durationMs) {
                    currentDurationMs = durationMs;
                }
                @Override public void onCompleted() {
                    isPlaying = false;
                    audioPlayer = null;
                    PlaybackKeepAliveService.stop(MainActivity.this);
                    playNextInDir();
                }
                @Override public void onError(Exception error) {
                    isPlaying = false;
                    audioPlayer = null;
                    PlaybackKeepAliveService.stop(MainActivity.this);
                    Toast.makeText(MainActivity.this, "שגיאה בנגינה: " + error.getMessage(), Toast.LENGTH_LONG).show();
                }
            });
            audioPlayer.start(savedPos);
            PlaybackKeepAliveService.start(this, file.getName());
            isPlaying = true;
            prefs.edit().putString("last_played", file.getAbsolutePath()).apply();
        } catch (Exception e) {
            Toast.makeText(this, "שגיאה בנגינת קובץ", Toast.LENGTH_SHORT).show();
        }
    }

    private String formatTime(int milliseconds) {
        int totalSeconds = Math.max(0, milliseconds) / 1000;
        return String.format(java.util.Locale.US, "%02d:%02d", totalSeconds / 60, totalSeconds % 60);
    }

    private void pauseAudio() {
        if (audioPlayer != null && isPlaying) {
            audioPlayer.pause();
            PlaybackKeepAliveService.stop(this);
            isPlaying = false;
            saveCurrentPosition();
        }
    }

    private void resumeWithSmartJump() {
        if (audioPlayer != null && !isPlaying) {
            audioPlayer.resume();
            PlaybackKeepAliveService.start(this, currentAudioFile == null ? "ניגון מוזיקה" : currentAudioFile.getName());
            isPlaying = true;
        } else if (currentAudioFile != null) {
            playAudioFile(currentAudioFile);
        }
    }

    private void saveCurrentPosition() {
        if (audioPlayer != null && currentAudioFile != null) {
            prefs.edit().putInt(currentAudioFile.getAbsolutePath() + "_pos", audioPlayer.getPositionMs())
                    .putFloat(currentAudioFile.getAbsolutePath() + "_speed", currentSpeed).apply();
        }
    }

    private void playNextInDir() {
        if (fileList == null || currentAudioFile == null) return;
        for (int i = 0; i < fileList.size(); i++) {
            if (fileList.get(i).equals(currentAudioFile)) {
                for (int step = 1; step < fileList.size(); step++) {
                    int j = (i + step) % fileList.size();
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
                for (int step = 1; step < fileList.size(); step++) {
                    int j = (i - step + fileList.size()) % fileList.size();
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
            case KeyEvent.KEYCODE_DPAD_UP:
                moveSelection(-1);
                return true;

            case KeyEvent.KEYCODE_DPAD_DOWN:
                moveSelection(1);
                return true;

            case KeyEvent.KEYCODE_STAR:
                activateSelectedItem();
                return true;

            case KeyEvent.KEYCODE_5:
            case KeyEvent.KEYCODE_DPAD_CENTER:
                if (audioPlayer == null && currentAudioFile == null) {
                    activateSelectedItem();
                } else if (isPlaying) {
                    pauseAudio();
                } else {
                    resumeWithSmartJump();
                }
                return true;

            case KeyEvent.KEYCODE_1:
                if (audioPlayer != null) {
                    int pos = audioPlayer.getPositionMs() - 5000;
                    restartAtPosition(Math.max(0, pos));
                }
                return true;

            case KeyEvent.KEYCODE_3:
                if (audioPlayer != null) {
                    int pos = audioPlayer.getPositionMs() + 5000;
                    restartAtPosition(pos);
                }
                return true;

            case KeyEvent.KEYCODE_2:
                if (!isPlaying) {
                    moveSelection(-1);
                } else {
                    AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
                    am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI);
                }
                return true;

            case KeyEvent.KEYCODE_8:
                if (!isPlaying) {
                    moveSelection(1);
                } else {
                    AudioManager amDown = (AudioManager) getSystemService(AUDIO_SERVICE);
                    amDown.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI);
                }
                return true;

            case KeyEvent.KEYCODE_7:
                if (currentSpeed > 0.5f) {
                    currentSpeed = Math.max(0.5f, Math.round((currentSpeed - 0.1f) * 10f) / 10f);
                    txtSpeed.setText("מהירות: " + currentSpeed + "x");
                    if (audioPlayer != null) audioPlayer.setSpeed(currentSpeed);
                    saveCurrentPosition();
                }
                return true;

            case KeyEvent.KEYCODE_9:
                if (currentSpeed < 5.0f) {
                    currentSpeed = Math.min(5.0f, Math.round((currentSpeed + 0.1f) * 10f) / 10f);
                    txtSpeed.setText("מהירות: " + currentSpeed + "x");
                    if (audioPlayer != null) audioPlayer.setSpeed(currentSpeed);
                    saveCurrentPosition();
                }
                return true;

            case KeyEvent.KEYCODE_0:
                currentSpeed = 1.0f;
                txtSpeed.setText("מהירות: 1.0x");
                if (audioPlayer != null) audioPlayer.setSpeed(currentSpeed);
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

    private void moveSelection(int delta) {
        int count = listView.getCount();
        if (count <= 0) return;
        int current = listView.getSelectedItemPosition();
        if (current < 0) current = 0;
        listView.setSelection((current + delta + count) % count);
        listView.requestFocus();
    }

    private void activateSelectedItem() {
        int position = listView.getSelectedItemPosition();
        if (position < 0 || fileList == null || position >= fileList.size()) return;
        File selected = fileList.get(position);
        if (selected.isDirectory()) {
            currentDir = selected;
            loadDirectory(selected);
        } else if (isAudioFile(selected)) {
            playAudioFile(selected);
        }
    }

    private void restartAtPosition(int positionMs) {
        if (currentAudioFile == null) return;
        prefs.edit().putInt(currentAudioFile.getAbsolutePath() + "_pos", positionMs).apply();
        playAudioFile(currentAudioFile);
    }

    @Override
    protected void onDestroy() {
        saveCurrentPosition();
        // Playback is owned by the foreground keep-alive service; do not stop audio when the UI closes.
        super.onDestroy();
    }
}
