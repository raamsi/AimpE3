package com.example.download.work

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.work.WorkInfo

enum class DownloadFilterTab {
    ALL, ACTIVE, COMPLETED
}

@Composable
fun SnaptubeDownloadManagerComponent(
    downloadManager: WorkDownloadManager,
    onPlayTrack: (filePath: String, title: String) -> Unit,
    modifier: Modifier = Modifier
) {
    val downloads by downloadManager.observeDownloads().collectAsState(initial = emptyList())
    var currentFilter by remember { mutableStateOf(DownloadFilterTab.ALL) }

    val activeCount = downloads.count { it.isRunning || it.isPending }
    val completedCount = downloads.count { it.isSuccess }

    val filteredList = when (currentFilter) {
        DownloadFilterTab.ALL -> downloads
        DownloadFilterTab.ACTIVE -> downloads.filter { it.isRunning || it.isPending }
        DownloadFilterTab.COMPLETED -> downloads.filter { it.isSuccess }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF121212))
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .testTag("download_manager_component")
    ) {
        // Summary & Speed Dashboard Card
        SnaptubeSpeedDashboard(
            activeCount = activeCount,
            completedCount = completedCount,
            onClearCompleted = { downloadManager.clearCompletedDownloads() }
        )

        Spacer(modifier = Modifier.height(10.dp))

        // Filter Chips Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SnaptubeFilterChip(
                label = "Tous (${downloads.size})",
                isSelected = currentFilter == DownloadFilterTab.ALL,
                onClick = { currentFilter = DownloadFilterTab.ALL },
                modifier = Modifier.testTag("filter_all")
            )
            SnaptubeFilterChip(
                label = "En cours ($activeCount)",
                isSelected = currentFilter == DownloadFilterTab.ACTIVE,
                onClick = { currentFilter = DownloadFilterTab.ACTIVE },
                modifier = Modifier.testTag("filter_active")
            )
            SnaptubeFilterChip(
                label = "Terminés ($completedCount)",
                isSelected = currentFilter == DownloadFilterTab.COMPLETED,
                onClick = { currentFilter = DownloadFilterTab.COMPLETED },
                modifier = Modifier.testTag("filter_completed")
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Content: Empty State vs Downloads List
        if (filteredList.isEmpty()) {
            SnaptubeEmptyDownloadsState(filter = currentFilter)
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("downloads_lazy_column"),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(filteredList, key = { it.id.toString() }) { item ->
                    SnaptubeDownloadItemCard(
                        item = item,
                        onCancel = { downloadManager.cancelDownload(item.id) },
                        onRetry = { downloadManager.retryDownload(item) },
                        onDelete = { downloadManager.deleteDownload(item) },
                        onPlay = { path, title -> onPlayTrack(path, title) }
                    )
                }
            }
        }
    }
}

@Composable
fun SnaptubeSpeedDashboard(
    activeCount: Int,
    completedCount: Int,
    onClearCompleted: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "⚡ Gestionnaire WorkManager",
                        color = Color(0xFFFFCA28),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Text(
                    text = if (activeCount > 0) "$activeCount téléchargement(s) actif(s)" else "$completedCount fichier(s) prêt(s)",
                    color = Color(0xFFAAAAAA),
                    fontSize = 11.sp
                )
            }

            if (completedCount > 0) {
                Button(
                    onClick = onClearCompleted,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2A2A2A)),
                    shape = RoundedCornerShape(20.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    modifier = Modifier.height(30.dp)
                ) {
                    Text("Nettoyer", color = Color(0xFFCCCCCC), fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
fun SnaptubeFilterChip(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val bgColor = if (isSelected) Color(0xFFFFCA28) else Color(0xFF252525)
    val textColor = if (isSelected) Color.Black else Color(0xFF888888)

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(bgColor)
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = textColor,
            fontSize = 12.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
        )
    }
}

