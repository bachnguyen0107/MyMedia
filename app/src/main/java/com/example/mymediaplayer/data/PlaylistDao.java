package com.example.mymediaplayer.data;

import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;

import java.util.List;

@Dao
public interface PlaylistDao {
    @Query("SELECT * FROM playlists WHERE userId = :userId ORDER BY name ASC")
    List<PlaylistEntity> getPlaylistsForUser(int userId);

    @Query("SELECT * FROM playlists WHERE id = :playlistId LIMIT 1")
    PlaylistEntity getById(int playlistId);

    @Insert(onConflict = OnConflictStrategy.ABORT)
    long insert(PlaylistEntity playlist);

    @Update
    int update(PlaylistEntity playlist);

    @Delete
    int delete(PlaylistEntity playlist);
}

