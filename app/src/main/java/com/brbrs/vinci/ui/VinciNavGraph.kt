@file:OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class)

package com.brbrs.vinci.ui

import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.runtime.*
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.brbrs.vinci.ui.screens.*
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Lets the avatar in a contact list morph into the one on their detail page instead of just
 * cutting between screens. Exposed as CompositionLocals (rather than threading two extra
 * parameters through every list/card composable) since ContactAvatar is used from several
 * unrelated screens -- most callers never set a key and get ordinary behavior.
 */
val LocalSharedTransitionScope = compositionLocalOf<SharedTransitionScope?> { null }
val LocalNavAnimatedVisibilityScope = compositionLocalOf<AnimatedContentScope?> { null }

sealed class Screen(val route: String) {
    object Onboarding      : Screen("onboarding")
    object ModePicker      : Screen("mode_picker")
    object AppLock         : Screen("app_lock")
    object Login           : Screen("login")
    object FolderPicker    : Screen("folder_picker")
    object Restore         : Screen("restore")
    object Permissions     : Screen("permissions")
    object Main            : Screen("main")
    object Settings        : Screen("settings")
    object Search          : Screen("search")
    object DuplicateMerge  : Screen("duplicate_merge")
    object ContactDetail   : Screen("contact_detail/{contactId}") {
        fun route(contactId: Long) = "contact_detail/$contactId"
    }
    object CallLog : Screen("call_log/{contactId}?phone={phone}&prefillTimestamp={prefillTimestamp}&prefillType={prefillType}&simLabel={simLabel}&prefillReason={prefillReason}&prefillParticipantIds={prefillParticipantIds}") {
        fun route(
            contactId: Long,
            phone: String = "",
            prefillTimestamp: Long = 0L,
            prefillType: String = "Call",
            simLabel: String = "",
            prefillReason: String = "",
            prefillParticipantIds: LongArray = longArrayOf(),
        ) = "call_log/$contactId" +
            "?phone=${URLEncoder.encode(phone, "UTF-8")}" +
            "&prefillTimestamp=$prefillTimestamp" +
            "&prefillType=${URLEncoder.encode(prefillType, "UTF-8")}" +
            "&simLabel=${URLEncoder.encode(simLabel, "UTF-8")}" +
            "&prefillReason=${URLEncoder.encode(prefillReason, "UTF-8")}" +
            "&prefillParticipantIds=${prefillParticipantIds.joinToString(",")}"
    }
    object EditInteraction : Screen("edit_interaction/{logId}") {
        fun route(logId: Long) = "edit_interaction/$logId"
    }
    object EditContact : Screen("edit_contact/{contactId}") {
        fun route(contactId: Long) = "edit_contact/$contactId"
    }
    object Company : Screen("company/{companyName}") {
        fun route(name: String) = "company/${URLEncoder.encode(name, "UTF-8")}"
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun VinciNavGraph(
    pendingContactId: Long? = null,
    pendingPhone: String = "",
    pendingSimLabel: String = "",
    pendingVoipCall: Boolean = false,
    pendingViewFollowUps: Boolean = false,
    pendingMeetingContactIds: LongArray = longArrayOf(),
    pendingMeetingTitle: String = "",
    pendingMeetingEnd: Long = 0L,
    onPendingConsumed: () -> Unit = {},
) {
    val navController = rememberNavController()

    // When a call notification is tapped, navigate to the log screen once the nav graph is ready.
    // contactId == -1 with a phone number means "unknown number" -- CallLogScreen handles
    // this as a no-contact interaction log. VoIP calls also route there, with no number, so the
    // user names the contact and picks the type on the full form.
    LaunchedEffect(pendingContactId, pendingPhone, pendingSimLabel, pendingVoipCall,
        pendingViewFollowUps, pendingMeetingContactIds.size, pendingMeetingTitle, pendingMeetingEnd) {
        val contactId = pendingContactId ?: -1L
        if (pendingViewFollowUps) {
            // Home surfaces the follow-ups section on its own; just land there and let the
            // existing list draw it. If the back stack is deep, pop to the root first.
            navController.popBackStack(Screen.Main.route, inclusive = false)
            onPendingConsumed()
        } else if (pendingMeetingContactIds.isNotEmpty() || pendingMeetingTitle.isNotEmpty()) {
            val primary = pendingMeetingContactIds.firstOrNull() ?: -1L
            val extras  = if (pendingMeetingContactIds.size > 1) pendingMeetingContactIds.drop(1).toLongArray() else longArrayOf()
            navController.navigate(
                Screen.CallLog.route(
                    contactId = primary,
                    phone = "",
                    prefillTimestamp = pendingMeetingEnd,
                    prefillType = "Meeting",
                    simLabel = "",
                    prefillReason = pendingMeetingTitle,
                    prefillParticipantIds = extras,
                )
            )
            onPendingConsumed()
        } else if (pendingVoipCall) {
            navController.navigate(Screen.CallLog.route(-1L, "", 0L, "Call", ""))
            onPendingConsumed()
        } else if (pendingPhone.isNotBlank() || contactId >= 0) {
            navController.navigate(Screen.CallLog.route(contactId, pendingPhone, 0L, "Call", pendingSimLabel))
            onPendingConsumed()
        }
    }

    SharedTransitionLayout {
    CompositionLocalProvider(LocalSharedTransitionScope provides this) {
    NavHost(navController = navController, startDestination = Screen.Onboarding.route) {

        composable(Screen.Onboarding.route) {
            OnboardingScreen(
                // Returning / upgrading user — already has onboarding done, go to AppLock as before
                onContinue   = { navController.navigate(Screen.AppLock.route) { popUpTo(Screen.Onboarding.route) { inclusive = true } } },
                // New user just tapped "Get started" — route through mode picker
                onGetStarted = { navController.navigate(Screen.ModePicker.route) { popUpTo(Screen.Onboarding.route) { inclusive = true } } },
            )
        }

        composable(Screen.ModePicker.route) {
            ModePickerScreen(
                onNextcloud = { navController.navigate(Screen.AppLock.route) { popUpTo(Screen.ModePicker.route) { inclusive = true } } },
                onLocal     = { navController.navigate(Screen.Permissions.route) { popUpTo(Screen.ModePicker.route) { inclusive = true } } },
            )
        }

        composable(Screen.AppLock.route) {
            AppLockScreen(
                onUnlocked       = { navController.navigate(Screen.Main.route) { popUpTo(Screen.AppLock.route) { inclusive = true } } },
                onNoLock         = { navController.navigate(Screen.Login.route) { popUpTo(Screen.AppLock.route) { inclusive = true } } },
                onLoggedInNoLock = { navController.navigate(Screen.Main.route) { popUpTo(Screen.AppLock.route) { inclusive = true } } },
            )
        }

        composable(Screen.Login.route) {
            LoginScreen(
                onLoginSuccess = { navController.navigate(Screen.FolderPicker.route) { popUpTo(Screen.Login.route) { inclusive = true } } }
            )
        }

        // After folder picker, show restore screen so returning users get their data back
        composable(Screen.FolderPicker.route) {
            FolderPickerScreen(
                onFolderConfirmed = { navController.navigate(Screen.Restore.route) { popUpTo(Screen.FolderPicker.route) { inclusive = true } } }
            )
        }

        // Restore screen — user can restore from Nextcloud or start fresh
        composable(Screen.Restore.route) {
            RestoreScreen(
                onDone = { navController.navigate(Screen.Permissions.route) { popUpTo(Screen.Restore.route) { inclusive = true } } }
            )
        }

        composable(Screen.Permissions.route) {
            PermissionsScreen(
                onAllGranted = { navController.navigate(Screen.Main.route) { popUpTo(Screen.Permissions.route) { inclusive = true } } }
            )
        }

        composable(Screen.Main.route) {
            CompositionLocalProvider(LocalNavAnimatedVisibilityScope provides this) {
                MainScaffold(
                    onContactClick    = { navController.navigate(Screen.ContactDetail.route(it)) },
                    onLogCall         = { contactId, phone -> navController.navigate(Screen.CallLog.route(contactId, phone)) },
                    onEditInteraction = { logId -> navController.navigate(Screen.EditInteraction.route(logId)) },
                    onSettings        = { navController.navigate(Screen.Settings.route) },
                    onAddContact      = { navController.navigate(Screen.EditContact.route(-1L)) },
                    onSearch          = { navController.navigate(Screen.Search.route) },
                    onOpenCompany     = { name -> navController.navigate(Screen.Company.route(name)) },
                )
            }
        }

        composable(Screen.Search.route) {
            SearchScreen(
                onBack            = { navController.popBackStack() },
                onEditInteraction = { logId -> navController.navigate(Screen.EditInteraction.route(logId)) },
            )
        }

        composable(
            route = Screen.ContactDetail.route,
            arguments = listOf(navArgument("contactId") { type = NavType.LongType }),
        ) { backStack ->
            val contactId = backStack.arguments?.getLong("contactId") ?: return@composable
            CompositionLocalProvider(LocalNavAnimatedVisibilityScope provides this) {
                ContactDetailScreen(
                    contactId        = contactId,
                    onBack           = { navController.popBackStack() },
                    onLogInteraction = { id, phone, ts, type -> navController.navigate(Screen.CallLog.route(id, phone, ts, type)) },
                    onEditInteraction= { logId -> navController.navigate(Screen.EditInteraction.route(logId)) },
                    onEditContact    = { id -> navController.navigate(Screen.EditContact.route(id)) },
                    onOpenCompany    = { name -> navController.navigate(Screen.Company.route(name)) },
                )
            }
        }

        composable(
            route = Screen.Company.route,
            arguments = listOf(navArgument("companyName") { type = NavType.StringType }),
        ) {
            CompanyScreen(
                onBack            = { navController.popBackStack() },
                onLogInteraction  = { id, phone -> navController.navigate(Screen.CallLog.route(id, phone)) },
                onContactClick    = { id -> navController.navigate(Screen.ContactDetail.route(id)) },
                onEditInteraction = { logId -> navController.navigate(Screen.EditInteraction.route(logId)) },
            )
        }

        composable(
            route = Screen.CallLog.route,
            arguments = listOf(
                navArgument("contactId")             { type = NavType.LongType },
                navArgument("phone")                 { type = NavType.StringType; defaultValue = "" },
                navArgument("prefillTimestamp")      { type = NavType.LongType;   defaultValue = 0L },
                navArgument("prefillType")           { type = NavType.StringType; defaultValue = "Call" },
                navArgument("simLabel")              { type = NavType.StringType; defaultValue = "" },
                navArgument("prefillReason")         { type = NavType.StringType; defaultValue = "" },
                navArgument("prefillParticipantIds") { type = NavType.StringType; defaultValue = "" },
            ),
        ) { backStack ->
            val contactId        = backStack.arguments?.getLong("contactId") ?: return@composable
            val phone            = URLDecoder.decode(backStack.arguments?.getString("phone") ?: "", "UTF-8")
            val prefillTimestamp = backStack.arguments?.getLong("prefillTimestamp") ?: 0L
            val prefillType      = URLDecoder.decode(backStack.arguments?.getString("prefillType") ?: "Call", "UTF-8")
            CallLogScreen(
                contactId        = contactId,
                phone            = phone,
                prefillTimestamp = prefillTimestamp,
                prefillType      = prefillType,
                onSaved          = { navController.popBackStack() },
                onBack           = { navController.popBackStack() },
            )
        }

        composable(
            route = Screen.EditInteraction.route,
            arguments = listOf(navArgument("logId") { type = NavType.LongType }),
        ) { backStack ->
            val logId = backStack.arguments?.getLong("logId") ?: return@composable
            EditInteractionScreen(
                logId     = logId,
                onSaved   = { navController.popBackStack() },
                onDeleted = { navController.popBackStack() },
                onBack    = { navController.popBackStack() },
            )
        }

        composable(
            route = Screen.EditContact.route,
            arguments = listOf(navArgument("contactId") { type = NavType.LongType }),
        ) { backStack ->
            val contactId = backStack.arguments?.getLong("contactId") ?: return@composable
            EditContactScreen(
                contactId = contactId,
                onSaved   = { navController.popBackStack() },
                onBack    = { navController.popBackStack() },
            )
        }

        composable(Screen.Settings.route) {
            SettingsScreen(
                onBack             = { navController.popBackStack() },
                onLoggedOut        = { navController.navigate(Screen.Login.route) { popUpTo(0) { inclusive = true } } },
                // Local-mode user chose to connect Nextcloud: navigate to Login and clear the back stack
                // so pressing back from Login doesn't return to Settings in an inconsistent state.
                onConnectNextcloud = { navController.navigate(Screen.Login.route) { popUpTo(0) { inclusive = true } } },
                onDuplicates       = { navController.navigate(Screen.DuplicateMerge.route) },
            )
        }

        composable(Screen.DuplicateMerge.route) {
            DuplicateMergeScreen(onBack = { navController.popBackStack() })
        }
    }
    }
    }
}
