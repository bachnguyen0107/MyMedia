package com.example.mymediaplayer;

import android.annotation.SuppressLint;
import android.content.ContentUris;
import android.content.Intent;
import android.database.Cursor;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.PlaybackParams;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.example.mymediaplayer.data.AppDatabase;
import com.example.mymediaplayer.data.PlaylistDao;
import com.example.mymediaplayer.data.PlaylistEntity;
import com.example.mymediaplayer.data.PlaylistItemDao;
import com.example.mymediaplayer.data.PlaylistItemEntity;
import com.example.mymediaplayer.data.SessionManager;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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

    // Account bar
    private TextView accountLabel;
    private Button signOutButton;

    private boolean isMuted = false;
    private int previousVolume = 0;

    private final float[] speeds = new float[]{1f, 1.5f, 0.75f};
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
    private ActivityResultLauncher<String[]> permissionLauncher;

    private final List<MediaItem> library = new ArrayList<>();
    private final List<MediaItem> filteredLibrary = new ArrayList<>();
    private final List<MediaItem> playQueue = new ArrayList<>();

    // Persistent storage for per-user playlists
    private AppDatabase db;
    private PlaylistDao playlistDao;
    private PlaylistItemDao playlistItemDao;
    private SessionManager sessionManager;
    private int currentUserId = -1;

    // Queue playback state
    private boolean playingFromQueue = false;
    private int queueIndex = -1;

    // Debounce handler for search
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private Runnable pendingSearch;

    @SuppressLint("DiscouragedApi")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Require login
        sessionManager = new SessionManager(this);
        if (!sessionManager.isLoggedIn()) {
            startActivity(new Intent(this, LoginActivity.class));
            finish();
            return;
        }
        currentUserId = sessionManager.getUserId();

        setContentView(R.layout.activity_main);

        // init DB
        db = AppDatabase.getInstance(this);
        playlistDao = db.playlistDao();
        playlistItemDao = db.playlistItemDao();

        // register permission launcher
        permissionLauncher = registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), result -> {
            boolean granted = false;
            for (Map.Entry<String, Boolean> e : result.entrySet()) {
                if (Boolean.TRUE.equals(e.getValue())) { granted = true; break; }
            }
            if (granted) {
                loadLibraryFromMediaStore();
                openLibraryDialog();
            } else {
                Toast.makeText(this, "Permission required to load media library", Toast.LENGTH_SHORT).show();
            }
        });

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
        accountLabel = findViewById(R.id.tv_account_label);
        signOutButton = findViewById(R.id.btn_sign_out);

        // account bar
        if (accountLabel != null) {
            String username = sessionManager.getUsername();
            accountLabel.setText(getString(R.string.account_label, username));
        }
        if (signOutButton != null) {
            signOutButton.setOnClickListener(v -> {
                sessionManager.logout();
                Toast.makeText(this, R.string.signed_out, Toast.LENGTH_SHORT).show();
                Intent i = new Intent(this, LoginActivity.class);
                i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                startActivity(i);
                finish();
            });
        }

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
                if (playingFromQueue && canSkipNext()) {
                    skipToNextInQueue();
                } else {
                    if (mediaPlayer == null) return;
                    int pos = mediaPlayer.getCurrentPosition();
                    int target = Math.min(pos + 15_000, mediaPlayer.getDuration());
                    mediaPlayer.seekTo(target);
                    if (seekBar != null) seekBar.setProgress(target);
                }
            });
        }

        // Skip backward 15s or previous track if in queue
        if (skipBackwardButton != null) {
            skipBackwardButton.setOnClickListener(v -> {
                if (playingFromQueue && canSkipPrev()) {
                    skipToPreviousInQueue();
                } else {
                    if (mediaPlayer == null) return;
                    int pos = mediaPlayer.getCurrentPosition();
                    int target = Math.max(pos - 15_000, 0);
                    mediaPlayer.seekTo(target);
                    if (seekBar != null) seekBar.setProgress(target);
                }
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
            });
        }

        // Wire library button
        Button libraryButton = findViewById(R.id.library_button);
        if (libraryButton != null) {
            libraryButton.setOnClickListener(v -> checkPermissionAndOpenLibrary());
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
            if (playingFromQueue && queueIndex >= 0 && queueIndex + 1 < playQueue.size()) {
                queueIndex++;
                MediaItem next = playQueue.get(queueIndex);
                if (next != null) {
                    prepareMediaPlayer(next.contentUri);
                    if (mediaPlayer != null) mediaPlayer.start();
                    if (titleText != null) titleText.setText(next.title);
                    return;
                }
            }
            // End of single track or queue; reset UI
            if (playPauseButton != null) playPauseButton.setImageResource(android.R.drawable.ic_media_play);
            if (seekBar != null) seekBar.setProgress(0);
            handler.removeCallbacks(updateProgress);
            playingFromQueue = false;
            queueIndex = -1;
        });
    }

    // Start playing the provided list as a queue from the beginning
    private void startQueuePlayback(@NonNull List<MediaItem> list, @NonNull String playlistName) {
        if (list.isEmpty()) {
            Toast.makeText(this, getString(R.string.playlist_empty), Toast.LENGTH_SHORT).show();
            return;
        }
        playQueue.clear();
        playQueue.addAll(list);
        queueIndex = 0;
        playingFromQueue = true;
        MediaItem first = playQueue.get(0);
        prepareMediaPlayer(first.contentUri);
        if (mediaPlayer != null) mediaPlayer.start();
        Toast.makeText(this, getString(R.string.playing_playlist, playlistName), Toast.LENGTH_SHORT).show();
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

    // MediaItem model
    private static class MediaItem {
        final long id;
        final String title;
        final String artist;
        final String album;
        final long dateAdded;
        final Uri contentUri;

        MediaItem(long id, String title, String artist, String album, long dateAdded, Uri contentUri) {
            this.id = id;
            this.title = title;
            this.artist = artist;
            this.album = album;
            this.dateAdded = dateAdded;
            this.contentUri = contentUri;
        }

        @NonNull
        @Override
        public String toString() {
            if (artist != null && !artist.isEmpty()) return title + " — " + artist;
            return title;
        }
    }

    // Adapter: library rows with Add to Queue and Add to Playlist buttons
    private class LibraryAdapter extends ArrayAdapter<MediaItem> {
        public LibraryAdapter(android.content.Context ctx, List<MediaItem> items) {
            super(ctx, 0, items);
        }

        @NonNull
        @Override
        public View getView(int position, @Nullable View convertView, @NonNull android.view.ViewGroup parent) {
            View v = convertView;
            if (v == null) {
                v = getLayoutInflater().inflate(R.layout.item_library, parent, false);
            }
            MediaItem item = getItem(position);
            TextView title = v.findViewById(R.id.item_title);
            android.widget.ImageButton btnAdd = v.findViewById(R.id.btn_add);

            title.setText(item == null ? "" : item.title + (item.artist != null && !item.artist.isEmpty() ? " — " + item.artist : ""));

            // Tag the view with the current item for reliable retrieval on click
            v.setTag(item);
            v.setOnClickListener(view -> {
                MediaItem m = (MediaItem) view.getTag();
                if (m != null) {
                    // exit queue mode when playing a single item from library
                    playingFromQueue = false;
                    queueIndex = -1;
                    prepareMediaPlayer(m.contentUri);
                    if (mediaPlayer != null) mediaPlayer.start();
                    Toast.makeText(MainActivity.this, getString(R.string.playing_prefix) + m.title, Toast.LENGTH_SHORT).show();
                }
            });

            btnAdd.setOnClickListener(view -> {
                if (item == null) return;
                PopupMenu popup = new PopupMenu(MainActivity.this, view);
                popup.getMenu().add(0, 1, 0, getString(R.string.add_to_queue));
                popup.getMenu().add(0, 2, 1, getString(R.string.add_to_playlist));
                popup.setOnMenuItemClickListener(menuItem -> {
                    int id = menuItem.getItemId();
                    if (id == 1) {
                        playQueue.add(item);
                        Toast.makeText(MainActivity.this, getString(R.string.added_to, getString(R.string.queue)), Toast.LENGTH_SHORT).show();
                        return true;
                    } else if (id == 2) {
                        showAddToPlaylistDialog(item);
                        return true;
                    }
                    return false;
                });
                popup.show();
            });

            return v;
        }
    }

    // Adapter: rows with remove icon, backed by a mutable list
    private class RemoveAdapter extends ArrayAdapter<MediaItem> {
        private final List<MediaItem> items;
        public RemoveAdapter(android.content.Context ctx, List<MediaItem> items) {
            super(ctx, 0, items);
            this.items = items;
        }

        @NonNull
        @Override
        public View getView(int position, @Nullable View convertView, @NonNull android.view.ViewGroup parent) {
            View v = convertView;
            if (v == null) {
                v = getLayoutInflater().inflate(R.layout.item_with_remove, parent, false);
            }
            MediaItem item = getItem(position);
            TextView title = v.findViewById(R.id.item_title);
            android.widget.ImageButton btnRemove = v.findViewById(R.id.btn_remove);

            title.setText(item == null ? "" : item.title + (item.artist != null && !item.artist.isEmpty() ? " — " + item.artist : ""));

            btnRemove.setOnClickListener(view -> {
                if (position >= 0 && position < items.size()) {
                    items.remove(position);
                    notifyDataSetChanged();
                }
            });

            return v;
        }
    }

    // Load device media into `library` on background thread
    private void loadLibraryFromMediaStore() {
        new Thread(() -> {
            List<MediaItem> tmp = new ArrayList<>();
            Uri uri = android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
            String[] projection = new String[]{
                    android.provider.MediaStore.Audio.Media._ID,
                    android.provider.MediaStore.Audio.Media.TITLE,
                    android.provider.MediaStore.Audio.Media.ARTIST,
                    android.provider.MediaStore.Audio.Media.ALBUM,
                    android.provider.MediaStore.Audio.Media.DATE_ADDED
            };
            Cursor c = null;
            try {
                c = getContentResolver().query(uri, projection, null, null, null);
                if (c != null) {
                    int idCol = c.getColumnIndexOrThrow(android.provider.MediaStore.Audio.Media._ID);
                    int titleCol = c.getColumnIndexOrThrow(android.provider.MediaStore.Audio.Media.TITLE);
                    int artistCol = c.getColumnIndexOrThrow(android.provider.MediaStore.Audio.Media.ARTIST);
                    int albumCol = c.getColumnIndexOrThrow(android.provider.MediaStore.Audio.Media.ALBUM);
                    int dateCol = c.getColumnIndexOrThrow(android.provider.MediaStore.Audio.Media.DATE_ADDED);
                    while (c.moveToNext()) {
                        long id = c.getLong(idCol);
                        String title = c.getString(titleCol);
                        String artist = c.getString(artistCol);
                        String album = c.getString(albumCol);
                        long dateAdded = c.getLong(dateCol);
                        Uri contentUri = ContentUris.withAppendedId(uri, id);
                        tmp.add(new MediaItem(id, title == null ? "Unknown" : title, artist, album, dateAdded, contentUri));
                    }
                }
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "Failed to load media: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            } finally {
                if (c != null) c.close();
            }

            // update library on UI thread
            runOnUiThread(() -> {
                library.clear();
                library.addAll(tmp);
                // default filtered view = library
                filteredLibrary.clear();
                filteredLibrary.addAll(library);
            });
        }).start();
    }

    // Open a dialog that shows library with search + sort + item actions
    private void openLibraryDialog() {
        View dlgView = getLayoutInflater().inflate(R.layout.dialog_media_library, null);
        android.widget.SearchView searchView = dlgView.findViewById(R.id.dialog_search);
        Spinner sortSpinner = dlgView.findViewById(R.id.dialog_sort_spinner);
        ListView listView = dlgView.findViewById(R.id.dialog_list);
        Button btnQueue = dlgView.findViewById(R.id.btn_show_queue);
        Button btnPlaylists = dlgView.findViewById(R.id.btn_manage_playlists);

        // Adapter for list view showing title — artist with action buttons
        LibraryAdapter adapter = new LibraryAdapter(this, filteredLibrary);
        listView.setAdapter(adapter);

        // Sort options
        String[] sortOptions = new String[]{"Title", "Artist", "Recently added"};
        ArrayAdapter<String> sa = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, sortOptions);
        sa.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        sortSpinner.setAdapter(sa);

        sortSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            final Collator collator = Collator.getInstance(Locale.getDefault());
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                Comparator<MediaItem> comp;
                if (position == 0) comp = (a, b) -> collator.compare(a.title, b.title);
                else if (position == 1) comp = (a, b) -> collator.compare(a.artist == null ? "" : a.artist, b.artist == null ? "" : b.artist);
                else comp = (a, b) -> Long.compare(b.dateAdded, a.dateAdded);
                filteredLibrary.sort(comp);
                adapter.notifyDataSetChanged();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });

        // Search handling
        searchView.setOnQueryTextListener(new android.widget.SearchView.OnQueryTextListener() {
            @Override
            public boolean onQueryTextSubmit(String query) {
                doFilter(query, adapter);
                return true;
            }

            @Override
            public boolean onQueryTextChange(String newText) {
                if (pendingSearch != null) uiHandler.removeCallbacks(pendingSearch);
                pendingSearch = () -> doFilter(newText, adapter);
                uiHandler.postDelayed(pendingSearch, 200);
                return true;
            }
        });

        // show queue
        btnQueue.setOnClickListener(v -> showQueueDialog());

        // manage playlists
        btnPlaylists.setOnClickListener(v -> showPlaylistsDialog());

        // Show dialog
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.media_library_title))
                .setView(dlgView)
                .setPositiveButton(getString(R.string.close), null)
                .show();
    }

    private void doFilter(String q, ArrayAdapter<MediaItem> adapter) {
        String nq = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
        filteredLibrary.clear();
        if (nq.isEmpty()) filteredLibrary.addAll(library);
        else {
            for (MediaItem m : library) {
                if ((m.title != null && m.title.toLowerCase(Locale.ROOT).contains(nq))
                        || (m.artist != null && m.artist.toLowerCase(Locale.ROOT).contains(nq))
                        || (m.album != null && m.album.toLowerCase(Locale.ROOT).contains(nq))) {
                    filteredLibrary.add(m);
                }
            }
        }
        adapter.notifyDataSetChanged();
    }

    // Show play queue with ability to remove items
    private void showQueueDialog() {
        RemoveAdapter adapter = new RemoveAdapter(this, playQueue);
        ListView lv = new ListView(this);

        // Header with previous/now playing/next and prev/next buttons
        android.widget.LinearLayout header = new android.widget.LinearLayout(this);
        header.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        header.setPadding(pad, pad, pad, pad);

        TextView tvPrevLabel = new TextView(this);
        tvPrevLabel.setText(getString(R.string.previous_item));
        TextView tvPrev = new TextView(this);

        TextView tvNowLabel = new TextView(this);
        tvNowLabel.setText(getString(R.string.now_playing));
        TextView tvNow = new TextView(this);

        TextView tvNextLabel = new TextView(this);
        tvNextLabel.setText(getString(R.string.up_next));
        TextView tvNext = new TextView(this);

        android.widget.LinearLayout navRow = new android.widget.LinearLayout(this);
        navRow.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        navRow.setPadding(0, pad / 2, 0, 0);

        ImageButton btnPrev = new ImageButton(this);
        btnPrev.setImageResource(android.R.drawable.ic_media_previous);
        btnPrev.setBackgroundResource(android.R.color.transparent);
        btnPrev.setContentDescription(getString(R.string.previous_track));

        ImageButton btnNext = new ImageButton(this);
        btnNext.setImageResource(android.R.drawable.ic_media_next);
        btnNext.setBackgroundResource(android.R.color.transparent);
        btnNext.setContentDescription(getString(R.string.next_track));

        navRow.addView(btnPrev);
        navRow.addView(btnNext);

        header.addView(tvPrevLabel);
        header.addView(tvPrev);
        header.addView(tvNowLabel);
        header.addView(tvNow);
        header.addView(tvNextLabel);
        header.addView(tvNext);
        header.addView(navRow);

        // helper to update header state
        Runnable updateHeader = () -> {
            if (playingFromQueue && queueIndex >= 0 && queueIndex < playQueue.size()) {
                MediaItem current = playQueue.get(queueIndex);
                MediaItem prev = queueIndex > 0 ? playQueue.get(queueIndex - 1) : null;
                MediaItem next = (queueIndex + 1 < playQueue.size()) ? playQueue.get(queueIndex + 1) : null;
                tvNow.setText(displayFor(current));
                tvPrev.setText(prev == null ? "-" : displayFor(prev));
                tvNext.setText(next == null ? "-" : displayFor(next));
                btnPrev.setEnabled(canSkipPrev());
                btnNext.setEnabled(canSkipNext());
            } else {
                tvNow.setText(getString(R.string.not_playing_from_queue));
                tvPrev.setText("-");
                tvNext.setText("-");
                btnPrev.setEnabled(false);
                btnNext.setEnabled(false);
            }
        };

        btnPrev.setOnClickListener(v -> {
            skipToPreviousInQueue();
            updateHeader.run();
        });
        btnNext.setOnClickListener(v -> {
            skipToNextInQueue();
            updateHeader.run();
        });

        lv.addHeaderView(header, null, false);
        lv.setAdapter(adapter);

        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.play_queue_title))
                .setView(lv)
                .setPositiveButton(getString(R.string.close), null)
                .show();

        // initialize header state after showing list
        updateHeader.run();
    }

    // Manage playlists (create, view)
    private void showPlaylistsDialog() {
        List<PlaylistEntity> pls = playlistDao.getPlaylistsForUser(currentUserId);
        List<String> names = new ArrayList<>();
        for (PlaylistEntity p : pls) names.add(p.name);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, names);
        ListView lv = new ListView(this);
        lv.setAdapter(adapter);

        // actions: Play, Open, Rename, Delete
        lv.setOnItemClickListener((parent, view, position, id) -> {
            PlaylistEntity selected = pls.get(position);
            String[] options = new String[]{
                    getString(R.string.play_whole_playlist),
                    getString(R.string.open),
                    getString(R.string.rename_playlist),
                    getString(R.string.delete_playlist)
            };
            new AlertDialog.Builder(this)
                    .setTitle(selected.name)
                    .setItems(options, (dialog, which) -> {
                        if (which == 0) {
                            List<PlaylistItemEntity> items = playlistItemDao.getItemsForPlaylist(selected.id);
                            startQueuePlayback(toMediaItems(items), selected.name);
                        } else if (which == 1) {
                            showPlaylistDetailDialog(selected);
                        } else if (which == 2) {
                            final EditText input = new EditText(this);
                            input.setText(selected.name);
                            new AlertDialog.Builder(this)
                                    .setTitle(getString(R.string.rename_playlist))
                                    .setView(input)
                                    .setPositiveButton(getString(R.string.rename), (d, w) -> {
                                        String newName = input.getText().toString().trim();
                                        if (newName.isEmpty()) return;
                                        // check unique
                                        for (PlaylistEntity p : pls) {
                                            if (p != selected && p.name.equalsIgnoreCase(newName)) {
                                                Toast.makeText(this, getString(R.string.playlist_exists), Toast.LENGTH_SHORT).show();
                                                return;
                                            }
                                        }
                                        selected.name = newName;
                                        int updated = playlistDao.update(selected);
                                        if (updated > 0) {
                                            names.set(position, newName);
                                            adapter.notifyDataSetChanged();
                                            Toast.makeText(this, getString(R.string.playlist_renamed), Toast.LENGTH_SHORT).show();
                                        }
                                    })
                                    .setNegativeButton(getString(R.string.cancel), null)
                                    .show();
                        } else if (which == 3) {
                            new AlertDialog.Builder(this)
                                    .setTitle(getString(R.string.delete_playlist))
                                    .setMessage(getString(R.string.confirm_delete_playlist, selected.name))
                                    .setPositiveButton(getString(R.string.delete), (d, w) -> {
                                        playlistDao.delete(selected);
                                        pls.remove(position);
                                        names.remove(position);
                                        adapter.notifyDataSetChanged();
                                        Toast.makeText(this, getString(R.string.playlist_deleted), Toast.LENGTH_SHORT).show();
                                    })
                                    .setNegativeButton(getString(R.string.cancel), null)
                                    .show();
                        }
                    })
                    .show();
        });

        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.playlists))
                .setView(lv)
                .setPositiveButton(getString(R.string.create_playlist), (dialog, which) -> {
                    // create new playlist
                    EditText input = new EditText(this);
                    new AlertDialog.Builder(this)
                            .setTitle(getString(R.string.create_playlist_name))
                            .setView(input)
                            .setPositiveButton(getString(R.string.create_playlist), (d, w) -> {
                                String name = input.getText().toString().trim();
                                if (name.isEmpty()) return;
                                for (PlaylistEntity p : pls) {
                                    if (p.name.equalsIgnoreCase(name)) {
                                        Toast.makeText(this, getString(R.string.playlist_exists), Toast.LENGTH_SHORT).show();
                                        return;
                                    }
                                }
                                PlaylistEntity created = new PlaylistEntity(currentUserId, name, System.currentTimeMillis());
                                created.id = (int) playlistDao.insert(created);
                                pls.add(created);
                                names.add(name);
                                adapter.notifyDataSetChanged();
                                Toast.makeText(this, getString(R.string.playlist_created), Toast.LENGTH_SHORT).show();
                            })
                            .setNegativeButton(getString(R.string.close), null)
                            .show();
                })
                .setNegativeButton(getString(R.string.close), null)
                .show();
    }
    private void showPlaylistDetailDialog(String name, List<MediaItem> items) {
        final List<MediaItem> playlistItems = (items == null) ? new ArrayList<>() : items;
        RemoveAdapter adapter = new RemoveAdapter(this, playlistItems);
        ListView lv = new ListView(this);
        lv.setAdapter(adapter);


        new AlertDialog.Builder(this)
                .setTitle(name)
                .setView(lv)
                .setPositiveButton(getString(R.string.close), null)
                .show();
    }

    // open playlist details by entity, loading from DB and persisting removals
    private void showPlaylistDetailDialog(@NonNull PlaylistEntity playlist) {
        // Load items for this playlist
        List<PlaylistItemEntity> rawItems = playlistItemDao.getItemsForPlaylist(playlist.id);
        class PlaylistDetailAdapter extends ArrayAdapter<PlaylistItemEntity> {
            private final List<PlaylistItemEntity> items;
            PlaylistDetailAdapter(@NonNull android.content.Context ctx, @NonNull List<PlaylistItemEntity> items) {
                super(ctx, 0, items);
                this.items = items;
            }
            @NonNull
            @Override
            public View getView(int position, @Nullable View convertView, @NonNull android.view.ViewGroup parent) {
                View v = convertView;
                if (v == null) v = getLayoutInflater().inflate(R.layout.item_with_remove, parent, false);
                PlaylistItemEntity pie = getItem(position);
                TextView title = v.findViewById(R.id.item_title);
                ImageButton btnRemove = v.findViewById(R.id.btn_remove);

                String display = (pie == null) ? "" : (pie.artist != null && !pie.artist.isEmpty() ? pie.title + " — " + pie.artist : pie.title);
                title.setText(display);

                // Play item on row click
                v.setOnClickListener(row -> {
                    if (pie == null) return;
                    playingFromQueue = false;
                    queueIndex = -1;
                    try {
                        prepareMediaPlayer(Uri.parse(pie.contentUri));
                        if (mediaPlayer != null) mediaPlayer.start();
                        if (titleText != null) titleText.setText(pie.title);
                    } catch (Exception ignored) {}
                });

                // Remove item
                btnRemove.setOnClickListener(click -> {
                    if (pie == null) return;
                    int deleted = playlistItemDao.delete(pie);
                    if (deleted > 0) {
                        items.remove(position);
                        notifyDataSetChanged();
                        Toast.makeText(MainActivity.this, R.string.delete, Toast.LENGTH_SHORT).show();
                    }
                });

                return v;
            }
        }

        PlaylistDetailAdapter adapter = new PlaylistDetailAdapter(this, new ArrayList<>(rawItems));
        ListView lv = new ListView(this);
        lv.setAdapter(adapter);

        new AlertDialog.Builder(this)
                .setTitle(playlist.name)
                .setView(lv)
                .setNeutralButton(getString(R.string.play_whole_playlist), (d, w) -> {
                    List<PlaylistItemEntity> current = new ArrayList<>();
                    for (int i = 0; i < adapter.getCount(); i++) {
                        PlaylistItemEntity it = adapter.getItem(i);
                        if (it != null) current.add(it);
                    }
                    List<MediaItem> list = toMediaItems(current);
                    startQueuePlayback(list, playlist.name);
                })
                .setPositiveButton(getString(R.string.close), null)
                .show();
    }

    // Ask for READ permission if needed
    private void checkPermissionAndOpenLibrary() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            String perm = android.Manifest.permission.READ_MEDIA_AUDIO;
            if (androidx.core.content.ContextCompat.checkSelfPermission(this, perm) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                loadLibraryFromMediaStore();
                openLibraryDialog();
            } else {
                permissionLauncher.launch(new String[]{perm});
            }
        } else {
            String perm = android.Manifest.permission.READ_EXTERNAL_STORAGE;
            if (androidx.core.content.ContextCompat.checkSelfPermission(this, perm) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                loadLibraryFromMediaStore();
                openLibraryDialog();
            } else {
                permissionLauncher.launch(new String[]{perm});
            }
        }
    }

    // Let user pick an existing playlist or create one, then add the item (persist)
    private void showAddToPlaylistDialog(MediaItem item) {
        List<PlaylistEntity> pls = playlistDao.getPlaylistsForUser(currentUserId);
        List<String> names = new ArrayList<>();
        names.add(getString(R.string.create_new_playlist_option));
        for (PlaylistEntity p : pls) names.add(p.name);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, names);
        ListView lv = new ListView(this);
        lv.setAdapter(adapter);

        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle(getString(R.string.add_to_playlist))
                .setView(lv)
                .setNegativeButton(getString(R.string.close), null)
                .create();

        lv.setOnItemClickListener((parent, view, position, id) -> {
            if (position == 0) {
                // create new
                EditText input = new EditText(this);
                new AlertDialog.Builder(this)
                        .setTitle(getString(R.string.create_playlist_name))
                        .setView(input)
                        .setPositiveButton(getString(R.string.create_playlist), (d, w) -> {
                            String name = input.getText().toString().trim();
                            if (!name.isEmpty()) {
                                // check duplicate
                                for (PlaylistEntity p : pls) {
                                    if (p.name.equalsIgnoreCase(name)) {
                                        Toast.makeText(this, getString(R.string.playlist_exists), Toast.LENGTH_SHORT).show();
                                        return;
                                    }
                                }
                                PlaylistEntity created = new PlaylistEntity(currentUserId, name, System.currentTimeMillis());
                                created.id = (int) playlistDao.insert(created);
                                insertPlaylistItem(created.id, item);
                                Toast.makeText(this, getString(R.string.added_to, name), Toast.LENGTH_SHORT).show();
                            }
                        })
                        .setNegativeButton(getString(R.string.close), null)
                        .show();
            } else {
                PlaylistEntity sel = pls.get(position - 1);
                insertPlaylistItem(sel.id, item);
                Toast.makeText(this, getString(R.string.added_to, sel.name), Toast.LENGTH_SHORT).show();
            }
            dlg.dismiss();
        });

        dlg.show();
    }

    private void insertPlaylistItem(int playlistId, MediaItem item) {
        PlaylistItemEntity pie = new PlaylistItemEntity(
                playlistId,
                item.contentUri.toString(),
                item.title == null ? "Unknown" : item.title,
                item.artist,
                item.album,
                System.currentTimeMillis()
        );
        playlistItemDao.insert(pie);
    }

    private String displayFor(@Nullable MediaItem m) {
        if (m == null) return "";
        return (m.artist != null && !m.artist.isEmpty()) ? (m.title + " — " + m.artist) : m.title;
    }

    private boolean canSkipNext() {
        return playingFromQueue && queueIndex >= 0 && (queueIndex + 1) < playQueue.size();
    }

    private boolean canSkipPrev() {
        return playingFromQueue && queueIndex > 0 && queueIndex < playQueue.size();
    }

    private void skipToNextInQueue() {
        if (!canSkipNext()) return;
        queueIndex++;
        MediaItem next = playQueue.get(queueIndex);
        prepareMediaPlayer(next.contentUri);
        if (mediaPlayer != null) mediaPlayer.start();
        if (titleText != null) titleText.setText(next.title);
        if (playPauseButton != null) playPauseButton.setImageResource(android.R.drawable.ic_media_pause);
    }

    private void skipToPreviousInQueue() {
        if (!canSkipPrev()) return;
        queueIndex--;
        MediaItem prev = playQueue.get(queueIndex);
        prepareMediaPlayer(prev.contentUri);
        if (mediaPlayer != null) mediaPlayer.start();
        if (titleText != null) titleText.setText(prev.title);
        if (playPauseButton != null) playPauseButton.setImageResource(android.R.drawable.ic_media_pause);
    }

    private List<MediaItem> toMediaItems(List<PlaylistItemEntity> items) {
        List<MediaItem> list = new ArrayList<>();
        long idx = 0;
        for (PlaylistItemEntity e : items) {
            Uri uri = Uri.parse(e.contentUri);
            list.add(new MediaItem(idx++, e.title, e.artist, e.album, e.dateAdded, uri));
        }
        return list;
    }
}
