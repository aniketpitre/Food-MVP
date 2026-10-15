package app.foodfeed.ui

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
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
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

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab(Routes.FEED, "Home", Icons.Filled.Home),
    Tab(Routes.SEARCH, "Search", Icons.Filled.Search),
    Tab(Routes.ADD, "Post", AppIcons.AddBox),
    Tab(Routes.SAVED, "Saved", AppIcons.Bookmark),
    Tab(Routes.ME, "Profile", Icons.Filled.Person),
)

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
            if (showBottomBar) {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                    tabs.forEach { tab ->
                        NavigationBarItem(
                            selected = currentRoute == tab.route,
                            onClick = { nav.navigateToTab(tab.route) },
                            icon = { Icon(tab.icon, tab.label) },
                            label = { Text(tab.label) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                            ),
                        )
                    }
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).then(if (showBottomBar) Modifier else Modifier.navigationBarsPadding())) {
            NavHost(nav, startDestination = startRoute) {
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
