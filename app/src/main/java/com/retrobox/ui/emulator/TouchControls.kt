package com.retrobox.ui.emulator

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.retrobox.emulation.RetroButton

/**
 * On-screen gamepad. Every key is a separate pointerInput so several fingers can
 * be pressed at once (e.g. run + jump).
 */
@Composable
fun TouchControls(
    onPress: (Int, Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val unit = if (maxWidth < maxHeight) maxWidth else maxHeight
        val pad = unit * 0.30f
        val face = unit * 0.30f
        val edge = 20.dp

        DPad(
            size = pad,
            onPress = onPress,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = edge, bottom = edge)
        )

        FaceButtons(
            size = face,
            onPress = onPress,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = edge, bottom = edge)
        )

        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = edge),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            PillKey("SELECT", RetroButton.SELECT, onPress, width = unit * 0.16f)
            PillKey("START", RetroButton.START, onPress, width = unit * 0.16f)
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .padding(top = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            PillKey("L", RetroButton.L, onPress, width = unit * 0.20f)
            PillKey("R", RetroButton.R, onPress, width = unit * 0.20f)
        }
    }
}

@Composable
private fun DPad(size: Dp, onPress: (Int, Boolean) -> Unit, modifier: Modifier = Modifier) {
    val key = size * 0.34f
    Box(modifier = modifier.size(size)) {
        PadKey(RetroButton.UP, "▲", key, onPress, Modifier.align(Alignment.TopCenter))
        PadKey(RetroButton.DOWN, "▼", key, onPress, Modifier.align(Alignment.BottomCenter))
        PadKey(RetroButton.LEFT, "◀", key, onPress, Modifier.align(Alignment.CenterStart))
        PadKey(RetroButton.RIGHT, "▶", key, onPress, Modifier.align(Alignment.CenterEnd))
    }
}

@Composable
private fun FaceButtons(size: Dp, onPress: (Int, Boolean) -> Unit, modifier: Modifier = Modifier) {
    val key = size * 0.32f
    Box(modifier = modifier.size(size)) {
        PadKey(RetroButton.X, "X", key, onPress, Modifier.align(Alignment.TopCenter))
        PadKey(RetroButton.B, "B", key, onPress, Modifier.align(Alignment.BottomCenter))
        PadKey(RetroButton.Y, "Y", key, onPress, Modifier.align(Alignment.CenterStart))
        PadKey(RetroButton.A, "A", key, onPress, Modifier.align(Alignment.CenterEnd))
    }
}

@Composable
private fun PadKey(
    id: Int,
    label: String,
    size: Dp,
    onPress: (Int, Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    PressableKey(id, onPress, modifier.size(size), CircleShape, label, (size.value * 0.34f).sp)
}

@Composable
private fun PillKey(
    label: String,
    id: Int,
    onPress: (Int, Boolean) -> Unit,
    width: Dp
) {
    PressableKey(
        id = id,
        onPress = onPress,
        modifier = Modifier
            .width(width)
            .height(width * 0.42f),
        shape = RoundedCornerShape(50),
        label = label,
        fontSize = 11.sp
    )
}

@Composable
private fun PressableKey(
    id: Int,
    onPress: (Int, Boolean) -> Unit,
    modifier: Modifier,
    shape: androidx.compose.ui.graphics.Shape,
    label: String,
    fontSize: androidx.compose.ui.unit.TextUnit
) {
    var pressed by remember { mutableStateOf(false) }
    Box(
        modifier = modifier
            .clip(shape)
            .background(if (pressed) Color(0xFF29B6F6).copy(alpha = 0.45f) else Color.White.copy(alpha = 0.10f))
            .border(1.dp, if (pressed) Color(0xFF29B6F6) else Color.White.copy(alpha = 0.25f), shape)
            .pointerInput(id) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    pressed = true
                    onPress(id, true)
                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            if (event.changes.none { it.pressed }) break
                        }
                    } finally {
                        pressed = false
                        onPress(id, false)
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = Color.White.copy(alpha = 0.85f),
            fontSize = fontSize,
            fontWeight = FontWeight.Bold
        )
    }
}
