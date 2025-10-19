package com.example.mymediaplayer.data;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(
        tableName = "users",
        indices = {@Index(value = {"username"}, unique = true)}
)
public class UserEntity {
    @PrimaryKey(autoGenerate = true)
    public int id;

    @NonNull
    public String username;

    @NonNull
    public String passwordHash;

    @NonNull
    public String salt;

    public long createdAt;

    public UserEntity(@NonNull String username, @NonNull String passwordHash, @NonNull String salt, long createdAt) {
        this.username = username;
        this.passwordHash = passwordHash;
        this.salt = salt;
        this.createdAt = createdAt;
    }
}

