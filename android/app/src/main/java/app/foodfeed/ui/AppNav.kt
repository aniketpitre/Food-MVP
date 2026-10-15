package app.foodfeed.ui

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.outlined.AddCircle
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.navigation.NavBackStackEntry
import app.foodfeed.ui.theme.Motion
import app.foodfeed.ui.theme.pressScale
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.foodfeed.data.AppContainer
import app.foodfeed.data.DeviceLocation
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
import kotlinx.coroutines.launch

object Routes {
    const val FEED = "feed"
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
    fun dish(id: String) = "dish/$id"
    fun restaurant(id: String) = "restaurant/$id"
    fun user(handle: String) = "user/$handle"
    fun setup(step: OnboardingStep? = null) = if (step == null) "setup" else "setup?step=${step.name}"
}

private data class Tab(val route: String, val label: String, val icon: ImageVector, val selectedIcon: ImageVector)

private val tabs = listOf(
    Tab(Routes.FEED, "Home", Icons.Outlined.Home, Icons.Filled.Home),
    Tab(Routes.SEARCH, "Search", Icons.Outlined.Search, Icons.Filled.Search),
    Tab(Routes.ADD, "Post a dish", Icons.Outlined.AddCircle, Icons.Filled.AddCircle),
    Tab(Routes.SAVED, "Saved", AppIcons.BookmarkBorder, AppIcons.Bookmark),
    Tab(Routes.ME, "Profile", Icons.Outlined.Person, Icons.Filled.Person),
)
private val tabRoutes = tabs.map { it.route }.toSet()

