package com.example.mymediaplayer.data;

import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import java.util.List;

@Dao
public interface PlaylistItemDao {
    @Query("SELECT * FROM playlist_items WHERE playlistId = :playlistId ORDER BY id ASC")
    List<PlaylistItemEntity> getItemsForPlaylist(int playlistId);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    long insert(PlaylistItemEntity item);

    @Delete
    int delete(PlaylistItemEntity item);

    @Query("DELETE FROM playlist_items WHERE playlistId = :playlistId")
    int deleteAllForPlaylist(int playlistId);
}

