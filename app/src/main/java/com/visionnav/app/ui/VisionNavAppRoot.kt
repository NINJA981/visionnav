package com.visionnav.app.ui

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.visionnav.app.BuildConfig
import com.visionnav.app.R
import com.visionnav.app.feature.AppNavigator
import com.visionnav.app.feature.FeatureScreen
import com.visionnav.app.settings.AppSettings
import com.visionnav.app.settings.ThemeMode
import com.visionnav.app.ui.about.AboutScreen
import com.visionnav.app.ui.components.FullScreenMessage
import com.visionnav.app.ui.components.SosDialog
import com.visionnav.app.ui.contract.AppUiState
import com.visionnav.app.ui.contract.SessionMode
import com.visionnav.app.ui.contract.UiAction
import com.visionnav.app.ui.crossing.CrossingAssistScreen
import com.visionnav.app.ui.debug.DebugOverlayPlaceholder
import com.visionnav.app.ui.debug.DebugScreen
import com.visionnav.app.ui.home.HomeScreen
import com.visionnav.app.ui.navigation.ActiveNavigationScreen
import com.visionnav.app.ui.onboarding.OnboardingScreen
import com.visionnav.app.ui.places.SavedPlacesScreen
import com.visionnav.app.ui.search.RoutePreviewScreen
import com.visionnav.app.ui.search.SearchScreen
import com.visionnav.app.ui.settings.SettingsScreen
import com.visionnav.app.ui.settings.SettingsViewModel
import com.visionnav.app.ui.theme.VisionNavTheme
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.serialization.Serializable

/** Type-safe navigation destinations. */
object Routes {
    @Serializable data object Onboarding
    @Serializable data object Home
    @Serializable data object Search
    @Serializable data object RoutePreview
    @Serializable data object ActiveNavigation
    @Serializable data object Crossing
    @Serializable data object SavedPlaces
    @Serializable data object Settings
    @Serializable data object About
    @Serializable data object Debug
}

/**
 * Root of the UI. Applies the theme from settings, shows onboarding on first run and hosts
 * all screens. Session screens (navigation, crossing) follow [AppUiState.sessionMode], so a
 * voice command that starts navigation brings the navigation screen forward automatically.
 *
 * @param debugOverlayContent camera preview + detection boxes, supplied by the integrator.
 */
@Composable
fun VisionNavAppRoot(
    appViewModel: AppViewModel = hiltViewModel(),
    settingsViewModel: SettingsViewModel = hiltViewModel(),
    debugOverlayContent: @Composable BoxScope.() -> Unit = { DebugOverlayPlaceholder() },
) {
    val settings by settingsViewModel.settings.collectAsStateWithLifecycle()
    val state by appViewModel.state.collectAsStateWithLifecycle()

    VisionNavTheme(themeMode = settings?.themeMode ?: ThemeMode.LIGHT) {
        val loaded = settings
        if (loaded == null) {
            FullScreenMessage(stringResource(R.string.loading))
            return@VisionNavTheme
        }
        // Start destination is decided once, from the first loaded settings.
        val start: Any = remember { if (loaded.onboardingComplete) Routes.Home else Routes.Onboarding }
        VisionNavNavHost(
            state = state,
            features = appViewModel.features,
            navigator = appViewModel.navigator,
            settings = loaded,
            startDestination = start,
            onAction = appViewModel::dispatch,
            settingsViewModel = settingsViewModel,
            debugOverlayContent = debugOverlayContent,
        )
        SosDialog(
            sos = state.sos,
            onConfirm = { appViewModel.dispatch(UiAction.ConfirmSos) },
            onCancel = { appViewModel.dispatch(UiAction.CancelSos) },
            onDismissResult = { appViewModel.dispatch(UiAction.DismissSosResult) },
        )
    }
}

