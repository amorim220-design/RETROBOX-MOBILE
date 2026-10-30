package com.retrobox.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.retrobox.domain.Console

@Entity(
    tableName = "games",
    indices = [Index(value = ["filePath"], unique = true)]
)
data class Game(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val console: Console,
    val filePath: String,
    val coverUri: String? = null,
    val isFavorite: Boolean = false,
    val lastPlayed: Long? = null,
    val dateAdded: Long = System.currentTimeMillis(),
    /** Nome original do arquivo (usado para procurar a capa e exibir as tags). */
    val rawTitle: String? = null,
    /** Item do catálogo de origem (null quando importado manualmente). */
    val catalogId: String? = null,
    /** SHA-256 do conteúdo, quando conhecido (identificação/verificação). */
    val sha256: String? = null
)
