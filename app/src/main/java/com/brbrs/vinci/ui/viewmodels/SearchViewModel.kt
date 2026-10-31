package com.brbrs.vinci.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.brbrs.vinci.data.CallLogDao
import com.brbrs.vinci.data.CallLogEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val callLogDao: CallLogDao,
) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    fun onQueryChanged(q: String) { _query.value = q }

    val results: StateFlow<List<CallLogEntity>> = _query
        .debounce(150)
        .flatMapLatest { q ->
            if (q.isBlank()) flowOf(emptyList()) else callLogDao.searchAllInteractions(q)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
}
