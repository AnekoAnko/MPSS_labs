package com.example.lab2_autorotatemanager.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.lab2_autorotatemanager.ble.BleConnectionState
import com.example.lab2_autorotatemanager.ble.BleDevice
import com.example.lab2_autorotatemanager.ble.BleLogEntry
import com.example.lab2_autorotatemanager.ble.BleProfile
import com.example.lab2_autorotatemanager.ble.BleViewModel
import com.example.lab2_autorotatemanager.ble.LogType

/** Екран керування BLE: пошук, список пристроїв, статус, команди, термінал. */
@Composable
fun BleScreen(viewModel: BleViewModel, onScanClick: () -> Unit) {
    val state by viewModel.uiState.collectAsState()
    val log by viewModel.log.collectAsState()
    var commandText by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("BLE-хаб (Bluetooth Interface Layer)", fontSize = 22.sp, fontWeight = FontWeight.Bold)

        ConnectionIndicator(state.connectionState, state.connectedDevice?.name)

        // Пошук пристроїв + ProgressBar
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = onScanClick,
                enabled = !state.isScanning && state.connectionState == BleConnectionState.DISCONNECTED
            ) { Text("Пошук пристроїв") }
            Spacer(Modifier.width(12.dp))
            if (state.isScanning) {
                CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
                TextButton(onClick = viewModel::stopScan) { Text("Стоп") }
            }
        }
        if (state.isScanning) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Text("Сканування ефіру…", style = MaterialTheme.typography.bodySmall)
        }

        // Список знайдених пристроїв
        Text("Знайдені пристрої: ${state.devices.size}", fontWeight = FontWeight.SemiBold)
        if (state.devices.isEmpty() && !state.isScanning) {
            Text("Натисніть «Пошук пристроїв»", style = MaterialTheme.typography.bodySmall)
        }
        state.devices.forEach { device ->
            DeviceItem(
                device = device,
                isCurrent = device.address == state.connectedDevice?.address,
                connectionState = state.connectionState,
                onConnect = { viewModel.connect(device) },
                onDisconnect = viewModel::disconnect
            )
        }

        // Керування підключеним пристроєм
        if (state.connectionState.isLinkUp) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Статус хаба (Notify): ${state.lastStatus ?: "—"}", fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf("PING", "RELAY:ON", "RELAY:OFF", "GET_STATUS").forEach { cmd ->
                            OutlinedButton(onClick = { viewModel.sendCommand(cmd) }) { Text(cmd, fontSize = 12.sp) }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = commandText,
                            onValueChange = { commandText = it },
                            label = { Text("Команда / дані") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = {
                            viewModel.sendCommand(commandText)
                            commandText = ""
                        }) { Text("Надіслати") }
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Імітувати перешкоди в ефірі")
            Switch(checked = state.interference, onCheckedChange = viewModel::setInterference)
        }

        // Термінал
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Термінал обміну", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            TextButton(onClick = viewModel::clearLog) { Text("Очистити") }
        }
        Terminal(log)
    }
}

@Composable
private fun ConnectionIndicator(state: BleConnectionState, deviceName: String?) {
    val color = when (state) {
        BleConnectionState.CONNECTED, BleConnectionState.TRANSFERRING -> Color(0xFF2E7D32)
        BleConnectionState.DISCONNECTED -> Color(0xFF9E9E9E)
        else -> Color(0xFFF9A825)
    }
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(16.dp)
                    .background(color, CircleShape)
            )
            Spacer(Modifier.width(12.dp))
            Column {
                Text(state.label, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = color)
                Text(deviceName ?: "Немає активного з'єднання", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun DeviceItem(
    device: BleDevice,
    isCurrent: Boolean,
    connectionState: BleConnectionState,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit
) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(device.name, fontWeight = FontWeight.Bold)
                Text("${device.address} · RSSI ${device.rssi} dBm", fontSize = 13.sp)
                Text(
                    if (BleProfile.SERVICE_AR_HUB in device.serviceUuids) "Сервіс: AR Hub" else "Non-connectable",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.outline
                )
            }
            when {
                !device.connectable -> {}
                isCurrent && connectionState != BleConnectionState.DISCONNECTED ->
                    OutlinedButton(onClick = onDisconnect) { Text("Відключити") }
                else -> Button(
                    onClick = onConnect,
                    enabled = connectionState == BleConnectionState.DISCONNECTED
                ) { Text("Підключити") }
            }
        }
    }
}

@Composable
private fun Terminal(entries: List<BleLogEntry>) {
    val listState = rememberLazyListState()
    LaunchedEffect(entries.size) {
        if (entries.isNotEmpty()) listState.animateScrollToItem(entries.lastIndex)
    }
    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxWidth()
            .height(300.dp)
            .background(Color(0xFF1E1E1E), RoundedCornerShape(8.dp))
            .padding(8.dp)
    ) {
        items(entries) { e ->
            val color = when (e.type) {
                LogType.TX -> Color(0xFF4FC3F7)
                LogType.RX -> Color(0xFF81C784)
                LogType.WARN -> Color(0xFFFFD54F)
                LogType.ERROR -> Color(0xFFE57373)
                LogType.INFO -> Color(0xFFBDBDBD)
            }
            Text(
                "${e.time} ${e.type.tag} ${e.message}",
                color = color,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                lineHeight = 14.sp
            )
        }
    }
}
