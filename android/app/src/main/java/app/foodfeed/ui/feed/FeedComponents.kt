package app.foodfeed.ui.feed

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.foodfeed.data.Category
import app.foodfeed.data.DishCard
import app.foodfeed.data.Engagement
import app.foodfeed.data.EngagementState
import app.foodfeed.ui.Format
import app.foodfeed.ui.components.ActionLook
import app.foodfeed.ui.components.AppIcons
import app.foodfeed.ui.components.AvatarStack
import app.foodfeed.ui.components.DishImage
import app.foodfeed.ui.components.EngageAction
import app.foodfeed.ui.components.KickerText
import app.foodfeed.ui.components.PriceTag
import app.foodfeed.ui.components.Sticker
import app.foodfeed.ui.components.VegMark
import app.foodfeed.ui.components.drawSpiceBurst
import app.foodfeed.ui.components.drawSquiggle
import app.foodfeed.ui.theme.CardTitle
import app.foodfeed.ui.theme.Chili
import app.foodfeed.ui.theme.Ink
import app.foodfeed.ui.theme.Lagoon
import app.foodfeed.ui.theme.Motion
import app.foodfeed.ui.theme.Saffron
import app.foodfeed.ui.theme.Turmeric
import app.foodfeed.ui.theme.dishHero
import app.foodfeed.ui.theme.pressScale
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** A greeting that knows what time it is, shown as the kicker above the city. */
fun greetingFor(hour: Int): String = when (hour) {
    in 5..10 -> "Good morning in"
    in 11..14 -> "Lunch o'clock in"
    in 15..17 -> "Chai time in"
    in 18..21 -> "Good evening in"
    else -> "Late-night cravings in"
}

/** Kicker + big city name on the left; round search and filter buttons on the right. */
@Composable
fun FeedHeader(
    cityName: String,
    filterActive: Boolean,
    onCityClick: () -> Unit,
    onSearch: () -> Unit,
    onFilter: () -> Unit,
    greeting: String = greetingFor(java.time.LocalTime.now().hour),
) {
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 14.dp, top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val interaction = remember { MutableInteractionSource() }
        Column(
            Modifier
                .weight(1f)
                .pressScale(interaction, 0.97f)
                .clickable(interaction, indication = null, role = Role.Button, onClick = onCityClick),
        ) {
            KickerText(greeting)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(cityName, style = MaterialTheme.typography.headlineLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Box(
                    Modifier.padding(start = 8.dp, top = 4.dp).size(24.dp).background(MaterialTheme.colorScheme.primary, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.KeyboardArrowDown, "Change city", tint = Ink, modifier = Modifier.size(20.dp))
                }
            }
        }
        RoundIconButton(Icons.Filled.Search, "Search dishes and places", onSearch)
        Spacer(Modifier.width(8.dp))
        Box {
            RoundIconButton(AppIcons.Tune, "Filter by dish type", onFilter)
            val dot by animateFloatAsState(if (filterActive) 1f else 0f, Motion.bouncy(), label = "filter-dot")
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .size(12.dp)
                    .graphicsLayer { scaleX = dot; scaleY = dot }
                    .background(MaterialTheme.colorScheme.background, CircleShape)
                    .padding(2.dp)
                    .background(MaterialTheme.colorScheme.tertiary, CircleShape),
            )
        }
    }
}

@Composable
private fun RoundIconButton(icon: ImageVector, description: String, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        Modifier
            .size(44.dp)
            .pressScale(interaction, 0.88f)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(interaction, indication = null, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, description, modifier = Modifier.size(22.dp))
    }
}

/**
 * Text tabs underlined by a hand-drawn saffron squiggle. Switching tabs, the squiggle
 * slides to the new label on a spring, stretching to its width, and its wave rolls as it
 * travels (the phase follows the position), then redraws itself from the left.
 */
