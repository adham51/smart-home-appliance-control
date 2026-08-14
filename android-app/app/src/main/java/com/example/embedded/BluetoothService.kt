package com.example.embedded

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

class BluetoothService(private val context: Context) {

    private val TAG = "BluetoothService"

    // Standard Serial Port Profile UUID
    private val SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothSocket: BluetoothSocket? = null
    private var outputStream: OutputStream? = null
    private var inputStream: InputStream? = null

    // Connection state
    var isConnected: Boolean = false
        private set

    // Callbacks
    var onConnectionStateChanged: ((Boolean) -> Unit)? = null
    var onDataReceived: ((String) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    init {
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
        if (bluetoothAdapter == null) {
            Log.e(TAG, "Device doesn't support Bluetooth")
        }
    }

    /**
     * Connect to ESP32 device by MAC address
     * @param deviceAddress MAC address of ESP32 (e.g., "30:AE:A4:XX:XX:XX")
     */
    @SuppressLint("MissingPermission")
    suspend fun connectToDevice(deviceAddress: String): Boolean = withContext(Dispatchers.IO) {
        try {
            if (bluetoothAdapter == null) {
                onError?.invoke("Bluetooth not supported")
                return@withContext false
            }

            if (!bluetoothAdapter!!.isEnabled) {
                onError?.invoke("Bluetooth is not enabled")
                return@withContext false
            }

            // Close existing connection if any
            disconnect()

            Log.d(TAG, "Attempting to connect to device: $deviceAddress")

            // Get the remote device
            val device: BluetoothDevice = bluetoothAdapter!!.getRemoteDevice(deviceAddress)

            // Create a socket
            bluetoothSocket = device.createRfcommSocketToServiceRecord(SPP_UUID)

            // Cancel discovery to speed up connection
            bluetoothAdapter?.cancelDiscovery()

            // Connect to the device
            bluetoothSocket?.connect()

            // Get input and output streams
            outputStream = bluetoothSocket?.outputStream
            inputStream = bluetoothSocket?.inputStream

            isConnected = true
            onConnectionStateChanged?.invoke(true)
            Log.d(TAG, "Connected successfully to $deviceAddress")

            // Start listening for incoming data
            startListening()

            return@withContext true

        } catch (e: IOException) {
            Log.e(TAG, "Connection failed: ${e.message}")
            onError?.invoke("Connection failed: ${e.message}")
            disconnect()
            return@withContext false
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error: ${e.message}")
            onError?.invoke("Error: ${e.message}")
            disconnect()
            return@withContext false
        }
    }

    /**
     * Send command to ESP32
     */
    private suspend fun sendCommand(command: String): Boolean = withContext(Dispatchers.IO) {
        try {
            if (!isConnected || outputStream == null) {
                Log.e(TAG, "Not connected to device")
                onError?.invoke("Not connected to device")
                return@withContext false
            }

            Log.d(TAG, "Sending command: $command")
            outputStream?.write("$command\n".toByteArray())
            outputStream?.flush()

            return@withContext true

        } catch (e: IOException) {
            Log.e(TAG, "Failed to send command: ${e.message}")
            onError?.invoke("Failed to send command")
            disconnect()
            return@withContext false
        }
    }

    /**
     * Toggle LED on/off
     */
    suspend fun toggleLed(): Boolean {
        return sendCommand("LED_TOGGLE")
    }

    /**
     * Toggle Fan on/off
     */
    suspend fun toggleFan(): Boolean {
        return sendCommand("FAN_TOGGLE")
    }

    /**
     * Set LED state explicitly
     */
    suspend fun setLed(state: Boolean): Boolean {
        return sendCommand(if (state) "LED_ON" else "LED_OFF")
    }

    /**
     * Set Fan state explicitly
     */
    suspend fun setFan(state: Boolean): Boolean {
        return sendCommand(if (state) "FAN_ON" else "FAN_OFF")
    }

    /**
     * Listen for incoming data from ESP32
     */
    private fun startListening() {
        Thread {
            val buffer = ByteArray(1024)
            var bytes: Int

            while (isConnected) {
                try {
                    if (inputStream?.available() ?: 0 > 0) {
                        bytes = inputStream?.read(buffer) ?: 0
                        val incomingMessage = String(buffer, 0, bytes)
                        Log.d(TAG, "Received: $incomingMessage")
                        onDataReceived?.invoke(incomingMessage.trim())
                    }
                    Thread.sleep(100)
                } catch (e: IOException) {
                    Log.e(TAG, "Disconnected: ${e.message}")
                    disconnect()
                    break
                } catch (e: Exception) {
                    Log.e(TAG, "Error reading: ${e.message}")
                }
            }
        }.start()
    }

    /**
     * Disconnect from device
     */
    fun disconnect() {
        try {
            isConnected = false
            inputStream?.close()
            outputStream?.close()
            bluetoothSocket?.close()

            inputStream = null
            outputStream = null
            bluetoothSocket = null

            onConnectionStateChanged?.invoke(false)
            Log.d(TAG, "Disconnected")

        } catch (e: IOException) {
            Log.e(TAG, "Error closing connection: ${e.message}")
        }
    }

    /**
     * Get list of paired devices
     */
    @SuppressLint("MissingPermission")
    fun getPairedDevices(): List<BluetoothDevice> {
        return bluetoothAdapter?.bondedDevices?.toList() ?: emptyList()
    }

    /**
     * Check if Bluetooth is enabled
     */
    fun isBluetoothEnabled(): Boolean {
        return bluetoothAdapter?.isEnabled ?: false
    }
}