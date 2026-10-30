package com.retrobox.ui.screens.bios

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.retrobox.domain.Console
import com.retrobox.emulation.BiosManager
import com.retrobox.ui.theme.BackgroundDark
import com.retrobox.ui.theme.NeonBlue
import com.retrobox.ui.theme.SurfaceDark
import com.retrobox.ui.theme.SurfaceVariantDark

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BiosScreen(
    viewModel: BiosViewModel,
    onBack: (() -> Unit)? = null
) {
    val statuses by viewModel.statuses.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    var pending by remember { mutableStateOf<Pair<Console, String>?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val request = pending
        pending = null
        if (uri != null && request != null) viewModel.import(request.first, request.second, uri)
    }

    LaunchedEffect(Unit) {
        viewModel.message.collect { snackbar.showSnackbar(it) }
    }

    Scaffold(
        containerColor = BackgroundDark,
        topBar = {
            TopAppBar(
                title = { Text("BIOS E FIRMWARE", fontWeight = FontWeight.Bold, letterSpacing = 1.sp) },
                navigationIcon = {
                    if (onBack != null) {
                        TextButton(onClick = onBack) { Text("Voltar", color = NeonBlue) }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BackgroundDark)
            )
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(BackgroundDark, SurfaceDark)))
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text(
                    "O app não inclui nem baixa BIOS: importe o arquivo que você já possui. " +
                        "Ele fica em files/system/, dentro do aparelho.",
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 12.sp
                )
            }
            items(statuses, key = { it.requirement.console.id }) { status ->
                BiosCard(
                    status = status,
                    onImport = { name ->
                        pending = status.requirement.console to name
                        picker.launch(arrayOf("*/*"))
                    },
                    onDelete = { name -> viewModel.delete(status.requirement.console, name) }
                )
            }
            item {
                Text(
                    "PS1: SCPH5500/5501/5502 ou OpenBIOS (512 KB). " +
                        "NDS: bios7.bin (16 KB) + bios9.bin (4 KB); firmware.bin é opcional. " +
                        "PSP: não precisa.",
                    color = Color.White.copy(alpha = 0.4f),
                    fontSize = 11.sp
                )
            }
        }
    }
}

@Composable
private fun BiosCard(
    status: BiosManager.Status,
    onImport: (String) -> Unit,
    onDelete: (String) -> Unit
) {
    Surface(
        color = SurfaceVariantDark,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (status.satisfied) Icons.Default.CheckCircle else Icons.Default.Error,
                    contentDescription = null,
                    tint = if (status.satisfied) Color(0xFF69F0AE) else Color(0xFFFF8A80),
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    status.requirement.console.displayName,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )
                Spacer(Modifier.weight(1f))
                Text(
                    if (status.satisfied) "pronto" else "opcional",
                    color = if (status.satisfied) Color(0xFF69F0AE) else Color.White.copy(alpha = 0.5f),
                    fontSize = 11.sp
                )
            }

            Spacer(Modifier.height(6.dp))
            Text(
                status.requirement.note,
                color = Color.White.copy(alpha = 0.5f),
                fontSize = 11.sp
            )

            if (status.requirement.files.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                status.files.forEach { file ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                file.file.name,
                                color = Color.White,
                                fontSize = 13.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                when {
                                    !file.exists -> "${file.file.label} — faltando"
                                    !file.sizeOk -> "${file.bytes / 1024} KB — tamanho inesperado"
                                    else -> "${file.file.label} — ${file.bytes / 1024} KB"
                                },
                                color = when {
                                    !file.exists -> Color.White.copy(alpha = 0.4f)
                                    !file.sizeOk -> Color(0xFFFFD180)
                                    else -> Color(0xFF69F0AE)
                                },
                                fontSize = 10.sp
                            )
                        }
                        if (file.exists) {
                            IconButton(onClick = { onDelete(file.file.name) }) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = "Remover",
                                    tint = Color.White.copy(alpha = 0.5f),
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                        TextButton(onClick = { onImport(file.file.name) }) {
                            Icon(
                                Icons.Default.UploadFile,
                                contentDescription = null,
                                tint = NeonBlue,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text("Importar", color = NeonBlue, fontSize = 11.sp)
                        }
                    }
                }
            }
        }
    }
}
