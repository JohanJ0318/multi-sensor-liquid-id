package com.group17.liquidsensor.data

/** Typed representation of every packet the device can send or receive. */
sealed class Packet {
    data class StatusPacket(
        val firmwareState: FirmwareState,
        val operatingMode: OperatingMode
    ) : Packet()

    data class SubstanceLabelTable(
        val labels: Map<Int, String>
    ) : Packet()

    data class MeasurementResultPacket(
        val record: MeasurementRecord
    ) : Packet()

    data class AcknowledgmentPacket(
        val commandCode: Byte
    ) : Packet()
}

/**
 * Packet type constants — must match firmware definitions exactly.
 *  Confirm values with Frederick against the USB CDC driver code.
 */
object PacketType {
    const val STATUS: Byte            = 0x01
    const val SUBSTANCE_TABLE: Byte   = 0x02
    const val MEASUREMENT_RESULT: Byte = 0x03
    const val COMMAND: Byte           = 0x04
    const val ACK: Byte               = 0x05
}

/**
 * Command codes sent from the app to the device.
 * Must match the firmware Command Packet handler.
 */
object CommandCode {
    const val START_MEASUREMENT: Byte        = 0x01
    const val MODE_IDENTIFICATION: Byte      = 0x02
    const val MODE_REFERENCE_COLLECTION: Byte = 0x03
    const val REQUEST_EXPORT: Byte           = 0x04
}
