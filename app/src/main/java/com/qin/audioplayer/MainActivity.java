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
import android.util.Base64;
import android.view.KeyEvent;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.SimpleAdapter;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.HashMap;
import java.util.Map;

public class MainActivity extends Activity {
    private static volatile MainActivity activeInstance;

    private TextView txtHeader, txtCurrentFile, txtTime, txtSpeed;
    private ListView listView;
    private ProgressBar progressBar;
    private int screenPage = 0; // 0 folders, 1 player, 2 history

    private File currentDir;
    private File playbackDir;
    private List<File> fileList = new ArrayList<>();
    private List<String> itemNames = new ArrayList<>();
    private List<File> historyFolders = new ArrayList<>();
    private List<File> recursiveQueue;
    private File recursiveRoot;
    private int recursiveIndex = -1;
    private boolean starLongPressHandled;

    private SonicAudioPlayer audioPlayer;
    private int currentDurationMs;
    private File currentAudioFile;
    private float currentSpeed = 1.0f;
    private boolean isPlaying;
    private boolean isPhoneCalling;
    private boolean wasPlayingBeforeCall;
    private boolean resumeAfterAudioFocus;
    private boolean abLoopRestarting;
    private int abStartMs = -1, abEndMs = -1;
    private int forcedStartPosition = -1;
    private int lastPersistedPosition;

    private SharedPreferences prefs;
    private final Handler handler = new Handler();
    private final Runnable uiRefresh = new Runnable() {
        @Override public void run() {
            if (audioPlayer == null || currentAudioFile == null || txtTime == null) return;
            int pos = audioPlayer.getPositionMs();
            txtTime.setText(formatTime(pos) + " / " + formatTime(currentDurationMs));
            txtSpeed.setText("מהירות: " + String.format(java.util.Locale.US, "%.1fx", currentSpeed));
            if (currentDurationMs > 0) progressBar.setProgress((int)Math.max(0, Math.min(1000, 1000L * pos / currentDurationMs)));
            handler.postDelayed(this, 500);
        }
    };
    private AudioManager audioManager;
    private final AudioManager.OnAudioFocusChangeListener audioFocusListener =
            new AudioManager.OnAudioFocusChangeListener() {
        @Override public void onAudioFocusChange(int change) {
            if (change == AudioManager.AUDIOFOCUS_LOSS ||
                    change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                resumeAfterAudioFocus = change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT && isPlaying;
                if (isPlaying) pauseAudio();
            } else if (change == AudioManager.AUDIOFOCUS_GAIN && resumeAfterAudioFocus) {
                resumeAfterAudioFocus = false;
                resumeWithSmartJump();
            }
        }
    };

    public static void handleExternalCommand(Context context, String command) {
        MainActivity a = activeInstance;
        if (a == null || command == null) return;
        if (MediaButtonReceiver.ACTION_PLAY_PAUSE.equals(command)) {
            if (a.isPlaying) a.pauseAudio(); else a.resumeWithSmartJump();
        } else if (MediaButtonReceiver.ACTION_RESUME.equals(command)) {
            a.resumeWithSmartJump();
        } else if (MediaButtonReceiver.ACTION_PAUSE.equals(command)) {
            if (a.isPlaying) a.pauseAudio();
        } else if (MediaButtonReceiver.ACTION_NEXT.equals(command)) {
            a.playNextInDir();
        } else if (MediaButtonReceiver.ACTION_PREVIOUS.equals(command)) {
            a.playPrevInDir();
        } else if (MediaButtonReceiver.ACTION_STOP.equals(command)) {
            a.pauseAudio();
        }
    }

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        MainActivity previous = activeInstance;
        if (previous != null && previous.audioPlayer != null) {
            audioPlayer = previous.audioPlayer;
            currentAudioFile = previous.currentAudioFile;
            playbackDir = previous.playbackDir;
            currentDir = previous.currentDir;
            currentSpeed = previous.currentSpeed;
            currentDurationMs = previous.currentDurationMs;
            isPlaying = previous.isPlaying;
            recursiveQueue = previous.recursiveQueue;
            recursiveRoot = previous.recursiveRoot;
            abStartMs = previous.abStartMs;
            abEndMs = previous.abEndMs;
            screenPage = 1;
        }
        activeInstance = this;
        setContentView(R.layout.activity_main);

