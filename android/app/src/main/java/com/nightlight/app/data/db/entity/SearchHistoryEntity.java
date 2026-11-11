package com.nightlight.app.data.db.entity;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(tableName = "search_history", indices = {@Index("createdAt")})
public class SearchHistoryEntity {

    @PrimaryKey(autoGenerate = true)
    public long id;

    @NonNull
    public String query = "";

    public long createdAt;

    public static SearchHistoryEntity from(String query, long createdAt) {
        SearchHistoryEntity e = new SearchHistoryEntity();
        e.query = query;
        e.createdAt = createdAt;
        return e;
    }
}