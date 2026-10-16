package app.foodfeed.ui

import app.foodfeed.ui.messages.InboxScreen
import app.foodfeed.ui.messages.InboxViewModel
import app.foodfeed.ui.messages.ChatScreen
import app.foodfeed.ui.messages.ChatViewModel
import app.foodfeed.ui.stories.StoryViewerScreen
import app.foodfeed.ui.stories.StoryViewerViewModel
import app.foodfeed.ui.stories.StoryComposerScreen
import app.foodfeed.ui.stories.StoryComposerViewModel
import app.foodfeed.data.OpenChat
import app.foodfeed.data.ApiException
import app.foodfeed.data.apiCall
import androidx.navigation.NavType
import androidx.navigation.navArgument
import kotlinx.coroutines.launch
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.graphics.luminance
import androidx.compose.foundation.layout.widthIn
import app.foodfeed.ui.theme.Ink
import app.foodfeed.ui.theme.LocalNavScope
import app.foodfeed.ui.theme.LocalSharedScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.outlined.AddCircle
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Search
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.navigation.NavBackStackEntry
import app.foodfeed.ui.theme.Motion
import app.foodfeed.ui.theme.pressScale
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import app.foodfeed.data.AppContainer
import app.foodfeed.data.DeviceLocation
import app.foodfeed.ui.activity.ActivityScreen
import app.foodfeed.ui.activity.ActivityViewModel
import app.foodfeed.ui.home.CravingScreen
import app.foodfeed.ui.home.CravingViewModel
import app.foodfeed.ui.home.HomeScreen
import app.foodfeed.ui.home.HomeViewModel
import app.foodfeed.ui.profile.BlockedScreen
import app.foodfeed.ui.profile.ChangePasswordScreen
import app.foodfeed.ui.profile.EditProfileScreen
import app.foodfeed.ui.profile.FollowListScreen
import app.foodfeed.ui.add.AddDishScreen
import app.foodfeed.ui.add.AddDishViewModel
import app.foodfeed.ui.admin.AdminScreen
import app.foodfeed.ui.admin.AdminViewModel
import app.foodfeed.ui.auth.AuthScreen
import app.foodfeed.ui.auth.AuthViewModel
import app.foodfeed.ui.components.AppIcons
import app.foodfeed.ui.components.LoadingBox
import app.foodfeed.ui.components.LocalApp
import app.foodfeed.ui.components.openUrl
import app.foodfeed.ui.components.UpdateSheet
import app.foodfeed.ui.dish.DishScreen
import app.foodfeed.ui.dish.DishViewModel
import app.foodfeed.ui.feed.FeedScreen
import app.foodfeed.ui.feed.FeedViewModel
import app.foodfeed.ui.onboarding.OnboardingScreen
import app.foodfeed.ui.onboarding.OnboardingStep
import app.foodfeed.ui.onboarding.OnboardingViewModel
import app.foodfeed.ui.profile.ProfileScreen
import app.foodfeed.ui.profile.ProfileViewModel
import app.foodfeed.ui.profile.SettingsScreen
import app.foodfeed.ui.restaurant.RestaurantScreen
import app.foodfeed.ui.restaurant.RestaurantViewModel
import app.foodfeed.ui.saved.SavedScreen
import app.foodfeed.ui.saved.SavedViewModel
import app.foodfeed.ui.search.SearchScreen
import app.foodfeed.ui.search.SearchViewModel

object Routes {
    const val HOME = "home"
    const val FEED = "feed"
    const val ACTIVITY = "activity"
    const val CRAVING = "craving/{q}"
    const val EDIT_PROFILE = "edit-profile"
    const val FOLLOWS = "follows/{handle}/{which}"
    const val BLOCKED = "blocked"
    const val PASSWORD = "password"
    const val SEARCH = "search"
    const val ADD = "add"
    const val SAVED = "saved"
    const val ME = "me"
    const val DISH = "dish/{id}"
    const val RESTAURANT = "restaurant/{id}"
    const val USER = "user/{handle}"
    const val AUTH = "auth"
    const val SETTINGS = "settings"
    const val ADMIN = "admin"
    const val SETUP = "setup?step={step}"
    const val INBOX = "inbox"
    const val CHAT = "chat/{id}"
    const val STORIES = "stories/{start}"
    const val USER_STORIES = "user-stories/{handle}"
    const val NEW_STORY = "new-story"
    fun chat(id: String) = "chat/$id"
    fun stories(start: Int) = "stories/$start"
    fun userStories(handle: String) = "user-stories/$handle"
    fun dish(id: String) = "dish/$id"
    fun restaurant(id: String) = "restaurant/$id"
    fun user(handle: String) = "user/$handle"
    fun craving(q: String) = "craving/${android.net.Uri.encode(q)}"
    fun follows(handle: String, followers: Boolean) = "follows/$handle/${if (followers) "followers" else "following"}"
    fun setup(step: OnboardingStep? = null) = if (step == null) "setup" else "setup?step=${step.name}"
}

