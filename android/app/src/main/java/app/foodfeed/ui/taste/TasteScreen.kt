package app.foodfeed.ui.taste

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.ExperimentalLayoutApi
import androidx.compose.foundation.FlowRow
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.foodfeed.data.ApiException
import app.foodfeed.data.AppContainer
import app.foodfeed.data.Category
import app.foodfeed.data.LearnedTaste
import app.foodfeed.data.TasteBody
import app.foodfeed.data.apiCall
import app.foodfeed.ui.components.LoadingBox
import app.foodfeed.ui.components.PrimaryButton
import app.foodfeed.ui.components.QuietButton
import app.foodfeed.ui.profile.BackBar
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TasteUiState(
    val loading: Boolean = true,
    val categories: List<Category> = emptyList(),
    val picked: Set<Int> = emptySet(),
    val diet: String = "nonveg",
    val spice: Int = 3,
    val learned: List<LearnedTaste> = emptyList(),
    val saving: Boolean = false,
    val error: String? = null,
)

class TasteViewModel(private val app: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(TasteUiState())
    val state: StateFlow<TasteUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            try {
                val cats = apiCall { app.api.categories() }
                val t = apiCall { app.api.taste() }
                _state.update {
                    it.copy(loading = false, categories = cats, picked = t.categories.toSet(), diet = t.diet, spice = t.spice, learned = t.learned)
                }
            } catch (e: ApiException) {
                _state.update { it.copy(loading = false, error = e.message) }
            }
        }
    }

    fun toggle(id: Int) = _state.update { s -> s.copy(picked = if (id in s.picked) s.picked - id else s.picked + id) }
    fun diet(d: String) = _state.update { it.copy(diet = d) }
    fun spice(n: Int) = _state.update { it.copy(spice = n) }

    fun save(onDone: () -> Unit, showMessage: (String) -> Unit) {
        val s = _state.value
        _state.update { it.copy(saving = true) }
        viewModelScope.launch {
            try {
                apiCall { app.api.setTaste(TasteBody(s.picked.sorted(), s.diet, s.spice)) }
                // Home and Explore reload with the new taste.
                app.contentVersion.update { it + 1 }
                onDone()
            } catch (e: ApiException) {
                showMessage(e.message)
            } finally {
                _state.update { it.copy(saving = false) }
            }
        }
    }
}

private val Diets = listOf(Triple("veg", "🟢", "Veg"), Triple("egg", "🥚", "Egg"), Triple("nonveg", "🍗", "Non-veg"))
private val SpiceWords = listOf("No spice", "Mild", "Medium", "Spicy", "Fire!")

/**
 * Your taste, in three taps: dish types you love, veg / egg / non-veg, and how much spice.
 * It finds people who eat like you (Taste Match) and fills For You until your loves, saves
 * and passes say more. [first] is the version shown right after signing up, with Skip.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TasteScreen(vm: TasteViewModel, first: Boolean, onDone: () -> Unit, showMessage: (String) -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()) {
        BackBar("Your taste", onDone)
        if (state.loading) {
            LoadingBox(Modifier.fillMaxSize())
            return@Column
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Tell us how you eat. We'll find people whose taste matches yours, and show you what they love.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Heading("What do you love?")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                state.categories.forEach { c ->
                    Choice(listOfNotNull(c.emoji, c.name).joinToString(" "), c.id in state.picked, "taste_cat_${c.slug}") { vm.toggle(c.id) }
                }
            }
            Heading("You eat")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Diets.forEach { (key, emoji, label) ->
                    Choice("$emoji $label", state.diet == key, "taste_diet_$key", Modifier.weight(1f)) { vm.diet(key) }
                }
            }
            Heading("Spice: ${SpiceWords[state.spice - 1]}")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                for (n in 1..5) {
                    val on = n <= state.spice
                    Text(
                        "🌶️",
                        fontSize = 28.sp,
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(if (on) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer)
                            .clickable(role = Role.Button) { vm.spice(n) }
                            .semantics { contentDescription = "Spice ${SpiceWords[n - 1]}"; selected = n == state.spice }
                            .testTag("taste_spice_$n")
                            .padding(8.dp),
                    )
                }
            }
            if (state.learned.isNotEmpty()) {
                Heading("What your votes say")
                Text(
                    "From the dishes you love, save and pass on:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.learned.forEach { l ->
                        Text(
                            listOfNotNull(l.emoji, l.name).joinToString(" "),
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.secondaryContainer)
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (first) QuietButton("Skip", onDone, Modifier.weight(1f))
            PrimaryButton(
                if (state.saving) "Saving…" else "Save my taste",
                { vm.save(onDone, showMessage) },
                Modifier.weight(if (first) 2f else 1f).testTag("taste_save"),
                enabled = !state.saving,
            )
        }
    }
}

@Composable
private fun Heading(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun Choice(label: String, on: Boolean, tag: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val bg by animateColorAsState(if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerLowest, label = "choice")
    val fg = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    Text(
        label,
        style = MaterialTheme.typography.labelLarge,
        color = fg,
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(bg)
            .border(1.dp, if (on) bg else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(50))
            .clickable(role = Role.Checkbox, onClick = onClick)
            .semantics { selected = on }
            .testTag(tag)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    )
}
