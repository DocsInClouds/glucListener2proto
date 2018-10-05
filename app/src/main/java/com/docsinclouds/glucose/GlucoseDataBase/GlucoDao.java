package com.docsinclouds.glucose.GlucoseDataBase;

import android.arch.persistence.room.Dao;
import android.arch.persistence.room.Delete;
import android.arch.persistence.room.Insert;
import android.arch.persistence.room.Query;
import java.util.List;

@Dao
public interface GlucoDao {
    @Query("SELECT * FROM GlucoEntity")
    List<GlucoEntity> getAll();

    @Query("SELECT * FROM GlucoEntity WHERE mId IN (:userIds)")
    List<GlucoEntity> loadAllByIds(int[] userIds);

    @Query("SELECT * FROM GlucoEntity WHERE timestamps LIKE :first AND "
            + "glucoValues LIKE :last LIMIT 1")
    GlucoEntity findByName(String first, String last);

    @Query("SELECT * FROM GlucoEntity ORDER BY mId DESC LIMIT :amount")
    List<GlucoEntity> getLastEntries(int amount);

    @Insert
    public void insertGlucovalue(GlucoEntity glucoEntity);


    @Insert
    void insertAll(GlucoEntity... glucoEntities);

    @Delete
    void delete(GlucoEntity glucoEntity);
}
