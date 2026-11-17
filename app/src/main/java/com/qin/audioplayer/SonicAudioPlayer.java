package com.qin.audioplayer;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.os.Handler;
import android.os.Looper;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;
import sonic.Sonic;

/**
 * Android 4.4-compatible decoder/output pipeline. MediaCodec decodes compressed
 * audio to PCM; Sonic time-stretches PCM; AudioTrack plays the processed stream.
 */
public final class SonicAudioPlayer {
    public interface Listener {
        void onProgress(int positionMs);
        void onDuration(int durationMs);
        void onCompleted();
        void onError(Exception error);
    }

    private final String path;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicBoolean stopped = new AtomicBoolean(false);
    private final Object pauseLock = new Object();
    private volatile boolean paused;
    private volatile float speed = 1.0f;
    private volatile int positionMs;
    private Thread worker;
    private AudioTrack audioTrack;

    public SonicAudioPlayer(String path, float initialSpeed, Listener listener) {
        this.path = path;
        this.speed = initialSpeed;
        this.listener = listener;
    }

    public void start(final int startMs) {
        worker = new Thread(new Runnable() {
            @Override public void run() { decodeAndPlay(startMs); }
        }, "Qin-Sonic-Playback");
        worker.start();
    }

    public void setSpeed(float value) { speed = Math.max(0.5f, Math.min(5.0f, value)); }
    public int getPositionMs() { return positionMs; }

    public void pause() {
        paused = true;
        if (audioTrack != null) audioTrack.pause();
    }

    public void resume() {
        synchronized (pauseLock) {
            paused = false;
            pauseLock.notifyAll();
        }
        if (audioTrack != null) audioTrack.play();
    }

