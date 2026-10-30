package com.retrobox.ui.emulator

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.retrobox.domain.Console
import com.retrobox.emulation.ConsoleInput
import com.retrobox.emulation.InputProfile
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Controle virtual na tela.
 *
 * Diferente do overlay antigo (botões fixos), este respeita o layout de cada
 * console, tem analógico, estilo, escala, opacidade, háptico e pode se
 * esconder sozinho depois de um tempo sem toque.
 */
@Composable
fun TouchControls(
    console: Console,
    layout: ConsoleInput.Layout,
    profile: InputProfile,
    onPress: (Int, Boolean) -> Unit,
    onAnalog: (Int, Int, Float) -> Unit,
    onActivity: () -> Unit,
    modifier: Modifier = Modifier
) {
    val style = TouchStyle.of(profile.onScreenStyle)
    val scale = profile.onScreenScale.coerceIn(50, 160) / 100f
    val alpha = profile.onScreenOpacity.coerceIn(10, 100) / 100f
    val haptic = LocalHapticFeedback.current

    Box(modifier = modifier.fillMaxSize().alpha(alpha)) {
        // Direcional (esquerda) ou direcional centralizado (N64).
        val dpad = layout.buttons.filter { it.shape == ConsoleInput.Shape.DPAD }
        if (dpad.isNotEmpty()) {
            DPad(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 18.dp),
                size = 132.dp * scale,
                buttons = dpad,
                style = style,
                onPress = { id, pressed ->
                    haptic.performHapticFeedback(
                        if (pressed) HapticFeedbackType.LongPress else HapticFeedbackType.TextHandleMove
                    )
                    onPress(id, pressed)
                    onActivity()
                }
            )
        }

        // Botões de face / gatilhos / sistema.
        val faces = layout.buttons.filter { it.shape == ConsoleInput.Shape.FACE }
        val triggers = layout.buttons.filter { it.shape == ConsoleInput.Shape.TRIGGER }
        val system = layout.buttons.filter { it.shape == ConsoleInput.Shape.SYSTEM }

        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 18.dp),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            triggers.forEach { button ->
                PillKey(
                    label = button.label,
                    shape = style.systemShape,
                    width = 74.dp * scale,
                    height = 34.dp * scale,
                    fill = style.triggerFill,
                    content = style.triggerText
                ) { pressed ->
                    onPress(button.id, pressed)
                    onActivity()
                }
            }
            if (faces.isNotEmpty()) {
                FaceCluster(
                    buttons = faces,
                    size = 150.dp * scale,
                    style = style
                ) { id, pressed ->
                    onPress(id, pressed)
                    onActivity()
                }
            }
        }

        // Botões de sistema (centro inferior).
        if (system.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                system.forEach { button ->
                    PillKey(
                        label = button.label,
                        shape = style.systemShape,
                        width = 72.dp * scale,
                        height = 30.dp * scale,
                        fill = style.systemFill,
                        content = style.systemText
                    ) { pressed ->
                        onPress(button.id, pressed)
                        onActivity()
                    }
                }
            }
        }

        // Analógico (N64, PSP, DS…).
        if (layout.hasAnalog && profile.showAnalog) {
            AnalogStick(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 22.dp, bottom = 18.dp),
                size = 118.dp * scale,
                style = style,
                onAxis = { index, axis, value ->
                    onAnalog(index, axis, value)
                    onActivity()
                }
            )
        }
    }
}

/** Aparência do controle virtual. */
enum class TouchStyle(val id: Int) {
    WIIU(0),
    SWITCH(1),
    FLAT(2),
    OUTLINE(3),
    CLASSIC(4);

    companion object {
        fun of(id: Int): TouchStyle = entries.firstOrNull { it.id == id } ?: WIIU
    }

    val faceFill: Color
        get() = when (this) {
            WIIU -> Color(0xFF2E3440)
            SWITCH -> Color(0xFF1F2430)
            FLAT -> Color(0x33121A2A)
            OUTLINE -> Color.Transparent
            CLASSIC -> Color(0xFF3A3F4B)
        }

    val faceBorder: Color
        get() = when (this) {
            OUTLINE -> Color(0xFF7FD8FF)
            FLAT -> Color.Transparent
            else -> Color(0xFF7FD8FF)
        }

    val faceText: Color
        get() = when (this) {
            WIIU -> Color(0xFFD7E3F4)
            SWITCH -> Color(0xFFEFF3FA)
            FLAT -> Color(0xFFD7E3F4)
            OUTLINE -> Color(0xFF7FD8FF)
            CLASSIC -> Color(0xFFFFE9A8)
        }

    val triggerFill: Color get() = Color(0xCC2A3140)
    val triggerText: Color get() = Color(0xFFD7E3F4)
    val systemFill: Color get() = Color(0x992A3140)
    val systemText: Color get() = Color(0xFFD7E3F4)

    val systemShape: Shape get() = RoundedCornerShape(14.dp)
    val dpadShape: Shape get() = CircleShape
    val faceShape: Shape get() = CircleShape
}

/** Direcional em cruz. */
@Composable
private fun DPad(
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp,
    buttons: List<ConsoleInput.Button>,
    style: TouchStyle,
    onPress: (Int, Boolean) -> Unit
) {
    val key = size * 0.34f
    fun button(id: Int): ConsoleInput.Button? = buttons.firstOrNull { it.id == id }

    // a cruz ocupa 3x3 células: direcional no meio
    Box(modifier = modifier.size(key * 3)) {
        PadCell(button(4)?.id, "↑", key, key, 0.dp, style, onPress)
        PadCell(button(5)?.id, "↓", key, key, key * 2, style, onPress)
        PadCell(button(6)?.id, "←", key, 0.dp, key, style, onPress)
        PadCell(button(7)?.id, "→", key, key * 2, key, style, onPress)
    }
}

