package com.retrobox

import android.app.Application
import androidx.room.Room
import com.retrobox.data.AppDatabase
import com.retrobox.data.GameRepository
import com.retrobox.data.UserPreferencesRepository

class RetroBoxApplication : Application() {
    val database by lazy {
        Room.databaseBuilder(this, AppDatabase::class.java, "retrobox_database")
            .addMigrations(AppDatabase.MIGRATION_1_2)
            .fallbackToDestructiveMigration()
            .build()
    }
    val gameRepository by lazy { GameRepository(database.gameDao()) }
    val userPreferencesRepository by lazy { UserPreferencesRepository(this) }
}
