package com.retrobox.emulation

import com.retrobox.domain.Console

/**
 * Como cada console mapeia os botões.
 *
 * O libretro usa um "RetroPad" padrão (B=0, Y=1, SELECT=2, START=3, direcional=4..7,
 * A=8, X=9, L=10, R=11), mas **cada core tem suas manias**:
 *
 *  - NES (FCEUmm) tem A e B invertidos em relação ao padrão;
 *  - Mega Drive (Genesis Plus GX) usa Y para o botão C e X para o Z;
 *  - N64 (Mupen64Plus-Next) reaproveta ids "estranhos" (C no SELECT/START, Start no L);
 *  - DS/GBA/PSX/PSP seguem o padrão (A = X/cruz).
 *
 * Por isso o app mostra o layout correto por console **e** deixa o usuário
 * remapear qualquer botão (ver [InputProfile]).
 */
object ConsoleInput {

    /** Formato do botão na tela (para desenhar o controle certo). */
    enum class Shape { DPAD, FACE, BUTTON, TRIGGER, SYSTEM, ANALOG }

    /** Posição no losango: 0 = sup. esq., 1 = sup. dir., 2 = inf. esq., 3 = inf. dir. */
    const val TOP_LEFT = 0
    const val TOP_RIGHT = 1
    const val BOTTOM_LEFT = 2
    const val BOTTOM_RIGHT = 3

    data class Button(
        /** id libretro do botão */
        val id: Int,
        /** rótulo visível */
        val label: String,
        val shape: Shape,
        /** posição no losango (ver [TOP_LEFT]…) */
        val slot: Int = 0
    )

    data class Layout(
        val console: Console,
        /** botões que aparecem no controle virtual */
        val buttons: List<Button>,
        /** o console usa analógico (N64, PSP, DS…) */
        val hasAnalog: Boolean = false,
        /** o direcional é centralizado (N64) */
        val centralDpad: Boolean = false,
        /** o console tem botão de sensor (NDS) */
        val hasSensor: Boolean = false
    )

    private fun dpad() = listOf(
        Button(RetroButton.UP, "↑", Shape.DPAD),
        Button(RetroButton.DOWN, "↓", Shape.DPAD),
        Button(RetroButton.LEFT, "←", Shape.DPAD),
        Button(RetroButton.RIGHT, "→", Shape.DPAD)
    )

