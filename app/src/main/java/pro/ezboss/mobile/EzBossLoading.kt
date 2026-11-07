package pro.ezboss.mobile

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.*
import kotlinx.coroutines.delay

@Composable fun EzBossLoadingIndicator(modifier: Modifier = Modifier, color: Color = Color(0xFFFF7D22), strokeWidth: Dp = 2.dp, page: Boolean = true) {
    if(page) EzBossPageSkeleton(modifier)
    else EzBossAnimatedMark(false, modifier.width(100.dp).aspectRatio(741f/221f), color)
}

@Composable private fun EzBossPageSkeleton(modifier: Modifier = Modifier) {
    Canvas(modifier.widthIn(max=560.dp).fillMaxWidth().height(220.dp).padding(20.dp).semantics { contentDescription="Loading page" }) {
        val gray=Color(0xFFE5E7EB)
        drawRoundRect(gray,size=androidx.compose.ui.geometry.Size(size.width*.42f,22.dp.toPx()),cornerRadius=androidx.compose.ui.geometry.CornerRadius(4.dp.toPx()))
        repeat(4) { row ->
            val y=50.dp.toPx()+row*36.dp.toPx()
            drawRoundRect(gray.copy(alpha=.6f),topLeft=androidx.compose.ui.geometry.Offset(0f,y),size=androidx.compose.ui.geometry.Size(size.width*.55f,14.dp.toPx()),cornerRadius=androidx.compose.ui.geometry.CornerRadius(4.dp.toPx()))
            drawRoundRect(gray.copy(alpha=.6f),topLeft=androidx.compose.ui.geometry.Offset(size.width*.7f,y),size=androidx.compose.ui.geometry.Size(size.width*.28f,14.dp.toPx()),cornerRadius=androidx.compose.ui.geometry.CornerRadius(4.dp.toPx()))
        }
    }
}

@Composable private fun EzBossAnimatedMark(reveal: Boolean, modifier: Modifier = Modifier, tint: Color = Color(0xFFFF7D22)) {
    val image = ImageBitmap.imageResource(R.drawable.ezboss_loading)
    val context = LocalContext.current
    val motionScale = remember(context) { android.provider.Settings.Global.getFloat(context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) }
    var seconds by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(reveal, motionScale) {
        if (motionScale == 0f) return@LaunchedEffect
        val start = withFrameNanos { it }
        while (true) { withFrameNanos { seconds = (it-start)/1_000_000_000f } }
    }
    Canvas(modifier.semantics { contentDescription = if (reveal) "Page ready" else "Loading" }) {
        val orange = tint
        val letters = listOf(60f to 160f,160f to 255f,255f to 372f,372f to 489f,489f to 587f,587f to 687f)
        letters.forEachIndexed { index, (left,right) ->
            clipRect(left/741*size.width,size.height*.2f,right/741*size.width,size.height*.8f) {
                drawImage(image, colorFilter = ColorFilter.tint(tint), dstSize = IntSize(size.width.toInt(),size.height.toInt()), alpha = if(reveal && motionScale>0) ((seconds-index*.173f)/.397f).coerceIn(0f,1f) else 1f)
            }
        }
        val progress = if(reveal && motionScale>0) ((seconds-.173f)/1.09f).coerceIn(0f,1f) else 1f
        clipRect(right = size.width*progress) {
            for (inset in listOf(3f,12f)) {
                val x=inset/741*size.width; val y=inset/221*size.height
                val w=size.width-2*x; val h=size.height-2*y
                val path=Path().apply { moveTo(x,y);lineTo(x+w,y);lineTo(x+w,y+h);lineTo(x,y+h);close() }
                if(reveal || motionScale==0f) drawPath(path,orange.copy(alpha=progress),style=Stroke(4/741f*size.width))
                else {
                    val perimeter=2*(w+h)
                    drawPath(path,orange,style=Stroke(width=4/741f*size.width,pathEffect=PathEffect.dashPathEffect(floatArrayOf(perimeter*.32f,perimeter*.68f),-(seconds%2.8f)/2.8f*perimeter)))
                }
            }
        }
    }
}

@Composable fun EzBossLoadingHost(authenticated: Boolean, signedOut: Boolean, content: @Composable () -> Unit) {
    var completed by remember { mutableStateOf(false) }
    var revealing by remember { mutableStateOf(false) }
    val coverOpacity = remember { Animatable(1f) }
    val context = LocalContext.current
    val motionScale = remember(context) { android.provider.Settings.Global.getFloat(context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) }
    val covering = authenticated && (!completed || revealing) && motionScale>0
    LaunchedEffect(authenticated, signedOut) {
        if(signedOut) completed=false
        coverOpacity.snapTo(1f)
        if(authenticated && !completed) {
            completed=true
            if(motionScale>0) {
                revealing=true
                try { delay(1420); coverOpacity.animateTo(0f, tween(320)) }
                finally { revealing=false }
            }
        }
    }
    Box(Modifier.fillMaxSize()) {
        Box(if(covering) Modifier.clearAndSetSemantics { } else Modifier) { content() }
        if(covering) Box(Modifier.fillMaxSize().alpha(coverOpacity.value).background(Color.White).pointerInput(Unit) { awaitPointerEventScope { while(true) { awaitPointerEvent().changes.forEach { it.consume() } } } },contentAlignment=Alignment.Center) {
            EzBossAnimatedMark(true,Modifier.widthIn(max=230.dp).fillMaxWidth(.75f).aspectRatio(741f/221f))
        }
    }
}