private data class Tab(val route: String, val label: String, val icon: ImageVector, val selectedIcon: ImageVector)

private val tabs = listOf(
    Tab(Routes.HOME, "Home", Icons.Outlined.Home, Icons.Filled.Home),
    Tab(Routes.FEED, "Explore", AppIcons.Compass, AppIcons.Compass),
    Tab(Routes.ADD, "Post a dish", Icons.Outlined.AddCircle, Icons.Filled.AddCircle),
    Tab(Routes.ACTIVITY, "Activity", AppIcons.BellOutline, AppIcons.Bell),
    Tab(Routes.ME, "Profile", Icons.Outlined.Person, Icons.Filled.Person),
)
private val tabRoutes = tabs.map { it.route }.toSet()

@Composable
inline fun <reified VM : ViewModel> appViewModel(key: String? = null, crossinline create: (AppContainer) -> VM): VM {
    val app = LocalApp.current
    return viewModel(key = key, factory = viewModelFactory { initializer { create(app) } })
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun FoodFeedRoot(deepLinkDishId: String?, onDeepLinkHandled: () -> Unit) {
    val app = LocalApp.current
    val ready by app.ready.collectAsState()
    val city by app.city.collectAsState()
    val server by app.serverUrl.collectAsState()
    if (!ready) {
        LoadingBox()
        return
    }
    val nav = rememberNavController()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val showMessage: (String) -> Unit = { msg -> scope.launch { snackbar.showSnackbar(msg) } }
    val needsSetup = server.isBlank() || city == null
    // Decided once: changing the start destination later would reset navigation.
    val startRoute = remember { if (needsSetup) Routes.SETUP else Routes.HOME }

    // Look for a newer version once per launch; offer it in a sheet.
    val update by app.availableUpdate.collectAsState()
    var updateDismissed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { app.checkForUpdate() }
    if (update != null && !updateDismissed) {
        UpdateSheet(update!!, onDismiss = { updateDismissed = true })
    }
    LaunchedEffect(update) { if (update != null) updateDismissed = false }

    // Refresh the approximate location quietly when permission was already given.
    LaunchedEffect(Unit) {
        if (DeviceLocation.hasPermission(context)) app.location.value = DeviceLocation.current(context, precise = DeviceLocation.hasPrecise(context))
    }
    LaunchedEffect(deepLinkDishId, needsSetup) {
        if (deepLinkDishId != null && !needsSetup) {
            nav.navigate(Routes.dish(deepLinkDishId))
            onDeepLinkHandled()
        }
    }

    val backStack by nav.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val showBottomBar = currentRoute in tabs.map { it.route }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        contentWindowInsets = WindowInsets(0),
        bottomBar = {
            AnimatedVisibility(
                visible = showBottomBar,
                enter = slideInVertically(Motion.enter()) { it } + fadeIn(Motion.enter()),
                exit = slideOutVertically(Motion.exit()) { it } + fadeOut(Motion.exit()),
            ) {
                val unread by app.unreadActivity.collectAsState()
                LiquidDock(currentRoute, unread) { nav.navigateToTab(it) }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).then(if (showBottomBar) Modifier else Modifier.navigationBarsPadding())) {
            SharedTransitionLayout(Modifier.fillMaxSize()) {
            CompositionLocalProvider(LocalSharedScope provides this) {
            NavHost(
                nav,
                startDestination = startRoute,
                // Tabs slide a little toward the side you tapped and fade; the dish page only
                // fades, because the photo itself morphs from the card (hero transition); other
                // pushed pages slide in from the right while the page underneath drifts back and dims.
                enterTransition = {
                    when {
                        isTabSwitch() -> tabEnter(tabDirection())
                        targetIs(Routes.DISH) -> fadeIn(tween(320, 80, Motion.Standard))
                        else -> slideInHorizontally(Motion.enter(Motion.LONG)) { it / 3 } + fadeIn(Motion.enter(Motion.MEDIUM))
                    }
                },
                exitTransition = {
                    when {
                        isTabSwitch() -> fadeOut(Motion.exit(110)) + scaleOut(Motion.exit(110), targetScale = 0.98f)
                        targetIs(Routes.DISH) -> fadeOut(tween(300, 60, Motion.Standard))
                        else -> slideOutHorizontally(Motion.enter(Motion.LONG)) { -it / 8 } + fadeOut(Motion.exit(Motion.MEDIUM))
                    }
                },
                popEnterTransition = {
                    when {
                        isTabSwitch() -> tabEnter(tabDirection())
                        initialIs(Routes.DISH) -> fadeIn(tween(300, 40, Motion.Standard))
                        else -> slideInHorizontally(Motion.enter(Motion.LONG)) { -it / 8 } + fadeIn(Motion.enter(Motion.MEDIUM))
                    }
                },
                popExitTransition = {
                    when {
                        isTabSwitch() -> fadeOut(Motion.exit(110))
                        initialIs(Routes.DISH) -> fadeOut(tween(260, 0, Motion.Standard))
                        else -> slideOutHorizontally(Motion.exit(Motion.MEDIUM)) { it / 3 } + fadeOut(Motion.exit(Motion.MEDIUM))
                    }
                },
            ) {
                val openDish: (String) -> Unit = { nav.navigate(Routes.dish(it)) }
                val openRestaurant: (String) -> Unit = { nav.navigate(Routes.restaurant(it)) }
                val openUser: (String) -> Unit = { nav.navigate(Routes.user(it)) }
                val signIn: () -> Unit = { nav.navigate(Routes.AUTH) }
                val openChatWith: suspend (String) -> Unit = { handle ->
                    try {
                        nav.navigate(Routes.chat(apiCall { app.api.openChat(OpenChat(handle)) }.id))
                    } catch (e: ApiException) {
                        showMessage(e.message)
                    }
                }

                composable(Routes.INBOX) {
                    val vm = appViewModel { InboxViewModel(it) }
                    InboxScreen(vm, { nav.popBackStack() }, { nav.navigate(Routes.chat(it)) }, signIn, showMessage)
                }
                composable(Routes.CHAT, arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
                    val id = entry.arguments?.getString("id").orEmpty()
                    val vm = appViewModel(key = "chat-$id") { ChatViewModel(it, id) }
                    ChatScreen(vm, { nav.popBackStack() }, openUser, openDish, showMessage)
                }
                composable(Routes.STORIES, arguments = listOf(navArgument("start") { type = NavType.IntType })) { entry ->
                    val start = entry.arguments?.getInt("start") ?: 0
                    val vm = appViewModel(key = "stories-$start") { StoryViewerViewModel(it, null, start) }
                    StoryViewerScreen(vm, { nav.popBackStack() }, openDish, openRestaurant, openUser, signIn, showMessage)
                }
                composable(Routes.USER_STORIES) { entry ->
                    val handle = entry.arguments?.getString("handle").orEmpty()
                    val vm = appViewModel(key = "user-stories-$handle") { StoryViewerViewModel(it, handle, 0) }
                    StoryViewerScreen(vm, { nav.popBackStack() }, openDish, openRestaurant, openUser, signIn, showMessage)
                }
                composable(Routes.NEW_STORY) {
                    val vm = appViewModel { StoryComposerViewModel(it) }
                    StoryComposerScreen(vm, { nav.popBackStack() }, signIn, showMessage)
                }

                composable(Routes.SETUP, arguments = listOf(navArgument("step") { type = NavType.StringType; nullable = true })) { entry ->
                    val step = entry.arguments?.getString("step")?.let { OnboardingStep.valueOf(it) }
                    val vm = appViewModel(key = "setup-$step") { OnboardingViewModel(it, step) }
                    OnboardingScreen(vm) {
                        if (nav.previousBackStackEntry != null) nav.popBackStack()
                        else nav.navigate(Routes.HOME) { popUpTo(Routes.SETUP) { inclusive = true } }
                    }
                }
                composable(Routes.HOME) {
                    NavScoped {
                        val vm = appViewModel { HomeViewModel(it) }
                        Box(Modifier.statusBarsPadding()) {
                            HomeScreen(
                                vm,
                                onCraving = { nav.navigate(Routes.craving(it)) },
                                onOpenDish = openDish,
                                onOpenUser = openUser,
                                onChangeCity = { nav.navigate(Routes.setup(OnboardingStep.City)) },
                                onExplore = { nav.navigateToTab(Routes.FEED) },
                                onAddDish = { nav.navigateToTab(Routes.ADD) },
                                onOpenStory = { nav.navigate(Routes.stories(it)) },
                                onAddStory = { if (app.session.value == null) signIn() else nav.navigate(Routes.NEW_STORY) },
                                onInbox = { nav.navigate(Routes.INBOX) },
                            )
                        }
                    }
                }
                composable(Routes.CRAVING, arguments = listOf(navArgument("q") { type = NavType.StringType })) { entry ->
                    NavScoped {
                        val q = entry.arguments?.getString("q").orEmpty()
                        val vm = appViewModel(key = "craving-$q") { CravingViewModel(it, q) }
                        CravingScreen(vm, { nav.popBackStack() }, openDish, openUser, onAddDish = { nav.navigateToTab(Routes.ADD) })
                    }
                }
                composable(Routes.ACTIVITY) {
                    val vm = appViewModel { ActivityViewModel(it) }
                    Box(Modifier.statusBarsPadding()) { ActivityScreen(vm, openDish, openUser, signIn, showMessage) }
                }
                composable(Routes.EDIT_PROFILE) { EditProfileScreen({ nav.popBackStack() }, showMessage) }
                composable(Routes.BLOCKED) { BlockedScreen({ nav.popBackStack() }, showMessage) }
                composable(Routes.PASSWORD) { ChangePasswordScreen({ nav.popBackStack() }, showMessage) }
                composable(Routes.FOLLOWS) { entry ->
                    val handle = entry.arguments?.getString("handle").orEmpty()
                    val which = entry.arguments?.getString("which")
                    FollowListScreen(handle, which == "followers", { nav.popBackStack() }, openUser, signIn, showMessage)
                }
                composable(Routes.FEED) {
                    NavScoped {
                        val vm = appViewModel { FeedViewModel(it) }
                        Box(Modifier.statusBarsPadding()) {
                            FeedScreen(
                                vm, openDish, openRestaurant,
                                onChangeCity = { nav.navigate(Routes.setup(OnboardingStep.City)) },
                                onSearch = { nav.navigate(Routes.SEARCH) },
                                onAddDish = { nav.navigateToTab(Routes.ADD) },
                                onNeedSignIn = signIn,
                                showMessage = showMessage,
                                onOpenUser = openUser,
                            )
                        }
                    }
                }
                composable(Routes.SEARCH) {
                    NavScoped {
                        val vm = appViewModel { SearchViewModel(it) }
                        Box(Modifier.statusBarsPadding()) {
                            SearchScreen(vm, openDish, openRestaurant, signIn, showMessage, onOpenUser = openUser, onBack = { nav.popBackStack() })
                        }
                    }
                }
                composable(Routes.ADD) {
                    val vm = appViewModel { AddDishViewModel(it) }
                    Box(Modifier.statusBarsPadding()) {
                        AddDishScreen(vm, signIn, onPosted = { id -> nav.navigate(Routes.dish(id)) }, showMessage = showMessage)
                    }
                }
                composable(Routes.SAVED) {
                    NavScoped {
                        val vm = appViewModel { SavedViewModel(it) }
                        Box(Modifier.statusBarsPadding()) { SavedScreen(vm, openDish, signIn, showMessage) }
                    }
                }
                composable(Routes.ME) {
                    NavScoped {
                        val vm = appViewModel(key = "me") { ProfileViewModel(it, null) }
                        Box(Modifier.statusBarsPadding()) {
                            ProfileScreen(
                                vm, true, null, openDish, { nav.navigate(Routes.SETTINGS) }, signIn, showMessage,
                                onEditProfile = { nav.navigate(Routes.EDIT_PROFILE) },
                                onFollows = { h, f -> nav.navigate(Routes.follows(h, f)) },
                                onMessage = { h -> scope.launch { openChatWith(h) } },
                                onOpenStory = { h -> nav.navigate(Routes.userStories(h)) },
                            )
                        }
                    }
                }
                composable(Routes.USER) { entry ->
                    NavScoped {
                        val handle = entry.arguments?.getString("handle").orEmpty()
                        val vm = appViewModel(key = "user-$handle") { ProfileViewModel(it, handle) }
                        Box(Modifier.statusBarsPadding()) {
                            ProfileScreen(
                                vm, false, { nav.popBackStack() }, openDish, { nav.navigate(Routes.SETTINGS) }, signIn, showMessage,
                                onEditProfile = { nav.navigate(Routes.EDIT_PROFILE) },
                                onFollows = { h, f -> nav.navigate(Routes.follows(h, f)) },
                                onMessage = { h -> scope.launch { openChatWith(h) } },
                                onOpenStory = { h -> nav.navigate(Routes.userStories(h)) },
                            )
                        }
                    }
                }
                composable(Routes.DISH) { entry ->
                    NavScoped {
                        val id = entry.arguments?.getString("id").orEmpty()
                        val vm = appViewModel(key = "dish-$id") { DishViewModel(it, id) }
                        DishScreen(vm, { nav.popBackStack() }, openDish, openRestaurant, openUser, signIn, showMessage)
                    }
                }
                composable(Routes.RESTAURANT) { entry ->
                    NavScoped {
                        val id = entry.arguments?.getString("id").orEmpty()
                        val vm = appViewModel(key = "restaurant-$id") { RestaurantViewModel(it, id) }
                        RestaurantScreen(vm, { nav.popBackStack() }, openDish, signIn, showMessage)
                    }
                }
                composable(Routes.AUTH) {
                    val vm = appViewModel { AuthViewModel(it) }
                    AuthScreen(
                        vm,
                        onDone = { showMessage("You're signed in."); nav.popBackStack() },
                        onClose = { app.setPendingAction(null); nav.popBackStack() },
                        onOpenTerms = { openUrl(context, "${app.serverUrl.value}/terms") },
                    )
                }
                composable(Routes.SETTINGS) {
                    SettingsScreen(
                        onBack = { nav.popBackStack() },
                        onChangeCity = { nav.navigate(Routes.setup(OnboardingStep.City)) },
                        onChangeServer = { nav.navigate(Routes.setup(OnboardingStep.Server)) },
                        onAdmin = { nav.navigate(Routes.ADMIN) },
                        onSignIn = signIn,
                        showMessage = showMessage,
                        onEditProfile = { nav.navigate(Routes.EDIT_PROFILE) },
                        onPassword = { nav.navigate(Routes.PASSWORD) },
                        onBlocked = { nav.navigate(Routes.BLOCKED) },
                    )
                }
                composable(Routes.ADMIN) {
                    val vm = appViewModel { AdminViewModel(it) }
                    AdminScreen(vm, { nav.popBackStack() }, openDish, showMessage)
                }
            }
            }
            }
        }
    }
}

