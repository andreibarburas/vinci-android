package com.brbrs.vinci.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.brbrs.vinci.auth.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val authRepository: AuthRepository,
) : ViewModel() {

    /** null = not yet known, true/false once read from DataStore. */
    val onboardingDone: StateFlow<Boolean?> = authRepository.onboardingDone
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /**
     * Whether the user has previously chosen a storage mode.
     * Upgrading users from pre-2.3.0 who are already logged in to Nextcloud
     * will have a session but no storageMode key — we auto-assign "nextcloud"
     * for them so they don't see the mode picker again.
     */
    val modePicked: StateFlow<Boolean?> = authRepository.storageMode
        .map { mode ->
            when {
                mode != null -> true          // already picked
                else -> false                 // never picked → show mode picker
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun markDone() {
        viewModelScope.launch { authRepository.setOnboardingDone() }
    }

    /**
     * Called by AppLockScreen / the nav graph when we detect a pre-2.3.0 user
     * who already has a Nextcloud session but no storageMode set yet.
     * Silently assigns "nextcloud" so they skip the mode picker.
     */
    fun ensureModePicked() {
        viewModelScope.launch {
            if (authRepository.storageMode.first() == null) {
                // Existing user: if they have a Nextcloud session, default to nextcloud;
                // otherwise default to local.
                val hasSession = authRepository.session.first() != null
                authRepository.setStorageMode(if (hasSession) "nextcloud" else "local")
            }
        }
    }
}
