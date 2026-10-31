package com.brbrs.vinci

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.brbrs.vinci.ui.VinciNavGraph
import com.brbrs.vinci.ui.theme.VinciTheme
import com.brbrs.vinci.ui.theme.ThemeRepository
import com.brbrs.vinci.ui.theme.DisplayPreferencesRepository
import com.brbrs.vinci.ui.theme.textSizeMultiplier
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    @Inject lateinit var themeRepository: ThemeRepository
    @Inject lateinit var displayPrefs: DisplayPreferencesRepository

    // Extras from CallReceiver notification tap
    var pendingContactId: Long? = null
    var pendingPhone: String    = ""
    var pendingIsOutgoing: Boolean = true
    var pendingDuration: Int    = 0
    var pendingSimLabel: String = ""
    /** True when the CallReceiver has prompted us to log a VoIP call (WhatsApp etc.): we don't
     *  have a phone number or a matched contact, so we route to the unknown-number log form. */
    var pendingVoipCall: Boolean = false
    /** True when the follow-up reminder notification was tapped: Home scrolls to its
     *  follow-ups section instead of opening a log form. */
    var pendingViewFollowUps: Boolean = false
    /** Calendar-aware nudge: the user tapped a "log this meeting" notification. The log form
     *  opens with the event title as the reason, meeting type, and matched Vinci contacts as
     *  participants (first match becomes primary). */
    var pendingMeetingContactIds: LongArray = longArrayOf()
    var pendingMeetingTitle: String = ""
    var pendingMeetingEnd: Long = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleCallIntent(intent)
        enableEdgeToEdge()
        setContent {
            val themeMode by themeRepository.themeMode.collectAsStateWithLifecycle(initialValue = "dark")
            val systemDark = isSystemInDarkTheme()
            val isDark = when (themeMode) { "light" -> false; "system" -> systemDark; else -> true } // "dark"/"oled"
            val isOled = themeMode == "oled"
            val textSize by displayPrefs.preferences.collectAsStateWithLifecycle(initialValue = com.brbrs.vinci.ui.theme.DisplayPreferences())
            VinciTheme(isDark = isDark, isOled = isOled, textScale = textSizeMultiplier(textSize.textSize), useCustomFont = textSize.useCustomFont) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    VinciNavGraph(
                        pendingContactId = pendingContactId,
                        pendingPhone     = pendingPhone,
                        pendingSimLabel  = pendingSimLabel,
                        pendingVoipCall  = pendingVoipCall,
                        pendingViewFollowUps     = pendingViewFollowUps,
                        pendingMeetingContactIds = pendingMeetingContactIds,
                        pendingMeetingTitle      = pendingMeetingTitle,
                        pendingMeetingEnd        = pendingMeetingEnd,
                        onPendingConsumed = {
                            pendingContactId = null
                            pendingPhone = ""
                            pendingSimLabel = ""
                            pendingVoipCall = false
                            pendingViewFollowUps = false
                            pendingMeetingContactIds = longArrayOf()
                            pendingMeetingTitle = ""
                            pendingMeetingEnd = 0L
                        }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleCallIntent(intent)
    }

    private fun handleCallIntent(intent: Intent?) {
        when (intent?.getStringExtra("action")) {
            "log_call" -> {
                pendingContactId = intent.getLongExtra("contact_id", -1L).takeIf { it >= 0 }
                pendingPhone     = intent.getStringExtra("phone") ?: ""
                pendingIsOutgoing= intent.getBooleanExtra("is_outgoing", true)
                pendingDuration  = intent.getIntExtra("duration", 0)
                pendingSimLabel  = intent.getStringExtra("sim_label") ?: ""
                pendingVoipCall  = false
            }
            "log_voip_call" -> {
                pendingContactId = null
                pendingPhone     = ""
                pendingIsOutgoing= true
                pendingDuration  = 0
                pendingSimLabel  = ""
                pendingVoipCall  = true
            }
            "view_followups" -> {
                pendingViewFollowUps = true
            }
            "log_meeting" -> {
                pendingMeetingContactIds = intent.getLongArrayExtra("contact_ids") ?: longArrayOf()
                pendingMeetingTitle      = intent.getStringExtra("event_title") ?: ""
                pendingMeetingEnd        = intent.getLongExtra("event_end", 0L)
            }
        }
    }
}
