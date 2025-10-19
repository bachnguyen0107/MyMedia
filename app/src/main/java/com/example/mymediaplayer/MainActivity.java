package com.example.mymediaplayer;

import android.content.Intent;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.PlaybackParams;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

public class MainActivity extends AppCompatActivity {

    private MediaPlayer mediaPlayer;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private SeekBar seekBar;
    private SeekBar volumeSeekBar;
    private AudioManager audioManager;
    private ImageButton playPauseButton;
    private ImageButton skipForwardButton;
    private ImageButton skipBackwardButton;
    private Button speedButton;
    private ImageButton muteButton;
    private TextView currentTimeText;
    private TextView totalTimeText;
    private TextView titleText;

    private boolean isMuted = false;
    private int previousVolume = 0;

    private float[] speeds = new float[]{1f, 1.5f, 0.75f};
    private int speedIndex = 0;

    private final Runnable updateProgress = new Runnable() {
        @Override
        public void run() {
            if (mediaPlayer != null && seekBar != null) {
                try {
                    int pos = mediaPlayer.getCurrentPosition();
                    seekBar.setProgress(pos);
                    if (currentTimeText != null) currentTimeText.setText(formatMs(pos));
                } catch (Exception ignored) {}
            }
            handler.postDelayed(this, 500);
        }
    };