        txtHeader = findViewById(R.id.txtHeader);
        txtCurrentFile = findViewById(R.id.txtCurrentFile);
        txtTime = findViewById(R.id.txtTime);
        txtSpeed = findViewById(R.id.txtSpeed);
        listView = findViewById(R.id.listView);
        progressBar = findViewById(R.id.progressBar);
        prefs = getSharedPreferences("QinPlayerPrefs", MODE_PRIVATE);
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        currentSpeed = Math.max(0.5f, Math.min(5.0f, prefs.getFloat("global_speed", 1.0f)));

        TelephonyManager tm = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
        tm.listen(new PhoneStateListener() {
            @Override public void onCallStateChanged(int state, String number) {
                if (state == TelephonyManager.CALL_STATE_RINGING ||
                        state == TelephonyManager.CALL_STATE_OFFHOOK) {
                    if (!isPhoneCalling) wasPlayingBeforeCall = isPlaying;
                    isPhoneCalling = true;
                    if (isPlaying) pauseAudio();
                } else if (state == TelephonyManager.CALL_STATE_IDLE && isPhoneCalling) {
                    isPhoneCalling = false;
                    if (wasPlayingBeforeCall) handler.postDelayed(new Runnable() {
                        @Override public void run() { resumeWithSmartJump(); }
                    }, 1200);
                    wasPlayingBeforeCall = false;
                }
            }
        }, PhoneStateListener.LISTEN_CALL_STATE);

