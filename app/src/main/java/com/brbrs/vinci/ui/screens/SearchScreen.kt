package com.brbrs.vinci.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.brbrs.vinci.ui.components.RecentInteractionCard
import com.brbrs.vinci.ui.components.SectionHeader
import com.brbrs.vinci.ui.theme.*
import com.brbrs.vinci.ui.viewmodels.SearchViewModel

/** Search notes/reasons/outcomes across every contact's interactions, not just one at a time. */
@Composable
fun SearchScreen(
    onBack: () -> Unit,
    onEditInteraction: (Long) -> Unit,
    viewModel: SearchViewModel = hiltViewModel(),
) {
    val query by viewModel.query.collectAsState()
    val results by viewModel.results.collectAsState()
    val isDark = LocalIsDark.current
    val isOled = LocalIsOled.current

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                if (isOled) Brush.verticalGradient(listOf(Color.Black, Color.Black))
                else if (isDark) Brush.verticalGradient(0f to NavyDeep, 0.4f to NavyMid, 1f to NavyDeep)
                else Brush.verticalGradient(0f to LightBg, 0.4f to Color(0xFFEDE6F8), 1f to LightSurface2)
            ),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Outlined.ArrowBack, "Back", tint = MaterialTheme.colorScheme.onBackground)
                }
                Text("Search interactions", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onBackground)
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .height(48.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(if (isDark) Color.White.copy(alpha = 0.07f) else LightSurface)
                    .border(1.dp, if (isDark) Color.White.copy(alpha = 0.12f) else LightBorderMed, RoundedCornerShape(24.dp)),
            ) {
                Row(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(10.dp))
                    BasicTextField(
                        value = query,
                        onValueChange = viewModel::onQueryChanged,
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                        modifier = Modifier.weight(1f),
                        decorationBox = { inner ->
                            Box {
                                if (query.isEmpty()) {
                                    Text("Search notes, reasons, outcomes...", color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        style = MaterialTheme.typography.bodyLarge)
                                }
                                inner()
                            }
                        },
                    )
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { viewModel.onQueryChanged("") }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Outlined.Close, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            if (query.isBlank()) {
                Text(
                    "Search across every contact's interaction history at once.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
            } else {
                LazyColumn(contentPadding = PaddingValues(bottom = 32.dp), modifier = Modifier.fillMaxSize()) {
                    item { SectionHeader(text = "Results", badge = results.size, color = CyanPrimary) }
                    items(results, key = { it.id }) { log ->
                        RecentInteractionCard(log = log, isDark = isDark, showDate = true, onClick = { onEditInteraction(log.id) })
                    }
                    if (results.isEmpty()) {
                        item {
                            Text(
                                "No interactions match \"$query\".",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(20.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
