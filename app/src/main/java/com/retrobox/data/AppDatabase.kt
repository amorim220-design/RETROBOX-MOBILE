package com.retrobox.data

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
    fun fromConsole(console: com.retrobox.domain.Console): String = console.name

    @androidx.room.TypeConverter
    fun toConsole(name: String): com.retrobox.domain.Console = com.retrobox.domain.Console.valueOf(name)
}
