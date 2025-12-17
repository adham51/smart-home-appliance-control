package com.example.embedded

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.embedded.ui.theme.EmbeddedTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var bluetoothService: BluetoothService
    private val REQUEST_ENABLE_BT = 1

    // Permission launcher
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            Toast.makeText(this, "Permissions granted", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Bluetooth permissions required", Toast.LENGTH_LONG).show()
        }
    }

    // Bluetooth enable launcher
    private val enableBluetoothLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            Toast.makeText(this, "Bluetooth enabled", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Bluetooth is required for this app", Toast.LENGTH_LONG).show()
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Initialize Bluetooth Service
        bluetoothService = BluetoothService(this)

        // Request permissions
        checkAndRequestPermissions()

        setContent {
            EmbeddedTheme {
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    topBar = {
                        TopAppBar(
                            title = {
                                Text(
                                    "Appliance Control",
                                    fontSize = 24.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            },
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = Color(0xFF6200EE),
                                titleContentColor = Color.White,
                                navigationIconContentColor = Color.White,
                                actionIconContentColor = Color.White
                            )
                        )
                    }
                ) { innerPadding ->
                    MainScreen(
                        outerPadding = innerPadding,
                        bluetoothService = bluetoothService,
                        onEnableBluetoothRequest = { enableBluetooth() }
                    )
                }
            }
        }
    }

    private fun checkAndRequestPermissions() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        } else {
            arrayOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        }

        val permissionsToRequest = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (permissionsToRequest.isNotEmpty()) {
            requestPermissionLauncher.launch(permissionsToRequest.toTypedArray())
        }
    }

    private fun enableBluetooth() {
        val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
        enableBluetoothLauncher.launch(enableBtIntent)
    }

    override fun onDestroy() {
        super.onDestroy()
        bluetoothService.disconnect()
    }
}

