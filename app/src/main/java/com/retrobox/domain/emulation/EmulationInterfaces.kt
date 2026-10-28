package com.retrobox.domain.emulation

import com.retrobox.domain.Console

interface ConsoleAdapter {
    val systemId: String
    val supportedExtensions: List<String>
    
    fun isMotorAvailable(): Boolean
    fun getRequirements(): String?
    fun launchGame(filePath: String): Result<Unit>
    fun getErrorDiagnostics(): String?
}

interface EmulationManager {
    fun getAdapterFor(console: Console): ConsoleAdapter?
    fun registerAdapter(adapter: ConsoleAdapter)
    fun isSystemSupported(console: Console): Boolean
}