@Composable
fun FeedTabs(selected: FeedTab, onSelect: (FeedTab) -> Unit) {
    val density = LocalDensity.current
    val positions = remember { mutableStateMapOf<FeedTab, Pair<Float, Float>>() } // x, width in px
    val target = positions[selected]
    val x by animateFloatAsState(target?.first ?: 0f, Motion.glide(), label = "tab-x")
    val w by animateFloatAsState(target?.second ?: 0f, Motion.glide(), label = "tab-w")
    val draw = remember { Animatable(1f) }
    var first by remember { mutableStateOf(true) }
    LaunchedEffect(selected) {
        if (first) { first = false; return@LaunchedEffect }
        draw.snapTo(0.25f)
        draw.animateTo(1f, tween(520, easing = Motion.EmphasizedDecelerate))
    }
    val squiggle = MaterialTheme.colorScheme.primary
    val wavelength = with(density) { 14.dp.toPx() }
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(top = 10.dp, bottom = 12.dp)
            .drawBehind {
                if (target != null) {
                    drawSquiggle(
                        x = x, width = w, y = size.height - 3.dp.toPx(), color = squiggle,
                        progress = draw.value, phase = -x / wavelength,
                    )
                }
            },
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.Bottom) {
            FeedTab.entries.forEach { tab ->
                val on = tab == selected
                val color by animateColorAsState(
                    if (on) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                    Motion.enter(Motion.SHORT),
                    label = "tab-color",
                )
                val scale by animateFloatAsState(if (on) 1f else 0.9f, Motion.pop(), label = "tab-scale")
                Text(
                    tab.label,
                    style = MaterialTheme.typography.titleLarge,
                    color = color,
                    modifier = Modifier
                        .onGloballyPositioned { positions[tab] = it.positionInParent().x to it.size.width.toFloat() }
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 1f)
                        }
                        .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Tab) { onSelect(tab) }
                        .padding(bottom = 8.dp),
                )
            }
        }
    }
}

