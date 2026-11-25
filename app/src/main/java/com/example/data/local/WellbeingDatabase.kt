package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        AppLimitEntity::class,
        DailyLaunchStatEntity::class,
        FocusModeEntity::class,
        EnforcementStateEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class WellbeingDatabase : RoomDatabase() {
    abstract fun appLimitDao(): AppLimitDao
    abstract fun dailyLaunchStatDao(): DailyLaunchStatDao
    abstract fun focusModeDao(): FocusModeDao
    abstract fun enforcementStateDao(): EnforcementStateDao

    companion object {
        @Volatile
        private var INSTANCE: WellbeingDatabase? = null

        fun getInstance(context: Context): WellbeingDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    WellbeingDatabase::class.java,
                    "wellbeing_lock_db"
                )
                    .fallbackToDestructiveMigration(dropAllTables = false)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
