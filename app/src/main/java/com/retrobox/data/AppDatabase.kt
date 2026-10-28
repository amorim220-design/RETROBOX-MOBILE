package com.retrobox.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [Game::class], version = 2, exportSchema = false)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun gameDao(): GameDao

    companion object {
        /** Adiciona a coluna rawTitle sem perder a biblioteca existente. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE games ADD COLUMN rawTitle TEXT")
            }
        }
    }
}

class Converters {
    @androidx.room.TypeConverter
    fun fromConsole(console: com.retrobox.domain.Console): String = console.name

    @androidx.room.TypeConverter
    fun toConsole(name: String): com.retrobox.domain.Console = com.retrobox.domain.Console.valueOf(name)
}
