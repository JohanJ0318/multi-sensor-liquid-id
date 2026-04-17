package com.group17.liquidid.data.usb

import com.group17.liquidid.data.model.DeviceStatus
import com.group17.liquidid.data.model.MeasurementRecord
import com.group17.liquidid.data.model.OperatingMode
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Parses structured binary packets from the STM32 device.
 *
 * Packet format:
 *   [0xAA 0x55] [type: 1B] [length: 2B LE] [payload: N bytes] [checksum: 1B XOR]
 */
object PacketParser {

    // Sync marker bytes
    const val SYNC_BYTE_1: Byte = 0xAA.toByte()
    const val SYNC_BYTE_2: Byte = 0x55

    // Packet type identifiers
    const val TYPE_MEASUREMENT: Byte = 0x01
    const val TYPE_COMMAND: Byte = 0x02
    const val TYPE_STATUS: Byte = 0x03
    const val TYPE_LABEL_TABLE: Byte = 0x04
    const val TYPE_ACK: Byte = 0x05

    // Header size: sync(2) + type(1) + length(2) = 5
    const val HEADER_SIZE = 5
    // Checksum size
    const val CHECKSUM_SIZE = 1

    /**
     * Parsed packet result.
     */
    sealed class ParsedPacket {
        data class Measurement(val record: MeasurementRecord) : ParsedPacket()
        data class Status(val status: DeviceStatus) : ParsedPacket()
        data class LabelTable(val labels: List<String>) : ParsedPacket()
        data class Ack(val commandCode: Byte) : ParsedPacket()
        data class Unknown(val type: Byte, val payload: ByteArray) : ParsedPacket()
    }

    /**
     * Validates checksum (XOR of all header + payload bytes).
     */
    fun validateChecksum(packet: ByteArray): Boolean {
        if (packet.isEmpty()) return false
        var xor: Byte = 0
        for (i in 0 until packet.size - 1) {
            xor = (xor.toInt() xor packet[i].toInt()).toByte()
        }
        return xor == packet.last()
    }

    /**
     * Builds a command packet ready for transmission.
     */
    fun buildCommandPacket(commandCode: Byte, params: ByteArray = byteArrayOf()): ByteArray {
        val payloadLength = 1 + params.size
        val packet = ByteArray(HEADER_SIZE + payloadLength + CHECKSUM_SIZE)
        packet[0] = SYNC_BYTE_1
        packet[1] = SYNC_BYTE_2
        packet[2] = TYPE_COMMAND
        // Length in little-endian
        packet[3] = (payloadLength and 0xFF).toByte()
        packet[4] = ((payloadLength shr 8) and 0xFF).toByte()
        // Payload
        packet[5] = commandCode
        params.copyInto(packet, 6)
        // Checksum: XOR of all preceding bytes
        var xor: Byte = 0
        for (i in 0 until packet.size - 1) {
            xor = (xor.toInt() xor packet[i].toInt()).toByte()
        }
        packet[packet.size - 1] = xor
        return packet
    }

    /**
     * Parse a complete packet (header + payload + checksum already extracted).
     */
    fun parse(type: Byte, payload: ByteArray): ParsedPacket {
        return when (type) {
            TYPE_MEASUREMENT -> parseMeasurement(payload)
            TYPE_STATUS -> parseStatus(payload)
            TYPE_LABEL_TABLE -> parseLabelTable(payload)
            TYPE_ACK -> ParsedPacket.Ack(if (payload.isNotEmpty()) payload[0] else 0)
            else -> ParsedPacket.Unknown(type, payload)
        }
    }

    /**
     * Parse Measurement Result Packet (Type 0x01) — 47-byte payload.
     */
    private fun parseMeasurement(payload: ByteArray): ParsedPacket.Measurement {
        val buf = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)

        val record = MeasurementRecord(
            recordNumber = buf.getInt().toLong() and 0xFFFFFFFFL,  // uint32
            timestampMs = buf.getInt().toLong() and 0xFFFFFFFFL,   // uint32
            rawConductivityAdc = buf.getFloat(),
            rawLoadCellAdc = buf.getFloat(),
            rawTemperature = buf.getFloat(),
            conductivityMsCm = buf.getFloat(),
            densityGMl = buf.getFloat(),
            temperatureC = buf.getFloat(),
            validityBitmask = buf.get().toInt() and 0xFF,
            knnPredictedIndex = buf.get().toInt() and 0xFF,
            knnConfidence = buf.getFloat(),
            nearestCentroidIndex = buf.get().toInt() and 0xFF,
            nearestCentroidConfidence = buf.getFloat(),
            modelVersion = buf.get().toInt() and 0xFF
        )

        return ParsedPacket.Measurement(record)
    }

    /**
     * Parse Status Packet (Type 0x03).
     */
    private fun parseStatus(payload: ByteArray): ParsedPacket.Status {
        val buf = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)

        val mode = OperatingMode.fromCode(buf.get())
        val storedRecords = buf.getShort().toInt() and 0xFFFF
        val batteryVoltage = if (buf.remaining() >= 4) buf.getFloat() else null
        val modelVersion = if (buf.remaining() >= 1) buf.get().toInt() and 0xFF else 0

        return ParsedPacket.Status(
            DeviceStatus(mode, storedRecords, batteryVoltage, modelVersion)
        )
    }

    /**
     * Parse Substance Label Table Packet (Type 0x04).
     * Format: [count: 1B] [null-terminated ASCII strings...]
     */
    private fun parseLabelTable(payload: ByteArray): ParsedPacket.LabelTable {
        if (payload.isEmpty()) return ParsedPacket.LabelTable(emptyList())

        val count = payload[0].toInt() and 0xFF
        val labels = mutableListOf<String>()
        var offset = 1

        repeat(count) {
            val start = offset
            while (offset < payload.size && payload[offset] != 0.toByte()) {
                offset++
            }
            if (start < offset) {
                labels.add(String(payload, start, offset - start, Charsets.US_ASCII))
            }
            offset++ // skip null terminator
        }

        return ParsedPacket.LabelTable(labels)
    }
}
