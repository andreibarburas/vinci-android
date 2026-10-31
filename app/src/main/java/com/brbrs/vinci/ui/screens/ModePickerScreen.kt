package com.brbrs.vinci.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.brbrs.vinci.ui.theme.*
import com.brbrs.vinci.ui.viewmodels.ModePickerViewModel

/**
 * First-launch mode selection screen shown after the welcome / onboarding flow.
 * The user decides here whether they want to connect a Nextcloud account or run
 * in fully local (no-account) mode. The choice is stored in AuthRepository and
 * gates the rest of the setup flow and the ongoing sync logic.
 */
@Composable
fun ModePickerScreen(
    onNextcloud: () -> Unit,
    onLocal: () -> Unit,
    viewModel: ModePickerViewModel = hiltViewModel(),
) {
    val isDark = LocalIsDark.current
    val navigateTo by viewModel.navigateTo.collectAsState()

    LaunchedEffect(navigateTo) {
        when (navigateTo) {
            "nextcloud" -> { viewModel.consumeNavigation(); onNextcloud() }
            "local"     -> { viewModel.consumeNavigation(); onLocal() }
        }
    }

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
                .navigationBarsPadding()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {

            Box(
                modifier = Modifier
                    .size(80.dp)
                    .glassCardPrimary(cornerRadius = 24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Outlined.Storage,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(40.dp),
                )
            }

            Spacer(Modifier.height(24.dp))

            Text(
                "Where should Vinci keep your data?",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(8.dp))

            Text(
                "You can change this later in Settings.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(36.dp))

            // ── Nextcloud option ─────────────────────────────────────────────
            ModeCard(
                icon = Icons.Outlined.Cloud,
                title = "Connect to Nextcloud",
                subtitle = "Sync your interaction history to your own Nextcloud server. " +
                    "Works across devices and keeps a backup in the cloud you control.",
                badges = listOf("Sync across devices", "Cloud backup", "Self-hosted"),
                isDark = isDark,
                onClick = viewModel::chooseNextcloud,
            )

            Spacer(Modifier.height(16.dp))

            // ── Local option ─────────────────────────────────────────────────
            ModeCard(
                icon = Icons.Outlined.PhoneAndroid,
                title = "Use locally",
                subtitle = "No account needed. Everything stays on this phone. Use the " +
                    "built-in export to back up and move your data when you change phones.",
                badges = listOf("No account needed", "On-device only", "Exportable"),
                isDark = isDark,
                onClick = viewModel::chooseLocal,
            )
        }
    }
}

@Composable
private fun ModeCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    badges: List<String>,
    isDark: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = if (isDark)
            MaterialTheme.colorScheme.surface.copy(alpha = 0.55f)
        else
            MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        onClick = onClick,
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .background(
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                            shape = RoundedCornerShape(12.dp),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                }
                Spacer(Modifier.width(14.dp))
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }

            Spacer(Modifier.height(10.dp))

            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(12.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                badges.forEach { badge ->
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                    ) {
                        Text(
                            badge,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                }
            }
        }
    }
}