/** The active dish-type filter as a saffron chip that pops in and shrinks away. */
@Composable
fun ActiveFilterChip(category: Category?, onClear: () -> Unit) {
    AnimatedVisibility(
        visible = category != null,
        enter = fadeIn(Motion.enter()) + scaleIn(Motion.bouncy(), initialScale = 0.6f, transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0.5f)),
        exit = fadeOut(Motion.exit()) + scaleOut(Motion.exit(), targetScale = 0.6f),
    ) {
        Row(
            Modifier
                .padding(start = 20.dp, bottom = 10.dp)
                .clip(RoundedCornerShape(50))
                .background(MaterialTheme.colorScheme.primary)
                .clickable(onClick = onClear)
                .padding(start = 14.dp, end = 10.dp, top = 7.dp, bottom = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(category?.name.orEmpty(), style = MaterialTheme.typography.labelLarge, color = Ink)
            Spacer(Modifier.width(6.dp))
            Icon(Icons.Filled.Close, "Clear filter", tint = Ink, modifier = Modifier.size(16.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CategorySheet(categories: List<Category>, selected: Int?, onSelect: (Int?) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.background,
    ) {
        Column(Modifier.padding(horizontal = 20.dp).navigationBarsPadding().padding(bottom = 24.dp)) {
            KickerText("Filter")
            Text("What are you craving?", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(18.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                FilterOption("Everything", selected == null, 0) { onSelect(null) }
                categories.forEachIndexed { i, c ->
                    FilterOption(listOfNotNull(c.emoji, c.name).joinToString("  "), selected == c.id, i + 1) { onSelect(c.id) }
                }
            }
        }
    }
}

/** A chip that pops in a beat after the one before it, and fills saffron when chosen. */
@Composable
private fun FilterOption(label: String, on: Boolean, index: Int, onClick: () -> Unit) {
    val bg by animateColorAsState(
        if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
        Motion.enter(Motion.SHORT), label = "opt-bg",
    )
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) { delay(40L * index); appear.animateTo(1f, Motion.bouncy()) }
    val interaction = remember { MutableInteractionSource() }
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = bg,
        contentColor = if (on) Ink else MaterialTheme.colorScheme.onSurface,
        interactionSource = interaction,
        modifier = Modifier
            .graphicsLayer {
                val p = appear.value
                scaleX = 0.6f + 0.4f * p
                scaleY = 0.6f + 0.4f * p
                alpha = p.coerceIn(0f, 1f)
            }
            .pressScale(interaction, 0.92f),
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
    }
}

/** What to stamp on a card's corner, if anything. Only claims the data supports. */
private data class StickerSpec(val text: String, val bg: Color, val fg: Color, val icon: ImageVector?)

private fun stickerFor(card: DishCard, recommenders: Int): StickerSpec? = when {
    card.cover?.isSponsored == true -> StickerSpec("Sponsored", Color.White, Ink, null)
    card.lane == "gem" -> StickerSpec("Hidden gem", Lagoon, Color.White, AppIcons.Gem)
    card.lane == "fresh" && recommenders < 3 -> StickerSpec("Just posted", Turmeric, Ink, AppIcons.Sparkle)
    recommenders >= 25 -> StickerSpec("Crowd favourite", Chili, Color.White, AppIcons.Heart)
    else -> null
}

/**
 * One full-height dish card.
 *
 * Landing choreography (one 1.1 s progress value, cut into steps with [Motion.window]):
 *  0-70%   the photo settles from a 25% push-in to its resting crop;
 *  15-50%  the title rises out of a mask, line by line feel;
 *  25-60%  the corner sticker slaps on (overshoot + swing to its tilt);
 *  28-75%  place, price and the recommenders follow 80 ms apart;
 *  30-80%  the action rail cascades in from the right, one button after another.
 * It plays once, when the card becomes the pager's target, so the photo arrives first and
 * the information lands on it.
 *
 * Paging (read in graphicsLayer, so swiping never recomposes): the card going away stays
 * nearly in place, shrinks and dims while the next card slides over it, like dealing from a
 * deck. Double-tap anywhere recommends, with a heart that bursts at your finger.
 */
@Composable
fun DishPage(
    card: DishCard,
    engagement: EngagementState,
    imageUrl: String?,
    onOpen: () -> Unit,
    onRestaurant: () -> Unit,
    onToggle: (Engagement) -> Unit,
    onShare: () -> Unit,
    modifier: Modifier = Modifier,
    pageOffset: () -> Float = { 0f },
    active: Boolean = true,
) {
    var burst by remember { mutableIntStateOf(0) }
    var burstAt by remember { mutableStateOf(Offset.Unspecified) }
    val intro = remember(card.id) { Animatable(0f) }
    LaunchedEffect(card.id, active) {
        if (active && intro.value == 0f) intro.animateTo(1f, tween(1100, easing = LinearEasing))
    }
    val t = { intro.value }
    val sticker = stickerFor(card, engagement.stats.recommenders)

    Box(
        modifier
            .fillMaxSize()
            .graphicsLayer {
                val o = pageOffset().coerceIn(-1f, 1f)
                if (o > 0f) {
                    // Leaving upwards: hold back most of the travel, shrink, so the next card covers it.
                    translationY = o * size.height * 0.82f
                    val s = 1f - 0.12f * o
                    scaleX = s
                    scaleY = s
                } else {
                    val s = 1f - 0.04f * -o
                    scaleX = s
                    scaleY = s
                }
            }
            .clip(RoundedCornerShape(32.dp))
            .drawWithContent {
                drawContent()
                val o = pageOffset().coerceIn(0f, 1f)
                if (o > 0f) drawRect(Color.Black.copy(alpha = 0.6f * o))
            }
            .pointerInput(card.id, engagement.me.recommended) {
                detectTapGestures(
                    onTap = { onOpen() },
                    onDoubleTap = { at ->
                        burstAt = at
                        burst++
                        if (!engagement.me.recommended) onToggle(Engagement.Recommend)
                    },
                )
            },
    ) {
        Box(Modifier.fillMaxSize().dishHero(card.id, 32.dp)) {
            DishImage(
                imageUrl, card.cover?.placeholderColor, card.name,
                Modifier.fillMaxSize().graphicsLayer {
                    val o = pageOffset()
                    translationY = if (o < 0f) o * size.height * 0.25f else 0f
                    val settle = Motion.window(t(), 0f, 0.7f)
                    val s = Motion.lerp(1.25f, 1.1f, settle)
                    scaleX = s
                    scaleY = s
                },
            )
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to Color.Black.copy(alpha = 0.28f),
                    0.16f to Color.Transparent,
                    0.45f to Color.Transparent,
                    0.78f to Color(0xFF1A0D06).copy(alpha = 0.6f),
                    1f to Color(0xFF1A0D06).copy(alpha = 0.92f),
                ),
            ),
        )

        val fadeOutOnLeave: () -> Float = { (1f - pageOffset().coerceIn(0f, 1f) * 1.8f).coerceIn(0f, 1f) }

        if (sticker != null) {
            Sticker(
                sticker.text, sticker.bg, content = sticker.fg, icon = sticker.icon,
                appear = { Motion.window(t(), 0.25f, 0.35f) { it } },
                modifier = Modifier.align(Alignment.TopStart).padding(18.dp).graphicsLayer { alpha = fadeOutOnLeave() },
            )
        }
        Format.distance(card.distanceM)?.let { d ->
            Row(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(18.dp)
                    .graphicsLayer {
                        val p = Motion.window(t(), 0.35f, 0.35f)
                        alpha = p * fadeOutOnLeave()
                        translationY = (1f - p) * -30f
                    }
                    .background(Color.Black.copy(alpha = 0.35f), RoundedCornerShape(50))
                    .padding(start = 8.dp, end = 11.dp, top = 5.dp, bottom = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(AppIcons.Pin, null, tint = Color.White, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(3.dp))
                Text(d, color = Color.White, style = MaterialTheme.typography.labelLarge)
            }
        }

        Column(
            Modifier
                .align(Alignment.BottomStart)
                .padding(start = 20.dp, end = 84.dp, bottom = 24.dp)
                .graphicsLayer {
                    alpha = fadeOutOnLeave()
                    translationY = pageOffset().coerceIn(0f, 1f) * 60f
                },
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.graphicsLayer {
                    val p = Motion.window(t(), 0.36f, 0.35f) { Motion.backOut(it) }
                    alpha = p.coerceIn(0f, 1f)
                    scaleX = 0.7f + 0.3f * p
                    scaleY = 0.7f + 0.3f * p
                    transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0.5f)
                },
            ) {
                if (card.isVeg != null) {
                    Box(Modifier.background(Color.White, RoundedCornerShape(4.dp)).padding(3.dp)) { VegMark(card.isVeg, size = 13.dp) }
                }
                Format.price(card.priceInr)?.let { PriceTag(it, tilt = -3f) }
            }
            // Title rises out of a mask.
            Box(Modifier.clipToBounds()) {
                Text(
                    card.name, color = Color.White, style = CardTitle, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.graphicsLayer {
                        val p = Motion.window(t(), 0.15f, 0.35f)
                        translationY = (1f - p) * size.height
                    },
                )
            }
            Text(
                listOfNotNull(card.restaurant.name, card.restaurant.locality).joinToString("  ·  "),
                color = Color.White.copy(alpha = 0.92f),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .graphicsLayer {
                        val p = Motion.window(t(), 0.28f, 0.35f)
                        alpha = p
                        translationY = (1f - p) * 24f
                    }
                    .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onRestaurant),
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 2.dp).graphicsLayer {
                    val p = Motion.window(t(), 0.44f, 0.35f)
                    alpha = p
                    translationY = (1f - p) * 24f
                },
            ) {
                val people = card.recentRecommenders
                val n = engagement.stats.recommenders
                if (people.isNotEmpty() && n > 0) {
                    AvatarStack(people, ringColor = Color(0xFF1A0D06), size = 24.dp)
                    Spacer(Modifier.width((10 + 16 * people.size.coerceAtMost(3)).dp))
                }
                Text(
                    if (n > 0) {
                        buildAnnotatedString {
                            append("Recommended by ")
                            withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = Color.White)) {
                                append(if (n == 1) "1 person" else "${Format.compact(n)} people")
                            }
                        }
                    } else {
                        buildAnnotatedString { append(Format.recommended(0)) }
                    },
                    color = Color.White.copy(alpha = 0.85f),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        Column(
            Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 12.dp, bottom = 20.dp)
                .graphicsLayer {
                    alpha = fadeOutOnLeave()
                    translationY = pageOffset().coerceIn(0f, 1f) * 120f
                },
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val rail: @Composable (Int, @Composable () -> Unit) -> Unit = { i, content ->
                Box(
                    Modifier.graphicsLayer {
                        val p = Motion.window(t(), 0.3f + 0.07f * i, 0.3f) { Motion.backOut(it, 1.4f) }
                        alpha = p.coerceIn(0f, 1f)
                        translationX = (1f - p) * 90f
                        rotationZ = (1f - p) * 25f
                    },
                ) { content() }
            }
            rail(0) {
                EngageAction(
                    Engagement.Recommend, engagement.me.recommended, "Love", engagement.stats.recommenders,
                    if (engagement.me.recommended) "Remove recommendation" else "Recommend",
                    { onToggle(Engagement.Recommend) }, look = ActionLook.Glass,
                )
            }
            rail(1) {
                EngageAction(
                    Engagement.Save, engagement.me.saved, if (engagement.me.saved) "Saved" else "Save", null,
                    if (engagement.me.saved) "Remove from saved" else "Save", { onToggle(Engagement.Save) }, look = ActionLook.Glass,
                )
            }
            rail(2) {
                EngageAction(
                    Engagement.Tried, engagement.me.tried, "Tried", null,
                    if (engagement.me.tried) "Unmark tried" else "I tried this", { onToggle(Engagement.Tried) }, look = ActionLook.Glass,
                )
            }
            rail(3) { EngageAction(null, false, "Share", null, "Share", onShare, look = ActionLook.Glass) }
        }

        HeartBurst(burst, burstAt, Modifier.fillMaxSize())
    }
}

