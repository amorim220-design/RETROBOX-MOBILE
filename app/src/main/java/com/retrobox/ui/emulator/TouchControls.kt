package com.retrobox.ui.emulator

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.graphics.Brush
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
        // Painel de fundo dos estilos Neon/Arcade (o jogo continua visível acima).
        style.panel?.let { panelColor ->
            val shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp)
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(268.dp * scale)
                    .clip(shape)
                    .background(
                        Brush.verticalGradient(
                            0f to panelColor.copy(alpha = panelColor.alpha * 0.1f),
                            0.3f to panelColor,
                            1f to panelColor
                        )
                    )
                    .border(1.5.dp, style.panelBorder.copy(alpha = 0.6f), shape)
            )
        }

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
                    content = style.triggerText,
                    border = if (style.panel != null) style.panelBorder.copy(alpha = 0.75f) else Color.Transparent
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
                        content = style.systemText,
                        border = if (style.panel != null) style.panelBorder.copy(alpha = 0.9f) else Color.Transparent
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
    CLASSIC(4),
    NEON(5),
    ARCADE(6);

    companion object {
        fun of(id: Int): TouchStyle = entries.firstOrNull { it.id == id } ?: WIIU
    }

    val displayName: String
        get() = when (this) {
            WIIU -> "Wii U"
            SWITCH -> "Switch"
            FLAT -> "Flat"
            OUTLINE -> "Contorno"
            CLASSIC -> "Clássico"
            NEON -> "Neon"
            ARCADE -> "Arcade"
        }

    /** Fundo do painel atrás dos controles (null = direto sobre o jogo). */
    val panel: Color?
        get() = when (this) {
            NEON -> Color(0xE6060C1A)
            ARCADE -> Color(0xE61A0308)
            else -> null
        }

    val panelBorder: Color
        get() = when (this) {
            NEON -> Color(0xFF1E9BFF)
            ARCADE -> Color(0xFFE01B2E)
            else -> Color.Transparent
        }

    /** Botões em relevo (brilho/esfera) em vez de chapados. */
    val glossy: Boolean get() = this == NEON || this == ARCADE

    /** Direcional desenhado como anel redondo (e não como cruz). */
    val dpadRing: Boolean get() = this == NEON || this == ARCADE

    /** Analógico com bolinha de manche (estilo fliperama). */
    val ballStick: Boolean get() = this == ARCADE

    val faceFill: Color
        get() = when (this) {
            WIIU -> Color(0xFF2E3440)
            SWITCH -> Color(0xFF1F2430)
            FLAT -> Color(0x33121A2A)
            OUTLINE -> Color.Transparent
            CLASSIC -> Color(0xFF3A3F4B)
            NEON -> Color(0xFF0B1526)
            ARCADE -> Color(0xFF1B1B1B)
        }

    val faceBorder: Color
        get() = when (this) {
            OUTLINE -> Color(0xFF7FD8FF)
            FLAT -> Color.Transparent
            NEON -> Color(0xFF1E9BFF)
            ARCADE -> Color(0xFFE01B2E)
            else -> Color(0xFF7FD8FF)
        }

    val faceText: Color
        get() = when (this) {
            WIIU -> Color(0xFFD7E3F4)
            SWITCH -> Color(0xFFEFF3FA)
            FLAT -> Color(0xFFD7E3F4)
            OUTLINE -> Color(0xFF7FD8FF)
            CLASSIC -> Color(0xFFFFE9A8)
            NEON -> Color(0xFFFFFFFF)
            ARCADE -> Color(0xFFFFFFFF)
        }

    /**
     * Cor de destaque do botão de face.
     *
     * Neon segue as cores do controle (verde/vermelho/azul/amarelo, como na
     * referência). Arcade usa a paleta de seis botões de fliperama, escolhida
     * pela posição para ficar igual em qualquer console.
     */
    fun accent(label: String, slot: Int): Color = when (this) {
        NEON -> when (label.trim().uppercase()) {
            "A" -> Color(0xFF2BE36B)
            "B", "○" -> Color(0xFFFF3355)
            "X", "✕" -> Color(0xFF2FA8FF)
            "Y", "△" -> Color(0xFFFFD524)
            "□" -> Color(0xFFFF4FD8)
            "START" -> Color(0xFF2BE36B)
            else -> Color(0xFF1E9BFF)
        }
        ARCADE -> when (slot) {
            0 -> Color(0xFFE8262B)   // vermelho
            1 -> Color(0xFFFFC417)   // amarelo
            2 -> Color(0xFF9B2BE8)   // roxo
            3 -> Color(0xFF14A02B)   // verde
            4 -> Color(0xFF1D6BF0)   // azul
            else -> Color(0xFFFF7A00) // laranja
        }
        else -> faceText
    }

    val triggerFill: Color
        get() = when (this) {
            NEON -> Color(0xCC0B1B33)
            ARCADE -> Color(0xCC22060A)
            else -> Color(0xCC2A3140)
        }
    val triggerText: Color
        get() = when (this) {
            NEON -> Color(0xFF7FD3FF)
            ARCADE -> Color(0xFFFFB0B8)
            else -> Color(0xFFD7E3F4)
        }
    val systemFill: Color
        get() = when (this) {
            NEON -> Color(0xCC0B1B33)
            ARCADE -> Color(0xCC22060A)
            else -> Color(0x992A3140)
        }
    val systemText: Color
        get() = when (this) {
            NEON -> Color(0xFFE8F6FF)
            ARCADE -> Color(0xFFFFD9DC)
            else -> Color(0xFFD7E3F4)
        }

    val systemShape: Shape get() = RoundedCornerShape(14.dp)
    val dpadShape: Shape get() = if (dpadRing) CircleShape else CircleShape
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
        if (style.dpadRing) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .clip(CircleShape)
                    .background(style.faceFill.copy(alpha = 0.55f))
                    .border(2.dp, style.faceBorder.copy(alpha = 0.55f), CircleShape)
            )
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(key * 1.02f)
                    .clip(CircleShape)
                    .background(style.faceFill.copy(alpha = 0.7f))
                    .border(1.5.dp, style.faceBorder.copy(alpha = 0.35f), CircleShape)
            )
        }
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
            .background(if (style.dpadRing) Color.Transparent else style.faceFill)
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
        Text(
            label,
            color = if (style.dpadRing) style.faceBorder else style.faceText,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold
        )
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
    val accent = style.accent(button.label, button.slot)

    val brush: Brush = when {
        !style.glossy -> androidx.compose.ui.graphics.SolidColor(style.faceFill)
        pressed -> Brush.radialGradient(
            listOf(accent, accent.copy(alpha = 0.55f), Color(0xFF0A0A0A))
        )
        else -> Brush.radialGradient(
            listOf(
                accent.copy(alpha = 0.95f),
                accent.copy(alpha = 0.7f),
                Color(0xFF0A0A0A)
            )
        )
    }

    Box(
        modifier = Modifier
            .size(size)
            .clip(style.faceShape)
            .background(brush, style.faceShape)
            .then(
                if (style.glossy) {
                    Modifier.border(2.dp, accent.copy(alpha = if (pressed) 1f else 0.85f), style.faceShape)
                } else {
                    Modifier
                }
            )
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
        // brilho superior: dá o efeito de botão de acrílico
        if (style.glossy && !pressed) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 4.dp)
                    .fillMaxWidth(0.45f)
                    .height(size * 0.16f)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.22f))
            )
        }
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
    border: Color = Color.Transparent,
    onPress: (Boolean) -> Unit
) {
    var pressed by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .width(width)
            .height(height)
            .clip(shape)
            .background(if (pressed) fill.copy(alpha = 0.6f) else fill)
            .then(if (border != Color.Transparent) Modifier.border(1.5.dp, border, shape) else Modifier)
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
            .then(
                if (style.dpadRing) {
                    Modifier.border(2.dp, style.faceBorder.copy(alpha = 0.5f), CircleShape)
                } else {
                    Modifier
                }
            )
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
                .size(if (style.ballStick) size * 0.5f else size * 0.42f)
                .offset(x = (size * 0.28f * knob), y = (size * 0.28f * knobY))
                .clip(CircleShape)
                .background(
                    if (style.ballStick) {
                        Brush.radialGradient(
                            listOf(Color(0xFFFF5A5A), Color(0xFFD3101B), Color(0xFF6B0309))
                        )
                    } else {
                        androidx.compose.ui.graphics.SolidColor(style.faceBorder.copy(alpha = 0.75f))
                    }
                )
                .then(
                    if (style.ballStick) {
                        Modifier.border(1.5.dp, Color(0xFF2A0206), CircleShape)
                    } else {
                        Modifier
                    }
                )
        )
    }
}