    private val layouts: Map<Console, Layout> = mapOf(
        // NES: A e B trocados em relação ao padrão do RetroPad.
        Console.NES to Layout(
            console = Console.NES,
            buttons = listOf(
                Button(RetroButton.B, "A", Shape.FACE, BOTTOM_RIGHT),
                Button(RetroButton.A, "B", Shape.FACE, BOTTOM_LEFT),
                Button(RetroButton.SELECT, "SELECT", Shape.SYSTEM),
                Button(RetroButton.START, "START", Shape.SYSTEM)
            ) + dpad()
        ),
        // SNES: Y/X em cima, B/A embaixo.
        Console.SNES to Layout(
            console = Console.SNES,
            buttons = listOf(
                Button(RetroButton.Y, "Y", Shape.FACE, TOP_LEFT),
                Button(RetroButton.X, "X", Shape.FACE, TOP_RIGHT),
                Button(RetroButton.B, "B", Shape.FACE, BOTTOM_LEFT),
                Button(RetroButton.A, "A", Shape.FACE, BOTTOM_RIGHT),
                Button(RetroButton.L, "L", Shape.TRIGGER),
                Button(RetroButton.R, "R", Shape.TRIGGER),
                Button(RetroButton.SELECT, "SELECT", Shape.SYSTEM),
                Button(RetroButton.START, "START", Shape.SYSTEM)
            ) + dpad()
        ),
        // Game Boy: só A e B + direcional + START/SELECT.
        Console.GB to Layout(
            console = Console.GB,
            buttons = listOf(
                Button(RetroButton.B, "B", Shape.FACE, BOTTOM_LEFT),
                Button(RetroButton.A, "A", Shape.FACE, BOTTOM_RIGHT),
                Button(RetroButton.SELECT, "SELECT", Shape.SYSTEM),
                Button(RetroButton.START, "START", Shape.SYSTEM)
            ) + dpad()
        ),
        Console.GBC to Layout(
            console = Console.GBC,
            buttons = listOf(
                Button(RetroButton.B, "B", Shape.FACE, BOTTOM_LEFT),
                Button(RetroButton.A, "A", Shape.FACE, BOTTOM_RIGHT),
                Button(RetroButton.SELECT, "SELECT", Shape.SYSTEM),
                Button(RetroButton.START, "START", Shape.SYSTEM)
            ) + dpad()
        ),
        // GBA: X/Y em cima, B/A embaixo.
        Console.GBA to Layout(
            console = Console.GBA,
            buttons = listOf(
                Button(RetroButton.X, "X", Shape.FACE, TOP_LEFT),
                Button(RetroButton.Y, "Y", Shape.FACE, TOP_RIGHT),
                Button(RetroButton.B, "B", Shape.FACE, BOTTOM_LEFT),
                Button(RetroButton.A, "A", Shape.FACE, BOTTOM_RIGHT),
                Button(RetroButton.L, "L", Shape.TRIGGER),
                Button(RetroButton.R, "R", Shape.TRIGGER),
                Button(RetroButton.SELECT, "SELECT", Shape.SYSTEM),
                Button(RetroButton.START, "START", Shape.SYSTEM)
            ) + dpad()
        ),
        // Mega Drive: A, B e C (C vem no Y do RetroPad) + START.
        Console.MEGADRIVE to Layout(
            console = Console.MEGADRIVE,
            buttons = listOf(
                Button(RetroButton.A, "A", Shape.FACE, BOTTOM_LEFT),
                Button(RetroButton.B, "B", Shape.FACE, BOTTOM_RIGHT),
                Button(RetroButton.Y, "C", Shape.FACE, TOP_RIGHT),
                Button(RetroButton.START, "START", Shape.SYSTEM)
            ) + dpad()
        ),
        // N64: A, B, Z (Y) e o direcional centralizado (C no SELECT/START).
        Console.N64 to Layout(
            console = Console.N64,
            buttons = listOf(
                Button(RetroButton.Y, "Z", Shape.FACE, TOP_RIGHT),
                Button(RetroButton.B, "B", Shape.FACE, BOTTOM_LEFT),
                Button(RetroButton.A, "A", Shape.FACE, BOTTOM_RIGHT),
                Button(RetroButton.SELECT, "C ▲", Shape.FACE, 4),
                Button(RetroButton.START, "C ▼", Shape.FACE, 5),
                Button(RetroButton.UP, "C ◄", Shape.FACE, 6),
                Button(RetroButton.DOWN, "C ►", Shape.FACE, 7),
                Button(RetroButton.LEFT, "L", Shape.TRIGGER),
                Button(RetroButton.RIGHT, "R", Shape.TRIGGER)
            ),
            hasAnalog = true,
            centralDpad = true
        ),
        // PS1: △=Y, □=X, ✕=A, ○=B.
        Console.PS1 to Layout(
            console = Console.PS1,
            buttons = listOf(
                Button(RetroButton.Y, "△", Shape.FACE, TOP_LEFT),
                Button(RetroButton.X, "□", Shape.FACE, TOP_RIGHT),
                Button(RetroButton.A, "✕", Shape.FACE, BOTTOM_LEFT),
                Button(RetroButton.B, "○", Shape.FACE, BOTTOM_RIGHT),
                Button(RetroButton.L, "L1", Shape.TRIGGER),
                Button(RetroButton.R, "R1", Shape.TRIGGER),
                Button(RetroButton.L2, "L2", Shape.TRIGGER),
                Button(RetroButton.R2, "R2", Shape.TRIGGER),
                Button(RetroButton.SELECT, "SELECT", Shape.SYSTEM),
                Button(RetroButton.START, "START", Shape.SYSTEM)
            ) + dpad()
        ),
        // Nintendo DS: padrão + tela de toque.
        Console.NDS to Layout(
            console = Console.NDS,
            buttons = listOf(
                Button(RetroButton.X, "X", Shape.FACE, TOP_LEFT),
                Button(RetroButton.Y, "Y", Shape.FACE, TOP_RIGHT),
                Button(RetroButton.B, "B", Shape.FACE, BOTTOM_LEFT),
                Button(RetroButton.A, "A", Shape.FACE, BOTTOM_RIGHT),
                Button(RetroButton.L, "L", Shape.TRIGGER),
                Button(RetroButton.R, "R", Shape.TRIGGER),
                Button(RetroButton.SELECT, "SELECT", Shape.SYSTEM),
                Button(RetroButton.START, "START", Shape.SYSTEM)
            ) + dpad(),
            hasSensor = true
        ),
        // PSP: analógico + botões.
        Console.PSP to Layout(
            console = Console.PSP,
            buttons = listOf(
                Button(RetroButton.Y, "△", Shape.FACE, TOP_LEFT),
                Button(RetroButton.X, "□", Shape.FACE, TOP_RIGHT),
                Button(RetroButton.A, "✕", Shape.FACE, BOTTOM_LEFT),
                Button(RetroButton.B, "○", Shape.FACE, BOTTOM_RIGHT),
                Button(RetroButton.L, "L", Shape.TRIGGER),
                Button(RetroButton.R, "R", Shape.TRIGGER),
                Button(RetroButton.SELECT, "SELECT", Shape.SYSTEM),
                Button(RetroButton.START, "START", Shape.SYSTEM)
            ) + dpad(),
            hasAnalog = true
        )
    )

