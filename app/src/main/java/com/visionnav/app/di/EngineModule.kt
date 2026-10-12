package com.visionnav.app.di

import android.content.Context
import com.visionnav.app.engine.FileSavedPlacesStore
import com.visionnav.app.engine.GatedHapticPlayer
import com.visionnav.app.engine.SavedPlacesStore
import com.visionnav.app.engine.SessionLog
import com.visionnav.core.announce.AnnouncementPolicy
import com.visionnav.core.announce.AudioOutcome
import com.visionnav.core.announce.AudioQueueManager
import com.visionnav.core.announce.EarconPlayer
import com.visionnav.core.announce.SafetyPhraseGuard
import com.visionnav.core.announce.Speaker
import com.visionnav.core.common.TimeSource
import com.visionnav.core.crossing.RoadMetadataService
import com.visionnav.core.navigation.GeocodingService
import com.visionnav.core.navigation.RoutingService
import com.visionnav.mapdata.MapDataServices
import com.visionnav.platform.common.ElapsedRealtimeTimeSource
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class EngineBindings {
    /** One clock for the whole app: elapsedRealtime keeps counting in deep sleep and matches camera/sensor timestamps. */
    @Binds @Singleton abstract fun timeSource(impl: ElapsedRealtimeTimeSource): TimeSource

    @Binds @Singleton abstract fun savedPlaces(impl: FileSavedPlacesStore): SavedPlacesStore
}

@Module
@InstallIn(SingletonComponent::class)
object EngineModule {

    @Provides @Singleton
    fun mapData(@ApplicationContext context: Context, time: TimeSource): MapDataServices =
        MapDataServices.create(overpassCacheDir = File(context.cacheDir, "overpass"), time = time)

    @Provides fun routing(m: MapDataServices): RoutingService = m.routing
    @Provides fun geocoding(m: MapDataServices): GeocodingService = m.geocoding
    @Provides fun roadMetadata(m: MapDataServices): RoadMetadataService = m.roadMetadata

    /** The single speech pipeline. Every utterance in the app goes through this queue and its phrase guard. */
    @Provides @Singleton
    fun audioQueue(
        speaker: Speaker,
        earcons: EarconPlayer,
        haptics: GatedHapticPlayer,
        time: TimeSource,
        log: SessionLog,
    ): AudioQueueManager {
        val guard = SafetyPhraseGuard { log.log("BLOCKED", it) }
        return AudioQueueManager(
            speaker = speaker,
            earcons = earcons,
            haptics = haptics,
            time = time,
            guard = guard,
            policy = AnnouncementPolicy(time),
            logSink = { entry -> if (entry.outcome != AudioOutcome.STARTED && entry.outcome != AudioOutcome.COMPLETED) log.log("AUDIO", "${entry.outcome}: ${entry.announcement.text}") },
        )
    }
}
