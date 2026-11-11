package com.nightlight.app.data.db.entity;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "preferences")
public class PreferenceEntity {

    @PrimaryKey
    @NonNull
    public String key = "";

    public String value;

    public static PreferenceEntity of(String key, String value) {
        PreferenceEntity e = new PreferenceEntity();
        e.key = key;
        e.value = value;
        return e;
    }
}