    fun forConsole(console: Console): Layout =
        layouts[console] ?: Layout(
            console,
            listOf(
                Button(RetroButton.X, "X", Shape.FACE, TOP_LEFT),
                Button(RetroButton.Y, "Y", Shape.FACE, TOP_RIGHT),
                Button(RetroButton.B, "B", Shape.FACE, BOTTOM_LEFT),
                Button(RetroButton.A, "A", Shape.FACE, BOTTOM_RIGHT),
                Button(RetroButton.L, "L", Shape.TRIGGER),
                Button(RetroButton.R, "R", Shape.TRIGGER),
                Button(RetroButton.SELECT, "SELECT", Shape.SYSTEM),
                Button(RetroButton.START, "START", Shape.SYSTEM)
            ) + dpad()
        )

    /**
     * Mapa padrão: botão físico do Android -> id libretro.
     *
     * Usamos a convenção "botão físico" = posição no pad (Xbox/DualShock):
     * A = canto inferior, B = canto direito, X = canto esquerdo, Y = cima.
     * Depois o [ConsoleInput] ajusta por console.
     */
    fun defaultMapping(console: Console): Map<Int, Int> {
        val map = LinkedHashMap<Int, Int>()
        map[android.view.KeyEvent.KEYCODE_BUTTON_A] = RetroButton.A
        map[android.view.KeyEvent.KEYCODE_BUTTON_B] = RetroButton.B
        map[android.view.KeyEvent.KEYCODE_BUTTON_X] = RetroButton.X
        map[android.view.KeyEvent.KEYCODE_BUTTON_Y] = RetroButton.Y
        map[android.view.KeyEvent.KEYCODE_BUTTON_L1] = RetroButton.L
        map[android.view.KeyEvent.KEYCODE_BUTTON_R1] = RetroButton.R
        map[android.view.KeyEvent.KEYCODE_BUTTON_L2] = RetroButton.L2
        map[android.view.KeyEvent.KEYCODE_BUTTON_R2] = RetroButton.R2
        map[android.view.KeyEvent.KEYCODE_BUTTON_THUMBL] = RetroButton.L3
        map[android.view.KeyEvent.KEYCODE_BUTTON_THUMBR] = RetroButton.R3
        map[android.view.KeyEvent.KEYCODE_BUTTON_START] = RetroButton.START
        map[android.view.KeyEvent.KEYCODE_BUTTON_SELECT] = RetroButton.SELECT
        map[android.view.KeyEvent.KEYCODE_DPAD_UP] = RetroButton.UP
        map[android.view.KeyEvent.KEYCODE_DPAD_DOWN] = RetroButton.DOWN
        map[android.view.KeyEvent.KEYCODE_DPAD_LEFT] = RetroButton.LEFT
        map[android.view.KeyEvent.KEYCODE_DPAD_RIGHT] = RetroButton.RIGHT

        // Teclado (útil no emulador de PC / alguns controles BT)
        map[android.view.KeyEvent.KEYCODE_ENTER] = RetroButton.START
        map[android.view.KeyEvent.KEYCODE_SPACE] = RetroButton.A
        map[android.view.KeyEvent.KEYCODE_SHIFT_LEFT] = RetroButton.B
        map[android.view.KeyEvent.KEYCODE_CTRL_LEFT] = RetroButton.B
        map[android.view.KeyEvent.KEYCODE_ALT_LEFT] = RetroButton.SELECT
        map[android.view.KeyEvent.KEYCODE_ALT_RIGHT] = RetroButton.START
        map[android.view.KeyEvent.KEYCODE_TAB] = RetroButton.SELECT
        map[android.view.KeyEvent.KEYCODE_Z] = RetroButton.X
        map[android.view.KeyEvent.KEYCODE_X] = RetroButton.Y
        map[android.view.KeyEvent.KEYCODE_S] = RetroButton.L
        map[android.view.KeyEvent.KEYCODE_D] = RetroButton.R

        // Ajuste por console (NES tem A/B trocados; N64 usa ids diferentes).
        return when (console) {
            Console.NES -> map.toMutableMap().apply {
                this[android.view.KeyEvent.KEYCODE_BUTTON_A] = RetroButton.B
                this[android.view.KeyEvent.KEYCODE_BUTTON_B] = RetroButton.A
                this[android.view.KeyEvent.KEYCODE_SPACE] = RetroButton.B
            }
            Console.N64 -> map.toMutableMap().apply {
                // N64: A, B, Z(Y) e C no SELECT/START/UP/DOWN.
                this[android.view.KeyEvent.KEYCODE_BUTTON_X] = RetroButton.Y
                this[android.view.KeyEvent.KEYCODE_BUTTON_Y] = RetroButton.SELECT
                this[android.view.KeyEvent.KEYCODE_BUTTON_L1] = RetroButton.START
            }
            Console.MEGADRIVE -> map.toMutableMap().apply {
                this[android.view.KeyEvent.KEYCODE_BUTTON_X] = RetroButton.Y // C
                this[android.view.KeyEvent.KEYCODE_BUTTON_Y] = RetroButton.X // Z
            }
            else -> map
        }
    }

    /** Rótulos dos ids libretro (para a tela de remapeamento). */
    fun labelFor(id: Int): String = when (id) {
        RetroButton.A -> "A"
        RetroButton.B -> "B"
        RetroButton.X -> "X"
        RetroButton.Y -> "Y"
        RetroButton.L -> "L"
        RetroButton.R -> "R"
        RetroButton.L2 -> "L2"
        RetroButton.R2 -> "R2"
        RetroButton.L3 -> "L3"
        RetroButton.R3 -> "R3"
        RetroButton.SELECT -> "SELECT"
        RetroButton.START -> "START"
        RetroButton.UP -> "↑"
        RetroButton.DOWN -> "↓"
        RetroButton.LEFT -> "←"
        RetroButton.RIGHT -> "→"
        else -> "?"
    }
}
