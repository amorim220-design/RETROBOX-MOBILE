package com.retrobox.ui.screens.home.mascots

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import com.retrobox.domain.Console
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Um personagem que atravessa a parte de baixo da tela ao escolher um console.
 *
 * O desenho acontece em tempo real (vetor, antialias, transparência nativa):
 * nada de sprite pixelado nem imagem estática fingindo movimento. O desenho é
 * proporcional à altura da área de conteúdo ([DrawScope.size]), então a mesma
 * animação se adapta a qualquer tela sem asset extra.
 */
class Mascot(
    val id: String,
    val name: String,
    /** Duração total da passagem (entrada + ação + saída). */
    val durationMs: Int = 2600,
    val draw: DrawScope.(t: Float) -> Unit
)

/**
 * Registro de personagens, um por console.
 *
 * Novos consoles entram com [register] (ou direto no mapa) e o overlay já
 * funciona — nada de `when(console)` espalhado pela interface.
 */
object MascotRegistry {

    private val byConsole = LinkedHashMap<String, Mascot>()

    init {
        register("snes", snesMascot())
        register("gba", gbaMascot())
        register("megadrive", megadriveMascot())
        register("ps1", ps1Mascot())
        register("psp", pspMascot())
        register("n64", n64Mascot())
    }

    fun register(consoleId: String, mascot: Mascot) {
        byConsole[consoleId.lowercase()] = mascot
    }

    fun forConsole(console: Console): Mascot? = byConsole[console.id.lowercase()]

    fun all(): List<Mascot> = byConsole.values.toList()
}

// ---------------------------------------------------------------- utilidades ----

private fun easeOutCubic(x: Float): Float = 1f - (1f - x) * (1f - x) * (1f - x)
private fun easeInOutSine(x: Float): Float = (-(cos(PI * x) - 1f) / 2f).toFloat()
private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

/**
 * Corpo comum dos personagens: cabeça, tronco, braços e pernas animados.
 *
 * [run] é a fase da corrida (radianos); [lean] inclina o corpo para frente.
 */
private fun DrawScope.drawBody(
    center: Offset,
    unit: Float,
    run: Float,
    lean: Float,
    head: Color,
    torso: Color,
    limbs: Color,
    hair: Color? = null,
    scaleX: Float = 1f
) {
    val headR = unit * 0.30f
    val torsoW = unit * 0.40f * scaleX
    val torsoH = unit * 0.46f

    // sombra de contato suave (sem desenhar chão: só um halo curto)
    drawOval(
        brush = Brush.radialGradient(
            listOf(Color.Black.copy(alpha = 0.28f), Color.Transparent),
            center = Offset(center.x, center.y + unit * 0.62f),
            radius = unit * 0.55f
        ),
        topLeft = Offset(center.x - unit * 0.5f, center.y + unit * 0.44f),
        size = Size(unit * 1.0f, unit * 0.30f)
    )

    rotate(degrees = lean, pivot = Offset(center.x, center.y + unit * 0.45f)) {
        // pernas
        val legSwing = sin(run) * unit * 0.26f
        drawCapsule(
            start = Offset(center.x - unit * 0.09f, center.y + torsoH * 0.42f),
            end = Offset(center.x - unit * 0.09f + legSwing, center.y + unit * 0.60f),
            width = unit * 0.15f,
            color = limbs
        )
        drawCapsule(
            start = Offset(center.x + unit * 0.09f, center.y + torsoH * 0.42f),
            end = Offset(center.x + unit * 0.09f - legSwing, center.y + unit * 0.60f),
            width = unit * 0.15f,
            color = limbs
        )

        // braços
        val armSwing = sin(run + PI.toFloat()) * unit * 0.22f
        drawCapsule(
            start = Offset(center.x - torsoW * 0.45f, center.y - unit * 0.02f),
            end = Offset(center.x - torsoW * 0.62f - armSwing, center.y + unit * 0.20f),
            width = unit * 0.12f,
            color = limbs
        )
        drawCapsule(
            start = Offset(center.x + torsoW * 0.45f, center.y - unit * 0.02f),
            end = Offset(center.x + torsoW * 0.62f + armSwing, center.y + unit * 0.20f),
            width = unit * 0.12f,
            color = limbs
        )

        // tronco com gradiente (volume, sem contorno duro)
        drawRoundRect(
            brush = Brush.linearGradient(
                colors = listOf(torso.copy(alpha = 0.98f), torso.copy(alpha = 0.72f)),
                start = Offset(center.x - torsoW, center.y - torsoH * 0.5f),
                end = Offset(center.x + torsoW, center.y + torsoH * 0.6f)
            ),
            topLeft = Offset(center.x - torsoW / 2f, center.y - torsoH * 0.5f),
            size = Size(torsoW, torsoH),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(torsoW * 0.42f, torsoW * 0.42f)
        )

        // cabeça
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(head.copy(alpha = 0.35f), head),
                center = Offset(center.x - headR * 0.3f, center.y - torsoH * 0.5f - headR * 1.15f),
                radius = headR * 1.6f
            ),
            radius = headR,
            center = Offset(center.x, center.y - torsoH * 0.5f - headR)
        )
        // brilho da cabeça
        drawCircle(
            color = Color.White.copy(alpha = 0.22f),
            radius = headR * 0.30f,
            center = Offset(center.x - headR * 0.35f, center.y - torsoH * 0.5f - headR * 1.35f)
        )
        hair?.let {
            drawArc(
                color = it,
                startAngle = 190f,
                sweepAngle = 160f,
                useCenter = false,
                topLeft = Offset(
                    center.x - headR - unit * 0.02f,
                    center.y - torsoH * 0.5f - headR * 2f - unit * 0.02f
                ),
                size = Size(headR * 2f + unit * 0.04f, headR * 2f + unit * 0.04f),
                style = Stroke(width = headR * 0.62f, cap = StrokeCap.Round)
            )
        }
    }
}

