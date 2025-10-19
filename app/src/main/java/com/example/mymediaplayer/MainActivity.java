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

import androidx.core.content.ContextCompat;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
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

    // New: in-memory library, filtered view, queue and playlists
    private final List<MediaItem> library = new ArrayList<>();
    private final List<MediaItem> filteredLibrary = new ArrayList<>();
    private final List<MediaItem> playQueue = new ArrayList<>();
    private final Map<String, List<MediaItem>> playlists = new HashMap<>();

    // Debounce handler for search
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private Runnable pendingSearch;

    @SuppressLint("DiscouragedApi")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

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
                // minSdk is >= 26 for this project, so PlaybackParams is available; handle device exceptions
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

        @Override
        public View getView(int position, View convertView, android.view.ViewGroup parent) {
            View v = convertView;
            if (v == null) {
                v = getLayoutInflater().inflate(R.layout.item_library, parent, false);
            }
            MediaItem item = getItem(position);
            TextView title = v.findViewById(R.id.item_title);
            android.widget.ImageButton btnAdd = v.findViewById(R.id.btn_add);

            title.setText(item == null ? "" : item.title + (item.artist != null && !item.artist.isEmpty() ? " — " + item.artist : ""));

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

        @Override
        public View getView(int position, View convertView, android.view.ViewGroup parent) {
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
        // Inflate dialog layout
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

        // Item click -> play (tap the row to play)
        listView.setOnItemClickListener((parent, view, position, id) -> {
            MediaItem item = filteredLibrary.get(position);
            if (item != null) {
                prepareMediaPlayer(item.contentUri);
                if (mediaPlayer != null) mediaPlayer.start();
                Toast.makeText(this, getString(R.string.playing_prefix) + item.title, Toast.LENGTH_SHORT).show();
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
        // adapter may be LibraryAdapter or RemoveAdapter which both extend ArrayAdapter
        adapter.notifyDataSetChanged();
    }

    // Show play queue with ability to remove items
    private void showQueueDialog() {
        RemoveAdapter adapter = new RemoveAdapter(this, playQueue);
        ListView lv = new ListView(this);
        lv.setAdapter(adapter);

        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.play_queue_title))
                .setView(lv)
                .setPositiveButton(getString(R.string.close), null)
                .show();
    }

    // Manage playlists (create, view)
    private void showPlaylistsDialog() {
        List<String> names = new ArrayList<>(playlists.keySet());
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, names);
        ListView lv = new ListView(this);
        lv.setAdapter(adapter);

        lv.setOnItemClickListener((parent, view, position, id) -> {
            String name = names.get(position);
            List<MediaItem> items = playlists.get(name);
            if (items == null) items = new ArrayList<>();
            showPlaylistDetailDialog(name, items);
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
                                if (!name.isEmpty() && !playlists.containsKey(name)) {
                                    playlists.put(name, new ArrayList<>());
                                    Toast.makeText(this, getString(R.string.playlist_created), Toast.LENGTH_SHORT).show();
                                }
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
                .setTitle(name + " (" + getString(R.string.long_press_remove) + ")")
                .setView(lv)
                .setPositiveButton(getString(R.string.close), null)
                .show();
    }

    private void showAddToPlaylistDialog(MediaItem item) {
        // show existing playlists or option to create new
        List<String> names = new ArrayList<>(playlists.keySet());
        names.add(0, getString(R.string.create_new_playlist_option));
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, names);
        ListView lv = new ListView(this);
        lv.setAdapter(adapter);

        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle(getString(R.string.add_to_playlist))
                .setView(lv)
                .setNegativeButton(getString(R.string.close), null)
                .create();

        lv.setOnItemClickListener((parent, view, position, id) -> {
            String sel = names.get(position);
            if (position == 0) {
                // create new
                EditText input = new EditText(this);
                new AlertDialog.Builder(this)
                        .setTitle(getString(R.string.create_playlist_name))
                        .setView(input)
                        .setPositiveButton(getString(R.string.create_playlist), (d, w) -> {
                            String name = input.getText().toString().trim();
                            if (!name.isEmpty()) {
                                playlists.putIfAbsent(name, new ArrayList<>());
                                List<MediaItem> list = playlists.get(name);
                                if (list != null) list.add(item);
                                Toast.makeText(this, getString(R.string.added_to, name), Toast.LENGTH_SHORT).show();
                            }
                        })
                        .setNegativeButton(getString(R.string.close), null)
                        .show();
            } else {
                playlists.putIfAbsent(sel, new ArrayList<>());
                List<MediaItem> list = playlists.get(sel);
                if (list != null) list.add(item);
                Toast.makeText(this, getString(R.string.added_to, sel), Toast.LENGTH_SHORT).show();
            }
            dlg.dismiss();
        });

        dlg.show();
    }

    private void checkPermissionAndOpenLibrary() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            String perm = android.Manifest.permission.READ_MEDIA_AUDIO;
            if (ContextCompat.checkSelfPermission(this, perm) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                loadLibraryFromMediaStore();
                openLibraryDialog();
            } else {
                permissionLauncher.launch(new String[]{perm});
            }
        } else {
            String perm = android.Manifest.permission.READ_EXTERNAL_STORAGE;
            if (ContextCompat.checkSelfPermission(this, perm) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                loadLibraryFromMediaStore();
                openLibraryDialog();
            } else {
                permissionLauncher.launch(new String[]{perm});
            }
        }
    }
}
