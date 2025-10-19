package com.example.mymediaplayer.data;

import android.content.Context;

import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;

@Database(entities = {UserEntity.class, PlaylistEntity.class, PlaylistItemEntity.class}, version = 1)
public abstract class AppDatabase extends RoomDatabase {
    private static volatile AppDatabase INSTANCE;

    public abstract UserDao userDao();
    public abstract PlaylistDao playlistDao();
    public abstract PlaylistItemDao playlistItemDao();

    public static AppDatabase getInstance(Context context) {
        if (INSTANCE == null) {
            synchronized (AppDatabase.class) {
                if (INSTANCE == null) {
                    INSTANCE = Room.databaseBuilder(context.getApplicationContext(), AppDatabase.class, "mmp.db")
                            .fallbackToDestructiveMigration()
                            .allowMainThreadQueries() // simplify for now
                            .build();
                }
            }
        }
        return INSTANCE;
    }
}