private fun DrawScope.drawCapsule(start: Offset, end: Offset, width: Float, color: Color) {
    val dx = end.x - start.x
    val dy = end.y - start.y
    val len = kotlin.math.hypot(dx, dy).coerceAtLeast(0.01f)
    val angle = Math.toDegrees(kotlin.math.atan2(dy.toDouble(), dx.toDouble())).toFloat()
    rotate(degrees = angle, pivot = start) {
        drawRoundRect(
            color = color,
            topLeft = Offset(start.x, start.y - width / 2f),
            size = Size(len + width / 2f, width),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(width / 2f, width / 2f)
        )
    }
}

/** Faísca de energia (raio curto) usada nos efeitos elétricos. */
private fun DrawScope.drawBolt(origin: Offset, size: Float, color: Color, alpha: Float) {
    val path = Path().apply {
        moveTo(origin.x, origin.y)
        lineTo(origin.x + size * 0.28f, origin.y - size * 0.45f)
        lineTo(origin.x + size * 0.10f, origin.y - size * 0.45f)
        lineTo(origin.x + size * 0.34f, origin.y - size)
        lineTo(origin.x + size * 0.20f, origin.y - size * 0.52f)
        lineTo(origin.x + size * 0.40f, origin.y - size * 0.52f)
        close()
    }
    drawPath(path, color.copy(alpha = alpha * 0.35f), style = Stroke(width = size * 0.22f))
    drawPath(path, color.copy(alpha = alpha))
}

/** Poeira/velocidade: riscos horizontais que reforçam o movimento. */
private fun DrawScope.drawSpeedLines(center: Offset, unit: Float, color: Color, strength: Float) {
    repeat(4) { i ->
        val y = center.y - unit * 0.25f + i * unit * 0.18f
        val w = unit * (0.5f + 0.25f * i) * strength
        drawLine(
            color = color.copy(alpha = 0.18f * strength),
            start = Offset(center.x - w * 1.6f, y),
            end = Offset(center.x - unit * 0.35f, y),
            strokeWidth = unit * 0.035f,
            cap = StrokeCap.Round
        )
    }
}