        if (audioPlayer != null && currentAudioFile != null) {
            showPlayerPage();
            txtTime.setText(formatTime(audioPlayer.getPositionMs()) + " / " + formatTime(currentDurationMs));
            handler.post(uiRefresh);
        } else {
            loadStorageRoots();
        }
        listView.setOnItemClickListener((parent, view, position, id) -> activatePosition(position));
    }

    private String folderKey(File folder) {
        return "folder_" + Base64.encodeToString(folder.getAbsolutePath().getBytes(), Base64.NO_WRAP);
    }

    private boolean isAudioFile(File f) {
        String n = f.getName().toLowerCase(java.util.Locale.US);
        return n.endsWith(".mp3") || n.endsWith(".wav") || n.endsWith(".m4a") ||
                n.endsWith(".ogg") || n.endsWith(".aac") || n.endsWith(".flac");
    }

    private boolean isSystemFolder(File f) {
        String n = f.getName();
        return n.equalsIgnoreCase("Android") || n.equalsIgnoreCase("LOST.DIR") ||
                n.equalsIgnoreCase("System Volume Information") || n.equalsIgnoreCase("$RECYCLE.BIN");
    }

    private void loadStorageRoots() {
        screenPage = 0;
        currentDir = null;
        fileList = new ArrayList<>();
        itemNames = new ArrayList<>();
        File primary = Environment.getExternalStorageDirectory();
        if (primary != null && primary.exists() && primary.canRead()) {
            fileList.add(primary);
            itemNames.add("מכשיר – " + primary.getName());
        }
        File[] roots = new File("/storage").listFiles();
        if (roots != null) for (File r : roots) {
            if (!r.isDirectory() || !r.canRead() || r.getName().startsWith(".") ||
                    "emulated".equals(r.getName()) || "self".equals(r.getName())) continue;
            if (primary != null && r.getAbsolutePath().equals(primary.getAbsolutePath())) continue;
            fileList.add(r);
            itemNames.add("כרטיס זיכרון – " + r.getName());
        }
        txtHeader.setText("בחר אחסון");
        listView.setVisibility(android.view.View.VISIBLE);
        progressBar.setVisibility(android.view.View.GONE);
        setListAdapter();
    }

    private void loadDirectory(File dir) {
        if (dir == null || !dir.isDirectory() || !dir.canRead()) return;
        currentDir = dir;
        screenPage = 0;
        fileList = new ArrayList<>();
        itemNames = new ArrayList<>();
        boolean storageRoot = dir.getParent() != null &&
                (dir.getParent().equals("/storage") || dir.getParent().equals("/storage/emulated"));
        if (dir.getParentFile() != null && !storageRoot &&
                !dir.getAbsolutePath().equals(Environment.getExternalStorageDirectory().getAbsolutePath())) {
            fileList.add(dir.getParentFile());
            itemNames.add("📁 .. (תיקיית האם)");
        }
        File[] files = dir.listFiles();
        List<File> folders = new ArrayList<>(), audios = new ArrayList<>();
        if (files != null) for (File f : files) {
            if (f.isDirectory() && !f.getName().startsWith(".") && !isSystemFolder(f) && f.canRead())
                folders.add(f);
            else if (f.isFile() && isAudioFile(f)) audios.add(f);
        }
        Collections.sort(folders);
        Collections.sort(audios);
        for (File f : folders) { fileList.add(f); itemNames.add("📁 " + f.getName()); }
        for (File f : audios) { fileList.add(f); itemNames.add("♫ " + f.getName()); }
        txtHeader.setText("תיקייה: " + dir.getName());
        listView.setVisibility(android.view.View.VISIBLE);
        progressBar.setVisibility(android.view.View.GONE);
        setListAdapter();
    }

    private void setListAdapter() {
        listView.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, itemNames));
        if (!itemNames.isEmpty()) listView.setSelection(0);
        listView.requestFocus();
    }

    private void showPlayerPage() {
        screenPage = 1;
        listView.setVisibility(android.view.View.GONE);
        progressBar.setVisibility(android.view.View.VISIBLE);
        txtHeader.setText(recursiveQueue != null ? "נגן – ניגון תיקייה ותתי־תיקיות" : "נגן");
        txtCurrentFile.setText(currentAudioFile == null ? "לא נבחר שיר" : currentAudioFile.getName());
        txtSpeed.setText("מהירות: " + String.format(java.util.Locale.US, "%.1fx", currentSpeed));
    }

    private void showHistoryPage() {
        screenPage = 2;
        historyFolders = new ArrayList<>();
        itemNames = new ArrayList<>();
        List<Map<String, String>> rows = new ArrayList<>();
        String raw = prefs.getString("folder_history_v2", "");
        for (String path : raw.split("\\n")) {
            if (path.length() == 0) continue;
            File folder = new File(path);
            if (!folder.isDirectory() || !folder.exists()) continue;
            String key = folderKey(folder);
            String trackPath = prefs.getString(key + "_track", "");
            File track = new File(trackPath);
            int pos = trackPath.length() == 0 ? 0 : prefs.getInt(trackPath + "_pos", 0);
            float speed = prefs.getFloat(key + "_speed", 1.0f);
            historyFolders.add(folder);
            Map<String, String> row = new HashMap<>();
            row.put("title", folder.getName());
            row.put("subtitle", (trackPath.length() == 0 ? "ללא רצועה אחרונה" : track.getName()) +
                    "   •   " + formatTime(pos) + "   •   ×" +
                    String.format(java.util.Locale.US, "%.1f", speed));
            rows.add(row);
        }
        fileList = new ArrayList<>(historyFolders);
        txtHeader.setText("היסטוריה – תיקיות שמע אחרונות");
        listView.setVisibility(android.view.View.VISIBLE);
        progressBar.setVisibility(android.view.View.GONE);
        listView.setAdapter(new SimpleAdapter(this, rows, android.R.layout.simple_list_item_2,
                new String[]{"title", "subtitle"},
                new int[]{android.R.id.text1, android.R.id.text2}));
        if (!rows.isEmpty()) listView.setSelection(0);
        listView.requestFocus();
    }

    private void rememberFolder(File folder, File track) {
        if (folder == null || track == null) return;
        String key = folderKey(folder);
        String old = prefs.getString("folder_history_v2", "");
        ArrayList<String> paths = new ArrayList<>();
        paths.add(folder.getAbsolutePath());
        for (String p : old.split("\\n"))
            if (p.length() > 0 && !p.equals(folder.getAbsolutePath()) && paths.size() < 40) paths.add(p);
        StringBuilder b = new StringBuilder();
        for (String p : paths) { if (b.length() > 0) b.append("\n"); b.append(p); }
        prefs.edit().putString("folder_history_v2", b.toString())
                .putString(key + "_track", track.getAbsolutePath()).apply();
    }

    private void activatePosition(int position) {
        if (screenPage == 2) {
            if (position < 0 || position >= historyFolders.size()) return;
            File folder = historyFolders.get(position);
            String trackPath = prefs.getString(folderKey(folder) + "_track", "");
            loadDirectory(folder);
            File track = new File(trackPath);
            if (track.exists() && isAudioFile(track)) { recursiveQueue = null; recursiveRoot = null; playAudioFile(track); }
            return;
        }
        if (screenPage != 0 || position < 0 || position >= fileList.size()) return;
        File selected = fileList.get(position);
        if (selected.isDirectory()) loadDirectory(selected);
        else if (isAudioFile(selected)) { recursiveQueue = null; recursiveRoot = null; playAudioFile(selected); }
    }

    private void playAudioFile(final File file) {
        if (file == null || !file.exists()) return;
        if (audioPlayer != null) {
            saveCurrentPosition();
            audioPlayer.stopAndWait();
            audioPlayer = null;
        }
        File previousTrack = currentAudioFile;
        if (previousTrack != null && !previousTrack.equals(file)) { abStartMs = -1; abEndMs = -1; abLoopRestarting = false; }
        currentAudioFile = file;
        if (recursiveQueue != null && !recursiveQueue.contains(file)) { recursiveQueue = null; recursiveRoot = null; }
        playbackDir = recursiveQueue != null && recursiveRoot != null ? recursiveRoot : file.getParentFile();
        currentDir = playbackDir;
        rememberFolder(playbackDir, file);
        String key = folderKey(currentDir);
        currentSpeed = Math.max(0.5f, Math.min(5.0f,
                prefs.getFloat(key + "_speed", prefs.getFloat("global_speed", currentSpeed))));
        int savedPos = forcedStartPosition >= 0 ? forcedStartPosition :
                prefs.getInt(file.getAbsolutePath() + "_pos", 0);
        forcedStartPosition = -1;
        currentDurationMs = 0;
        lastPersistedPosition = savedPos;
        txtTime.setText(formatTime(savedPos) + " / --:--");
        txtCurrentFile.setText(file.getName());
        txtSpeed.setText("מהירות: " + String.format(java.util.Locale.US, "%.1fx", currentSpeed));
        showPlayerPage();
        try {
            audioManager.requestAudioFocus(audioFocusListener, AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN);
            audioPlayer = new SonicAudioPlayer(file.getAbsolutePath(), currentSpeed,
                    new SonicAudioPlayer.Listener() {
                @Override public void onProgress(int positionMs) {
                    MainActivity ui = activeInstance == null ? MainActivity.this : activeInstance;
                    ui.txtTime.setText(ui.formatTime(positionMs) + " / " + ui.formatTime(ui.currentDurationMs));
                    if (ui.currentDurationMs > 0)
                        ui.progressBar.setProgress((int)Math.max(0, Math.min(1000,
                                1000L * positionMs / ui.currentDurationMs)));
                    if (positionMs - ui.lastPersistedPosition >= 5000) {
                        ui.lastPersistedPosition = positionMs;
                        ui.persistFolderPosition(positionMs);
                    }
                    if (ui.abEndMs > ui.abStartMs && ui.abStartMs >= 0 &&
                            positionMs >= ui.abEndMs && !ui.abLoopRestarting) {
                        ui.abLoopRestarting = true;
                        ui.restartAtPosition(ui.abStartMs);
                    }
                }
                @Override public void onDuration(int durationMs) {
                    MainActivity ui = activeInstance == null ? MainActivity.this : activeInstance;
                    ui.currentDurationMs = durationMs;
                    ui.progressBar.setProgress(0);
                    ui.txtTime.setText(ui.formatTime(ui.audioPlayer == null ? 0 :
                            ui.audioPlayer.getPositionMs()) + " / " + ui.formatTime(durationMs));
                }
                @Override public void onCompleted() {
                    MainActivity ui = activeInstance == null ? MainActivity.this : activeInstance;
                    ui.isPlaying = false;
                    ui.audioPlayer = null;
                    if (ui.currentAudioFile != null) ui.persistFolderPosition(0);
                    if (!ui.playNextInDir()) {
                        PlaybackKeepAliveService.stop(ui);
                        ui.audioManager.abandonAudioFocus(ui.audioFocusListener);
                    }
                }
                @Override public void onError(Exception error) {
                    MainActivity ui = activeInstance == null ? MainActivity.this : activeInstance;
                    ui.isPlaying = false;
                    ui.audioPlayer = null;
                    PlaybackKeepAliveService.stop(ui);
                    ui.audioManager.abandonAudioFocus(ui.audioFocusListener);
                    Toast.makeText(ui, "שגיאה בנגינה: " + error.getMessage(),
                            Toast.LENGTH_LONG).show();
                }
            });
            audioPlayer.start(savedPos);
            handler.removeCallbacks(uiRefresh);
            handler.post(uiRefresh);
            abLoopRestarting = false;
            isPlaying = true;
            PlaybackKeepAliveService.start(this, file.getName());
            prefs.edit().putString("last_played", file.getAbsolutePath()).putFloat("global_speed", currentSpeed).apply();
        } catch (Exception e) {
            isPlaying = false;
            PlaybackKeepAliveService.stop(this);
            Toast.makeText(this, "שגיאה בפתיחת הרצועה: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void persistFolderPosition(int positionMs) {
        if (currentAudioFile == null || playbackDir == null) return;
        String key = folderKey(playbackDir);
        prefs.edit().putInt(currentAudioFile.getAbsolutePath() + "_pos", positionMs)
                .putString(key + "_track", currentAudioFile.getAbsolutePath())
                .putInt(key + "_pos", positionMs).putFloat(key + "_speed", currentSpeed)
                .putFloat("global_speed", currentSpeed).apply();
    }

    private void saveCurrentPosition() {
        if (audioPlayer != null && currentAudioFile != null) {
            persistFolderPosition(audioPlayer.getPositionMs());
        }
    }

    private String formatTime(int milliseconds) {
        int seconds = Math.max(0, milliseconds) / 1000;
        return String.format(java.util.Locale.US, "%02d:%02d", seconds / 60, seconds % 60);
    }

    private void pauseAudio() {
        if (audioPlayer != null && isPlaying) {
            audioPlayer.pause();
            isPlaying = false;
            saveCurrentPosition();
            PlaybackKeepAliveService.pause(this);
        }
    }

    private void resumeWithSmartJump() {
        if (audioPlayer != null && !isPlaying) {
            audioManager.requestAudioFocus(audioFocusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
            audioPlayer.resume();
            isPlaying = true;
            PlaybackKeepAliveService.start(this, currentAudioFile == null ? null : currentAudioFile.getName());
        } else if (currentAudioFile != null && !isPlaying) {
            playAudioFile(currentAudioFile);
        }
    }

    private boolean playNextInDir() {
        if (currentAudioFile == null) return false;
        if (recursiveQueue != null && !recursiveQueue.isEmpty()) {
            int index = recursiveQueue.indexOf(currentAudioFile);
            if (index >= 0 && index + 1 < recursiveQueue.size()) {
                recursiveIndex = index + 1;
                playAudioFile(recursiveQueue.get(recursiveIndex));
                return true;
            }
            return false;
        }
        List<File> audios = listAudioFiles(playbackDir);
        int index = audios.indexOf(currentAudioFile);
        if (index >= 0 && audios.size() > 1) {
            playAudioFile(audios.get((index + 1) % audios.size()));
            return true;
        }
        return false;
    }

    private boolean playPrevInDir() {
        if (currentAudioFile == null) return false;
        if (recursiveQueue != null && !recursiveQueue.isEmpty()) {
            int index = recursiveQueue.indexOf(currentAudioFile);
            if (index > 0) { playAudioFile(recursiveQueue.get(index - 1)); return true; }
            return false;
        }
        List<File> audios = listAudioFiles(playbackDir);
        int index = audios.indexOf(currentAudioFile);
        if (index >= 0 && audios.size() > 1) {
            playAudioFile(audios.get((index - 1 + audios.size()) % audios.size()));
            return true;
        }
        return false;
    }

    private List<File> listAudioFiles(File folder) {
        List<File> audios = new ArrayList<>();
        if (folder == null) return audios;
        File[] files = folder.listFiles();
        if (files != null) for (File f : files) if (f.isFile() && isAudioFile(f)) audios.add(f);
        Collections.sort(audios);
        return audios;
    }

    private void collectRecursive(File folder, List<File> result) {
        if (folder == null || !folder.isDirectory() || !folder.canRead()) return;
        File[] files = folder.listFiles();
        if (files == null) return;
        List<File> entries = new ArrayList<>();
        for (File f : files) {
            if (f.getName().startsWith(".") || isSystemFolder(f)) continue;
            if (f.isDirectory() || (f.isFile() && isAudioFile(f))) entries.add(f);
        }
        Collections.sort(entries);
        for (File f : entries) {
            if (f.isDirectory()) collectRecursive(f, result);
            else result.add(f);
        }
    }

    private void startRecursiveFolder(File folder) {
        recursiveRoot = folder;
        recursiveQueue = new ArrayList<>();
        collectRecursive(folder, recursiveQueue);
        recursiveIndex = 0;
        if (recursiveQueue.isEmpty()) {
            Toast.makeText(this, "לא נמצאו קובצי שמע בתיקייה", Toast.LENGTH_SHORT).show();
            recursiveQueue = null;
            recursiveRoot = null;
            return;
        }
        playbackDir = folder;
        currentDir = folder;
        Toast.makeText(this, "ניגון כל תתי־התיקיות לפי הסדר", Toast.LENGTH_SHORT).show();
        playAudioFile(recursiveQueue.get(0));
    }

    private void restartAtPosition(int positionMs) {
        if (currentAudioFile == null) return;
        boolean shouldResume = isPlaying;
        forcedStartPosition = Math.max(0, positionMs);
        playAudioFile(currentAudioFile);
        if (!shouldResume && audioPlayer != null) {
            audioPlayer.pause();
            isPlaying = false;
            PlaybackKeepAliveService.pause(this);
        }
    }

    private void markABPoint() {
        if (audioPlayer == null) return;
        int position = audioPlayer.getPositionMs();
        if (abStartMs < 0) {
            abStartMs = position;
            abEndMs = -1;
            abLoopRestarting = false;
            Toast.makeText(this, "נקודת A נשמרה: " + formatTime(position), Toast.LENGTH_SHORT).show();
        } else if (abEndMs < 0) {
            if (position > abStartMs) {
                abEndMs = position;
                Toast.makeText(this, "נקודת B נשמרה – חזרה בין A ל־B", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "נקודת B חייבת להיות אחרי A", Toast.LENGTH_SHORT).show();
            }
        } else {
            abStartMs = -1;
            abEndMs = -1;
            abLoopRestarting = false;
            Toast.makeText(this, "חזרה A–B בוטלה", Toast.LENGTH_SHORT).show();
        }
    }

    @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP: moveSelection(-1); return true;
            case KeyEvent.KEYCODE_DPAD_DOWN: moveSelection(1); return true;
            case KeyEvent.KEYCODE_STAR:
                if (screenPage == 1) markABPoint();
                else { starLongPressHandled = false; event.startTracking(); }
                return true;
            case KeyEvent.KEYCODE_5:
            case KeyEvent.KEYCODE_DPAD_CENTER:
                if (audioPlayer == null && currentAudioFile == null) activateSelectedItem();
                else if (isPlaying) pauseAudio(); else resumeWithSmartJump();
                return true;
            case KeyEvent.KEYCODE_1:
                if (audioPlayer != null) restartAtPosition(Math.max(0, audioPlayer.getPositionMs() - 5000));
                return true;
            case KeyEvent.KEYCODE_3:
                if (audioPlayer != null) restartAtPosition(audioPlayer.getPositionMs() + 5000);
                return true;
            case KeyEvent.KEYCODE_2:
                if (!isPlaying) moveSelection(-1);
                else audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI);
                return true;
            case KeyEvent.KEYCODE_8:
                if (!isPlaying) moveSelection(1);
                else audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI);
                return true;
            case KeyEvent.KEYCODE_7:
                currentSpeed = Math.max(0.5f, Math.round((currentSpeed - 0.1f) * 10f) / 10f);
                updateSpeed();
                return true;
            case KeyEvent.KEYCODE_9:
                currentSpeed = Math.min(5.0f, Math.round((currentSpeed + 0.1f) * 10f) / 10f);
                updateSpeed();
                return true;
            case KeyEvent.KEYCODE_0:
                currentSpeed = 1.0f;
                updateSpeed();
                return true;
            case KeyEvent.KEYCODE_4:
            case KeyEvent.KEYCODE_DPAD_LEFT: playPrevInDir(); return true;
            case KeyEvent.KEYCODE_6:
            case KeyEvent.KEYCODE_DPAD_RIGHT: playNextInDir(); return true;
            case KeyEvent.KEYCODE_POUND:
                if (screenPage == 0) showPlayerPage();
                else if (screenPage == 1) showHistoryPage();
                else if (playbackDir != null && playbackDir.exists()) loadDirectory(playbackDir);
                else if (currentDir != null && currentDir.exists()) loadDirectory(currentDir);
                else loadStorageRoots();
                return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_STAR && screenPage != 1) {
            if (!starLongPressHandled) activateSelectedItem();
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    @Override public boolean onKeyLongPress(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_STAR && screenPage == 0) {
            starLongPressHandled = true;
            int pos = listView.getSelectedItemPosition();
            if (pos >= 0 && pos < fileList.size() && fileList.get(pos).isDirectory()) {
                startRecursiveFolder(fileList.get(pos));
                return true;
            }
        }
        return super.onKeyLongPress(keyCode, event);
    }

    private void updateSpeed() {
        if (audioPlayer != null) audioPlayer.setSpeed(currentSpeed);
        txtSpeed.setText("מהירות: " + String.format(java.util.Locale.US, "%.1fx", currentSpeed));
        if (playbackDir != null) prefs.edit().putFloat(folderKey(playbackDir) + "_speed", currentSpeed).apply();
        prefs.edit().putFloat("global_speed", currentSpeed).apply();
        saveCurrentPosition();
    }

    private void moveSelection(int delta) {
        if (listView.getVisibility() != android.view.View.VISIBLE) return;
        int count = listView.getCount();
        if (count <= 0) return;
        int current = listView.getSelectedItemPosition();
        if (current < 0) current = 0;
        listView.setSelection((current + delta + count) % count);
        listView.requestFocus();
    }

    private void activateSelectedItem() {
        int position = listView.getSelectedItemPosition();
        activatePosition(position);
    }

    @Override public void onBackPressed() {
        moveTaskToBack(true);
    }

    @Override protected void onDestroy() {
        handler.removeCallbacks(uiRefresh);
        saveCurrentPosition();
        // The foreground service and playback worker continue after the UI is closed.
        super.onDestroy();
    }
}