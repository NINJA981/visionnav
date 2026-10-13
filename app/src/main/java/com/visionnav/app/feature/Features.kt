package com.visionnav.app.feature

import androidx.compose.runtime.Composable
import com.visionnav.platform.skill.AuraSkill
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.Multibinds
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A screen contributed by a feature (phone, camera tools, app control, safety network).
 * Register with `@Binds @IntoSet FeatureScreen`; the navigation root adds a destination for
 * [route] and Home lists it when [homeLabel] is set. Features never edit the nav graph or Home.
 */
interface FeatureScreen {
    /** Unique string route, e.g. "phone/calls". */
    val route: String

    /** Label on Home's list (plain, sentence case), or null to keep it off Home. */
    val homeLabel: String? get() = null

    /** TalkBack hint for the Home entry, e.g. "make calls and hear missed calls". */
    val homeHint: String? get() = null

    /** Position on Home's list: lower first. */
    val order: Int get() = 500

    @Composable
    fun Content(onBack: () -> Unit, navigate: (String) -> Unit)
}

/** Lets non-UI code (the engine, skills) bring a feature screen to the front. */
@Singleton
class AppNavigator @Inject constructor() {
    private val _routes = MutableSharedFlow<String>(extraBufferCapacity = 4, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val routes: SharedFlow<String> = _routes.asSharedFlow()
    fun open(route: String) {
        _routes.tryEmit(route)
    }
}

/** Declares the (possibly empty) sets so the app builds before any feature registers. */
@Module
@InstallIn(SingletonComponent::class)
abstract class FeatureMultibindings {
    @Multibinds abstract fun featureScreens(): Set<FeatureScreen>
    @Multibinds abstract fun skills(): Set<AuraSkill>
}
