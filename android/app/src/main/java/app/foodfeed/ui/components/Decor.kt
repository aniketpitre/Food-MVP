package app.foodfeed.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.foodfeed.ui.theme.Ink
import app.foodfeed.ui.theme.Motion
import app.foodfeed.ui.theme.TextFamily
import app.foodfeed.ui.theme.Turmeric
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * A rounded card with a half-circle bite out of each side at [notchFromTop], like a
 * restaurant token or a bill stub. Pair with [DashedLine] across the notches.
 */
class TicketShape(
    private val corner: Dp = 22.dp,
    private val notch: Dp = 11.dp,
    private val notchFromTop: Dp? = null,
) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val c = with(density) { corner.toPx() }
        val r = with(density) { notch.toPx() }
        val y = notchFromTop?.let { with(density) { it.toPx() } } ?: (size.height / 2f)
        val base = Path().apply { addRoundRect(RoundRect(0f, 0f, size.width, size.height, CornerRadius(c))) }
        val bites = Path().apply {
            addOval(Rect(Offset(0f, y), r))
            addOval(Rect(Offset(size.width, y), r))
        }
        val out = Path()
        out.op(base, bites, PathOperation.Difference)
        return Outline.Generic(out)
    }
}

/** A perforation line, drawn between a ticket's notches. */
@Composable
fun DashedLine(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.fillMaxWidth().height(1.5.dp)) {
        drawLine(
            color, Offset(0f, size.height / 2), Offset(size.width, size.height / 2),
            strokeWidth = size.height, cap = StrokeCap.Round,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 9f)),
        )
    }
}

/**
 * A die-cut sticker: bold label on a colour block with a white edge and a drop shadow,
 * slapped on at a slight angle. [appear] (0..1, read during draw) scales it in from 1.6x
 * and swings it to its resting angle, like a sticker being pressed onto the photo.
 */
@Composable
fun Sticker(
    text: String,
    background: Color,
    modifier: Modifier = Modifier,
    content: Color = Ink,
    icon: ImageVector? = null,
    tilt: Float = -5f,
    appear: () -> Float = { 1f },
) {
    val shape = RoundedCornerShape(10.dp)
    Row(
        modifier
            .graphicsLayer {
                val p = appear().coerceIn(0f, 1f)
                val s = Motion.lerp(1.6f, 1f, Motion.backOut(p, 2.2f))
                scaleX = s
                scaleY = s
                alpha = (p * 3f).coerceAtMost(1f)
                rotationZ = Motion.lerp(tilt - 14f, tilt, Motion.backOut(p, 2.6f))
            }
            .shadow(6.dp, shape, clip = false)
            .background(background, shape)
            .border(2.dp, Color.White, shape)
            .padding(start = if (icon != null) 8.dp else 10.dp, end = 10.dp, top = 5.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = content, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(4.dp))
        }
        Text(
            text,
            color = content,
            fontFamily = TextFamily,
            fontWeight = FontWeight.ExtraBold,
            fontSize = 13.sp,
            letterSpacing = 0.2.sp,
        )
    }
}

/** A turmeric price tag, tilted the other way from the sticker. */
@Composable
fun PriceTag(text: String, modifier: Modifier = Modifier, tilt: Float = 3f) {
    Row(
        modifier
            .graphicsLayer { rotationZ = tilt }
            .background(Turmeric, RoundedCornerShape(8.dp))
            .padding(horizontal = 9.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, color = Ink, fontFamily = TextFamily, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp)
    }
}

/**
 * Draws a hand-drawn-looking wave from [x] to [x] + [width] at [y]. [progress] trims it from
 * the left (0 = nothing, 1 = whole wave) and [phase] slides the wave along, so it can wiggle
 * while it travels.
 */
fun DrawScope.drawSquiggle(
    x: Float,
    width: Float,
    y: Float,
    color: Color,
    progress: Float = 1f,
    phase: Float = 0f,
    amplitude: Float = 3.2.dp.toPx(),
    wavelength: Float = 14.dp.toPx(),
    stroke: Float = 2.6.dp.toPx(),
) {
    if (width <= 1f || progress <= 0f) return
    val path = Path()
    val steps = (width / 2f).toInt().coerceAtLeast(8)
    for (i in 0..steps) {
        val px = x + width * i / steps
        val py = y + amplitude * sin(((px - x) / wavelength + phase) * 2f * PI.toFloat())
        if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
    }
    val draw = if (progress >= 1f) path else Path().also { seg ->
        val m = PathMeasure()
        m.setPath(path, false)
        m.getSegment(0f, m.length * progress, seg, true)
    }
    drawPath(draw, color, style = Stroke(width = stroke, cap = StrokeCap.Round))
}

/**
 * A ring and a spray of spice-coloured confetti bursting out from [center]. [t] runs 0..1:
 * the ring expands and thins, the bits fly out on an ease-out, spin, and shrink away.
 */
fun DrawScope.drawSpiceBurst(
    center: Offset,
    t: Float,
    colors: List<Color>,
    radius: Float,
    count: Int = 12,
    seed: Int = 0,
) {
    if (t <= 0f || t >= 1f) return
    val out = 1f - (1f - t) * (1f - t) * (1f - t)
    // Shockwave ring.
    val ringT = (t / 0.55f).coerceAtMost(1f)
    if (ringT < 1f) {
        drawCircle(
            colors.first().copy(alpha = (1f - ringT) * 0.9f),
            radius = radius * (0.35f + 0.75f * (1f - (1f - ringT) * (1f - ringT))),
            center = center,
            style = Stroke(width = radius * 0.16f * (1f - ringT) + 1f),
        )
    }
    // Confetti: alternating dots and short dashes, each with its own angle and reach.
    repeat(count) { i ->
        val jitter = ((i * 37 + seed * 11) % 17) / 17f
        val angle = (2.0 * PI * i / count + jitter * 0.5).toFloat()
        val reach = radius * (0.7f + 0.55f * jitter) * out
        val p = Offset(center.x + cos(angle) * reach, center.y + sin(angle) * reach)
        val fade = (1f - ((t - 0.45f) / 0.55f).coerceIn(0f, 1f))
        val c = colors[i % colors.size].copy(alpha = fade)
        val s = radius * 0.085f * (1f - 0.6f * t)
        if (i % 2 == 0) {
            drawCircle(c, radius = s, center = p)
        } else {
            rotate(degrees = angle * 57.3f + 300f * t, pivot = p) {
                drawRoundRect(c, Offset(p.x - s * 1.6f, p.y - s * 0.55f), Size(s * 3.2f, s * 1.1f), CornerRadius(s))
            }
        }
    }
}

/** Small uppercase kicker label in the theme's muted colour. */
@Composable
fun KickerText(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(text.uppercase(), style = app.foodfeed.ui.theme.Kicker, color = color, modifier = modifier)
}
