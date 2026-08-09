package com.group17.liquidsensor.ble

import com.group17.liquidsensor.data.*
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Stateful streaming parser for the device byte stream (now carried over BLE
 * notify/write characteristics instead of USB CDC, same wire format).
 *
 * Wire format (little-endian, must match STM32 firmware):
 *   [0xAA][0x55][TYPE:1][LEN_LO:1][LEN_HI:1][PAYLOAD:LEN][CHECKSUM:1]
 *
 * Checksum = XOR of every byte in PAYLOAD.
 *
 * Measurement Result Packet payload layout (43 bytes):
 *   record_number : uint32   (MSB set => historical / export replay)
 *   timestamp     : uint32   (seconds since device boot or epoch)
 *   conductivity_raw     : float32
 *   density_raw          : float32
 *   temperature_raw      : float32
 *   conductivity_feature : float32  (mS/cm)
 *   density_feature      : float32  (g/mL)
 *   temperature_feature  : float32  (°C)
 *   validity             : uint8
 *   knn_idx              : uint8
 *   knn_confidence       : float32
 *   nc_idx               : uint8
 *   nc_confidence        : float32
 *
 * TODO: verify byte layout with Frederick once firmware BLE UART driver is finalised.
 */
class PacketParser {

    private val buf = ArrayDeque<Byte>()

    /** Feed raw bytes from the BLE notify stream; returns any complete packets found. */
    fun feed(bytes: ByteArray): List<Packet> {
        bytes.forEach { buf.addLast(it) }
        val results = mutableListOf<Packet>()

        while (buf.size >= 6) {
            val syncIdx = findSync()
            if (syncIdx < 0) {
                // No sync — keep last byte (might be first byte of next sync)
                val last = buf.last()
                buf.clear()
                buf.addLast(last)
                break
            }
            repeat(syncIdx) { buf.removeFirst() }          // discard pre-sync junk
            if (buf.size < 5) break                         // need sync(2)+type(1)+len(2)

            val len = (buf[3].toInt() and 0xFF) or ((buf[4].toInt() and 0xFF) shl 8)
            val total = 2 + 1 + 2 + len + 1               // sync + type + lenBytes + payload + checksum
            if (buf.size < total) break                     // wait for more bytes

            val type     = buf[2]
            val payload  = ByteArray(len) { buf[5 + it] }
            val checksum = buf[5 + len]

            val computed = payload.fold(0.toByte()) { acc, b -> (acc.toInt() xor b.toInt()).toByte() }
            if (computed == checksum) {
                parsePacket(type, payload)?.let { results += it }
            }
            repeat(total) { buf.removeFirst() }
        }
        return results
    }

    /** Build a command packet to send to the device. */
    fun buildCommand(commandCode: Byte): ByteArray {
        val payload  = byteArrayOf(commandCode)
        val checksum = payload.fold(0.toByte()) { acc, b -> (acc.toInt() xor b.toInt()).toByte() }
        return byteArrayOf(0xAA.toByte(), 0x55.toByte(),
            PacketType.COMMAND, 0x01, 0x00, commandCode, checksum)
    }

    // ── private helpers ──────────────────────────────────────────────────────

    private fun findSync(): Int {
        for (i in 0 until buf.size - 1) {
            if (buf[i] == 0xAA.toByte() && buf[i + 1] == 0x55.toByte()) return i
        }
        return -1
    }

    private fun parsePacket(type: Byte, payload: ByteArray): Packet? {
        val bb = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        return when (type) {
            PacketType.STATUS             -> parseStatus(bb)
            PacketType.SUBSTANCE_TABLE    -> parseSubstanceTable(bb)
            PacketType.MEASUREMENT_RESULT -> parseMeasurementResult(bb)
            PacketType.ACK                -> if (payload.isNotEmpty()) Packet.AcknowledgmentPacket(payload[0]) else null
            else                          -> null
        }
    }

    private fun parseStatus(bb: ByteBuffer): Packet.StatusPacket {
        val stateCode = bb.get().toInt() and 0xFF
        val modeCode  = bb.get().toInt() and 0xFF
        return Packet.StatusPacket(
            firmwareState = FirmwareState.entries.getOrElse(stateCode) { FirmwareState.IDLE },
            operatingMode = if (modeCode == 1) OperatingMode.REFERENCE_COLLECTION else OperatingMode.IDENTIFICATION
        )
    }

    private fun parseSubstanceTable(bb: ByteBuffer): Packet.SubstanceLabelTable {
        val count  = bb.get().toInt() and 0xFF
        val labels = mutableMapOf<Int, String>()
        repeat(count) {
            val idx   = bb.get().toInt() and 0xFF
            val chars = mutableListOf<Byte>()
            while (bb.hasRemaining()) {
                val c = bb.get()
                if (c == 0.toByte()) break
                chars += c
            }
            labels[idx] = String(chars.toByteArray(), Charsets.UTF_8)
        }
        return Packet.SubstanceLabelTable(labels)
    }

    private fun parseMeasurementResult(bb: ByteBuffer): Packet.MeasurementResultPacket {
        val recordRaw    = bb.int.toLong() and 0xFFFFFFFFL
        val isHistorical = (recordRaw and 0x80000000L) != 0L
        val recordNum    = recordRaw and 0x7FFFFFFFL
        val timestampSec = bb.int.toLong() and 0xFFFFFFFFL

        val condRaw  = bb.float
        val densRaw  = bb.float
        val tempRaw  = bb.float
        val condFeat = bb.float
        val densFeat = bb.float
        val tempFeat = bb.float

        val validity = ValidityStatus.fromCode(bb.get().toInt() and 0xFF)
        val knnIdx   = bb.get().toInt() and 0xFF
        val knnConf  = bb.float
        val ncIdx    = bb.get().toInt() and 0xFF
        val ncConf   = bb.float

        return Packet.MeasurementResultPacket(
            MeasurementRecord(
                recordNumber        = recordNum,
                timestamp           = timestampSec * 1000L,
                isHistorical        = isHistorical,
                conductivityRaw     = condRaw,
                densityRaw          = densRaw,
                temperatureRaw      = tempRaw,
                conductivityFeature = condFeat,
                densityFeature      = densFeat,
                temperatureFeature  = tempFeat,
                validityStatus      = validity,
                knnPredictionIndex  = knnIdx,
                knnConfidence       = knnConf,
                ncPredictionIndex   = ncIdx,
                ncConfidence        = ncConf
            )
        )
    }
}