private fun DrawScope.drawSparkles(center: Offset, unit: Float, color: Color, count: Int, phase: Float) {
    repeat(count) { i ->
        val a = phase * 2f * PI.toFloat() + i * (2f * PI.toFloat() / count)
        val r = unit * (0.55f + 0.18f * i)
        val p = Offset(center.x + cos(a) * r, center.y - unit * 0.1f + sin(a) * r * 0.55f)
        val s = unit * 0.05f * (0.7f + 0.5f * sin(phase * 8f + i).toFloat().coerceAtLeast(0f))
        drawCircle(color.copy(alpha = 0.85f), s, p)
        drawCircle(color.copy(alpha = 0.25f), s * 2.2f, p)
    }
}

// ------------------------------------------------------------------ mascotes ----

/** Posição horizontal da travessia (0→1 no eixo X) + fator de escala de entrada. */
private fun DrawScope.travel(t: Float): Pair<Float, Float> {
    val x = lerp(-0.12f, 1.12f, easeInOutSine(t.coerceIn(0f, 1f))) * size.width
    val pop = easeOutCubic((t / 0.10f).coerceIn(0f, 1f))
    return x to pop
}

private fun snesMascot() = Mascot(
    id = "snes-hero",
    name = "Herói 16-bit",
    durationMs = 2600
) { t ->
    val (x, pop) = travel(t)
    val unit = size.height * 0.42f * pop
    val phase = t * 16f
    val jump = if (t in 0.38f..0.66f) sin(((t - 0.38f) / 0.28f) * PI.toFloat()) else 0f
    val center = Offset(x, size.height - unit * 0.62f - jump * unit * 0.95f)

    if (jump > 0.05f) drawSparkles(center, unit, Color(0xFFFFE066), 5, t * 3f)
    drawBody(
        center = center,
        unit = unit,
        run = phase,
        lean = -6f - jump * 18f,
        head = Color(0xFFFFD1A3),
        torso = Color(0xFFE5342B),
        limbs = Color(0xFF2C4CC9),
        hair = Color(0xFF2C4CC9)
    )
    // gorro
    drawRoundRect(
        color = Color(0xFFE5342B),
        topLeft = Offset(center.x - unit * 0.30f, center.y - unit * 0.92f),
        size = Size(unit * 0.60f, unit * 0.16f),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(unit * 0.08f, unit * 0.08f)
    )
}

private fun gbaMascot() = Mascot(
    id = "gba-spark",
    name = "Faísca",
    durationMs = 2500
) { t ->
    val (x, pop) = travel(t)
    val unit = size.height * 0.40f * pop
    val hop = abs(sin(t * 14f)) * unit * 0.30f
    val center = Offset(x, size.height - unit * 0.60f - hop)

    // descargas elétricas saindo das bochechas
    val flicker = 0.5f + 0.5f * sin(t * 60f)
    drawBolt(Offset(center.x - unit * 0.30f, center.y - unit * 0.30f), unit * 0.55f, Color(0xFFFFE44D), flicker)
    drawBolt(Offset(center.x + unit * 0.34f, center.y - unit * 0.26f), unit * 0.48f, Color(0xFFFFE44D), 1f - flicker)
    drawSpeedLines(center, unit, Color(0xFFFFE44D), 1f)

    drawBody(
        center = center,
        unit = unit,
        run = t * 22f,
        lean = 10f,
        head = Color(0xFFFFE44D),
        torso = Color(0xFFFFC21C),
        limbs = Color(0xFFFFD84D),
        scaleX = 1.15f
    )
    // orelhas pontudas
    val ear = Path().apply {
        moveTo(center.x - unit * 0.24f, center.y - unit * 0.82f)
        lineTo(center.x - unit * 0.34f, center.y - unit * 1.30f)
        lineTo(center.x - unit * 0.10f, center.y - unit * 0.92f)
        close()
        moveTo(center.x + unit * 0.24f, center.y - unit * 0.82f)
        lineTo(center.x + unit * 0.34f, center.y - unit * 1.30f)
        lineTo(center.x + unit * 0.10f, center.y - unit * 0.92f)
        close()
    }
    drawPath(ear, Color(0xFFFFD84D))
    // bochechas vermelhas
    drawCircle(Color(0xFFFF7A6B).copy(alpha = 0.85f), unit * 0.07f, Offset(center.x - unit * 0.18f, center.y - unit * 0.62f))
    drawCircle(Color(0xFFFF7A6B).copy(alpha = 0.85f), unit * 0.07f, Offset(center.x + unit * 0.18f, center.y - unit * 0.62f))
}

