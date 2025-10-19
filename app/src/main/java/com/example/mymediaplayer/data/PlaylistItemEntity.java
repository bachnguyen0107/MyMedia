package com.example.mymediaplayer.data;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(
        tableName = "playlist_items",
        foreignKeys = @ForeignKey(
                entity = PlaylistEntity.class,
                parentColumns = "id",
                childColumns = "playlistId",
                onDelete = ForeignKey.CASCADE
        ),
        indices = {@Index("playlistId")}
)
public class PlaylistItemEntity {
    @PrimaryKey(autoGenerate = true)
    public int id;

    public int playlistId;

    // Persist a playable reference and some metadata for display
    @NonNull
    public String contentUri;

    @NonNull
    public String title;

    public String artist;

    public String album;

    public long dateAdded;

    public PlaylistItemEntity(int playlistId, @NonNull String contentUri, @NonNull String title, String artist, String album, long dateAdded) {
        this.playlistId = playlistId;
        this.contentUri = contentUri;
        this.title = title;
        this.artist = artist;
        this.album = album;
        this.dateAdded = dateAdded;
    }
}

