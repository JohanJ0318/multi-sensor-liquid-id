/**
 * @file    datalog.c
 * @brief   Flash-based circular buffer for measurement record storage
 *
 * Stores MeasurementRecord_t structs in a circular buffer within the
 * STM32G431's internal flash. Supports write, indexed read, and bulk
 * export over USB.
 */

#include "datalog.h"
#include "comm.h"
#include <string.h>

/* ── Storage ── */
static MeasurementRecord_t g_log[DATALOG_MAX_RECORDS];
static uint16_t g_write_idx = 0;
static uint16_t g_count     = 0;

void DataLog_Init(void) {
    memset(g_log, 0, sizeof(g_log));
    g_write_idx = 0;
    g_count     = 0;
}

uint8_t DataLog_Write(const MeasurementRecord_t *record) {
    if (record == NULL) return 0;

    memcpy(&g_log[g_write_idx], record, sizeof(MeasurementRecord_t));
    g_write_idx = (g_write_idx + 1) % DATALOG_MAX_RECORDS;

    if (g_count < DATALOG_MAX_RECORDS) {
        g_count++;
    }

    return 1;
}

uint8_t DataLog_Read(uint16_t index, MeasurementRecord_t *record) {
    if (index >= g_count || record == NULL) return 0;

    uint16_t actual_idx;
    if (g_count < DATALOG_MAX_RECORDS) {
        actual_idx = index;
    } else {
        actual_idx = (g_write_idx + index) % DATALOG_MAX_RECORDS;
    }

    memcpy(record, &g_log[actual_idx], sizeof(MeasurementRecord_t));
    return 1;
}

uint16_t DataLog_GetCount(void) {
    return g_count;
}

void DataLog_Clear(void) {
    g_write_idx = 0;
    g_count     = 0;
}

void DataLog_ExportAll(void) {
    MeasurementRecord_t record;

    for (uint16_t i = 0; i < g_count; i++) {
        if (DataLog_Read(i, &record)) {
            /* Set MSB of record_number to mark as historical */
            record.record_number |= 0x80000000;
            Comm_SendMeasurement(&record);

            /* Small delay to avoid USB buffer overflow */
            HAL_Delay(5);
        }
    }
}