private fun NavHostController.navigateToTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

private fun AnimatedContentTransitionScope<NavBackStackEntry>.isTabSwitch(): Boolean =
    initialState.destination.route in tabRoutes && targetState.destination.route in tabRoutes

private fun AnimatedContentTransitionScope<NavBackStackEntry>.targetIs(route: String): Boolean =
    targetState.destination.route == route

private fun AnimatedContentTransitionScope<NavBackStackEntry>.initialIs(route: String): Boolean =
    initialState.destination.route == route

/** +1 when moving to a tab on the right, -1 to the left. */
private fun AnimatedContentTransitionScope<NavBackStackEntry>.tabDirection(): Int {
    val from = tabs.indexOfFirst { it.route == initialState.destination.route }
    val to = tabs.indexOfFirst { it.route == targetState.destination.route }
    return if (to >= from) 1 else -1
}

private fun tabEnter(direction: Int): EnterTransition =
    fadeIn(Motion.enter(Motion.MEDIUM, 40)) +
        slideInHorizontally(Motion.enter(Motion.LONG, 40)) { direction * it / 12 } +
        scaleIn(Motion.enter(Motion.LONG, 40), initialScale = 0.97f)

/** Gives the screen inside a destination the scope its hero photos animate with. */
@Composable
private fun AnimatedContentScope.NavScoped(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalNavScope provides this, content = content)
}