@Composable
private fun PadCell(
    id: Int?,
    label: String,
    size: androidx.compose.ui.unit.Dp,
    dx: androidx.compose.ui.unit.Dp,
    dy: androidx.compose.ui.unit.Dp,
    style: TouchStyle,
    onPress: (Int, Boolean) -> Unit
) {
    if (id == null) return
    Box(
        modifier = Modifier
            .offset(x = dx, y = dy)
            .size(size)
            .clip(style.dpadShape)
            .background(style.faceFill)
            .pointerInput(id) {
                detectTapGestures(
                    onPress = {
                        onPress(id, true)
                        tryAwaitRelease()
                        onPress(id, false)
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = style.faceText, fontSize = 18.sp, fontWeight = FontWeight.Bold)
    }
}

/** Botões de face em losango (posições 0=sup.esq, 1=sup.dir, 2=inf.esq, 3=inf.dir). */
@Composable
private fun FaceCluster(
    buttons: List<ConsoleInput.Button>,
    size: androidx.compose.ui.unit.Dp,
    style: TouchStyle,
    onPress: (Int, Boolean) -> Unit
) {
    val key = size * 0.32f
    val top = buttons.filter { it.slot <= ConsoleInput.TOP_RIGHT }
    val bottom = buttons.filter { it.slot == ConsoleInput.BOTTOM_LEFT || it.slot == ConsoleInput.BOTTOM_RIGHT }
    val extra = buttons.filter { it.slot > ConsoleInput.BOTTOM_RIGHT }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        if (top.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(key * 0.5f)) {
                top.sortedBy { it.slot }.forEach { button ->
                    FaceKey(button, key, style) { id, pressed -> onPress(id, pressed) }
                }
            }
        }
        Spacer(Modifier.height(key * 0.2f))
        if (bottom.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(key * 0.5f)) {
                bottom.sortedBy { it.slot }.forEach { button ->
                    FaceKey(button, key, style) { id, pressed -> onPress(id, pressed) }
                }
            }
        }
        if (extra.isNotEmpty()) {
            Spacer(Modifier.height(key * 0.2f))
            Row(horizontalArrangement = Arrangement.spacedBy(key * 0.5f)) {
                extra.sortedBy { it.slot }.forEach { button ->
                    FaceKey(button, key, style) { id, pressed -> onPress(id, pressed) }
                }
            }
        }
    }
}

@Composable
private fun FaceKey(
    button: ConsoleInput.Button,
    size: androidx.compose.ui.unit.Dp,
    style: TouchStyle,
    onPress: (Int, Boolean) -> Unit
) {
    var pressed by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .size(size)
            .clip(style.faceShape)
            .background(if (pressed) style.faceBorder.copy(alpha = 0.5f) else style.faceFill)
            .pointerInput(button.id) {
                detectTapGestures(
                    onPress = {
                        pressed = true
                        onPress(button.id, true)
                        tryAwaitRelease()
                        pressed = false
                        onPress(button.id, false)
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Text(
            button.label,
            color = style.faceText,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

/** Botão retangular (L, R, START, SELECT). */
@Composable
private fun PillKey(
    label: String,
    shape: Shape,
    width: androidx.compose.ui.unit.Dp,
    height: androidx.compose.ui.unit.Dp,
    fill: Color,
    content: Color,
    onPress: (Boolean) -> Unit
) {
    var pressed by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .width(width)
            .height(height)
            .clip(shape)
            .background(if (pressed) fill.copy(alpha = 0.6f) else fill)
            .pointerInput(label) {
                detectTapGestures(
                    onPress = {
                        pressed = true
                        onPress(true)
                        tryAwaitRelease()
                        pressed = false
                        onPress(false)
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = content, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

/** Analógico: arrasta e reporta o vetor normalizado (-1..1). */
@Composable
private fun AnalogStick(
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp,
    style: TouchStyle,
    onAxis: (Int, Int, Float) -> Unit
) {
    var knob by remember { mutableFloatStateOf(0f) }
    var knobY by remember { mutableFloatStateOf(0f) }
    val radius = size / 2

    fun report(x: Float, y: Float) {
        val len = hypot(x, y)
        val nx: Float
        val ny: Float
        if (len > 1f) {
            nx = x / len
            ny = y / len
        } else {
            nx = x
            ny = y
        }
        // eixo 0 = X, eixo 1 = Y; stick esquerdo (índice 0)
        onAxis(0, 0, nx)
        onAxis(0, 1, ny)
    }

    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(style.faceFill.copy(alpha = 0.55f))
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { offset ->
                        knob = (offset.x - radius.toPx()) / radius.toPx()
                        knobY = (offset.y - radius.toPx()) / radius.toPx()
                        report(knob, knobY)
                    },
                    onDrag = { change, _ ->
                        val v = change.position
                        knob = ((v.x - radius.toPx()) / radius.toPx()).coerceIn(-1f, 1f)
                        knobY = ((v.y - radius.toPx()) / radius.toPx()).coerceIn(-1f, 1f)
                        report(knob, knobY)
                    },
                    onDragEnd = {
                        knob = 0f
                        knobY = 0f
                        report(0f, 0f)
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(size * 0.42f)
                .offset(x = (size * 0.28f * knob), y = (size * 0.28f * knobY))
                .clip(CircleShape)
                .background(style.faceBorder.copy(alpha = 0.75f))
        )
    }
}
