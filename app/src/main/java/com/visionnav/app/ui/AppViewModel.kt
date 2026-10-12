package com.visionnav.app.ui

import androidx.lifecycle.ViewModel
import com.visionnav.app.ui.contract.AppController
import com.visionnav.app.ui.contract.AppUiState
import com.visionnav.app.ui.contract.UiAction
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow

/**
 * Thin bridge from the singleton [AppController] (engine) to the Compose tree. The controller
 * outlives the Activity, so sessions continue across configuration changes.
 */
@HiltViewModel
class AppViewModel @Inject constructor(
    private val controller: AppController,
) : ViewModel() {
    val state: StateFlow<AppUiState> = controller.state

    fun dispatch(action: UiAction) = controller.dispatch(action)
}
