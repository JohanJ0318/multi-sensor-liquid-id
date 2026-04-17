/**
 * @file    comm.c
 * @brief   USB CDC packet framing and transmission
 */

#include "comm.h"
#include "usbd_cdc_if.h"
#include <string.h>

/* ── Transmit buffer ── */
static uint8_t tx_buf[256];

/* ── Receive state machine ── */
typedef enum {
    RX_WAIT_SYNC1, RX_WAIT_SYNC2, RX_HEADER, RX_PAYLOAD, RX_CHECKSUM
} RxState_t;

static RxState_t rx_state = RX_WAIT_SYNC1;
static uint8_t   rx_header[3];     // type + length(2)
static uint8_t   rx_payload[64];
static uint16_t  rx_payload_len;
static uint16_t  rx_payload_pos;
static uint8_t   rx_checksum_acc;

/* ── Pending command ── */
static volatile PendingCommand_t g_pending_cmd = {0};

/* ── Helper: compute XOR checksum ── */
static uint8_t compute_checksum(const uint8_t *data, uint16_t len) {
    uint8_t xor = 0;
    for (uint16_t i = 0; i < len; i++) {
        xor ^= data[i];
    }
    return xor;
}

/* ── Helper: build and send a packet ── */
static void send_packet(uint8_t type, const uint8_t *payload, uint16_t payload_len) {
    uint16_t total = PKT_HEADER_SIZE + payload_len + 1; // +1 for checksum
    if (total > sizeof(tx_buf)) return;

    tx_buf[0] = PKT_SYNC_1;
    tx_buf[1] = PKT_SYNC_2;
    tx_buf[2] = type;
    tx_buf[3] = (uint8_t)(payload_len & 0xFF);
    tx_buf[4] = (uint8_t)((payload_len >> 8) & 0xFF);

    if (payload_len > 0) {
        memcpy(&tx_buf[5], payload, payload_len);
    }

    tx_buf[total - 1] = compute_checksum(tx_buf, total - 1);

    CDC_Transmit_FS(tx_buf, total);
}

/* ── Public API ── */

void Comm_Init(void) {
    rx_state = RX_WAIT_SYNC1;
    memset((void*)&g_pending_cmd, 0, sizeof(g_pending_cmd));
}

void Comm_SendMeasurement(const MeasurementRecord_t *record) {
    uint8_t payload[PKT_MEASUREMENT_LEN];
    uint16_t pos = 0;

    // record_number (uint32 LE)
    memcpy(&payload[pos], &record->record_number, 4); pos += 4;
    // timestamp_ms (uint32 LE)
    memcpy(&payload[pos], &record->timestamp_ms, 4); pos += 4;
    // raw values (3x float32)
    memcpy(&payload[pos], &record->raw.raw_conductivity_adc, 4); pos += 4;
    memcpy(&payload[pos], &record->raw.raw_loadcell_adc, 4); pos += 4;
    memcpy(&payload[pos], &record->raw.raw_temperature, 4); pos += 4;
    // computed features (3x float32)
    memcpy(&payload[pos], &record->features.conductivity_ms_cm, 4); pos += 4;
    memcpy(&payload[pos], &record->features.density_g_ml, 4); pos += 4;
    memcpy(&payload[pos], &record->features.temperature_c, 4); pos += 4;
    // validity (uint8)
    payload[pos++] = record->features.validity;
    // kNN result
    payload[pos++] = record->knn_result.predicted_index;
    memcpy(&payload[pos], &record->knn_result.confidence, 4); pos += 4;
    // NC result
    payload[pos++] = record->nc_result.predicted_index;
    memcpy(&payload[pos], &record->nc_result.confidence, 4); pos += 4;
    // model version
    payload[pos++] = record->model_version;

    send_packet(PKT_TYPE_MEASUREMENT, payload, pos);
}

void Comm_SendStatus(SystemState_t state, OperatingMode_t mode,
                     uint16_t stored_records, float battery_v) {
    uint8_t payload[8];
    uint16_t pos = 0;

    payload[pos++] = (uint8_t)mode;
    payload[pos++] = (uint8_t)(stored_records & 0xFF);
    payload[pos++] = (uint8_t)((stored_records >> 8) & 0xFF);
    memcpy(&payload[pos], &battery_v, 4); pos += 4;
    payload[pos++] = FIRMWARE_VERSION;

    send_packet(PKT_TYPE_STATUS, payload, pos);
}

void Comm_SendLabelTable(const char **labels, uint8_t count) {
    uint8_t payload[256];
    uint16_t pos = 0;

    payload[pos++] = count;
    for (uint8_t i = 0; i < count && pos < sizeof(payload) - 1; i++) {
        uint16_t len = strlen(labels[i]);
        if (pos + len + 1 >= sizeof(payload)) break;
        memcpy(&payload[pos], labels[i], len);
        pos += len;
        payload[pos++] = 0; // null terminator
    }

    send_packet(PKT_TYPE_LABEL_TABLE, payload, pos);
}

void Comm_SendAck(uint8_t command_code) {
    send_packet(PKT_TYPE_ACK, &command_code, 1);
}

/* ── Receive byte processing ── */

void Comm_ProcessRxByte(uint8_t byte) {
    switch (rx_state) {
        case RX_WAIT_SYNC1:
            if (byte == PKT_SYNC_1) {
                rx_checksum_acc = byte;
                rx_state = RX_WAIT_SYNC2;
            }
            break;

        case RX_WAIT_SYNC2:
            if (byte == PKT_SYNC_2) {
                rx_checksum_acc ^= byte;
                rx_state = RX_HEADER;
                rx_payload_pos = 0;
            } else {
                rx_state = RX_WAIT_SYNC1;
            }
            break;

        case RX_HEADER:
            rx_header[rx_payload_pos++] = byte;
            rx_checksum_acc ^= byte;
            if (rx_payload_pos >= 3) {
                rx_payload_len = rx_header[1] | ((uint16_t)rx_header[2] << 8);
                if (rx_payload_len > sizeof(rx_payload)) {
                    rx_state = RX_WAIT_SYNC1;
                } else if (rx_payload_len == 0) {
                    rx_state = RX_CHECKSUM;
                } else {
                    rx_payload_pos = 0;
                    rx_state = RX_PAYLOAD;
                }
            }
            break;

        case RX_PAYLOAD:
            rx_payload[rx_payload_pos++] = byte;
            rx_checksum_acc ^= byte;
            if (rx_payload_pos >= rx_payload_len) {
                rx_state = RX_CHECKSUM;
            }
            break;

        case RX_CHECKSUM:
            if (byte == rx_checksum_acc) {
                // Valid packet — process command
                if (rx_header[0] == PKT_TYPE_COMMAND && rx_payload_len >= 1) {
                    g_pending_cmd.command_code = rx_payload[0];
                    g_pending_cmd.param_len = (rx_payload_len > 1) ? rx_payload_len - 1 : 0;
                    if (g_pending_cmd.param_len > 0) {
                        memcpy(g_pending_cmd.params, &rx_payload[1], g_pending_cmd.param_len);
                    }
                    g_pending_cmd.pending = 1;
                }
            }
            rx_state = RX_WAIT_SYNC1;
            break;
    }
}

PendingCommand_t Comm_GetPendingCommand(void) {
    PendingCommand_t cmd = g_pending_cmd;
    g_pending_cmd.pending = 0;
    return cmd;
}
