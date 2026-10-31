package com.brbrs.vinci.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.brbrs.vinci.ui.theme.*
import com.brbrs.vinci.ui.viewmodels.OnboardingViewModel

/**
 * First-run welcome screen. Briefly explains what Vinci is, how it stores data
 * (locally + on the user's own Nextcloud), and what's coming next (permissions,
 * Nextcloud login). Shown once -- if onboarding was already completed, this
 * screen immediately hands off to [onContinue] without rendering anything visible.
 */
@Composable
fun OnboardingScreen(
    onContinue: () -> Unit,   // returning user: skip straight to AppLock
    onGetStarted: () -> Unit, // new user: go to mode picker
    viewModel: OnboardingViewModel = hiltViewModel(),
) {
    val isDark = LocalIsDark.current
    val done by viewModel.onboardingDone.collectAsState()

    // Set the instant "Get started" is tapped, so the markDone() write landing moments later
    // doesn't also trigger the returning-user effect below and race its own onGetStarted() nav.
    var navigatedFromButton by rememberSaveable { mutableStateOf(false) }

    // Returning / upgrading user who already completed onboarding — skip to AppLock.
    LaunchedEffect(done) {
        if (done == true && !navigatedFromButton) {
            viewModel.ensureModePicked()
            onContinue()
        }
    }

    if (done != false) return // null (loading) or true (skipping) -- render nothing

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                if (isDark) Brush.verticalGradient(listOf(NavyDeep, NavyMid))
                else Brush.verticalGradient(listOf(LightBg, LightSurface2))
            ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(48.dp))

            Box(
                modifier = Modifier
                    .size(84.dp)
                    .glassCardPrimary(cornerRadius = 24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.Contacts, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(42.dp))
            }

            Spacer(Modifier.height(24.dp))

            Text("Welcome to Vinci", style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground, fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center)

            Spacer(Modifier.height(8.dp))

            Text(
                "A personal CRM that helps you stay in touch with the people who matter -- " +
                "log calls and conversations, set follow-up reminders, and keep notes on " +
                "your contacts.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(32.dp))

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OnboardingPoint(
                    icon = Icons.Outlined.CloudOff,
                    title = "Your data, your choice",
                    body = "Keep everything on this device, or sync to your own Nextcloud " +
                        "server. Nothing ever goes to Vinci's or anyone else's servers.",
                    isDark = isDark,
                )
                OnboardingPoint(
                    icon = Icons.Outlined.Lock,
                    title = "Open source & private",
                    body = "Vinci is free and open source. Optional app lock and biometric " +
                        "unlock keep your contact history protected on-device.",
                    isDark = isDark,
                )
                OnboardingPoint(
                    icon = Icons.Outlined.AdminPanelSettings,
                    title = "Permissions, explained",
                    body = "Next, choose how to store your data (local or Nextcloud), then " +
                        "grant a few permissions -- we'll explain exactly why each one is needed.",
                    isDark = isDark,
                )
            }

            Spacer(Modifier.height(40.dp))

            Button(
                onClick = { navigatedFromButton = true; viewModel.markDone(); onGetStarted() },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
            ) {
                Text("Get started", style = MaterialTheme.typography.titleMedium)
            }

            Spacer(Modifier.height(40.dp))
        }
    }
}

@Composable
private fun OnboardingPoint(icon: ImageVector, title: String, body: String, isDark: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .glassCard(cornerRadius = 16.dp, tint = if (isDark) androidx.compose.ui.graphics.Color.White else androidx.compose.ui.graphics.Color.Black)
            .padding(14.dp),
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(2.dp))
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
