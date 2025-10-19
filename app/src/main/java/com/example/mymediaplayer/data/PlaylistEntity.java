package com.example.mymediaplayer.data;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(
        tableName = "playlists",
        foreignKeys = @ForeignKey(
                entity = UserEntity.class,
                parentColumns = "id",
                childColumns = "userId",
                onDelete = ForeignKey.CASCADE
        ),
        indices = {
                @Index("userId"),
                @Index(value = {"userId", "name"}, unique = true)
        }
)
public class PlaylistEntity {
    @PrimaryKey(autoGenerate = true)
    public int id;

    public int userId;

    @NonNull
    public String name;

    public long createdAt;

    public PlaylistEntity(int userId, @NonNull String name, long createdAt) {
        this.userId = userId;
        this.name = name;
        this.createdAt = createdAt;
    }
}

