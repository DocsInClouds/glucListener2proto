package com.docsinclouds.glucose.GlucoseDataBase;

import android.arch.persistence.room.ColumnInfo;
import android.arch.persistence.room.Entity;
import android.arch.persistence.room.PrimaryKey;


@Entity (tableName = "GlucoEntity")
public class GlucoEntity {
    @PrimaryKey(autoGenerate = true)
    private int mId;

    @ColumnInfo(name = "timestamps")
    private String mTimestamp;

    @ColumnInfo(name = "glucoValues")
    private int mGlucoValue;

    public String getTimestamp() {
        return mTimestamp;
    }

    public int getId() {
        return mId;
    }

    public int getGlucoValue() {
        return mGlucoValue;
    }

    public void setId(int mId) {
        this.mId = mId;
    }

    public void setGlucoValue(int mGlucoValue) {
        this.mGlucoValue = mGlucoValue;
    }

    public void setTimestamp(String mTimestamp) {
        this.mTimestamp = mTimestamp;
    }

    public GlucoEntity(int id, String timestamp, int glucoValue) {
        this.mId = id;
        this.mTimestamp = timestamp;

        this.mGlucoValue = glucoValue;
    }



}