@Composable
inline fun <reified VM : ViewModel> appViewModel(key: String? = null, crossinline create: (AppContainer) -> VM): VM {
    val app = LocalApp.current
    return viewModel(key = key, factory = viewModelFactory { initializer { create(app) } })
}

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
    val startRoute = remember { if (needsSetup) Routes.SETUP else Routes.FEED }

    // Refresh the approximate location quietly when permission was already given.
    LaunchedEffect(Unit) {
        if (DeviceLocation.hasPermission(context)) app.location.value = DeviceLocation.current(context)
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
                MinimalTabBar(currentRoute) { nav.navigateToTab(it) }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).then(if (showBottomBar) Modifier else Modifier.navigationBarsPadding())) {
            NavHost(
                nav,
                startDestination = startRoute,
                // Tabs cross-fade in place; pushed pages slide in from the right with the
                // emphasized curve while the page underneath drifts back a little and dims.
                enterTransition = {
                    if (isTabSwitch()) fadeIn(Motion.enter(Motion.MEDIUM, 60))
                    else slideInHorizontally(Motion.enter(Motion.LONG)) { it / 3 } + fadeIn(Motion.enter(Motion.MEDIUM))
                },
                exitTransition = {
                    if (isTabSwitch()) fadeOut(Motion.exit(90))
                    else slideOutHorizontally(Motion.enter(Motion.LONG)) { -it / 8 } + fadeOut(Motion.exit(Motion.MEDIUM))
                },
                popEnterTransition = {
                    if (isTabSwitch()) fadeIn(Motion.enter(Motion.MEDIUM, 60))
                    else slideInHorizontally(Motion.enter(Motion.LONG)) { -it / 8 } + fadeIn(Motion.enter(Motion.MEDIUM))
                },
                popExitTransition = {
                    if (isTabSwitch()) fadeOut(Motion.exit(90))
                    else slideOutHorizontally(Motion.exit(Motion.MEDIUM)) { it / 3 } + fadeOut(Motion.exit(Motion.MEDIUM))
                },
            ) {
                val openDish: (String) -> Unit = { nav.navigate(Routes.dish(it)) }
                val openRestaurant: (String) -> Unit = { nav.navigate(Routes.restaurant(it)) }
                val openUser: (String) -> Unit = { nav.navigate(Routes.user(it)) }
                val signIn: () -> Unit = { nav.navigate(Routes.AUTH) }

                composable(Routes.SETUP, arguments = listOf(navArgument("step") { type = NavType.StringType; nullable = true })) { entry ->
                    val step = entry.arguments?.getString("step")?.let { OnboardingStep.valueOf(it) }
                    val vm = appViewModel(key = "setup-$step") { OnboardingViewModel(it, step) }
                    OnboardingScreen(vm) {
                        if (nav.previousBackStackEntry != null) nav.popBackStack()
                        else nav.navigate(Routes.FEED) { popUpTo(Routes.SETUP) { inclusive = true } }
                    }
                }
                composable(Routes.FEED) {
                    val vm = appViewModel { FeedViewModel(it) }
                    Box(Modifier.statusBarsPadding()) {
                        FeedScreen(
                            vm, openDish, openRestaurant,
                            onChangeCity = { nav.navigate(Routes.setup(OnboardingStep.City)) },
                            onSearch = { nav.navigateToTab(Routes.SEARCH) },
                            onAddDish = { nav.navigateToTab(Routes.ADD) },
                            onNeedSignIn = signIn,
                            showMessage = showMessage,
                        )
                    }
                }
                composable(Routes.SEARCH) {
                    val vm = appViewModel { SearchViewModel(it) }
                    Box(Modifier.statusBarsPadding()) { SearchScreen(vm, openDish, openRestaurant, signIn, showMessage) }
                }
                composable(Routes.ADD) {
                    val vm = appViewModel { AddDishViewModel(it) }
                    Box(Modifier.statusBarsPadding()) {
                        AddDishScreen(vm, signIn, onPosted = { id -> nav.navigate(Routes.dish(id)) }, showMessage = showMessage)
                    }
                }
                composable(Routes.SAVED) {
                    val vm = appViewModel { SavedViewModel(it) }
                    Box(Modifier.statusBarsPadding()) { SavedScreen(vm, openDish, signIn, showMessage) }
                }
                composable(Routes.ME) {
                    val vm = appViewModel(key = "me") { ProfileViewModel(it, null) }
                    Box(Modifier.statusBarsPadding()) {
                        ProfileScreen(vm, true, null, openDish, { nav.navigate(Routes.SETTINGS) }, signIn, showMessage)
                    }
                }
                composable(Routes.USER) { entry ->
                    val handle = entry.arguments?.getString("handle").orEmpty()
                    val vm = appViewModel(key = "user-$handle") { ProfileViewModel(it, handle) }
                    Box(Modifier.statusBarsPadding()) {
                        ProfileScreen(vm, false, { nav.popBackStack() }, openDish, { nav.navigate(Routes.SETTINGS) }, signIn, showMessage)
                    }
                }
                composable(Routes.DISH) { entry ->
                    val id = entry.arguments?.getString("id").orEmpty()
                    val vm = appViewModel(key = "dish-$id") { DishViewModel(it, id) }
                    DishScreen(vm, { nav.popBackStack() }, openDish, openRestaurant, openUser, signIn, showMessage)
                }
                composable(Routes.RESTAURANT) { entry ->
                    val id = entry.arguments?.getString("id").orEmpty()
                    val vm = appViewModel(key = "restaurant-$id") { RestaurantViewModel(it, id) }
                    RestaurantScreen(vm, { nav.popBackStack() }, openDish, signIn, showMessage)
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

private fun NavHostController.navigateToTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

private fun AnimatedContentTransitionScope<NavBackStackEntry>.isTabSwitch(): Boolean =
    initialState.destination.route in tabRoutes && targetState.destination.route in tabRoutes

/**
 * Icons only, no pill indicator: the active icon fills in, and a 4dp ink dot glides
 * underneath it on a spring.
 */
@Composable
private fun MinimalTabBar(currentRoute: String?, onSelect: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).navigationBarsPadding()) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        BoxWithConstraints(Modifier.fillMaxWidth().height(56.dp)) {
            val slot = maxWidth / tabs.size
            Row(Modifier.fillMaxSize()) {
                tabs.forEach { tab ->
                    val selected = tab.route == currentRoute
                    val tint by animateColorAsState(
                        if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                        Motion.enter(Motion.SHORT),
                        label = "tab-tint",
                    )
                    val interaction = remember { MutableInteractionSource() }
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clickable(interaction, indication = null, role = Role.Tab) { onSelect(tab.route) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            if (selected) tab.selectedIcon else tab.icon, tab.label, tint = tint,
                            modifier = Modifier.size(26.dp).pressScale(interaction, 0.82f),
                        )
                    }
                }
            }
            val index = tabs.indexOfFirst { it.route == currentRoute }.coerceAtLeast(0)
            val dotX by animateDpAsState(slot * index + slot / 2 - 2.dp, Motion.glide(), label = "tab-dot")
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .offset(x = dotX, y = (-7).dp)
                    .size(4.dp)
                    .background(MaterialTheme.colorScheme.onSurface, CircleShape),
            )
        }
    }
}
