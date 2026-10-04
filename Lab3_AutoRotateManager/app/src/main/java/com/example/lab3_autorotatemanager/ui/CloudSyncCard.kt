package com.example.lab3_autorotatemanager.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.lab3_autorotatemanager.cloud.CloudUiState
import com.example.lab3_autorotatemanager.cloud.SyncStatus

/** Індикатор «Хмарна синхронізація»: зелений — останній запит успішний, червоний — помилка або Pending. */
@Composable
fun CloudSyncCard(
    state: CloudUiState,
    onEnabledChange: (Boolean) -> Unit,
    onSendNow: () -> Unit
) {
    val color = when (state.status) {
        SyncStatus.SUCCESS -> Color(0xFF2E7D32)
        SyncStatus.ERROR, SyncStatus.PENDING -> Color(0xFFC62828)
        SyncStatus.SENDING -> Color(0xFFF9A825)
        SyncStatus.IDLE, SyncStatus.DISABLED -> Color(0xFF9E9E9E)
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(16.dp)
                        .background(color, CircleShape)
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("Хмарна синхронізація", fontWeight = FontWeight.Bold)
                    Text(state.status.label, color = color, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }
                Switch(checked = state.enabled, onCheckedChange = onEnabledChange)
            }

            val small = MaterialTheme.typography.bodySmall
            Text("Пристрій: ${state.deviceId} · мережа: ${if (state.online) "онлайн" else "офлайн"}", style = small)
            Text("Надіслано: ${state.sentCount} · Pending: ${state.pendingCount}", style = small)
            state.lastSuccessTime?.let { Text("Останній запис: $it, key ${state.lastRecordKey}", style = small) }
            state.lastError?.let { Text("Помилка: $it", style = small, color = Color(0xFFC62828)) }
            state.lastPayloadJson?.let {
                Text(it, fontFamily = FontFamily.Monospace, fontSize = 10.sp, lineHeight = 13.sp)
            }
            state.recent.forEach {
                Text(it, fontFamily = FontFamily.Monospace, fontSize = 10.sp, lineHeight = 13.sp,
                    color = MaterialTheme.colorScheme.outline)
            }
            OutlinedButton(onClick = onSendNow, enabled = state.enabled) { Text("Надіслати зараз") }
        }
    }
}
