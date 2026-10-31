package com.brbrs.vinci.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.brbrs.vinci.auth.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ModePickerViewModel @Inject constructor(
    private val authRepository: AuthRepository,
) : ViewModel() {

    private val _navigateTo = MutableStateFlow<String?>(null)
    /** "nextcloud" → go to Login; "local" → go to Permissions. null = idle. */
    val navigateTo: StateFlow<String?> = _navigateTo.asStateFlow()

    fun chooseNextcloud() {
        viewModelScope.launch {
            authRepository.setStorageMode("nextcloud")
            _navigateTo.value = "nextcloud"
        }
    }

    fun chooseLocal() {
        viewModelScope.launch {
            authRepository.setStorageMode("local")
            _navigateTo.value = "local"
        }
    }

    fun consumeNavigation() { _navigateTo.value = null }
}
