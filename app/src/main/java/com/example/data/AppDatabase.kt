package com.example.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(entities = [Game::class], version = 1, exportSchema = false)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun gameDao(): GameDao
}

class Converters {
    @androidx.room.TypeConverter
    fun fromConsole(console: com.example.domain.Console): String = console.name

    @androidx.room.TypeConverter
    fun toConsole(name: String): com.example.domain.Console = com.example.domain.Console.valueOf(name)
}