@Composable
fun SnaptubeDownloadItemCard(
    item: WorkDownloadItem,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onDelete: () -> Unit,
    onPlay: (filePath: String, title: String) -> Unit
) {
    val animatedProgress by animateFloatAsState(
        targetValue = (item.progressPercent.coerceIn(0, 100)) / 100f,
        label = "progress"
    )

    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1C1C1C)),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("download_card_${item.id}")
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Row 1: Icon/Thumbnail + Titles + Status Badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Music Icon / Thumbnail Box
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            when {
                                item.isSuccess -> Color(0xFF1B5E20)
                                item.isRunning -> Color(0xFF332A00)
                                item.isFailedOrCancelled -> Color(0xFF3E1A1A)
                                else -> Color(0xFF2A2A2A)
                            }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = when {
                            item.isSuccess -> Icons.Default.CheckCircle
                            item.isRunning -> Icons.Default.Download
                            item.isFailedOrCancelled -> Icons.Default.Error
                            else -> Icons.Default.MusicNote
                        },
                        contentDescription = null,
                        tint = when {
                            item.isSuccess -> Color(0xFF4CAF50)
                            item.isRunning -> Color(0xFFFFCA28)
                            item.isFailedOrCancelled -> Color(0xFFE57373)
                            else -> Color(0xFF888888)
                        },
                        modifier = Modifier.size(24.dp)
                    )
                }

                Spacer(modifier = Modifier.width(10.dp))

                // Song Title & Artist
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.title,
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = item.artist,
                        color = Color(0xFFAAAAAA),
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Status or Percentage Badge
                if (item.isRunning) {
                    Surface(
                        color = Color(0x33FFCA28),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text(
                            text = "${item.progressPercent}%",
                            color = Color(0xFFFFCA28),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                } else if (item.isSuccess) {
                    Surface(
                        color = Color(0x221DB954),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text(
                            text = "MP3 ✓",
                            color = Color(0xFF1DB954),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }
            }

            // Real-Time Progress Bar (Visible while running or pending)
            if (item.isRunning || item.isPending) {
                Spacer(modifier = Modifier.height(10.dp))

                if (item.progressPercent >= 0) {
                    LinearProgressIndicator(
                        progress = { animatedProgress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp)),
                        color = Color(0xFFFFCA28),
                        trackColor = Color(0xFF333333)
                    )
                } else {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp)),
                        color = Color(0xFFFFCA28),
                        trackColor = Color(0xFF333333)
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))

                // Stats row: Speed & Downloaded Size
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "⚡ ${item.speedText}",
                        color = Color(0xFFFFCA28),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "${item.formattedDownloadedSize} / ${item.formattedTotalSize}",
                        color = Color(0xFF888888),
                        fontSize = 11.sp
                    )
                }
            } else if (item.isFailedOrCancelled) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = item.errorMessage ?: item.speedText,
                    color = Color(0xFFE57373),
                    fontSize = 11.sp
                )
            }

            // Action Buttons Row
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (item.isSuccess) {
                    // Play Button
                    Button(
                        onClick = {
                            val path = item.filePath ?: ""
                            onPlay(path, item.title)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1DB954)),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.height(32.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 0.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = "Écouter",
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Écouter", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    // Delete Button
                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Supprimer",
                            tint = Color(0xFF888888),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                } else if (item.isRunning || item.isPending) {
                    // Cancel/Stop Button
                    Button(
                        onClick = onCancel,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF333333)),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.height(30.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 0.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Annuler",
                            tint = Color(0xFFCCCCCC),
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Annuler", color = Color(0xFFCCCCCC), fontSize = 11.sp)
                    }
                } else {
                    // Retry Button
                    Button(
                        onClick = onRetry,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF333333)),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.height(30.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 0.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Réessayer",
                            tint = Color(0xFFFFCA28),
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Réessayer", color = Color(0xFFFFCA28), fontSize = 11.sp)
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Supprimer",
                            tint = Color(0xFF888888),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SnaptubeEmptyDownloadsState(filter: DownloadFilterTab) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Default.Download,
                contentDescription = null,
                tint = Color(0xFF444444),
                modifier = Modifier.size(56.dp)
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = when (filter) {
                    DownloadFilterTab.ACTIVE -> "Aucun téléchargement en cours"
                    DownloadFilterTab.COMPLETED -> "Aucun morceau terminé"
                    DownloadFilterTab.ALL -> "Aucun téléchargement enregistré"
                },
                color = Color(0xFFAAAAAA),
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Recherchez un titre de chanson dans l'onglet Recherche pour lancer un téléchargement haute vitesse.",
                color = Color(0xFF666666),
                fontSize = 12.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
    }
}
