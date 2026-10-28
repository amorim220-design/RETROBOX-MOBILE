package com.example

import android.app.Application
import androidx.room.Room
import com.example.data.AppDatabase
import com.example.data.GameRepository
import com.example.data.UserPreferencesRepository

class RetroBoxApplication : Application() {
    val database by lazy { 
        Room.databaseBuilder(this, AppDatabase::class.java, "retrobox_database")
            .fallbackToDestructiveMigration()
            .build() 
    }
    val gameRepository by lazy { GameRepository(database.gameDao()) }
    val userPreferencesRepository by lazy { UserPreferencesRepository(this) }
}
