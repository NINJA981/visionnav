package com.visionnav.platform.di

import com.visionnav.core.announce.EarconPlayer
import com.visionnav.core.announce.HapticPlayer
import com.visionnav.core.announce.Speaker
import com.visionnav.core.sensing.ActivitySource
import com.visionnav.core.sensing.LocationSource
import com.visionnav.core.sensing.OrientationSource
import com.visionnav.platform.activity.StepActivitySource
import com.visionnav.platform.audio.AndroidTtsSpeaker
import com.visionnav.platform.audio.ToneEarconPlayer
import com.visionnav.platform.haptics.VibratorHapticPlayer
import com.visionnav.platform.location.FusedLocationSource
import com.visionnav.platform.location.GmsLocationUpdatesClient
import com.visionnav.platform.location.LocationUpdatesClient
import com.visionnav.platform.orientation.SensorOrientationSource
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Binds the core device-service contracts to their Android implementations. All bindings are
 * application-wide singletons, so every consumer shares one location subscription, one sensor
 * registration, one TTS engine, etc.
 *
 * Concrete classes not bound to an interface ([com.visionnav.platform.speech.SpeechInput],
 * [com.visionnav.platform.sos.SosMessenger], [com.visionnav.platform.power.PowerGovernor],
 * [com.visionnav.platform.common.ElapsedRealtimeTimeSource]) are injectable directly via their
 * `@Inject` constructors.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class PlatformModule {

    @Binds
    @Singleton
    abstract fun bindLocationUpdatesClient(impl: GmsLocationUpdatesClient): LocationUpdatesClient

    @Binds
    @Singleton
    abstract fun bindLocationSource(impl: FusedLocationSource): LocationSource

    @Binds
    @Singleton
    abstract fun bindOrientationSource(impl: SensorOrientationSource): OrientationSource

    @Binds
    @Singleton
    abstract fun bindActivitySource(impl: StepActivitySource): ActivitySource

    @Binds
    @Singleton
    abstract fun bindSpeaker(impl: AndroidTtsSpeaker): Speaker

    @Binds
    @Singleton
    abstract fun bindEarconPlayer(impl: ToneEarconPlayer): EarconPlayer

    @Binds
    @Singleton
    abstract fun bindHapticPlayer(impl: VibratorHapticPlayer): HapticPlayer
}
