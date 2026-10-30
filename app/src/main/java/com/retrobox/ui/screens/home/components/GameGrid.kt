package com.retrobox.ui.screens.home.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Gamepad
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.retrobox.R
import com.retrobox.data.Game
import com.retrobox.ui.theme.NeonBlue
import com.retrobox.ui.theme.SurfaceVariantDark

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GameGrid(
    games: List<Game>,
    onGameClick: (Game) -> Unit,
    onToggleFavorite: (Game) -> Unit,
    /** Long-press: remover da biblioteca (opcional). */
    onDelete: ((Game, deleteFile: Boolean) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    var pendingDelete by remember { mutableStateOf<Game?>(null) }

    if (games.isEmpty()) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = "Nenhum jogo importado",
                style = MaterialTheme.typography.bodyLarge,
                color = Color.Gray
            )
        }
    } else {
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier = modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(games) { game ->
                GameCard(
                    game = game,
                    onClick = { onGameClick(game) },
                    onToggleFavorite = { onToggleFavorite(game) },
                    onLongClick = if (onDelete != null) {
                        { pendingDelete = game }
                    } else {
                        null
                    }
                )
            }
        }
    }

    pendingDelete?.let { game ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Remover “${game.title}”?") },
            text = {
                Text(
                    "Sai da biblioteca. O arquivo copiado para o app " +
                        "(files/roms/${game.console.id}/) pode ser apagado junto; " +
                        "o original que você importou não é afetado."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onDelete?.invoke(game, true)
                    pendingDelete = null
                }) { Text("Remover e apagar arquivo") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        onDelete?.invoke(game, false)
                        pendingDelete = null
                    }) { Text("Só da lista") }
                    TextButton(onClick = { pendingDelete = null }) { Text("Cancelar") }
                }
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GameCard(
    game: Game,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit,
    onLongClick: (() -> Unit)? = null
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.78f)
                .clip(RoundedCornerShape(12.dp))
                .background(SurfaceVariantDark)
        ) {
            if (game.coverUri != null) {
                AsyncImage(
                    model = game.coverUri,
                    contentDescription = game.title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                // placeholder: capa gerada pelo proprio app
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                listOf(
                                    NeonBlue.copy(alpha = 0.25f),
                                    SurfaceVariantDark,
                                    Color.Black
                                )
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.Gamepad,
                            contentDescription = null,
                            modifier = Modifier.size(36.dp),
                            tint = Color.White.copy(alpha = 0.35f)
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = game.title,
                            color = Color.White.copy(alpha = 0.75f),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 8.dp)
                        )
                    }
                }
            }

            // Favorite Overlay
            IconButton(
                onClick = onToggleFavorite,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .size(32.dp)
            ) {
                Icon(
                    imageVector = if (game.isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    contentDescription = null,
                    tint = if (game.isFavorite) NeonBlue else Color.White.copy(alpha = 0.7f),
                    modifier = Modifier.size(18.dp)
                )
            }
        }
        
        Spacer(modifier = Modifier.height(8.dp))
        
        Text(
            text = game.title,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp
            ),
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Default.Gamepad,
                contentDescription = null,
                modifier = Modifier.size(12.dp),
                tint = NeonBlue
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = game.console.displayName,
                style = MaterialTheme.typography.labelSmall,
                color = NeonBlue
            )
        }
    }
}
