package com.group17.liquidsensor.ble

import java.util.UUID

/**
 * GATT UUIDs for the Microchip RN4871's factory-default "Transparent UART"
 * private service. The module exposes a write characteristic (phone → module)
 * and a notify characteristic (module → phone), bridging straight to its
 * physical UART pins — so the existing [PacketParser] byte protocol is
 * untouched, only the transport changed from USB CDC to BLE.
 *
 * TODO: confirm with Frederick that the module hasn't been reconfigured with
 * custom UUIDs (`SS`/`ST` commands) — these are the RN4870/RN4871 defaults.
 */
object BleConstants {
    // Naming is from the module's UART perspective (per the RN4870/71 user guide):
    //   "Transparent UART TX" (…1e4d…) = module transmits → phone SUBSCRIBES to notifications
    //   "Transparent UART RX" (…8841…) = module receives  → phone WRITES commands here
    val SERVICE_UUID: UUID         = UUID.fromString("49535343-fe7d-4ae5-8fa9-9fafd205e455")
    val TX_CHARACTERISTIC_UUID: UUID = UUID.fromString("49535343-8841-43f4-a8d4-ecbe34729bb3") // phone writes here (module's RX)
    val RX_CHARACTERISTIC_UUID: UUID = UUID.fromString("49535343-1e4d-4bd9-ba61-23c647249616") // module notifies here (module's TX)

    /** Standard Client Characteristic Configuration Descriptor, used to enable notifications. */
    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    /** Optional: filter scan results by an advertised local name prefix, if known. */
    const val DEVICE_NAME_PREFIX = "RN4871"

    const val SCAN_TIMEOUT_MS = 10_000L
}
