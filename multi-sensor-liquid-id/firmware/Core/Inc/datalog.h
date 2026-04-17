/**
 * @file    datalog.h
 * @brief   Flash-based circular buffer for measurement record storage
 */

#ifndef __DATALOG_H
#define __DATALOG_H

#include "main.h"

#define DATALOG_MAX_RECORDS     256

void DataLog_Init(void);
uint8_t DataLog_Write(const MeasurementRecord_t *record);
uint8_t DataLog_Read(uint16_t index, MeasurementRecord_t *record);
uint16_t DataLog_GetCount(void);
void DataLog_Clear(void);

/* Export all records over USB (called when CMD_REQUEST_EXPORT received) */
void DataLog_ExportAll(void);

#endif /* __DATALOG_H */