    private ActivityResultLauncher<Intent> audioPickerLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        // view refs
        playPauseButton = findViewById(R.id.play_pause);
        skipForwardButton = findViewById(R.id.skip_forward);
        skipBackwardButton = findViewById(R.id.skip_backward);
        seekBar = findViewById(R.id.seek_bar);
        volumeSeekBar = findViewById(R.id.volume_seek_bar);
        speedButton = findViewById(R.id.speed_button);
        muteButton = findViewById(R.id.mute_button);
        currentTimeText = findViewById(R.id.current_time);
        totalTimeText = findViewById(R.id.total_time);
        titleText = findViewById(R.id.title_text);

        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);

        // volume seekbar setup
        if (audioManager != null && volumeSeekBar != null) {
            int maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            int curVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
            volumeSeekBar.setMax(maxVolume);
            volumeSeekBar.setProgress(curVolume);
            volumeSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    if (audioManager != null) {
                        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, progress, 0);
                        if (progress > 0) isMuted = false;
                    }
                }
                @Override public void onStartTrackingTouch(SeekBar seekBar) {}
                @Override public void onStopTrackingTouch(SeekBar seekBar) {}
            });
        }

        // Activity result launcher for picking audio from storage
        audioPickerLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                Uri uri = result.getData().getData();
                if (uri != null) {
                    prepareMediaPlayer(uri);
                }
            }
        });

        // Attempt to load a bundled raw resource named "sample_audio" if present
        int resId = getResources().getIdentifier("sample_audio", "raw", getPackageName());
        if (resId != 0) {
            prepareMediaPlayer(resId);
        } else {
            // no bundled audio found; prompt user to pick one
            titleText.setText(R.string.tap_load_audio);
            titleText.setOnClickListener(v -> openAudioPicker());
            // disable playback buttons until audio loaded
            setControlsEnabled(false);
        }

        // Play/pause
        if (playPauseButton != null) {
            playPauseButton.setOnClickListener(v -> {
                if (mediaPlayer == null) return;
                if (mediaPlayer.isPlaying()) {
                    mediaPlayer.pause();
                    playPauseButton.setImageResource(android.R.drawable.ic_media_play);
                } else {
                    mediaPlayer.start();
                    playPauseButton.setImageResource(android.R.drawable.ic_media_pause);
                    handler.post(updateProgress);
                }
            });
        }

        // Skip forward 15s
        if (skipForwardButton != null) {
            skipForwardButton.setOnClickListener(v -> {
                if (mediaPlayer == null) return;
                int pos = mediaPlayer.getCurrentPosition();
                int target = Math.min(pos + 15_000, mediaPlayer.getDuration());
                mediaPlayer.seekTo(target);
                if (seekBar != null) seekBar.setProgress(target);
            });
        }

        // Skip backward 15s
        if (skipBackwardButton != null) {
            skipBackwardButton.setOnClickListener(v -> {
                if (mediaPlayer == null) return;
                int pos = mediaPlayer.getCurrentPosition();
                int target = Math.max(pos - 15_000, 0);
                mediaPlayer.seekTo(target);
                if (seekBar != null) seekBar.setProgress(target);
            });
        }

        // SeekBar position changes
        if (seekBar != null) {
            seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    if (fromUser && mediaPlayer != null) {
                        mediaPlayer.seekTo(progress);
                        if (currentTimeText != null) currentTimeText.setText(formatMs(progress));
                    }
                }
                @Override public void onStartTrackingTouch(SeekBar seekBar) {}
                @Override public void onStopTrackingTouch(SeekBar seekBar) {}
            });
        }

        // Mute/unmute
        if (muteButton != null) {
            muteButton.setOnClickListener(v -> {
                if (audioManager == null) return;
                if (!isMuted) {
                    previousVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0);
                    if (volumeSeekBar != null) volumeSeekBar.setProgress(0);
                    isMuted = true;
                    muteButton.setImageResource(android.R.drawable.ic_lock_silent_mode);
                } else {
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, previousVolume, 0);
                    if (volumeSeekBar != null) volumeSeekBar.setProgress(previousVolume);
                    isMuted = false;
                    muteButton.setImageResource(android.R.drawable.ic_lock_silent_mode_off);
                }
            });
        }

        // Playback speed
        if (speedButton != null) {
            speedButton.setOnClickListener(v -> {
                if (mediaPlayer == null) return;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    speedIndex = (speedIndex + 1) % speeds.length;
                    float nextSpeed = speeds[speedIndex];
                    try {
                        PlaybackParams params = mediaPlayer.getPlaybackParams();
                        params.setSpeed(nextSpeed);
                        mediaPlayer.setPlaybackParams(params);
                        speedButton.setText(String.format(Locale.US, "%.2fx", nextSpeed));
                    } catch (Exception e) {
                        Toast.makeText(this, "Playback speed not supported on this device", Toast.LENGTH_SHORT).show();
                    }
                } else {
                    Toast.makeText(this, "Requires Android M+ for speed control", Toast.LENGTH_SHORT).show();
                }
            });
        }
    }

    private void openAudioPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("audio/*");
        try {
            audioPickerLauncher.launch(intent);
        } catch (Exception e) {
            Toast.makeText(this, "Unable to open picker", Toast.LENGTH_SHORT).show();
        }
    }

    private void prepareMediaPlayer(int resId) {
        try {
            releasePlayer();
            mediaPlayer = MediaPlayer.create(this, resId);
            if (mediaPlayer == null) {
                Toast.makeText(this, "Failed to load bundled audio", Toast.LENGTH_SHORT).show();
                return;
            }
            afterPlayerPrepared("Bundled audio");
        } catch (Exception e) {
            Toast.makeText(this, "Error preparing media: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void prepareMediaPlayer(@NonNull Uri uri) {
        try {
            releasePlayer();
            mediaPlayer = MediaPlayer.create(this, uri);
            if (mediaPlayer == null) {
                Toast.makeText(this, "Failed to load audio", Toast.LENGTH_SHORT).show();
                return;
            }
            afterPlayerPrepared(uri.getLastPathSegment());
        } catch (Exception e) {
            Toast.makeText(this, "Error preparing media: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void afterPlayerPrepared(String title) {
        setControlsEnabled(true);
        if (titleText != null) titleText.setText(title == null ? "Track" : title);
        if (seekBar != null) seekBar.setMax(mediaPlayer.getDuration());
        if (totalTimeText != null) totalTimeText.setText(formatMs(mediaPlayer.getDuration()));
        handler.post(updateProgress);

        mediaPlayer.setOnCompletionListener(mp -> {
            if (playPauseButton != null) playPauseButton.setImageResource(android.R.drawable.ic_media_play);
            if (seekBar != null) seekBar.setProgress(0);
            handler.removeCallbacks(updateProgress);
        });
    }

    private void setControlsEnabled(boolean enabled) {
        if (playPauseButton != null) playPauseButton.setEnabled(enabled);
        if (skipForwardButton != null) skipForwardButton.setEnabled(enabled);
        if (skipBackwardButton != null) skipBackwardButton.setEnabled(enabled);
        if (seekBar != null) seekBar.setEnabled(enabled);
        if (volumeSeekBar != null) volumeSeekBar.setEnabled(enabled);
        if (speedButton != null) speedButton.setEnabled(enabled);
        if (muteButton != null) muteButton.setEnabled(enabled);
    }

    private String formatMs(int ms) {
        int totalSeconds = ms / 1000;
        int minutes = totalSeconds / 60;
        int seconds = totalSeconds % 60;
        return String.format(Locale.US, "%d:%02d", minutes, seconds);
    }

    private void releasePlayer() {
        handler.removeCallbacks(updateProgress);
        if (mediaPlayer != null) {
            try {
                if (mediaPlayer.isPlaying()) mediaPlayer.stop();
            } catch (Exception ignored) {}
            try { mediaPlayer.release(); } catch (Exception ignored) {}
            mediaPlayer = null;
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        releasePlayer();
    }
}