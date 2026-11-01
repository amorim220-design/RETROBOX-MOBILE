package com.retrobox.ui.screens.home.mascots

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import com.retrobox.domain.Console

/**
 * Animação do personagem do console escolhido, na parte de baixo da área de
 * conteúdo (acima da barra de navegação).
 *
 * A camada não tem `pointerInput`: ela nunca consome toques, então a
 * biblioteca, os filtros e os FABs continuam funcionando normalmente. Nada é
 * medido nem reservado no layout — o desenho é sobreposto e só existe enquanto
 * a animação roda; ao terminar a composição sai da árvore e os recursos de
 * desenho são liberados.
 *
 * @param console console selecionado na barra lateral.
 * @param trigger contador incrementado a cada clique. Clicar de novo no mesmo
 *   console gera um novo valor e a animação recomeça; clicar em outro console
 *   cancela a anterior (a própria `LaunchedEffect` é reiniciada).
 */
@Composable
fun MascotOverlay(
    console: Console,
    trigger: Int,
    modifier: Modifier = Modifier
) {
    var active by remember { mutableStateOf<Mascot?>(null) }
    val progress = remember { Animatable(0f) }

    LaunchedEffect(trigger) {
        if (trigger <= 0) return@LaunchedEffect
        val mascot = MascotRegistry.forConsole(console) ?: return@LaunchedEffect
        active = mascot
        progress.snapTo(0f)
        progress.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = mascot.durationMs, easing = LinearEasing)
        )
        active = null
    }

    val mascot = active ?: return

    // Fade curto na entrada e na saída: o personagem surge e se dissolve na
    // borda inferior em vez de aparecer cortado.
    Canvas(
        modifier = modifier
            .fillMaxSize()
            .graphicsLayer {
                val t = progress.value
                val fadeIn = (t / 0.10f).coerceIn(0f, 1f)
                val fadeOut = ((1f - t) / 0.12f).coerceIn(0f, 1f)
                alpha = minOf(fadeIn, fadeOut)
            }
    ) {
        mascot.draw(this, progress.value)
    }
}
