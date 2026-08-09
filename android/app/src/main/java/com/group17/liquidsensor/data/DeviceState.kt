package com.group17.liquidsensor.data

data class DeviceState(
    val connectionState: ConnectionState = ConnectionState.DISCONNECTED,
    val operatingMode: OperatingMode = OperatingMode.IDENTIFICATION,
    val firmwareState: FirmwareState = FirmwareState.IDLE,
    val substanceLabels: Map<Int, String> = emptyMap()
)

enum class ConnectionState { DISCONNECTED, CONNECTING, CONNECTED, ERROR }

enum class OperatingMode { IDENTIFICATION, REFERENCE_COLLECTION }

/** Mirrors the firmware state machine: Idle → Acquire → Compute → Classify → Report */
enum class FirmwareState { IDLE, ACQUIRE, COMPUTE, CLASSIFY, REPORT }