private fun megadriveMascot() = Mascot(
    id = "md-turbo",
    name = "Turbo",
    durationMs = 2400
) { t ->
    val (x, pop) = travel(t)
    val unit = size.height * 0.42f * pop
    val spinning = t in 0.30f..0.72f
    val center = Offset(x, size.height - unit * 0.62f)

    drawSpeedLines(center, unit, Color(0xFF59C8FF), if (spinning) 1f else 0.55f)

    if (spinning) {
        // giro: bola azul com riscos e "pó" saindo do chão
        val r = unit * 0.42f
        rotate(degrees = t * 1800f, pivot = center) {
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(Color(0xFF3EA0FF), Color(0xFF1247C4), Color(0xFF071C50)),
                    center = center,
                    radius = r * 1.5f
                ),
                radius = r,
                center = center
            )
            repeat(3) { i ->
                drawArc(
                    color = Color(0xFFCBEAFF).copy(alpha = 0.55f),
                    startAngle = i * 120f,
                    sweepAngle = 70f,
                    useCenter = false,
                    topLeft = Offset(center.x - r * 0.7f, center.y - r * 0.7f),
                    size = Size(r * 1.4f, r * 1.4f),
                    style = Stroke(width = unit * 0.05f, cap = StrokeCap.Round)
                )
            }
        }
        drawSparkles(Offset(center.x, center.y + unit * 0.45f), unit * 0.7f, Color(0xFF9BD9FF), 4, t * 5f)
    } else {
        drawBody(
            center = center,
            unit = unit,
            run = t * 26f,
            lean = 16f,
            head = Color(0xFF3EA0FF),
            torso = Color(0xFF1247C4),
            limbs = Color(0xFF2B7BE4),
            hair = Color(0xFF0B2C7A)
        )
        // tênis vermelhos (marca registrada do visual "speedster")
        drawCircle(Color(0xFFE5342B), unit * 0.11f, Offset(center.x - unit * 0.12f, size.height - unit * 0.06f))
        drawCircle(Color(0xFFE5342B), unit * 0.11f, Offset(center.x + unit * 0.12f, size.height - unit * 0.06f))
    }
}

private fun ps1Mascot() = Mascot(
    id = "ps1-spinner",
    name = "Giro",
    durationMs = 2500
) { t ->
    val (x, pop) = travel(t)
    val unit = size.height * 0.42f * pop
    val center = Offset(x, size.height - unit * 0.62f)
    val spinning = t in 0.28f..0.74f

    if (spinning) {
        rotate(degrees = t * 1600f, pivot = center) {
            drawBody(
                center = center,
                unit = unit,
                run = t * 20f,
                lean = 0f,
                head = Color(0xFFFFB26B),
                torso = Color(0xFFF06A1E),
                limbs = Color(0xFFFF8A3D),
                hair = Color(0xFFB33B00)
            )
        }
        // vórtice
        repeat(3) { i ->
            drawArc(
                color = Color(0xFFFFB26B).copy(alpha = 0.35f),
                startAngle = i * 120f + t * 720f,
                sweepAngle = 90f,
                useCenter = false,
                topLeft = Offset(center.x - unit * 0.75f, center.y - unit * 0.35f),
                size = Size(unit * 1.5f, unit * 0.9f),
                style = Stroke(width = unit * 0.06f, cap = StrokeCap.Round)
            )
        }
    } else {
        drawBody(
            center = center,
            unit = unit,
            run = t * 20f,
            lean = 8f,
            head = Color(0xFFFFB26B),
            torso = Color(0xFFF06A1E),
            limbs = Color(0xFFFF8A3D),
            hair = Color(0xFFB33B00)
        )
        drawSparkles(center, unit * 0.8f, Color(0xFFFFD24A), 3, t * 4f)
    }
}