/**
 * A floating ink dock with a saffron blob behind the current tab. The blob is liquid: its
 * leading edge races to the new tab on a stiff spring while the trailing edge follows on a
 * soft one, so it stretches as it travels (and thins, keeping its volume), then snaps back
 * into a round pill. The icon that gains the blob pops and wiggles.
 */
@Composable
internal fun LiquidDock(currentRoute: String?, unread: Int = 0, onSelect: (String) -> Unit) {
    val index = tabs.indexOfFirst { it.route == currentRoute }.coerceAtLeast(0)
    // Light theme: an ink dock. Dark theme: a raised dark dock, so it never glares at night.
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val dock = if (dark) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.inverseSurface
    val idle = (if (dark) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.inverseOnSurface).copy(alpha = 0.62f)
    val blob = MaterialTheme.colorScheme.primary
    val shape = RoundedCornerShape(50)
    // Sized to the phone: slimmer on short screens, never wider than 440dp (tablets,
    // landscape, foldables), and always clear of the system navigation bar.
    val config = LocalConfiguration.current
    val dockHeight = when {
        config.screenHeightDp < 640 -> 52.dp
        config.screenHeightDp < 760 -> 58.dp
        else -> 62.dp
    }
    val iconSize = if (dockHeight < 56.dp) 22.dp else 25.dp
    Box(
        Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 14.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        BoxWithConstraints(
            Modifier
                .widthIn(max = 440.dp)
                .fillMaxWidth()
                .height(dockHeight)
                .shadow(16.dp, shape, ambientColor = Color.Black.copy(alpha = 0.4f), spotColor = Color.Black.copy(alpha = 0.4f))
                .background(dock, shape),
        ) {
            val density = LocalDensity.current
            val slot = with(density) { (maxWidth / tabs.size).toPx() }
            val inset = with(density) { 7.dp.toPx() }
            val left = remember { Animatable(slot * index + inset) }
            val right = remember { Animatable(slot * (index + 1) - inset) }
            LaunchedEffect(index, slot) {
                val l = slot * index + inset
                val r = slot * (index + 1) - inset
                val toRight = l > left.value
                launch { left.animateTo(l, if (toRight) Motion.liquidTrail() else Motion.liquidLead()) }
                launch { right.animateTo(r, if (toRight) Motion.liquidLead() else Motion.liquidTrail()) }
            }
            Box(
                Modifier.fillMaxSize().drawBehind {
                    val w = right.value - left.value
                    val rest = slot - inset * 2
                    // Stretch thins the blob so it reads as liquid rather than a sliding box.
                    val h = (size.height - inset * 2) * (rest / w).coerceIn(0.62f, 1f).let { kotlin.math.sqrt(it) }
                    drawRoundRect(
                        blob,
                        topLeft = Offset(left.value, (size.height - h) / 2),
                        size = Size(w, h),
                        cornerRadius = CornerRadius(h / 2),
                    )
                },
            )
            Row(Modifier.fillMaxSize()) {
                tabs.forEachIndexed { i, tab ->
                    val selected = i == index
                    val tint by animateColorAsState(if (selected) Ink else idle, Motion.enter(Motion.SHORT), label = "tab-tint")
                    val pop = remember { Animatable(1f) }
                    val wiggle = remember { Animatable(0f) }
                    var was by remember { mutableStateOf(selected) }
                    LaunchedEffect(selected) {
                        if (selected && !was) {
                            launch { pop.snapTo(0.6f); pop.animateTo(1f, Motion.bouncy()) }
                            launch { wiggle.snapTo(if (tab.route == Routes.ADD) -90f else -16f); wiggle.animateTo(0f, Motion.pop()) }
                        }
                        was = selected
                    }
                    val interaction = remember { MutableInteractionSource() }
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clickable(interaction, indication = null, role = Role.Tab) { onSelect(tab.route) },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (tab.route == Routes.ACTIVITY && unread > 0 && !selected) {
                            // Unread activity: a chili dot that pops in on the bell.
                            val dot = remember { Animatable(0f) }
                            LaunchedEffect(unread) { dot.snapTo(0.3f); dot.animateTo(1f, Motion.bouncy()) }
                            Box(
                                Modifier
                                    .offset(x = 9.dp, y = (-9).dp)
                                    .size(9.dp)
                                    .graphicsLayer { scaleX = dot.value; scaleY = dot.value }
                                    .background(MaterialTheme.colorScheme.tertiary, androidx.compose.foundation.shape.CircleShape),
                            )
                        }
                        Icon(
                            if (selected) tab.selectedIcon else tab.icon, tab.label, tint = tint,
                            modifier = Modifier
                                .size(iconSize)
                                .pressScale(interaction, 0.8f)
                                .graphicsLayer {
                                    scaleX = pop.value
                                    scaleY = pop.value
                                    rotationZ = wiggle.value
                                },
                        )
                    }
                }
            }
        }
    }
}