@Composable
private fun VisionNavNavHost(
    state: AppUiState,
    features: List<FeatureScreen>,
    navigator: AppNavigator,
    settings: AppSettings,
    startDestination: Any,
    onAction: (UiAction) -> Unit,
    settingsViewModel: SettingsViewModel,
    debugOverlayContent: @Composable BoxScope.() -> Unit,
    navController: NavHostController = rememberNavController(),
) {
    FollowSessionMode(navController, state.sessionMode, enabled = settings.onboardingComplete)
    val back: () -> Unit = { navController.popBackStack() }
    val openRoute: (String) -> Unit = { route -> navController.navigate(route) { launchSingleTop = true } }
    // Skills and the engine can bring a feature screen forward ("read my messages").
    LaunchedEffect(navigator) { navigator.routes.collect(openRoute) }

    // No transitions: respects reduced motion and avoids TalkBack focus jumping mid-animation.
    NavHost(
        navController = navController,
        startDestination = startDestination,
        enterTransition = { EnterTransition.None },
        exitTransition = { ExitTransition.None },
        popEnterTransition = { EnterTransition.None },
        popExitTransition = { ExitTransition.None },
    ) {
        composable<Routes.Onboarding> {
            OnboardingScreen(
                contacts = settings.emergencyContacts,
                onAddContact = settingsViewModel::addContact,
                onRemoveContact = settingsViewModel::removeContact,
                onFinished = {
                    settingsViewModel.setOnboardingComplete(true)
                    // Replayed from Settings: Home is already on the stack, return to it.
                    if (!navController.popBackStack(Routes.Home, inclusive = false)) {
                        navController.navigate(Routes.Home) {
                            popUpTo(Routes.Onboarding) { inclusive = true }
                            launchSingleTop = true
                        }
                    }
                },
            )
        }
        composable<Routes.Home> {
            HomeScreen(
                state = state,
                features = features,
                onOpenFeature = openRoute,
                showDebug = settings.debugOverlay,
                onAction = onAction,
                onOpenSearch = { navController.navigate(Routes.Search) { launchSingleTop = true } },
                onOpenNavigation = { navController.navigate(Routes.ActiveNavigation) { launchSingleTop = true } },
                onOpenCrossing = { navController.navigate(Routes.Crossing) { launchSingleTop = true } },
                onOpenSavedPlaces = { navController.navigate(Routes.SavedPlaces) { launchSingleTop = true } },
                onOpenSettings = { navController.navigate(Routes.Settings) { launchSingleTop = true } },
                onOpenAbout = { navController.navigate(Routes.About) { launchSingleTop = true } },
                onOpenDebug = { navController.navigate(Routes.Debug) { launchSingleTop = true } },
            )
        }
        composable<Routes.Search> {
            SearchScreen(
                state = state,
                onAction = onAction,
                onPlaceChosen = { navController.navigate(Routes.RoutePreview) { launchSingleTop = true } },
                onBack = back,
                units = settings.units,
            )
        }
        composable<Routes.RoutePreview> {
            // StartNavigation switches sessionMode; FollowSessionMode then opens the guidance screen.
            RoutePreviewScreen(state = state, onAction = onAction, onBack = back)
        }
        composable<Routes.ActiveNavigation> {
            ActiveNavigationScreen(
                state = state,
                onAction = onAction,
                onOpenCrossing = { navController.navigate(Routes.Crossing) { launchSingleTop = true } },
                onBack = back,
            )
        }
        composable<Routes.Crossing> {
            CrossingAssistScreen(state = state, onAction = onAction, onBack = back)
        }
        composable<Routes.SavedPlaces> {
            SavedPlacesScreen(
                state = state,
                onAction = onAction,
                onNavigateTo = { saved ->
                    onAction(UiAction.SelectPlace(saved.place))
                    navController.navigate(Routes.RoutePreview) { launchSingleTop = true }
                },
                onBack = back,
            )
        }
        composable<Routes.Settings> {
            SettingsScreen(
                settings = settings,
                onUpdate = settingsViewModel::update,
                onAddContact = settingsViewModel::addContact,
                onRemoveContact = settingsViewModel::removeContact,
                onReplayOnboarding = {
                    navController.navigate(Routes.Onboarding) { launchSingleTop = true }
                },
                onBack = back,
            )
        }
        composable<Routes.About> {
            AboutScreen(versionName = BuildConfig.VERSION_NAME, onBack = back)
        }
        composable<Routes.Debug> {
            DebugScreen(state = state, onAction = onAction, onBack = back, overlayContent = debugOverlayContent)
        }
        // Screens contributed by feature modules (phone, camera tools, app control, safety network).
        features.forEach { feature ->
            composable(feature.route) { feature.Content(onBack = back, navigate = openRoute) }
        }
    }
}

/**
 * Keeps the session screens in sync with the engine:
 *  - NAVIGATING      -> guidance screen in front (Home below it)
 *  - CROSSING_ASSIST -> traffic-check screen in front
 *  - IDLE/EXPLORING  -> session screens are closed, back to Home
 * Only reacts to *changes* of mode, so the user can still browse Home while a route runs.
 */
@Composable
private fun FollowSessionMode(navController: NavHostController, mode: SessionMode, enabled: Boolean) {
    val currentMode by rememberUpdatedState(mode)
    LaunchedEffect(navController, enabled) {
        if (!enabled) return@LaunchedEffect
        var previous: SessionMode? = null
        snapshotFlow { currentMode }.distinctUntilChanged().collect { m ->
            val first = previous == null
            previous = m
            val dest = navController.currentDestination
            when (m) {
                SessionMode.NAVIGATING -> if (dest?.hasRoute<Routes.ActiveNavigation>() != true) {
                    navController.navigate(Routes.ActiveNavigation) {
                        popUpTo(Routes.Home)
                        launchSingleTop = true
                    }
                }
                SessionMode.CROSSING_ASSIST -> if (dest?.hasRoute<Routes.Crossing>() != true) {
                    navController.navigate(Routes.Crossing) { launchSingleTop = true }
                }
                SessionMode.IDLE, SessionMode.EXPLORING -> if (!first &&
                    (dest?.hasRoute<Routes.ActiveNavigation>() == true || dest?.hasRoute<Routes.Crossing>() == true)
                ) {
                    navController.popBackStack(Routes.Home, inclusive = false)
                }
            }
        }
    }
}
