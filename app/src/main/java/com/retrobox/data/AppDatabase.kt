package com.retrobox.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [Game::class, CatalogEntry::class, DownloadRecord::class],
    version = 3,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun gameDao(): GameDao
    abstract fun catalogDao(): CatalogDao
    abstract fun downloadDao(): DownloadDao

    companion object {
        /** Adiciona a coluna rawTitle sem perder a biblioteca existente. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE games ADD COLUMN rawTitle TEXT")
            }
        }

        /**
         * v3: catálogo remoto + identificação por hash.
         *
         * - `catalogId` liga o jogo ao item do catálogo que o originou;
         * - `sha256` permite reconhecer o mesmo arquivo mesmo se renomeado;
         * - índice único em `filePath` (havia duplicatas na v2, então limpa antes).
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE games ADD COLUMN catalogId TEXT")
                db.execSQL("ALTER TABLE games ADD COLUMN sha256 TEXT")
                db.execSQL(
                    "DELETE FROM games WHERE id NOT IN " +
                        "(SELECT MIN(id) FROM games GROUP BY filePath)"
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_games_filePath` ON `games` (`filePath`)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `catalog` (
                        `id` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `console` TEXT NOT NULL,
                        `fileName` TEXT NOT NULL,
                        `sizeBytes` INTEGER NOT NULL,
                        `sha256` TEXT NOT NULL,
                        `url` TEXT,
                        `coverUrl` TEXT,
                        `description` TEXT,
                        `developer` TEXT,
                        `releaseYear` INTEGER,
                        `region` TEXT,
                        `catalogVersion` INTEGER NOT NULL,
                        `addedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `downloads` (
                        `catalogId` TEXT NOT NULL,
                        `state` TEXT NOT NULL,
                        `bytes` INTEGER NOT NULL,
                        `totalBytes` INTEGER NOT NULL,
                        `error` TEXT,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`catalogId`)
                    )
                    """.trimIndent()
                )
            }
        }
    }
}

class Converters {
    @androidx.room.TypeConverter
    fun fromConsole(console: com.retrobox.domain.Console): String = console.name

    @androidx.room.TypeConverter
    fun toConsole(name: String): com.retrobox.domain.Console = com.retrobox.domain.Console.valueOf(name)

    @androidx.room.TypeConverter
    fun fromDownloadState(state: DownloadState): String = state.name

    @androidx.room.TypeConverter
    fun toDownloadState(name: String): DownloadState =
        runCatching { DownloadState.valueOf(name) }.getOrDefault(DownloadState.QUEUED)
}
