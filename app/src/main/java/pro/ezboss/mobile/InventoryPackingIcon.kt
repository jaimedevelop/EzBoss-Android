package pro.ezboss.mobile

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private const val PackingDuration = 2430
private data class BoxPoint(val x: Float, val y: Float, val z: Float)

@Composable
internal fun InventoryPackingIcon(play: Boolean, onFinished: () -> Unit) {
    val progress = remember { Animatable(0f) }
    val finish by rememberUpdatedState(onFinished)
    LaunchedEffect(play) {
        progress.snapTo(0f)
        if (play) {
            progress.animateTo(1f, tween(durationMillis = PackingDuration, easing = LinearEasing))
            finish()
        }
    }
    Canvas(Modifier.size(20.dp)) {
        val time = if (progress.value >= 1f) 0f else progress.value * 2600f
        val scale = size.width / 20f
        val bounds = Rect(-4 * scale, -15 * scale, 32 * scale, 35 * scale)
        drawIntoCanvas { canvas ->
            canvas.saveLayer(bounds, Paint())
            try {
                clipRect(bounds.left, bounds.top, bounds.right, bounds.bottom) {
                    withTransform({
                        translate(bounds.left, bounds.top)
                        scale(scale, scale, Offset.Zero)
                    }) { drawPacking(time) }
                }
            } finally {
                canvas.restore()
            }
        }
    }
}

private fun DrawScope.drawPacking(time: Float) {
    val half = 25f
    val rim = 46.7f
    val outline = 8.4f
    fun phase(start: Int, end: Int) = ((time - start) / (end - start)).coerceIn(0f, 1f)
    fun ease(p: Float) = p * p * (3 - 2 * p)

    val angle = 2.39f * (ease(phase(280, 650)) - ease(phase(1480, 1800)))
    val ship = phase(1950, 2550)
    val pullback = 9 * sin((PI / 2 * phase(1830, 1950)).toFloat()) * (1 - ship)
    val x = 14 + (165 * ship * ship - pullback) * 0.2f
    val y = 30 - 3 * ship
    if (ship > 0 && ship < 1) {
        for ((dy, trailing) in listOf(-3f to 0f, 0f to 3f, 3f to 0f)) {
            val end = x - 10 - trailing
            drawLine(Color.White, Offset(end - 3 - ship * 7, y + dy), Offset(end, y + dy),
                strokeWidth = 0.9f, cap = StrokeCap.Round, alpha = 0.55f * sin((PI * ship).toFloat()))
        }
    }

    withTransform({
        translate(x, y)
        scale(0.2f, 0.2f, Offset.Zero)
        rotate(-0.085f * ship * 180f / PI.toFloat(), Offset.Zero)
    }) {
        fun DrawScope.path(
            vararg points: BoxPoint,
            face: Boolean = false,
            width: Float = outline,
            rounding: FloatArray = floatArrayOf(),
            alpha: Float = 1f,
        ) {
            val projected = points.map { Offset((it.x - it.y) * 0.84f, (it.x + it.y) * 0.485f - it.z) }
            val path = Path()
            if (rounding.isEmpty()) {
                path.moveTo(projected.first().x, projected.first().y)
                projected.drop(1).forEach { path.lineTo(it.x, it.y) }
            } else {
                val start = (projected.first() + projected.last()) / 2f
                path.moveTo(start.x, start.y)
                projected.forEachIndexed { index, point ->
                    val radius = rounding[index]
                    if (radius == 0f) {
                        path.lineTo(point.x, point.y)
                    } else {
                        val previous = projected[(index + projected.size - 1) % projected.size] - point
                        val next = projected[(index + 1) % projected.size] - point
                        val before = point + previous / previous.getDistance() * radius
                        val after = point + next / next.getDistance() * radius
                        path.lineTo(before.x, before.y)
                        path.quadraticBezierTo(point.x, point.y, after.x, after.y)
                    }
                }
            }
            if (face) {
                path.close()
                drawPath(path, Color.White, alpha = alpha, blendMode = BlendMode.DstOut)
            }
            drawPath(path, Color.White, alpha = alpha, style = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        fun flap(side: Float) {
            val hinge = side * half
            val edge = hinge - side * half * cos(angle)
            val rise = rim + half * sin(angle)
            path(BoxPoint(-half, hinge, rim), BoxPoint(half, hinge, rim), BoxPoint(half, edge, rise), BoxPoint(-half, edge, rise), face = true)
        }

        path(BoxPoint(-half, -half, rim), BoxPoint(half, -half, rim), BoxPoint(half, half, rim), BoxPoint(-half, half, rim),
            face = true, rounding = floatArrayOf(9.25f, 0f, 0f, 0f))
        if (angle > 0) {
            flap(-1f)
            if (time > 640 && time < 1480) {
                val fall = phase(770, 1230)
                val base = 98 - 94 * fall * fall + 5 * sin((PI * phase(1230, 1450)).toFloat())
                val top = base + rim * 0.38f
                val a = 9.5f
                val width = 3.2f
                val alpha = phase(640, 770)
                withTransform({
                    translate(-8 * (1 - fall), 0f)
                    rotate(-0.16f * (1 - fall) * 180f / PI.toFloat(), Offset.Zero)
                }) {
                    path(BoxPoint(-a, -a, top), BoxPoint(a, -a, top), BoxPoint(a, -a, base), BoxPoint(a, a, base), BoxPoint(-a, a, base), BoxPoint(-a, a, top),
                        face = true, width = width, rounding = FloatArray(6) { 3.5f }, alpha = alpha)
                    path(BoxPoint(-a, a, top), BoxPoint(a, a, top), BoxPoint(a, -a, top), width = width, alpha = alpha)
                    path(BoxPoint(a, a, top), BoxPoint(a, a, base), width = width, alpha = alpha)
                    path(BoxPoint(-a, 0f, top), BoxPoint(a, 0f, top), width = width, alpha = alpha)
                }
            }
        } else {
            path(BoxPoint(-half, 0f, rim), BoxPoint(half, 0f, rim))
        }
        path(BoxPoint(-half, half, rim), BoxPoint(half, half, rim), BoxPoint(half, -half, rim),
            BoxPoint(half, -half, 0f), BoxPoint(half, half, 0f), BoxPoint(-half, half, 0f),
            face = true, rounding = floatArrayOf(0f, 0f, 0f, 9.25f, 9.25f, 9.25f))
        path(BoxPoint(half, half, rim), BoxPoint(half, half, 0f))
        if (angle > 0) flap(1f)
    }
}
