package com.retrobox.ui.screens.catalog

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.SubcomposeAsyncImage
import com.retrobox.data.CatalogEntry
import com.retrobox.data.DownloadState
import com.retrobox.domain.Console
import com.retrobox.ui.emulator.EmulatorActivity
import com.retrobox.ui.theme.BackgroundDark
import com.retrobox.ui.theme.NeonBlue
import com.retrobox.ui.theme.SurfaceDark
import com.retrobox.ui.theme.SurfaceVariantDark

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CatalogScreen(viewModel: CatalogViewModel) {
    val items by viewModel.items.collectAsState()
    val console by viewModel.console.collectAsState()
    val search by viewModel.search.collectAsState()
    val refreshing by viewModel.refreshing.collectAsState()
    val lastError by viewModel.lastError.collectAsState(null)
    val context = LocalContext.current

    var pendingLocal by remember { mutableStateOf<CatalogEntry?>(null) }
    val snackbar = remember { SnackbarHostState() }

    val localPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val entry = pendingLocal
        pendingLocal = null
        if (uri != null && entry != null) viewModel.installLocal(entry, uri)
    }

    LaunchedEffect(Unit) {
        viewModel.message.collect { snackbar.showSnackbar(it) }
    }

    val consoles by viewModel.consoles.collectAsState()

    Scaffold(
        containerColor = BackgroundDark,
        topBar = {
            TopAppBar(
                title = {
                    Text("CATÁLOGO", fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                },
                actions = {
                    IconButton(onClick = { viewModel.refresh() }, enabled = !refreshing) {
                        if (refreshing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                                color = NeonBlue
                            )
                        } else {
                            Icon(Icons.Default.Refresh, contentDescription = "Atualizar", tint = NeonBlue)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BackgroundDark)
            )
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(BackgroundDark, SurfaceDark)))
                .padding(padding)
        ) {
            if (consoles.isNotEmpty()) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item {
                        FilterChip(
                            selected = console == null,
                            onClick = { viewModel.selectConsole(null) },
                            label = { Text("Todos") },
                            colors = chipColors()
                        )
                    }
                    items(consoles) { item ->
                        FilterChip(
                            selected = console == item,
                            onClick = { viewModel.selectConsole(item) },
                            label = { Text(item.displayName) },
                            colors = chipColors()
                        )
                    }
                }
            }

            OutlinedTextField(
                value = search,
                onValueChange = viewModel::setSearch,
                placeholder = { Text("Buscar jogo pelo nome…") },
                singleLine = true,
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (search.isNotEmpty()) {
                        IconButton(onClick = { viewModel.setSearch("") }) {
                            Icon(Icons.Default.Close, contentDescription = "Limpar")
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                shape = RoundedCornerShape(12.dp)
            )

            if (items.isEmpty()) {
                EmptyCatalog(refreshing, lastError) { viewModel.refresh() }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(items, key = { it.entry.id }) { item ->
                        CatalogCard(
                            item = item,
                            onOpen = { game -> context.startActivity(EmulatorActivity.intent(context, game)) },
                            onDownload = { viewModel.download(item.entry) },
                            onCancel = { viewModel.cancel(item.entry) },
                            onUseLocal = {
                                pendingLocal = item.entry
                                localPicker.launch(arrayOf("*/*"))
                            }
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun chipColors() = FilterChipDefaults.filterChipColors(
    selectedContainerColor = NeonBlue.copy(alpha = 0.2f),
    selectedLabelColor = NeonBlue,
    labelColor = Color.White.copy(alpha = 0.8f)
)

@Composable
private fun EmptyCatalog(refreshing: Boolean, lastError: String?, onRefresh: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.CloudDownload,
            contentDescription = null,
            tint = NeonBlue.copy(alpha = 0.7f),
            modifier = Modifier.size(56.dp)
        )
        Spacer(Modifier.height(16.dp))
        Text(
            "Nenhum item no catálogo",
            color = Color.White,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(8.dp))
        Text(
            lastError ?: "Atualize para buscar o catálogo configurado.",
            color = if (lastError == null) Color.Gray else Color(0xFFFF8A80),
            fontSize = 13.sp
        )
        Spacer(Modifier.height(20.dp))
        Button(onClick = onRefresh, enabled = !refreshing) {
            Text(if (refreshing) "Atualizando…" else "Atualizar catálogo")
        }
        Spacer(Modifier.height(24.dp))
        Text(
            "O catálogo só descreve jogos (título, console, SHA-256 e uma URL opcional). " +
                "O app não inclui ROMs nem BIOS.",
            color = Color.White.copy(alpha = 0.45f),
            fontSize = 11.sp
        )
    }
}

@Composable
private fun PlaceholderCover(title: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(NeonBlue.copy(alpha = 0.22f), SurfaceVariantDark, Color.Black)
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            title,
            color = Color.White.copy(alpha = 0.8f),
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(10.dp)
        )
    }
}

/**
 * Capa do item do catálogo.
 *
 * O nome do arquivo da box art nem sempre é igual ao título do catálogo
 * (o reposório de thumbnails usa o nome do dump). Então tentamos as variações
 * em sequência: o primeiro nome que responder vira a capa; se nenhum
 * responder, fica o desenho gerado.
 */
@Composable
private fun CatalogCover(entry: com.retrobox.data.CatalogEntry) {
    val urls = remember(entry.id, entry.coverUrl) {
        val variants = com.retrobox.emulation.CoverStore.boxArtUrls(entry.console, entry.title)
        entry.coverUrl?.let { listOf(it) + variants } ?: variants
    }
    var index by remember(entry.id, entry.coverUrl) { mutableStateOf(0) }

    if (urls.isEmpty()) {
        PlaceholderCover(entry.title)
        return
    }

    val current = urls[index.coerceIn(0, urls.lastIndex)]
    SubcomposeAsyncImage(
        model = current,
        contentDescription = entry.title,
        modifier = Modifier.fillMaxSize(),
        contentScale = ContentScale.Crop,
        loading = { PlaceholderCover(entry.title) },
        error = {
            val next = index + 1
            if (next < urls.size) {
                LaunchedEffect(urls[next]) { index = next }
            }
            PlaceholderCover(entry.title)
        }
    )
}

@Composable
private fun CatalogCard(
    item: CatalogItem,
    onOpen: (com.retrobox.data.Game) -> Unit,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onUseLocal: () -> Unit
) {
    val entry = item.entry
    Column(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.78f)
                .clip(RoundedCornerShape(12.dp))
                .background(SurfaceVariantDark)
                .clickable(enabled = item.installed) {
                    item.installedGame?.let(onOpen)
                }
        ) {
            CatalogCover(entry)

            if (item.installed) {
                Surface(
                    color = NeonBlue,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp)
                ) {
                    Text(
                        "NA BIBLIOTECA",
                        color = BackgroundDark,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                    )
                }
            }
        }

        Spacer(Modifier.height(6.dp))
        Text(
            entry.title,
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            buildString {
                append(entry.console.displayName)
                entry.releaseYear?.let { append(" · $it") }
                if (entry.sizeBytes > 0) append(" · ${mb(entry.sizeBytes)}")
            },
            color = NeonBlue,
            fontSize = 10.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        Spacer(Modifier.height(6.dp))

        val record = item.record
        when {
            item.installed -> Button(
                onClick = { item.installedGame?.let(onOpen) },
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(4.dp)
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Jogar", fontSize = 12.sp)
            }

            record?.state == DownloadState.RUNNING || record?.state == DownloadState.QUEUED -> {
                val total = record.totalBytes.coerceAtLeast(1)
                val progress = (record.bytes.toFloat() / total).coerceIn(0f, 1f)
                Column {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth(),
                        color = NeonBlue,
                        trackColor = SurfaceVariantDark
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (record.state == DownloadState.QUEUED) "Na fila…"
                            else "${(progress * 100).toInt()}% — ${mb(record.bytes)}",
                            color = Color.White.copy(alpha = 0.7f),
                            fontSize = 10.sp,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = onCancel, modifier = Modifier.size(28.dp)) {
                            Icon(
                                Icons.Default.Cancel,
                                contentDescription = "Cancelar",
                                tint = Color.White.copy(alpha = 0.7f),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }

            record?.state == DownloadState.FAILED -> Column {
                Text(
                    record.error ?: "Falhou",
                    color = Color(0xFFFF8A80),
                    fontSize = 10.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(4.dp))
                Button(onClick = onDownload, modifier = Modifier.fillMaxWidth()) {
                    Text("Tentar de novo", fontSize = 12.sp)
                }
            }

            entry.hasDownload -> Button(
                onClick = onDownload,
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(4.dp)
            ) {
                Icon(Icons.Default.CloudDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(if (entry.sizeBytes > 0) "Baixar" else "Baixar", fontSize = 12.sp)
            }

            else -> OutlinedButton(
                onClick = onUseLocal,
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(4.dp)
            ) {
                Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Usar arquivo", fontSize = 12.sp)
            }
        }
    }
}

private fun mb(bytes: Long): String =
    if (bytes >= 1024L * 1024 * 1024) {
        String.format(java.util.Locale.US, "%.1f GB", bytes / (1024.0 * 1024 * 1024))
    } else {
        String.format(java.util.Locale.US, "%.0f MB", bytes / (1024.0 * 1024))
    }
