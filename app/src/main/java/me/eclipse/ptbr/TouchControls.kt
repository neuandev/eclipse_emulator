package me.eclipse.ptbr

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.sqrt

private data class Face(val label: String, val bit: Int, val x: Int, val y: Int, val size: Int)

private fun facesFor(l: PadLayout): Pair<List<Face>, Pair<Int, Int>> = when (l) {
    PadLayout.GB, PadLayout.GBA, PadLayout.NES ->
        listOf(Face("B", Btn.B, 6, 74, 64), Face("A", Btn.A, 90, 34, 64)) to (160 to 140)
    PadLayout.SNES ->
        listOf(Face("X", Btn.X, 62, 0, 54), Face("Y", Btn.Y, 4, 54, 54), Face("A", Btn.A, 120, 54, 54), Face("B", Btn.B, 62, 108, 54)) to (178 to 164)
    PadLayout.SMS ->
        listOf(Face("1", Btn.B, 6, 74, 64), Face("2", Btn.A, 90, 34, 64)) to (160 to 140)
    PadLayout.GENESIS ->
        listOf(Face("A", Btn.Y, 0, 80, 56), Face("B", Btn.B, 64, 54, 56), Face("C", Btn.A, 128, 28, 56)) to (190 to 140)
}

@Composable
fun TouchControls(
    layout: PadLayout,
    opacity: Float,
    landscape: Boolean,
    modifier: Modifier,
    onButton: (Int, Boolean) -> Unit,
) {
    val (faces, dim) = remember(layout) { facesFor(layout) }
    val shoulders = layout == PadLayout.GBA || layout == PadLayout.SNES
    val hasSelect = layout == PadLayout.GB || layout == PadLayout.GBA || layout == PadLayout.NES || layout == PadLayout.SNES

    Box(modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        DPad(
            Modifier.align(if (landscape) Alignment.BottomStart else Alignment.CenterStart).padding(bottom = if (landscape) 8.dp else 0.dp),
            opacity, onButton,
        )
        Box(
            Modifier.align(if (landscape) Alignment.BottomEnd else Alignment.CenterEnd)
                .size(dim.first.dp, dim.second.dp).padding(bottom = if (landscape) 8.dp else 0.dp),
        ) {
            faces.forEach { f -> RoundButton(f.label, f.bit, f.size.dp, opacity, onButton, Modifier.offset(f.x.dp, f.y.dp)) }
        }
        if (shoulders) {
            PillButton("L", Btn.L, opacity, onButton, Modifier.align(Alignment.TopStart))
            PillButton("R", Btn.R, opacity, onButton, Modifier.align(Alignment.TopEnd))
        }
        Row(Modifier.align(Alignment.BottomCenter), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            if (hasSelect) PillButton("SELECT", Btn.SELECT, opacity, onButton, Modifier)
            PillButton("START", Btn.START, opacity, onButton, Modifier)
        }
    }
}

@Composable
private fun RoundButton(label: String, bit: Int, size: Dp, opacity: Float, onButton: (Int, Boolean) -> Unit, modifier: Modifier) {
    var pressed by remember { mutableStateOf(false) }
    val a = opacity.coerceIn(0.15f, 1f)
    Box(
        modifier.size(size).clip(CircleShape)
            .background(Color.White.copy(alpha = if (pressed) 0.55f * a else 0.2f * a))
            .border(2.dp, Color.White.copy(alpha = 0.7f * a), CircleShape)
            .pointerInput(bit) {
                awaitEachGesture {
                    awaitFirstDown()
                    pressed = true
                    onButton(bit, true)
                    waitForUpOrCancellation()
                    pressed = false
                    onButton(bit, false)
                }
            },
        contentAlignment = Alignment.Center,
    ) { Text(label, color = Color.White.copy(alpha = (a + 0.2f).coerceAtMost(1f)), fontWeight = FontWeight.Bold, fontSize = 18.sp) }
}

@Composable
private fun PillButton(label: String, bit: Int, opacity: Float, onButton: (Int, Boolean) -> Unit, modifier: Modifier) {
    var pressed by remember { mutableStateOf(false) }
    val a = opacity.coerceIn(0.15f, 1f)
    val shape = RoundedCornerShape(50)
    Box(
        modifier.height(36.dp).widthIn(min = 56.dp).clip(shape)
            .background(Color.White.copy(alpha = if (pressed) 0.55f * a else 0.2f * a))
            .border(2.dp, Color.White.copy(alpha = 0.7f * a), shape)
            .pointerInput(bit) {
                awaitEachGesture {
                    awaitFirstDown()
                    pressed = true
                    onButton(bit, true)
                    waitForUpOrCancellation()
                    pressed = false
                    onButton(bit, false)
                }
            },
        contentAlignment = Alignment.Center,
    ) { Text(label, color = Color.White.copy(alpha = (a + 0.2f).coerceAtMost(1f)), fontWeight = FontWeight.Bold, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 12.dp)) }
}

@Composable
private fun DPad(modifier: Modifier, opacity: Float, onButton: (Int, Boolean) -> Unit) {
    var dir by remember { mutableIntStateOf(0) }
    val a = opacity.coerceIn(0.15f, 1f)
    val bits = intArrayOf(Btn.UP, Btn.DOWN, Btn.LEFT, Btn.RIGHT)

    Box(
        modifier.size(150.dp).clip(CircleShape)
            .background(Color.White.copy(alpha = 0.18f * a))
            .border(2.dp, Color.White.copy(alpha = 0.6f * a), CircleShape)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    var cur = 0
                    fun update(pos: Offset) {
                        val dx = pos.x - size.width / 2f
                        val dy = pos.y - size.height / 2f
                        val r = sqrt(dx * dx + dy * dy)
                        var m = 0
                        if (r > size.width * 0.1f) {
                            val nx = dx / r
                            val ny = dy / r
                            if (nx > 0.38f) m = m or (1 shl Btn.RIGHT)
                            if (nx < -0.38f) m = m or (1 shl Btn.LEFT)
                            if (ny > 0.38f) m = m or (1 shl Btn.DOWN)
                            if (ny < -0.38f) m = m or (1 shl Btn.UP)
                        }
                        for (b in bits) {
                            val was = (cur and (1 shl b)) != 0
                            val now = (m and (1 shl b)) != 0
                            if (was != now) onButton(b, now)
                        }
                        cur = m
                        dir = m
                    }
                    update(down.position)
                    while (true) {
                        val ev = awaitPointerEvent()
                        val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                        if (!ch.pressed) break
                        update(ch.position)
                        ch.consume()
                    }
                    for (b in bits) if ((cur and (1 shl b)) != 0) onButton(b, false)
                    cur = 0
                    dir = 0
                }
            },
    ) {
        fun tint(bit: Int) = Color.White.copy(alpha = if ((dir and (1 shl bit)) != 0) 1f else 0.7f * a)
        Text("▲", color = tint(Btn.UP), modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp))
        Text("▼", color = tint(Btn.DOWN), modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp))
        Text("◀", color = tint(Btn.LEFT), modifier = Modifier.align(Alignment.CenterStart).padding(start = 10.dp))
        Text("▶", color = tint(Btn.RIGHT), modifier = Modifier.align(Alignment.CenterEnd).padding(end = 10.dp))
    }
}