@Composable
fun MainScreen(
    modifier: Modifier = Modifier,
    outerPadding: PaddingValues,
    bluetoothService: BluetoothService,
    onEnableBluetoothRequest: () -> Unit
) {
    var isConnected by remember { mutableStateOf(false) }
    var isFanOn by remember { mutableStateOf(false) }
    var isLedOn by remember { mutableStateOf(false) }
    var isConnecting by remember { mutableStateOf(false) }
    var showDeviceDialog by remember { mutableStateOf(false) }
    var pairedDevices by remember { mutableStateOf<List<BluetoothDevice>>(emptyList()) }
    var selectedDevice by remember { mutableStateOf<BluetoothDevice?>(null) }
    var errorMessage by remember { mutableStateOf("") }

    val scope = rememberCoroutineScope()

    // Set up Bluetooth callbacks
    DisposableEffect(Unit) {
        bluetoothService.onConnectionStateChanged = { connected ->
            isConnected = connected
            isConnecting = false
            if (!connected) {
                // Reset states on disconnect
                isFanOn = false
                isLedOn = false
            }
        }

        bluetoothService.onError = { error ->
            errorMessage = error
            isConnecting = false
        }

        bluetoothService.onDataReceived = { data ->
            // Parse status updates from ESP32
            // Format: "LED:1,FAN:0"
            if (data.contains("LED:") && data.contains("FAN:")) {
                val parts = data.split(",")
                parts.forEach { part ->
                    when {
                        part.startsWith("LED:") -> {
                            val state = part.substringAfter("LED:").trim()
                            isLedOn = state == "1"
                        }
                        part.startsWith("FAN:") -> {
                            val state = part.substringAfter("FAN:").trim()
                            isFanOn = state == "1"
                        }
                    }
                }
            }
        }

        onDispose {
            // Cleanup if needed
        }
    }

    // Device Selection Dialog
    if (showDeviceDialog) {
        DeviceSelectionDialog(
            devices = pairedDevices,
            selectedDevice = selectedDevice,
            onDeviceSelected = { device ->
                selectedDevice = device
            },
            onConfirm = {
                selectedDevice?.let { device ->
                    showDeviceDialog = false
                    isConnecting = true
                    errorMessage = ""

                    scope.launch {
                        val success = bluetoothService.connectToDevice(device.address)
                        if (!success) {
                            isConnecting = false
                        }
                    }
                }
            },
            onDismiss = { showDeviceDialog = false }
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color(0xFFF5F5F5), Color(0xFFE8E8E8))
                )
            )
            .padding(outerPadding)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        // Connection Status Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = if (isConnected) Color(0xFF4CAF50) else Color(0xFFFF5722)
            ),
            elevation = CardDefaults.cardElevation(8.dp)
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "Connection Status",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White
                )
                Spacer(modifier = Modifier.height(8.dp))

                if (isConnecting) {
                    CircularProgressIndicator(
                        color = Color.White,
                        modifier = Modifier.padding(8.dp)
                    )
                    Text(
                        "Connecting...",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                } else {
                    Text(
                        if (isConnected) "Connected" else "Not Connected",
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            }
        }

        // Error Message
        if (errorMessage.isNotEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFFFFCDD2)
                )
            ) {
                Text(
                    errorMessage,
                    modifier = Modifier.padding(16.dp),
                    color = Color(0xFFC62828),
                    fontSize = 14.sp
                )
            }
        }

        // Connect Button
        Button(
            onClick = {
                if (isConnected) {
                    bluetoothService.disconnect()
                } else {
                    if (!bluetoothService.isBluetoothEnabled()) {
                        onEnableBluetoothRequest()
                    } else {
                        // Get paired devices
                        pairedDevices = bluetoothService.getPairedDevices()
                        if (pairedDevices.isEmpty()) {
                            errorMessage = "No paired devices found. Please pair your ESP32 in Android Bluetooth settings first."
                        } else {
                            showDeviceDialog = true
                            errorMessage = ""
                        }
                    }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF6200EE)
            ),
            shape = RoundedCornerShape(12.dp),
            enabled = !isConnecting
        ) {
            Text(
                if (isConnected) "Disconnect Device" else "Connect to Device",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Fan Control Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = Color.White
            ),
            elevation = CardDefaults.cardElevation(4.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        "Fan",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF212121)
                    )
                    Text(
                        if (isFanOn) "Currently On" else "Currently Off",
                        fontSize = 16.sp,
                        color = if (isFanOn) Color(0xFF4CAF50) else Color(0xFF757575)
                    )
                }
                Switch(
                    checked = isFanOn,
                    onCheckedChange = {
                        if (isConnected) {
                            scope.launch {
                                val success = bluetoothService.toggleFan()
                                if (success) {
                                    isFanOn = !isFanOn
                                }
                            }
                        }
                    },
                    enabled = isConnected,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = Color(0xFF03A9F4),
                        uncheckedThumbColor = Color.White,
                        uncheckedTrackColor = Color(0xFFBDBDBD),
                        disabledCheckedThumbColor = Color.White,
                        disabledCheckedTrackColor = Color(0xFF90CAF9),
                        disabledUncheckedThumbColor = Color.White,
                        disabledUncheckedTrackColor = Color(0xFFE0E0E0)
                    )
                )
            }
        }

        // LED Control Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = Color.White
            ),
            elevation = CardDefaults.cardElevation(4.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        "LED Light",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF212121)
                    )
                    Text(
                        if (isLedOn) "Currently On" else "Currently Off",
                        fontSize = 16.sp,
                        color = if (isLedOn) Color(0xFF4CAF50) else Color(0xFF757575)
                    )
                }
                Switch(
                    checked = isLedOn,
                    onCheckedChange = {
                        if (isConnected) {
                            scope.launch {
                                val success = bluetoothService.toggleLed()
                                if (success) {
                                    isLedOn = !isLedOn
                                }
                            }
                        }
                    },
                    enabled = isConnected,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = Color(0xFFFFEB3B),
                        uncheckedThumbColor = Color.White,
                        uncheckedTrackColor = Color(0xFFBDBDBD),
                        disabledCheckedThumbColor = Color.White,
                        disabledCheckedTrackColor = Color(0xFFFFF59D),
                        disabledUncheckedThumbColor = Color.White,
                        disabledUncheckedTrackColor = Color(0xFFE0E0E0)
                    )
                )
            }
        }

        // Info Text
        if (!isConnected) {
            Text(
                "Connect to your ESP32 device to control appliances. Make sure your ESP32 is paired in Android Bluetooth settings.",
                fontSize = 14.sp,
                color = Color.Gray,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

@Composable
fun DeviceSelectionDialog(
    devices: List<BluetoothDevice>,
    selectedDevice: BluetoothDevice?,
    onDeviceSelected: (BluetoothDevice) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                "Select ESP32 Device",
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column {
                Text(
                    "Choose your ESP32 from paired devices:",
                    fontSize = 14.sp,
                    color = Color.Gray
                )
                Spacer(modifier = Modifier.height(16.dp))

                if (devices.isEmpty()) {
                    Text(
                        "No paired devices found",
                        fontSize = 14.sp,
                        color = Color.Red
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.height(300.dp)
                    ) {
                        items(devices) { device ->
                            DeviceListItem(
                                device = device,
                                isSelected = device == selectedDevice,
                                onSelect = { onDeviceSelected(device) }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = selectedDevice != null
            ) {
                Text("Connect")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
fun DeviceListItem(
    device: BluetoothDevice,
    isSelected: Boolean,
    onSelect: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable { onSelect() },
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) Color(0xFFE3F2FD) else Color.White
        ),
        elevation = CardDefaults.cardElevation(2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = device.name ?: "Unknown Device",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color(0xFF212121)
                )
                Text(
                    text = device.address,
                    fontSize = 12.sp,
                    color = Color.Gray
                )
            }
            RadioButton(
                selected = isSelected,
                onClick = onSelect
            )
        }
    }
}