    public void stopAndWait() {
        stop();
        if (worker != null && worker != Thread.currentThread()) {
            try { worker.join(1200); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }

    public void stop() {
        stopped.set(true);
        synchronized (pauseLock) {
            paused = false;
            pauseLock.notifyAll();
        }
        if (audioTrack != null) {
            try { audioTrack.pause(); audioTrack.flush(); } catch (Exception ignored) {}
        }
        if (worker != null) worker.interrupt();
    }

    private void waitIfPaused() throws InterruptedException {
        synchronized (pauseLock) {
            while (paused && !stopped.get()) pauseLock.wait();
        }
    }

    private void notifyError(final Exception error) {
        main.post(new Runnable() { @Override public void run() { if (!stopped.get()) listener.onError(error); }});
    }

    private void decodeAndPlay(int startMs) {
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec decoder = null;
        Sonic sonic = null;
        boolean completed = false;
        try {
            extractor.setDataSource(path);
            int audioTrackIndex = -1;
            MediaFormat inputFormat = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat f = extractor.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    audioTrackIndex = i;
                    inputFormat = f;
                    break;
                }
            }
            if (audioTrackIndex < 0) throw new IOException("No audio track");
            if (inputFormat.containsKey(MediaFormat.KEY_DURATION)) {
                final int duration = (int)(inputFormat.getLong(MediaFormat.KEY_DURATION) / 1000L);
                main.post(new Runnable() { @Override public void run() { if (!stopped.get()) listener.onDuration(duration); }});
            }
            extractor.selectTrack(audioTrackIndex);
            if (startMs > 0) extractor.seekTo(startMs * 1000L, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);

            String mime = inputFormat.getString(MediaFormat.KEY_MIME);
            decoder = MediaCodec.createDecoderByType(mime);
            decoder.configure(inputFormat, null, null, 0);
            decoder.start();

            ByteBuffer[] inputBuffers = decoder.getInputBuffers();
            ByteBuffer[] outputBuffers = decoder.getOutputBuffers();
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputDone = false, outputDone = false;
            long sourceFramesSubmitted = 0L;
            long outputFramesWritten = 0L;
            int lastProgressReport = -1;
            int sampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE);
            int channels = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
            int channelMask = channels == 1 ? AudioFormat.CHANNEL_OUT_MONO : AudioFormat.CHANNEL_OUT_STEREO;
            int minBuffer = AudioTrack.getMinBufferSize(sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT);
            if (minBuffer <= 0) throw new IOException("Unsupported PCM output format");
            audioTrack = new AudioTrack(AudioManager.STREAM_MUSIC, sampleRate, channelMask,
                    AudioFormat.ENCODING_PCM_16BIT, Math.max(minBuffer * 4, 8192), AudioTrack.MODE_STREAM);
            if (audioTrack.getState() != AudioTrack.STATE_INITIALIZED) throw new IOException("AudioTrack initialization failed");
            sonic = new Sonic(sampleRate, channels);
            sonic.setQuality(0);
            audioTrack.play();

            byte[] processed = new byte[Math.max(8192, minBuffer * 2)];
            while (!outputDone && !stopped.get()) {
                waitIfPaused();
                if (stopped.get()) break;

                if (!inputDone) {
                    int inputIndex = decoder.dequeueInputBuffer(10000);
                    if (inputIndex >= 0) {
                        ByteBuffer input = inputBuffers[inputIndex];
                        input.clear();
                        int sampleSize = extractor.readSampleData(input, 0);
                        if (sampleSize < 0) {
                            decoder.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            long pts = extractor.getSampleTime();
                            decoder.queueInputBuffer(inputIndex, 0, sampleSize, pts, 0);
                            extractor.advance();
                        }
                    }
                }

                int outputIndex = decoder.dequeueOutputBuffer(info, 10000);
                if (outputIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                    outputBuffers = decoder.getOutputBuffers();
                } else if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat out = decoder.getOutputFormat();
                    sampleRate = out.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    channels = out.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    // Recreate output stages if the decoder changed PCM characteristics.
                    audioTrack.pause();
                    audioTrack.flush();
                    audioTrack.release();
                    channelMask = channels == 1 ? AudioFormat.CHANNEL_OUT_MONO : AudioFormat.CHANNEL_OUT_STEREO;
                    minBuffer = AudioTrack.getMinBufferSize(sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT);
                    audioTrack = new AudioTrack(AudioManager.STREAM_MUSIC, sampleRate, channelMask,
                            AudioFormat.ENCODING_PCM_16BIT, Math.max(minBuffer * 4, 8192), AudioTrack.MODE_STREAM);
                    sonic = new Sonic(sampleRate, channels);
                    audioTrack.play();
                } else if (outputIndex >= 0) {
                    ByteBuffer output = outputBuffers[outputIndex];
                    if (info.size > 0) {
                        positionMs = (int)(info.presentationTimeUs / 1000L);
                        final int p = positionMs;
                        main.post(new Runnable() { @Override public void run() { if (!stopped.get()) listener.onProgress(p); }});
                        output.position(info.offset);
                        output.limit(info.offset + info.size);
                        byte[] pcm = new byte[info.size];
                        output.get(pcm);
                        sourceFramesSubmitted += pcm.length / (2L * channels);
                        sonic.setSpeed(speed);
                        sonic.writeBytesToStream(pcm, pcm.length);
                        int count;
                        while ((count = sonic.readBytesFromStream(processed, processed.length)) > 0) {
                            waitIfPaused();
                            if (stopped.get()) break;
                            int offset = 0;
                            while (offset < count && !stopped.get()) {
                                waitIfPaused();
                                int written = audioTrack.write(processed, offset, count - offset);
                                if (written < 0) throw new IOException("AudioTrack write failed: " + written);
                                if (written == 0) throw new IOException("AudioTrack returned zero bytes");
                                offset += written;
                                outputFramesWritten += written / (2L * channels);
                            }
                            long playedFrames = audioTrack.getPlaybackHeadPosition() & 0xffffffffL;
                            if (outputFramesWritten > 0 && sourceFramesSubmitted > 0) {
                                positionMs = startMs + (int)(playedFrames * sourceFramesSubmitted * 1000L /
                                        (outputFramesWritten * (long)sampleRate));
                                if (positionMs - lastProgressReport >= 250) {
                                    lastProgressReport = positionMs;
                                    final int progress = positionMs;
                                    main.post(new Runnable() { @Override public void run() {
                                        if (!stopped.get()) listener.onProgress(progress);
                                    }});
                                }
                            }
                        }
                    }
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        sonic.flushStream();
                        int count;
                        while ((count = sonic.readBytesFromStream(processed, processed.length)) > 0 && !stopped.get()) {
                            waitIfPaused();
                            int offset = 0;
                            while (offset < count && !stopped.get()) {
                                int flushed = audioTrack.write(processed, offset, count - offset);
                                if (flushed < 0) throw new IOException("AudioTrack flush write failed: " + flushed);
                                if (flushed == 0) throw new IOException("AudioTrack returned zero bytes");
                                offset += flushed;
                            }
                        }
                        outputDone = true;
                    }
                    decoder.releaseOutputBuffer(outputIndex, false);
                }
            }
            if (!stopped.get()) {
                audioTrack.stop();
                completed = true;
            }
        } catch (Exception e) {
            notifyError(e);
        } finally {
            try { if (decoder != null) { decoder.stop(); decoder.release(); } } catch (Exception ignored) {}
            try { extractor.release(); } catch (Exception ignored) {}
            try { if (audioTrack != null) audioTrack.release(); } catch (Exception ignored) {}
            if (completed && !stopped.get()) {
                main.post(new Runnable() { @Override public void run() { if (!stopped.get()) listener.onCompleted(); }});
            }
        }
    }
}
