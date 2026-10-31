package com.brbrs.vinci.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.brbrs.vinci.data.CallLogDao
import com.brbrs.vinci.data.ContactDao
import com.brbrs.vinci.data.ContactEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DuplicateGroup(
    val name: String,
    val contacts: List<ContactEntity>,
)

data class DuplicateMergeUiState(
    val isLoading: Boolean = true,
    val groups: List<DuplicateGroup> = emptyList(),
    val lastMergeMessage: String? = null,
)

/**
 * Spots probable duplicate contacts -- same display name, but a different CardDAV uid each,
 * meaning their Vinci interaction history is split across two contact rows (exactly the
 * situation a contact getting recreated on the CardDAV server produces). Offers to merge
 * one's interactions onto the other and remove the duplicate contact row.
 */
@HiltViewModel
class DuplicateMergeViewModel @Inject constructor(
    private val contactDao: ContactDao,
    private val callLogDao: CallLogDao,
) : ViewModel() {

    private val _uiState = MutableStateFlow(DuplicateMergeUiState())
    val uiState: StateFlow<DuplicateMergeUiState> = _uiState.asStateFlow()

    init { scan() }

    fun scan() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val all = contactDao.getAllContactsSuspend()
            val groups = all
                .groupBy { it.displayName.trim().lowercase() }
                .values
                .filter { group -> group.size > 1 && group.map { it.cardavUid }.distinct().size > 1 }
                .map { group -> DuplicateGroup(name = group.first().displayName, contacts = group.sortedByDescending { it.lastCallTimestamp }) }
            _uiState.update { it.copy(isLoading = false, groups = groups) }
        }
    }

    /** Moves every interaction from [merge] onto [keep], then deletes the now-empty [merge] row. */
    fun merge(keep: ContactEntity, merge: ContactEntity) {
        viewModelScope.launch {
            callLogDao.reassignLogs(mergeId = merge.id, keepId = keep.id)
            contactDao.deleteContact(merge.id)
            _uiState.update { it.copy(lastMergeMessage = "Merged ${merge.displayName}'s history into the kept contact.") }
            scan()
        }
    }

    fun dismissMessage() { _uiState.update { it.copy(lastMergeMessage = null) } }
}
