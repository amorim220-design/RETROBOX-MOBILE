package com.retrobox

import android.app.Application
import androidx.room.Room
import com.retrobox.backup.SaveBackupManager
import com.retrobox.catalog.CatalogRepository
import com.retrobox.data.AppDatabase
import com.retrobox.data.GameRepository
import com.retrobox.data.StorageLayout
import com.retrobox.data.UserPreferencesRepository
import com.retrobox.download.DownloadNotifications
import com.retrobox.download.DownloadScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class RetroBoxApplication : Application() {

    val database by lazy {
        Room.databaseBuilder(this, AppDatabase::class.java, "retrobox_database")
            .addMigrations(
                AppDatabase.MIGRATION_1_2,
                AppDatabase.MIGRATION_2_3,
                AppDatabase.MIGRATION_3_4
            )
            .fallbackToDestructiveMigration()
            .build()
    }

    val gameRepository by lazy {
        GameRepository(database.gameDao(), database.catalogDao(), database.downloadDao())
    }

    val userPreferencesRepository by lazy { UserPreferencesRepository(this) }

    val catalogRepository by lazy {
        CatalogRepository(this, gameRepository, userPreferencesRepository)
    }

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()

        StorageLayout.ensure(this)
        DownloadNotifications.ensureChannel(this)

        // o processo `:emulator` (núcleo libretro) não faz trabalho de biblioteca
        if (!isMainProcess()) return

        applicationScope.launch {
            // catálogo em cache/embutido: não bloqueia a primeira tela
            catalogRepository.ensureLoaded()
            // downloads interrompidos por morte do processo voltam para a fila
            runCatching { DownloadScheduler.reconcile(this@RetroBoxApplication, gameRepository) }
            // backup automático de saves (rotação diária, mantém 7)
            runCatching { SaveBackupManager.autoBackupIfDue(this@RetroBoxApplication) }
        }
    }

    private fun isMainProcess(): Boolean {
        val name = android.app.Application.getProcessName()
        return name == null || name == packageName
    }
}
