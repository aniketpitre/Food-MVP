package app.foodfeed.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.foodfeed.data.Champion
import app.foodfeed.data.Person
import app.foodfeed.data.Rating
import app.foodfeed.data.Taster
import app.foodfeed.ui.Format
import app.foodfeed.ui.theme.Ink
import app.foodfeed.ui.theme.Motion
import app.foodfeed.ui.theme.TextFamily
import app.foodfeed.ui.theme.Turmeric
import app.foodfeed.ui.theme.accent
import app.foodfeed.ui.theme.pressScale

/**
 * The People's Pick verdict in one glance: "♥ 92% · 38 votes". The heart is chili when most
 * people loved it and muted when opinion is split; dishes with no verdicts yet say so.
 */
@Composable
fun RatingPill(rating: Rating, modifier: Modifier = Modifier, onPhoto: Boolean = false) {
    val percent = rating.percent
    val strong = percent != null && percent >= 70
    val bg = when {
        onPhoto -> Color.Black.copy(alpha = 0.38f)
        strong -> MaterialTheme.colorScheme.tertiary.copy(alpha = 0.12f)
        else -> MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val fg = if (onPhoto) Color.White else MaterialTheme.colorScheme.onSurface
    Row(
        modifier.background(bg, RoundedCornerShape(50)).padding(start = 8.dp, end = 10.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.Favorite, null,
            tint = if (strong) (if (onPhoto) Color.White else accent()) else fg.copy(alpha = 0.6f),
            modifier = Modifier.size(13.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            buildAnnotatedString {
                if (percent == null) {
                    append("No verdicts yet")
                } else {
                    withStyle(SpanStyle(fontWeight = FontWeight.ExtraBold)) { append("$percent%") }
                    append(" loved · ${Format.compact(rating.votes)} ${if (rating.votes == 1) "vote" else "votes"}")
                }
            },
            color = fg,
            fontFamily = TextFamily,
            fontSize = 12.sp,
            maxLines = 1,
        )
    }
}

/** "Loved by @diya_k · Misal expert": the most trusted person who loved a dish. */
@Composable
fun ChampionLine(champion: Champion, onOpen: (String) -> Unit, modifier: Modifier = Modifier, onPhoto: Boolean = false) {
    val fg = if (onPhoto) Color.White else MaterialTheme.colorScheme.onSurface
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .clickable(remember { MutableInteractionSource() }, indication = null) { onOpen(champion.handle) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(champion.handle, champion.displayName, 22.dp, photo = champion.avatarUrl)
        Spacer(Modifier.width(6.dp))
        Text(
            buildAnnotatedString {
                append("Top pick of ")
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("@${champion.handle}") }
            },
            color = fg.copy(alpha = 0.9f),
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (champion.isExpert) {
            Spacer(Modifier.width(6.dp))
            ExpertBadge()
        }
    }
}

@Composable
fun ExpertBadge(label: String = "Expert") {
    Text(
        label,
        color = Ink,
        fontFamily = TextFamily,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 10.sp,
        modifier = Modifier.background(Turmeric, RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/** Saffron "Follow" that turns into an outlined "Following", with a little squash on tap. */
@Composable
fun FollowButton(isFollowing: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, small: Boolean = false, followsYou: Boolean = false) {
    val interaction = remember { MutableInteractionSource() }
    val bg by animateColorAsState(
        if (isFollowing) Color.Transparent else MaterialTheme.colorScheme.primary, Motion.enter(Motion.SHORT), label = "follow-bg",
    )
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = bg,
        contentColor = if (isFollowing) MaterialTheme.colorScheme.onSurface else Ink,
        border = if (isFollowing) BorderStroke(1.5.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f)) else null,
        interactionSource = interaction,
        modifier = modifier.height(if (small) 34.dp else 42.dp).pressScale(interaction, 0.92f),
    ) {
        Row(Modifier.padding(horizontal = if (small) 14.dp else 20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            Text(
                when {
                    isFollowing -> "Following"
                    followsYou -> "Follow back"
                    else -> "Follow"
                },
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
            )
        }
    }
}

/** A person in a list: face, username and name, and a follow button unless it's you. */
@Composable
fun PersonRow(person: Person, onOpen: () -> Unit, onFollow: (() -> Unit)?, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null) {
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier
            .fillMaxWidth()
            .pressScale(interaction, 0.98f)
            .clickable(interaction, indication = null, role = Role.Button, onClick = onOpen)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(person.handle, person.displayName, 48.dp, photo = person.avatarUrl)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(person.handle, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            person.displayName?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
        }
        if (trailing != null) trailing()
        else if (onFollow != null && !person.isMe) FollowButton(person.isFollowing, onFollow, small = true)
    }
}

/** Someone the community trusts for a craving: face, name, and how many people backed them. */
@Composable
fun TasterCard(taster: Taster, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    Column(
        modifier
            .width(132.dp)
            .pressScale(interaction, 0.95f)
            .clip(RoundedCornerShape(22.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .clickable(interaction, indication = null, role = Role.Button, onClick = onOpen)
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Avatar(taster.handle, taster.displayName, 56.dp, photo = taster.avatarUrl, ring = if (taster.isExpert) Turmeric else null)
        Spacer(Modifier.height(8.dp))
        Text("@${taster.handle}", style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            if (taster.backers == 1) "1 person agreed" else "${Format.compact(taster.backers)} agreed",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (taster.isExpert) {
            Spacer(Modifier.height(6.dp))
            ExpertBadge()
        }
    }
}
