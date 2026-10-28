package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.domain.Console

@Entity(tableName = "games")
data class Game(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val console: Console,
    val filePath: String,
    val coverUri: String? = null,
    val isFavorite: Boolean = false,
    val lastPlayed: Long? = null,
    val dateAdded: Long = System.currentTimeMillis()
)
