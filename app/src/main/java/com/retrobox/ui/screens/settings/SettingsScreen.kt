package com.retrobox.ui.screens.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.retrobox.BuildConfig
import com.retrobox.ui.theme.BackgroundDark
import com.retrobox.ui.theme.NeonBlue
import com.retrobox.ui.theme.SurfaceDark
import com.retrobox.ui.theme.SurfaceVariantDark
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onOpenBios: () -> Unit,
    onOpenInput: () -> Unit
) {
    val catalogUrl by viewModel.catalogUrl.collectAsState()
    val stats by viewModel.stats.collectAsState()
    val backups by viewModel.backups.collectAsState()
    val bios by viewModel.bios.collectAsState()
    val lastError by viewModel.lastError.collectAsState()
    val lastRefresh by viewModel.lastRefresh.collectAsState(0L)
    val lastVersion by viewModel.lastVersion.collectAsState(0)
    val catalogError by viewModel.catalogError.collectAsState(null)
    val snackbar = remember { SnackbarHostState() }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri -> if (uri != null) viewModel.exportBackup(uri) }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) viewModel.restoreBackup(uri) }

    LaunchedEffect(Unit) {
        viewModel.message.collect { snackbar.showSnackbar(it) }
    }

    Scaffold(
        containerColor = BackgroundDark,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("CONFIGURAÇÕES", fontWeight = FontWeight.Bold, letterSpacing = 1.sp) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BackgroundDark)
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(BackgroundDark, SurfaceDark)))
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Section("CATÁLOGO") {
                    Text(
                        "Assinatura verificada (Ed25519) ou hash fixado. Sem URL, o app usa o " +
                            "catálogo de exemplo embutido (só metadados, sem ROMs).",
                        color = Color.White.copy(alpha = 0.5f),
                        fontSize = 11.sp
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = catalogUrl,
                        onValueChange = viewModel::updateCatalogUrl,
                        label = { Text("URL do catalog.json assinado") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            imeAction = ImeAction.Done
                        )
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { viewModel.saveCatalogUrl() }) { Text("Salvar URL") }
                        OutlinedButton(onClick = { viewModel.refreshCatalog() }) {
                            Text("Atualizar")
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        buildString {
                            append("Versão em cache: v")
                            append(lastVersion)
                            if (lastRefresh > 0) {
                                append(" · atualizado ")
                                append(ago(lastRefresh))
                            }
                            append(" · ${stats.catalog} itens")
                        },
                        color = Color.White.copy(alpha = 0.65f),
                        fontSize = 11.sp
                    )
                    catalogError?.let {
                        Text(it, color = Color(0xFFFF8A80), fontSize = 11.sp)
                    }
                }
            }

            item {
                Section("CONTROLES") {
                    Text(
                        "Zona morta, sensibilidade, mapeamento de botões e aparência do " +
                            "controle na tela. Vale para USB e Bluetooth.",
                        color = Color.White.copy(alpha = 0.5f),
                        fontSize = 11.sp
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(onClick = onOpenInput) {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("Ajustar controles")
                    }
                }
            }

            item {
                Section("BIOS E FIRMWARE") {
                    Text(
                        "Importe os arquivos do seu aparelho. Nunca vem BIOS junto com o app.",
                        color = Color.White.copy(alpha = 0.5f),
                        fontSize = 11.sp
                    )
                    Spacer(Modifier.height(8.dp))
                    bios.forEach { status ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                status.requirement.console.displayName,
                                color = Color.White,
                                fontSize = 13.sp,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                if (status.satisfied) "pronto" else "faltando",
                                color = if (status.satisfied) Color(0xFF69F0AE) else Color(0xFFFFD180),
                                fontSize = 11.sp
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    OutlinedButton(onClick = onOpenBios) {
                        Text("Gerenciar BIOS")
                        Spacer(Modifier.width(6.dp))
                        Icon(Icons.Default.ArrowForward, contentDescription = null, modifier = Modifier.size(16.dp))
                    }
                }
            }

            item {
                Section("SAVES E BACKUP") {
                    Text(
                        "SRAM e save states: ${mb(stats.savesBytes)}. Backups automáticos " +
                            "diários (7 mais recentes) ficam no próprio app.",
                        color = Color.White.copy(alpha = 0.5f),
                        fontSize = 11.sp
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { viewModel.createBackup() }) {
                            Icon(Icons.Default.Backup, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Backup agora")
                        }
                        OutlinedButton(onClick = {
                            exportLauncher.launch("retrobox-backup.zip")
                        }) {
                            Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Exportar")
                        }
                        OutlinedButton(onClick = {
                            importLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
                        }) {
                            Icon(Icons.Default.Restore, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Restaurar")
                        }
                    }
                    if (backups.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        backups.take(4).forEach { file ->
                            BackupRow(file) { viewModel.deleteBackup(file) }
                        }
                    }
                }
            }

            item {
                Section("ARMAZENAMENTO") {
                    StatRow("Jogos na biblioteca", "${stats.games}")
                    StatRow("ROMs", mb(stats.romsBytes))
                    StatRow("Saves + states", mb(stats.savesBytes))
                    StatRow("Capas", mb(stats.coversBytes))
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Layout v1: roms/ saves/ states/ system/ covers/ downloads/ backup/",
                        color = Color.White.copy(alpha = 0.4f),
                        fontSize = 10.sp
                    )
                }
            }

            if (lastError != null) {
                item {
                    Section("ÚLTIMO ERRO") {
                        Text(
                            lastError!!.take(1200),
                            color = Color.White.copy(alpha = 0.6f),
                            fontSize = 10.sp,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = { viewModel.clearLastError() }) {
                            Text("Limpar")
                        }
                    }
                }
            }

            item {
                Section("SOBRE") {
                    Text("RetroBox ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                        color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "9 núcleos libretro embutidos (FCEUmm, Snes9x 2005 Plus, Gambatte, mGBA, " +
                            "Genesis Plus GX, PCSX-ReARMed, Mupen64Plus-Next, melonDS, PPSSPP). " +
                            "Núcleos GPL/LGPL: o app não inclui ROMs nem BIOS.",
                        color = Color.White.copy(alpha = 0.5f),
                        fontSize = 11.sp
                    )
                }
            }

            item { Spacer(Modifier.height(12.dp)) }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(color = SurfaceVariantDark, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                title,
                color = NeonBlue,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
        Text(value, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun BackupRow(file: File, onDelete: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(file.name, color = Color.White, fontSize = 11.sp)
            Text(
                "${mb(file.length())} · ${ago(file.lastModified())}",
                color = Color.White.copy(alpha = 0.45f),
                fontSize = 10.sp
            )
        }
        IconButton(onClick = onDelete, modifier = Modifier.size(28.dp)) {
            Icon(
                Icons.Default.Delete,
                contentDescription = "Apagar",
                tint = Color.White.copy(alpha = 0.5f),
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

private fun mb(bytes: Long): String =
    String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))

private fun ago(timestamp: Long): String {
    val diff = System.currentTimeMillis() - timestamp
    return when {
        diff < 60_000 -> "agora"
        diff < 3_600_000 -> "${diff / 60_000} min"
        diff < 86_400_000 -> "${diff / 3_600_000} h"
        else -> SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).format(Date(timestamp))
    }
}
