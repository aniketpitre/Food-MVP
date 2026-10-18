package app.worthabite.ui.add

import android.Manifest
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.worthabite.data.DeviceLocation
import app.worthabite.data.LocationResult
import app.worthabite.data.PlaceOption
import app.worthabite.ui.Format
import app.worthabite.ui.components.AppIcons
import app.worthabite.ui.components.CenteredMessage
import app.worthabite.ui.components.PrimaryButton
import app.worthabite.ui.components.QuietButton
import app.worthabite.ui.components.LocalApp
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun AddDishScreen(
    vm: AddDishViewModel,
    onSignIn: () -> Unit,
    onPosted: (dishId: String) -> Unit,
    showMessage: (String) -> Unit,
) {
    val app = LocalApp.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val session by app.session.collectAsState()
    val state by vm.state.collectAsStateWithLifecycle()

    if (session == null) {
        CenteredMessage(
            "Share a dish you loved",
            "Sign in to post a photo. It takes under 30 seconds.",
            null, "Sign in", onSignIn,
        )
        return
    }

    LaunchedEffect(state.created) {
        state.created?.let { post ->
            showMessage(
                if (post.restaurantStatus == "pending") "Posted! The new place appears once it's reviewed."
                else "Posted! Thanks for recommending.",
            )
            vm.reset()
            onPosted(post.dishId)
        }
    }

    var cameraUri by remember { mutableStateOf<Uri?>(null) }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) vm.setPhoto(uri)
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) vm.setPhoto(cameraUri)
    }
    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.any { it }) scope.launch {
            val found = DeviceLocation.locate(context)
            app.location.value = (found as? LocationResult.Found)?.coords
            DeviceLocation.explain(found)?.let(showMessage)
            if (found == LocationResult.Off) DeviceLocation.openSettings(context)
            vm.loadPlaces()
        }
    }

    AddDishContent(
        state = state,
        photoModel = state.photo,
        onGallery = { gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
        onCamera = {
            val dir = File(context.cacheDir, "camera").apply { mkdirs() }
            val file = File(dir, "dish-${System.currentTimeMillis()}.jpg")
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
            cameraUri = uri
            camera.launch(uri)
        },
        onClearPhoto = { vm.setPhoto(null) },
        onDishName = vm::setDishName,
        onSuggestion = vm::pickSuggestion,
        onCategory = vm::setCategory,
        onPlaceQuery = vm::setPlaceQuery,
        onSelectPlace = vm::selectPlace,
        onNewPlace = vm::startNewPlace,
        onCancelNewPlace = vm::cancelNewPlace,
        onNewPlaceName = vm::setNewPlaceName,
        onNewPlaceLocality = vm::setNewPlaceLocality,
        onUseLocation = { locationPermission.launch(DeviceLocation.PERMISSIONS) },
        onPrice = vm::setPrice,
        onVeg = vm::setVeg,
        onCaption = vm::setCaption,
        onSponsored = vm::setSponsored,
        onSubmit = { vm.submit(context) },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AddDishContent(
    state: AddDishUiState,
    photoModel: Any?,
    onGallery: () -> Unit,
    onCamera: () -> Unit,
    onClearPhoto: () -> Unit,
    onDishName: (String) -> Unit,
    onSuggestion: (app.worthabite.data.DishSuggestion) -> Unit,
    onCategory: (Int?) -> Unit,
    onPlaceQuery: (String) -> Unit,
    onSelectPlace: (PlaceOption?) -> Unit,
    onNewPlace: () -> Unit,
    onCancelNewPlace: () -> Unit,
    onNewPlaceName: (String) -> Unit,
    onNewPlaceLocality: (String) -> Unit,
    onUseLocation: () -> Unit,
    onPrice: (String) -> Unit,
    onVeg: (VegChoice) -> Unit,
    onCaption: (String) -> Unit,
    onSponsored: (Boolean) -> Unit,
    onSubmit: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Add a dish", style = MaterialTheme.typography.headlineMedium)

        // Photo
        Surface(
            shape = RoundedCornerShape(22.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.fillMaxWidth().aspectRatio(if (photoModel == null) 1.8f else 0.9f),
        ) {
            if (photoModel == null) {
                Column(
                    Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("Photo of the dish", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Photo location data is removed before upload.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        PrimaryButton("Camera", onCamera, leading = AppIcons.Camera)
                        QuietButton("Gallery", onGallery, leading = AppIcons.Photo)
                    }
                }
            } else {
                Box {
                    AsyncImage(photoModel, "Selected photo", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    IconButton(onClick = onClearPhoto, modifier = Modifier.align(Alignment.TopEnd)) {
                        Surface(shape = RoundedCornerShape(50), color = Color.Black.copy(alpha = 0.5f)) {
                            Icon(Icons.Filled.Close, "Remove photo", tint = Color.White, modifier = Modifier.padding(4.dp))
                        }
                    }
                }
            }
        }

        // Dish name with suggestions from the local dish vocabulary
        Column {
            OutlinedTextField(
                value = state.dishName,
                onValueChange = onDishName,
                label = { Text("Dish name") },
                placeholder = { Text("e.g. Misal Pav") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (state.suggestions.isNotEmpty()) {
                FlowRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    state.suggestions.forEach { s ->
                        FilterChip(selected = false, onClick = { onSuggestion(s) }, label = { Text(s.name) })
                    }
                }
            }
        }

        if (state.categories.isNotEmpty()) {
            Text("Category", style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                state.categories.forEach { c ->
                    FilterChip(
                        selected = state.categoryId == c.id,
                        onClick = { onCategory(c.id) },
                        label = { Text(c.name) },
                    )
                }
            }
        }

        // Place
        Text("Where did you eat it?", style = MaterialTheme.typography.titleSmall)
        when {
            state.selectedPlace != null -> {
                val p = state.selectedPlace!!
                Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(AppIcons.Restaurant, null)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(p.name, style = MaterialTheme.typography.titleMedium)
                            p.locality?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        }
                        TextButton(onClick = { onSelectPlace(null) }) { Text("Change") }
                    }
                }
            }
            state.addingNewPlace -> {
                Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("New place", style = MaterialTheme.typography.titleMedium)
                        OutlinedTextField(state.newPlaceName, onNewPlaceName, label = { Text("Place name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(state.newPlaceLocality, onNewPlaceLocality, label = { Text("Area (e.g. College Road)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.LocationOn, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(
                                if (state.hasLocation) "The pin uses your current approximate location." else "We need your approximate location for the pin.",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f),
                            )
                            if (!state.hasLocation) TextButton(onClick = onUseLocation) { Text("Allow") }
                        }
                        Text(
                            "New places appear for everyone after a quick review.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(onClick = onCancelNewPlace) { Text("Pick an existing place instead") }
                    }
                }
            }
            else -> {
                OutlinedTextField(
                    value = state.placeQuery,
                    onValueChange = onPlaceQuery,
                    label = { Text(if (state.placesNearby) "Search places near you" else "Search places") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (!state.placesNearby) {
                    TextButton(onClick = onUseLocation) {
                        Icon(Icons.Filled.LocationOn, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Show places near me")
                    }
                }
                Column(Modifier.clip(RoundedCornerShape(14.dp))) {
                    state.places.take(8).forEach { p ->
                        Row(
                            Modifier.fillMaxWidth().clickable { onSelectPlace(p) }.padding(horizontal = 4.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(AppIcons.Restaurant, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(p.name, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    listOfNotNull(p.locality, Format.distance(p.distanceM)).joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                TextButton(onClick = onNewPlace) {
                    Icon(Icons.Filled.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Add a new place")
                }
            }
        }

        // Details
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = state.price,
                onValueChange = onPrice,
                label = { Text("Price ₹ (optional)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
            )
        }
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            val options = listOf(VegChoice.Veg to "Veg", VegChoice.NonVeg to "Non-veg", VegChoice.Unsure to "Not sure")
            options.forEachIndexed { i, (choice, label) ->
                SegmentedButton(
                    selected = state.veg == choice,
                    onClick = { onVeg(choice) },
                    shape = SegmentedButtonDefaults.itemShape(i, options.size),
                ) { Text(label) }
            }
        }
        OutlinedTextField(
            value = state.caption,
            onValueChange = onCaption,
            label = { Text("Why is it good? (optional)") },
            minLines = 2,
            maxLines = 4,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.fillMaxWidth().clickable { onSponsored(!state.sponsored) }, verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = state.sponsored, onCheckedChange = onSponsored)
            Text(
                "This post is sponsored, or I got this food or anything else for free",
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }

        PrimaryButton(if (state.submitting) "Posting…" else "Post", onSubmit, Modifier.fillMaxWidth(), enabled = state.canSubmit)
        Spacer(Modifier.height(24.dp))
    }
}