/**
 * The double-tap heart, at the finger: a chili heart with a white rim springs up from
 * nothing with a tilt, a shockwave ring and a spray of spice confetti burst around it, it
 * holds for a beat, then floats up, shrinks and fades. Alternate taps tilt the other way.
 */
@Composable
private fun HeartBurst(trigger: Int, at: Offset, modifier: Modifier) {
    val scale = remember { Animatable(0f) }
    val alpha = remember { Animatable(0f) }
    val rise = remember { Animatable(0f) }
    val spray = remember { Animatable(0f) }
    LaunchedEffect(trigger) {
        if (trigger == 0) return@LaunchedEffect
        alpha.snapTo(1f)
        rise.snapTo(0f)
        scale.snapTo(0.2f)
        spray.snapTo(0f)
        launch { spray.animateTo(1f, tween(700, easing = LinearEasing)) }
        launch { scale.animateTo(1f, Motion.bouncy()) }
        delay(560)
        launch { rise.animateTo(-140f, Motion.exit(320)) }
        launch { scale.animateTo(0.6f, Motion.exit(320)) }
        alpha.animateTo(0f, Motion.exit(320))
    }
    val tilt = if (trigger % 2 == 0) 12f else -12f
    val density = LocalDensity.current
    val heart = with(density) { 110.dp.toPx() }
    val burstColors = listOf(Chili, Turmeric, Saffron, Color.White)
    Box(
        modifier.drawBehind {
            val c = if (at.isSet()) at else center
            drawSpiceBurst(c, spray.value, burstColors, radius = heart * 1.05f, count = 14, seed = trigger)
        },
    ) {
        if (alpha.value > 0.01f) {
            Box(
                Modifier
                    .offset {
                        val c = if (at.isSet()) at else Offset(0f, 0f)
                        IntOffset((c.x - heart / 2).roundToInt(), (c.y - heart / 2).roundToInt())
                    }
                    .then(if (at.isSet()) Modifier else Modifier.align(Alignment.Center))
                    .size(110.dp)
                    .graphicsLayer {
                        scaleX = scale.value
                        scaleY = scale.value
                        this.alpha = alpha.value
                        translationY = rise.value
                        rotationZ = tilt * (1.2f - scale.value.coerceIn(0f, 1.2f)) + tilt * 0.4f
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Favorite, null, tint = Color.White, modifier = Modifier.fillMaxSize())
                Icon(Icons.Filled.Favorite, null, tint = Chili, modifier = Modifier.fillMaxSize(0.84f))
            }
        }
    }
}

private fun Offset.isSet(): Boolean = this != Offset.Unspecified && !x.isNaN()
