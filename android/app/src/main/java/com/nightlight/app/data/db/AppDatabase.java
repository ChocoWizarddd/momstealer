package com.nightlight.app.data.db;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

import com.nightlight.app.data.db.dao.LibraryDao;
import com.nightlight.app.data.db.dao.PlaylistDao;
import com.nightlight.app.data.db.entity.LikedTrackEntity;
import com.nightlight.app.data.db.entity.PlaylistEntity;
import com.nightlight.app.data.db.entity.PlaylistTrackEntity;
import com.nightlight.app.data.db.entity.PreferenceEntity;
import com.nightlight.app.data.db.entity.RecentTrackEntity;
import com.nightlight.app.data.db.entity.SearchHistoryEntity;

@Database(
        entities = {
                LikedTrackEntity.class,
                RecentTrackEntity.class,
                SearchHistoryEntity.class,
                PlaylistEntity.class,
                PlaylistTrackEntity.class,
                PreferenceEntity.class
        },
        version = 2,
        exportSchema = false)
public abstract class AppDatabase extends RoomDatabase {

    private static volatile AppDatabase instance;

    public abstract LibraryDao libraryDao();

    public abstract PlaylistDao playlistDao();

    public static AppDatabase get(Context context) {
        if (instance == null) {
            synchronized (AppDatabase.class) {
                if (instance == null) {
                    instance = Room.databaseBuilder(context.getApplicationContext(), AppDatabase.class, "nightlight.db")
                            .addMigrations(MIGRATION_1_2)
                            .build();
                }
            }
        }
        return instance;
    }

    /** v2 stores the playable stream URL on library rows so taps play instantly. */
    static final Migration MIGRATION_1_2 = new Migration(1, 2) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL("ALTER TABLE recent_tracks ADD COLUMN streamUrl TEXT");
            database.execSQL("ALTER TABLE liked_tracks ADD COLUMN streamUrl TEXT");
        }
    };
}