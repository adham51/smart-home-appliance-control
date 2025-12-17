package com.example.embedded

import android.util.Log

class VoiceCommandService {

    private val TAG = "VoiceCommandService"

    data class VoiceCommand(
        val type: CommandType,
        val device: DeviceType,
        val action: ActionType
    )

    enum class CommandType {
        SINGLE_DEVICE,
        ALL_DEVICES,
        UNKNOWN
    }

    enum class DeviceType {
        FAN,
        LED,
        ALL,
        UNKNOWN
    }

    enum class ActionType {
        TURN_ON,
        TURN_OFF,
        TOGGLE,
        UNKNOWN
    }

    /**
     * Process spoken text and extract command
     */
    fun processVoiceCommand(spokenText: String): VoiceCommand {
        Log.d(TAG, "Processing voice command: $spokenText")

        val lowerText = spokenText.lowercase().trim()

        // Determine device type
        val deviceType = when {
            lowerText.contains("fan") -> DeviceType.FAN
            lowerText.contains("led") || lowerText.contains("light") -> DeviceType.LED
            lowerText.contains("everything") || lowerText.contains("all") ||
                    lowerText.contains("both") -> DeviceType.ALL
            else -> DeviceType.UNKNOWN
        }

        // Determine action type
        val actionType = when {
            lowerText.contains("on") || lowerText.contains("turn on") ||
                    lowerText.contains("start") || lowerText.contains("enable") ||
                    lowerText.contains("activate") -> ActionType.TURN_ON

            lowerText.contains("off") || lowerText.contains("turn off") ||
                    lowerText.contains("stop") || lowerText.contains("disable") ||
                    lowerText.contains("deactivate") -> ActionType.TURN_OFF

            lowerText.contains("toggle") || lowerText.contains("switch") -> ActionType.TOGGLE

            else -> ActionType.UNKNOWN
        }

        // Determine command type
        val commandType = when (deviceType) {
            DeviceType.ALL -> CommandType.ALL_DEVICES
            DeviceType.FAN, DeviceType.LED -> CommandType.SINGLE_DEVICE
            DeviceType.UNKNOWN -> CommandType.UNKNOWN
        }

        val command = VoiceCommand(commandType, deviceType, actionType)
        Log.d(TAG, "Extracted command: $command")

        return command
    }

    /**
     * Execute the voice command using Bluetooth service
     */
    suspend fun executeCommand(
        command: VoiceCommand,
        bluetoothService: BluetoothService,
        onFanStateChanged: (Boolean) -> Unit,
        onLedStateChanged: (Boolean) -> Unit
    ): Boolean {
        Log.d(TAG, "Executing command: $command")

        return when (command.type) {
            CommandType.SINGLE_DEVICE -> {
                executeSingleDeviceCommand(
                    command,
                    bluetoothService,
                    onFanStateChanged,
                    onLedStateChanged
                )
            }
            CommandType.ALL_DEVICES -> {
                executeAllDevicesCommand(
                    command,
                    bluetoothService,
                    onFanStateChanged,
                    onLedStateChanged
                )
            }
            CommandType.UNKNOWN -> {
                Log.w(TAG, "Unknown command type")
                false
            }
        }
    }

    private suspend fun executeSingleDeviceCommand(
        command: VoiceCommand,
        bluetoothService: BluetoothService,
        onFanStateChanged: (Boolean) -> Unit,
        onLedStateChanged: (Boolean) -> Unit
    ): Boolean {
        return when (command.device) {
            DeviceType.FAN -> {
                when (command.action) {
                    ActionType.TURN_ON -> {
                        val success = bluetoothService.setFan(true)
                        if (success) onFanStateChanged(true)
                        success
                    }
                    ActionType.TURN_OFF -> {
                        val success = bluetoothService.setFan(false)
                        if (success) onFanStateChanged(false)
                        success
                    }
                    ActionType.TOGGLE -> {
                        bluetoothService.toggleFan()
                    }
                    ActionType.UNKNOWN -> false
                }
            }
            DeviceType.LED -> {
                when (command.action) {
                    ActionType.TURN_ON -> {
                        val success = bluetoothService.setLed(true)
                        if (success) onLedStateChanged(true)
                        success
                    }
                    ActionType.TURN_OFF -> {
                        val success = bluetoothService.setLed(false)
                        if (success) onLedStateChanged(false)
                        success
                    }
                    ActionType.TOGGLE -> {
                        bluetoothService.toggleLed()
                    }
                    ActionType.UNKNOWN -> false
                }
            }
            else -> false
        }
    }

    private suspend fun executeAllDevicesCommand(
        command: VoiceCommand,
        bluetoothService: BluetoothService,
        onFanStateChanged: (Boolean) -> Unit,
        onLedStateChanged: (Boolean) -> Unit
    ): Boolean {
        return when (command.action) {
            ActionType.TURN_ON -> {
                val fanSuccess = bluetoothService.setFan(true)
                val ledSuccess = bluetoothService.setLed(true)
                if (fanSuccess) onFanStateChanged(true)
                if (ledSuccess) onLedStateChanged(true)
                fanSuccess && ledSuccess
            }
            ActionType.TURN_OFF -> {
                val fanSuccess = bluetoothService.setFan(false)
                val ledSuccess = bluetoothService.setLed(false)
                if (fanSuccess) onFanStateChanged(false)
                if (ledSuccess) onLedStateChanged(false)
                fanSuccess && ledSuccess
            }
            ActionType.TOGGLE -> {
                val fanSuccess = bluetoothService.toggleFan()
                val ledSuccess = bluetoothService.toggleLed()
                fanSuccess && ledSuccess
            }
            ActionType.UNKNOWN -> false
        }
    }

    /**
     * Get a user-friendly error message for unrecognized commands
     */
    fun getCommandErrorMessage(command: VoiceCommand): String {
        return when {
            command.device == DeviceType.UNKNOWN ->
                "Device not recognized. Try saying 'Fan', 'LED', or 'Everything'"

            command.action == ActionType.UNKNOWN ->
                "Action not recognized. Try saying 'on', 'off', or 'toggle'"

            else ->
                "Command not recognized. Try: 'Fan on', 'LED off', 'Everything on', etc."
        }
    }

    /**
     * Get a success message for the executed command
     */
    fun getSuccessMessage(command: VoiceCommand): String {
        val device = when (command.device) {
            DeviceType.FAN -> "Fan"
            DeviceType.LED -> "LED"
            DeviceType.ALL -> "All devices"
            DeviceType.UNKNOWN -> "Device"
        }

        val action = when (command.action) {
            ActionType.TURN_ON -> "turned on"
            ActionType.TURN_OFF -> "turned off"
            ActionType.TOGGLE -> "toggled"
            ActionType.UNKNOWN -> "controlled"
        }

        return "$device $action successfully"
    }
}