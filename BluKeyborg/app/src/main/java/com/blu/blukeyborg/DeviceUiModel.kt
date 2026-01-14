package com.blu.blukeyborg

// High-level connection state used by the Devices screen
enum class ConnectionState {
    CONNECTED,              // Green – BLE + MTLS up
    PARTIAL,                // Orange – BLE up, MTLS not ready
    DISCONNECTED_AVAILABLE, // Red – fully setup but currently not connected
    OFFLINE                 // Grey – not setup / not bonded / not in range
}

data class DeviceUiModel(
    val name: String,
    val address: String,
    val bonded: Boolean,
    val isProvisioned: Boolean,
    val keyboardLayout: String?,
    val firmwareVersion: String?,
    val protocolVersion: String?,
    val rssi: Int?,
    val isSelected: Boolean,
    val connectionState: ConnectionState,
    val lastError: String?
)
