package com.visionnav.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.visionnav.app.settings.AppSettings
import com.visionnav.app.settings.EmergencyContact
import com.visionnav.app.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Exposes [AppSettings] (null until first load) and persists edits. */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repository: SettingsRepository,
) : ViewModel() {

    val settings: StateFlow<AppSettings?> = repository.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun update(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch { repository.update(transform) }
    }

    fun addContact(contact: EmergencyContact) {
        viewModelScope.launch { repository.addEmergencyContact(contact) }
    }

    fun removeContact(contact: EmergencyContact) {
        viewModelScope.launch { repository.removeEmergencyContact(contact) }
    }

    fun setOnboardingComplete(complete: Boolean) {
        viewModelScope.launch { repository.setOnboardingComplete(complete) }
    }
}
