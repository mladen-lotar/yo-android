package com.example.yo.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [YoEntity::class], version = 1)
abstract class YoDatabase : RoomDatabase() {
    abstract fun yoDao(): YoDao
}
