package com.retrobox.emulation

import android.content.Context
import android.view.InputDevice
import android.view.MotionEvent

/**
 * Descoberta e mapeamento de controles (fÃ­sicos, USB ou Bluetooth).
 *
 * O Android jÃ¡ entrega qualquer gamepad como [InputDevice]; o que falta Ã©
 * dizer **qual** controle Ã©, em que **porta** ele entra e o que fazer quando
 * ele aparece ou some. O Retro64 (Mupen64Plus AE) faz o mesmo com
 * `PlayerMap`; aqui fazemos uma versÃ£o enxuta.
 */
class GamepadManager(private val context: Context) {

    data class Device(
        val id: Int,
        val name: String,
        val isGamepad: Boolean,
        val isConnected: Boolean
    )

    /** deviceId -> porta libretro (1..4) */
    private val ports = LinkedHashMap<Int, Int>()

    /** Quando ligado, um Ãºnico controlemove mais de um jogador. */
    var shareController: Boolean = false

    val connected: List<Device> get() = devices()

    /** Lista os dispositivos de entrada que o Android conhece. */
    fun devices(): List<Device> {
        val out = mutableListOf<Device>()
        for (id in InputDevice.getDeviceIds()) {
            val device = InputDevice.getDevice(id) ?: continue
            val name = device.name ?: "Controle $id"
            val isGamepad = isGamepad(device)
            out += Device(id, name, isGamepad, true)
        }
        return out
    }

    fun deviceName(deviceId: Int): String? =
        InputDevice.getDevice(deviceId)?.name ?: devices().firstOrNull { it.id == deviceId }?.name

    fun isGamepadEvent(source: Int): Boolean =
        (source and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
            (source and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK

    private fun isGamepad(device: InputDevice): Boolean =
        (device.sources and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
            (device.sources and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK

    /** Porta (1..4) de um controle; atribui a primeira livre se for novo. */
    fun portFor(deviceId: Int): Int {
        ports[deviceId]?.let { return it }
        val used = ports.values.toSet()
        val port = (1..4).firstOrNull { it !in used } ?: 1
        ports[deviceId] = port
        return port
    }

    /** Troca o controle de um jogador para outro controle. */
    fun assign(deviceId: Int, port: Int) {
        ports[deviceId] = port.coerceIn(1, 4)
    }

    fun clear() = ports.clear()

    /** Identificadores de eixo que o Android usa para os dois sticks. */
    fun axisInfo(): String {
        val axes = mutableListOf<String>()
        for (device in devices()) {
            if (!device.isGamepad) continue
            val d = InputDevice.getDevice(device.id) ?: continue
            val list = d.getMotionRanges().map {
                "${it.axis}:${MotionEvent.axisToString(it.axis)}"
            }
            if (list.isNotEmpty()) axes += "${device.name} -> ${list.joinToString(", ")}"
        }
        return axes.joinToString("\n")
    }
}
