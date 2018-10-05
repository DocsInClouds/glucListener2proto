package com.docsinclouds.glucose.GlucoseDataBase;

import android.arch.persistence.room.Database;
import android.arch.persistence.room.RoomDatabase;

@Database(entities = {GlucoEntity.class}, version = 1)
public abstract class AppDatabase extends RoomDatabase {
    public abstract GlucoDao glucoDao();
}
