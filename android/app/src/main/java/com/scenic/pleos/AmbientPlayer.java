package com.scenic.pleos;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

/**
 * Local, original synthesized scene music and user-selected SAF audio playback.
 * No recording, network request, account, media library permission, or external asset is used.
 * Call playScene/playUri only after an explicit user playback action. Pause in onStop,
 * and release in onDestroy. All public methods are safe to call more than once.
 */
public final class AmbientPlayer {
    private static final int SAMPLE_RATE = 22050;
    private static final int LOOP_SECONDS = 12;
    private final Context context;
    private final AudioManager audioManager;
    private final AudioAttributes attributes;
    private final AudioFocusRequest focusRequest;
    private AudioTrack track;
    private MediaPlayer mediaPlayer;
    private boolean focused;
    private boolean preparing;
    private boolean released;
    private String label = "음악을 시작해 주세요";

    public AmbientPlayer(Context context) {
        this.context = context.getApplicationContext();
        audioManager = (AudioManager) this.context.getSystemService(Context.AUDIO_SERVICE);
        attributes = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build();
        focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attributes)
                .setAcceptsDelayedFocusGain(false)
                .setWillPauseWhenDucked(true)
                .setOnAudioFocusChangeListener(change -> {
                    // Navigation/phone calls take priority; resuming requires a user action.
                    if (change == AudioManager.AUDIOFOCUS_LOSS
                            || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
                            || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
                        pause();
                    }
                }, new Handler(Looper.getMainLooper()))
                .build();
    }

    /** Plays a generated 12-second loop. Returns false if audio focus or audio output fails. */
    public synchronized boolean playScene(String scene) {
        if (released) return false;
        stopSources();
        abandonFocus();
        if (!requestFocus()) return false;
        final String selected = "sea".equals(scene) ? "sea" : "night".equals(scene) ? "night" : "lake";
        try {
            short[] samples = synthesize(selected);
            track = new AudioTrack.Builder()
                    .setAudioAttributes(attributes)
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build())
                    .setBufferSizeInBytes(samples.length * 2)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build();
            if (track.getState() == AudioTrack.STATE_UNINITIALIZED
                    || track.write(samples, 0, samples.length) != samples.length
                    || track.setLoopPoints(0, samples.length, -1) != AudioTrack.SUCCESS) {
                throw new IllegalStateException("Audio output unavailable");
            }
            track.play();
            label = "sea".equals(selected) ? "해안 · Azure Waves"
                    : "night".equals(selected) ? "야경 · City Afterglow" : "호수 · Still Water";
            return true;
        } catch (RuntimeException error) {
            stopSources();
            abandonFocus();
            label = "음악 재생을 시작할 수 없어요";
            return false;
        }
    }

    /**
     * Starts asynchronous preparation of a user-selected audio document.
     * True means the request was accepted; isPlaying() becomes true when prepared.
     * The activity owns the SAF URI grant and any persisted metadata.
     */
    public synchronized boolean playUri(Uri uri) {
        if (released || uri == null) return false;
        stopSources();
        abandonFocus();
        if (!requestFocus()) return false;
        try {
            MediaPlayer player = new MediaPlayer();
            mediaPlayer = player;
            preparing = true;
            label = "내 음악 · 준비 중";
            player.setAudioAttributes(attributes);
            player.setDataSource(context, uri);
            player.setLooping(true);
            player.setOnPreparedListener(prepared -> {
                synchronized (AmbientPlayer.this) {
                    if (released || mediaPlayer != prepared || !focused || !preparing) return;
                    preparing = false;
                    try {
                        prepared.start();
                        label = "내 음악 · 선택한 오디오";
                    } catch (RuntimeException error) {
                        stopSources();
                        abandonFocus();
                        label = "선택한 음악을 재생할 수 없어요";
                    }
                }
            });
            player.setOnErrorListener((failed, what, extra) -> {
                synchronized (AmbientPlayer.this) {
                    if (mediaPlayer == failed) {
                        stopSources();
                        abandonFocus();
                        label = "지원하지 않거나 열 수 없는 음악 파일이에요";
                    }
                }
                return true;
            });
            player.prepareAsync();
            return true;
        } catch (Exception error) {
            stopSources();
            abandonFocus();
            label = "음악 파일 접근 권한을 다시 확인해 주세요";
            return false;
        }
    }

    public synchronized void pause() {
        // Releasing a still-preparing player prevents its callback from playing after a pause.
        if (preparing) stopSources();
        try {
            if (track != null && track.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) track.pause();
        } catch (RuntimeException ignored) { }
        try {
            if (mediaPlayer != null && mediaPlayer.isPlaying()) mediaPlayer.pause();
        } catch (RuntimeException ignored) { }
        abandonFocus();
    }

    public synchronized void release() {
        if (released) return;
        released = true;
        stopSources();
        abandonFocus();
    }

    public synchronized boolean isPlaying() {
        try {
            return (track != null && track.getPlayState() == AudioTrack.PLAYSTATE_PLAYING)
                    || (mediaPlayer != null && !preparing && mediaPlayer.isPlaying());
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    public synchronized String getLabel() { return label; }

    private boolean requestFocus() {
        if (audioManager == null) {
            label = "오디오 출력을 사용할 수 없어요";
            return false;
        }
        try {
            focused = audioManager.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        } catch (RuntimeException ignored) {
            focused = false;
        }
        if (!focused) label = "다른 오디오가 끝나면 음악을 다시 시작해 주세요";
        return focused;
    }

    private void abandonFocus() {
        boolean hadFocus = focused;
        focused = false;
        if (hadFocus && audioManager != null) {
            try { audioManager.abandonAudioFocusRequest(focusRequest); }
            catch (RuntimeException ignored) { }
        }
    }

    private void stopSources() {
        preparing = false;
        AudioTrack previousTrack = track;
        track = null;
        if (previousTrack != null) {
            try { previousTrack.stop(); } catch (RuntimeException ignored) { }
            try { previousTrack.release(); } catch (RuntimeException ignored) { }
        }
        MediaPlayer previousPlayer = mediaPlayer;
        mediaPlayer = null;
        if (previousPlayer != null) {
            try { previousPlayer.setOnPreparedListener(null); } catch (RuntimeException ignored) { }
            try { previousPlayer.setOnErrorListener(null); } catch (RuntimeException ignored) { }
            try { previousPlayer.release(); } catch (RuntimeException ignored) { }
        }
    }

    /** Sine-based original pad and bell arrangement, with a quiet and click-free loop seam. */
    private static short[] synthesize(String scene) {
        final double[] notes;
        final double brightness;
        if ("sea".equals(scene)) {
            notes = new double[] {146.832, 220.0, 293.665, 329.628}; // Dsus2
            brightness = 0.12;
        } else if ("night".equals(scene)) {
            notes = new double[] {110.0, 164.814, 195.998, 246.942}; // Am9 colors
            brightness = 0.18;
        } else {
            notes = new double[] {130.813, 164.814, 195.998, 293.665}; // Cmaj9 colors
            brightness = 0.08;
        }
        short[] samples = new short[SAMPLE_RATE * LOOP_SECONDS];
        final double twoPi = 2.0 * Math.PI;
        for (int i = 0; i < samples.length; i++) {
            double t = i / (double) SAMPLE_RATE;
            double pad = 0.0;
            for (int voice = 0; voice < notes.length; voice++) {
                double frequency = notes[voice];
                double breath = 0.76 + 0.24 * Math.sin(twoPi * t / 6.0 + voice);
                pad += Math.sin(twoPi * frequency * t) * breath;
            }
            // Four sparse bell tones in each loop, completely generated in process.
            int step = (int) (t / 3.0);
            double age = t - step * 3.0;
            double bellEnvelope = (1.0 - Math.exp(-age * 14.0)) * Math.exp(-age * 1.4);
            double frequency = notes[(step + 2) % notes.length] * 2.0;
            double bell = (Math.sin(twoPi * frequency * t)
                    + brightness * Math.sin(twoPi * frequency * 2.0 * t)) * bellEnvelope;
            double seam = Math.min(1.0, Math.min(t / 0.12, (LOOP_SECONDS - t) / 0.12));
            seam = Math.max(0.0, seam);
            double value = (pad * 0.045 + bell * 0.075) * seam;
            samples[i] = (short) Math.round(Math.max(-0.8, Math.min(0.8, value)) * 32767.0);
        }
        return samples;
    }
}
