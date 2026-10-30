package com.retrobox.ui.screens.bios

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.retrobox.RetroBoxApplication
import com.retrobox.domain.Console
import com.retrobox.emulation.BiosManager
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class BiosViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as RetroBoxApplication

    private val _statuses = MutableStateFlow<List<BiosManager.Status>>(emptyList())
    val statuses: StateFlow<List<BiosManager.Status>> = _statuses.asStateFlow()

    private val _message = MutableSharedFlow<String>(extraBufferCapacity = 2)
    val message: SharedFlow<String> = _message

    init {
        reload()
    }

    fun reload() {
        viewModelScope.launch {
            _statuses.value = BiosManager.requirements.mapNotNull { requirement ->
                BiosManager.status(app, requirement.console)
            }
        }
    }

    fun import(console: Console, preferredName: String, uri: Uri) {
        viewModelScope.launch {
            val result = BiosManager.import(app, console, uri, preferredName)
            _message.emit(result.message)
            if (result.ok) reload()
        }
    }

    fun delete(console: Console, name: String) {
        viewModelScope.launch {
            val removed = BiosManager.delete(app, name)
            _message.emit(if (removed) "$name removido" else "Não foi possível remover $name")
            reload()
        }
    }
}
