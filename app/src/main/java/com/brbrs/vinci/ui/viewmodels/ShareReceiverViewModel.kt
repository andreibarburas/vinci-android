package com.brbrs.vinci.ui.viewmodels

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.brbrs.vinci.data.AttachmentDao
import com.brbrs.vinci.data.AttachmentEntity
import com.brbrs.vinci.data.CallLogDao
import com.brbrs.vinci.data.CallLogEntity
import com.brbrs.vinci.data.ContactDao
import com.brbrs.vinci.data.ContactEntity
import com.brbrs.vinci.network.WebDavRepository
import com.brbrs.vinci.util.stableLogId
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ShareReceiverUiState(
    val searchQuery: String = "",
    val contacts: List<ContactEntity> = emptyList(),
    val isSaving: Boolean = false,
    val done: Boolean = false,
)

/**
 * Backs the "share to Vinci" flow: pick a contact, and whatever was shared (a link, text, or
 * an image/screenshot) lands as a note or attachment on a freshly logged interaction for them.
 */
@HiltViewModel
class ShareReceiverViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val contactDao: ContactDao,
    private val callLogDao: CallLogDao,
    private val attachmentDao: AttachmentDao,
    private val webDavRepository: WebDavRepository,
) : ViewModel() {

    private val _searchQuery = MutableStateFlow("")
    private val _isSaving = MutableStateFlow(false)
    private val _done = MutableStateFlow(false)

    fun onSearchChanged(q: String) { _searchQuery.value = q }

    val uiState: StateFlow<ShareReceiverUiState> = combine(
        _searchQuery.flatMapLatest { q -> if (q.isBlank()) contactDao.getAllContacts() else contactDao.searchContacts(q) },
        _searchQuery,
        _isSaving,
        _done,
    ) { contacts, query, saving, done ->
        ShareReceiverUiState(searchQuery = query, contacts = contacts, isSaving = saving, done = done)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ShareReceiverUiState())

    /** Saves the shared content against [contact] as a new "Other"-type interaction. */
    fun saveToContact(contact: ContactEntity, sharedText: String?, sharedImageUri: Uri?) {
        viewModelScope.launch {
            _isSaving.value = true
            val now = System.currentTimeMillis()
            val log = CallLogEntity(
                id              = stableLogId(now),
                contactId       = contact.id,
                contactUid      = contact.cardavUid,
                contactName     = contact.displayName,
                phoneNumber     = contact.phoneNumber,
                normalizedPhone = "",
                callTimestamp   = now,
                durationSeconds = 0,
                isOutgoing      = true,
                interactionType = "Other",
                reason          = "Shared",
                outcome         = "",
                notes           = sharedText.orEmpty(),
                tags            = "",
                participants    = "[]",
                followUpDays    = 0,
                isSynced        = false,
            )
            callLogDao.insertLog(log)
            contactDao.updateLastCall(contact.id, now)

            runCatching {
                webDavRepository.uploadCallLog(log)
                callLogDao.markSynced(log.id)
            }

            if (sharedImageUri != null) {
                runCatching {
                    val resolver = context.contentResolver
                    val mimeType = resolver.getType(sharedImageUri) ?: "image/*"
                    val name = "shared_${now}.${if (mimeType.contains("png")) "png" else "jpg"}"
                    val bytes = resolver.openInputStream(sharedImageUri)?.use { it.readBytes() }
                        ?: throw java.io.IOException("Could not read shared image")
                    val remotePath = webDavRepository.uploadAttachment(log, name, mimeType, bytes)
                        ?: throw java.io.IOException("Upload failed")
                    attachmentDao.insert(
                        AttachmentEntity(
                            logId = log.id, fileName = name, mimeType = mimeType,
                            sizeBytes = bytes.size.toLong(), nextcloudPath = remotePath,
                            localPath = "", cachedLocally = false, isSynced = true,
                        )
                    )
                }
            }

            _isSaving.value = false
            _done.value = true
        }
    }
}
