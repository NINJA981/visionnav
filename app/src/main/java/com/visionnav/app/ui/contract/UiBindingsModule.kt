package com.visionnav.app.ui.contract

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Binds the UI contract. Integration: replace [FakeAppController] with the real engine
 * orchestrator (which must be a @Singleton outliving any single Activity).
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class UiBindingsModule {

    @Binds
    @Singleton
    abstract fun bindAppController(impl: com.visionnav.app.integration.EngineAppController): AppController
}
