/**
 * @file    sensors.h
 * @brief   Sensor acquisition drivers — conductivity, load cell, temperature
 */

#ifndef __SENSORS_H
#define __SENSORS_H

#include "main.h"

/* ── Conductivity (AC excitation via DAC + ADC) ── */
void Conductivity_Init(DAC_HandleTypeDef *hdac, ADC_HandleTypeDef *hadc, TIM_HandleTypeDef *htim);
void Conductivity_StartExcitation(void);
void Conductivity_StopExcitation(void);
void Conductivity_AcquireSamples(float *buffer, uint16_t count);

/* ── Load Cell (HX711 bit-banged SPI) ── */
void LoadCell_Init(void);
int32_t LoadCell_ReadRaw(void);
uint8_t LoadCell_WaitSettled(int32_t *settled_value, uint32_t timeout_ms);
void LoadCell_AcquireBurst(int32_t *buffer, uint16_t count);

/* ── Temperature (DS18B20 1-Wire) ── */
void DS18B20_Init(void);
void DS18B20_StartConversion(void);
uint8_t DS18B20_IsConversionDone(void);
float DS18B20_ReadTemperature(void);

#endif /* __SENSORS_H */