private fun pspMascot() = Mascot(
    id = "psp-blade",
    name = "Lâmina",
    durationMs = 2600
) { t ->
    val (x, pop) = travel(t)
    val unit = size.height * 0.44f * pop
    val center = Offset(x, size.height - unit * 0.62f)
    val slash = ((t - 0.32f) / 0.34f).coerceIn(0f, 1f)
    val active = t in 0.32f..0.72f

    drawBody(
        center = center,
        unit = unit,
        run = t * 14f,
        lean = if (active) 4f else 12f,
        head = Color(0xFFE8C7A8),
        torso = Color(0xFF2B3A55),
        limbs = Color(0xFFC9B79C),
        hair = Color(0xFFEDEDED)
    )

    // capa
    val cape = Path().apply {
        moveTo(center.x - unit * 0.18f, center.y - unit * 0.35f)
        lineTo(center.x - unit * 0.55f - unit * 0.25f * sin(t * 10f), center.y + unit * 0.30f)
        lineTo(center.x - unit * 0.10f, center.y + unit * 0.18f)
        close()
    }
    drawPath(cape, Color(0xFF8E1B22).copy(alpha = 0.9f))

    // duas lâminas em arco (brilho)
    val bladeColor = Color(0xFFB7E4FF)
    drawArc(
        color = bladeColor.copy(alpha = 0.85f),
        startAngle = -120f + slash * 180f,
        sweepAngle = 70f,
        useCenter = false,
        topLeft = Offset(center.x - unit * 0.95f, center.y - unit * 0.85f),
        size = Size(unit * 1.9f, unit * 1.6f),
        style = Stroke(width = unit * 0.05f, cap = StrokeCap.Round)
    )
    drawArc(
        color = bladeColor.copy(alpha = 0.45f),
        startAngle = -60f - slash * 180f,
        sweepAngle = 70f,
        useCenter = false,
        topLeft = Offset(center.x - unit * 0.85f, center.y - unit * 0.75f),
        size = Size(unit * 1.7f, unit * 1.4f),
        style = Stroke(width = unit * 0.03f, cap = StrokeCap.Round)
    )
}

private fun n64Mascot() = Mascot(
    id = "n64-cub",
    name = "Urso",
    durationMs = 2600
) { t ->
    val (x, pop) = travel(t)
    val unit = size.height * 0.44f * pop
    val jump = if (t in 0.34f..0.68f) sin(((t - 0.34f) / 0.34f) * PI.toFloat()) else 0f
    val center = Offset(x, size.height - unit * 0.60f - jump * unit * 0.85f)

    if (jump > 0.02f) drawSparkles(center, unit * 0.9f, Color(0xFF9BE38A), 4, t * 5f)

    drawBody(
        center = center,
        unit = unit,
        run = t * 12f,
        lean = -4f,
        head = Color(0xFFB07A4A),
        torso = Color(0xFF8C5A2E),
        limbs = Color(0xFFA46C3B),
        scaleX = 1.1f
    )
    // orelhas de urso
    drawCircle(Color(0xFF8C5A2E), unit * 0.13f, Offset(center.x - unit * 0.24f, center.y - unit * 1.02f))
    drawCircle(Color(0xFF8C5A2E), unit * 0.13f, Offset(center.x + unit * 0.24f, center.y - unit * 1.02f))
    // focinho
    drawOval(
        color = Color(0xFFE9CDA9),
        topLeft = Offset(center.x - unit * 0.13f, center.y - unit * 0.80f),
        size = Size(unit * 0.26f, unit * 0.16f)
    )
    // mochila e estrela
    drawRoundRect(
        color = Color(0xFF3B6E2E),
        topLeft = Offset(center.x - unit * 0.40f, center.y - unit * 0.30f),
        size = Size(unit * 0.22f, unit * 0.34f),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(unit * 0.06f, unit * 0.06f)
    )
    drawCircle(Color(0xFFFFE066).copy(alpha = 0.9f), unit * 0.06f, Offset(center.x + unit * 0.42f, center.y - unit * 0.55f))
}

/** Retângulo útil do personagem (para depuração e testes de layout). */
internal fun mascotBounds(): Rect = Rect(0f, 0f, 1f, 1f)
