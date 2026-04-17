/**
 * @file    comm.h
 * @brief   USB CDC communication — packet framing, parsing, and transmission
 */

#ifndef __COMM_H
#define __COMM_H

#include "main.h"

/* ── Packet Types ── */
#define PKT_TYPE_MEASUREMENT    0x01
#define PKT_TYPE_COMMAND        0x02
#define PKT_TYPE_STATUS         0x03
#define PKT_TYPE_LABEL_TABLE    0x04
#define PKT_TYPE_ACK            0x05

/* ── Command Codes ── */
#define CMD_START_MEASUREMENT   0x01
#define CMD_SET_MODE_IDENT      0x02
#define CMD_SET_MODE_REF        0x03
#define CMD_REQUEST_EXPORT      0x04
#define CMD_REQUEST_STATUS      0x05

/* ── Packet Header ── */
#define PKT_SYNC_1              0xAA
#define PKT_SYNC_2              0x55
#define PKT_HEADER_SIZE         5       // sync(2) + type(1) + length(2)
#define PKT_MEASUREMENT_LEN     47      // payload size for measurement result

/* ── Functions ── */
void Comm_Init(void);
void Comm_SendMeasurement(const MeasurementRecord_t *record);
void Comm_SendStatus(SystemState_t state, OperatingMode_t mode,
                     uint16_t stored_records, float battery_v);
void Comm_SendLabelTable(const char **labels, uint8_t count);
void Comm_SendAck(uint8_t command_code);

/* Receive handling — called from USB CDC rx callback */
void Comm_ProcessRxByte(uint8_t byte);

/* Check for pending commands (polled in main loop) */
typedef struct {
    uint8_t command_code;
    uint8_t params[8];
    uint8_t param_len;
    uint8_t pending;
} PendingCommand_t;

PendingCommand_t Comm_GetPendingCommand(void);

#endif /* __COMM_H */
