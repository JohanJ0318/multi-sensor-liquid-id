/**
 * @file    sensors.c
 * @brief   Sensor acquisition drivers — conductivity, load cell, temperature
 *
 * NOTE: These are scaffolds. Pin configs and peripheral handles come from
 *       STM32CubeMX-generated code. Fill in hardware-specific details once
 *       the PCB is assembled and connected.
 */

#include "sensors.h"
#include <math.h>

/* ══════════════════════════════════════════════════════════════════════════
 * Conductivity Channel — AC Excitation (DAC) + ADC Sampling
 * ══════════════════════════════════════════════════════════════════════════ */

static DAC_HandleTypeDef  *g_hdac  = NULL;
static ADC_HandleTypeDef  *g_hadc  = NULL;
static TIM_HandleTypeDef  *g_htim  = NULL;

/* Precomputed sine lookup table (64 points, 12-bit DAC range 0–4095) */
static const uint16_t sine_lut[SINE_LUT_SIZE] = {
    2048,2249,2447,2642,2831,3013,3185,3347,
    3496,3631,3750,3854,3940,4007,4056,4086,
    4095,4086,4056,4007,3940,3854,3750,3631,
    3496,3347,3185,3013,2831,2642,2447,2249,
    2048,1846,1648,1453,1264,1082, 910, 748,
     599, 464, 345, 241, 155,  88,  39,   9,
       0,   9,  39,  88, 155, 241, 345, 464,
     599, 748, 910,1082,1264,1453,1648,1846
};

void Conductivity_Init(DAC_HandleTypeDef *hdac, ADC_HandleTypeDef *hadc, TIM_HandleTypeDef *htim) {
    g_hdac = hdac;
    g_hadc = hadc;
    g_htim = htim;

    /* Configure timer for 1 kHz excitation:
     * Timer freq = SINE_LUT_SIZE * EXCITATION_FREQ_HZ
     * e.g., 64 * 1000 = 64 kHz update rate */
    // HAL_TIM_Base_Start(htim);
    // HAL_DAC_Start_DMA(hdac, DAC_CHANNEL_1, (uint32_t*)sine_lut, SINE_LUT_SIZE, DAC_ALIGN_12B_R);
}

void Conductivity_StartExcitation(void) {
    if (g_hdac && g_htim) {
        HAL_TIM_Base_Start(g_htim);
        HAL_DAC_Start_DMA(g_hdac, DAC_CHANNEL_1,
                          (uint32_t*)sine_lut, SINE_LUT_SIZE, DAC_ALIGN_12B_R);
    }
}

void Conductivity_StopExcitation(void) {
    if (g_hdac && g_htim) {
        HAL_DAC_Stop_DMA(g_hdac, DAC_CHANNEL_1);
        HAL_TIM_Base_Stop(g_htim);
    }
}

void Conductivity_AcquireSamples(float *buffer, uint16_t count) {
    if (!g_hadc) return;

    for (uint16_t i = 0; i < count; i++) {
        HAL_ADC_Start(g_hadc);
        if (HAL_ADC_PollForConversion(g_hadc, 10) == HAL_OK) {
            buffer[i] = (float)HAL_ADC_GetValue(g_hadc);
        } else {
            buffer[i] = 0.0f;
        }
        HAL_ADC_Stop(g_hadc);
    }
}

/* ══════════════════════════════════════════════════════════════════════════
 * Load Cell Channel — HX711 Bit-Banged Serial
 * ══════════════════════════════════════════════════════════════════════════ */

static inline void hx711_sck_high(void) {
    HAL_GPIO_WritePin(HX711_SCK_GPIO_Port, HX711_SCK_Pin, GPIO_PIN_SET);
}

static inline void hx711_sck_low(void) {
    HAL_GPIO_WritePin(HX711_SCK_GPIO_Port, HX711_SCK_Pin, GPIO_PIN_RESET);
}

static inline uint8_t hx711_dout_read(void) {
    return HAL_GPIO_ReadPin(HX711_DOUT_GPIO_Port, HX711_DOUT_Pin);
}

void LoadCell_Init(void) {
    hx711_sck_low();
    HAL_Delay(100); // Allow HX711 to stabilize
}

int32_t LoadCell_ReadRaw(void) {
    /* Wait for DOUT to go low (data ready) */
    uint32_t timeout = HAL_GetTick() + 200;
    while (hx711_dout_read() && HAL_GetTick() < timeout) {}

    /* Read 24 bits MSB-first */
    int32_t value = 0;
    for (int i = 0; i < 24; i++) {
        hx711_sck_high();
        // Brief delay (~1 µs — adjust as needed)
        volatile uint8_t dummy = 0; (void)dummy;
        value = (value << 1) | hx711_dout_read();
        hx711_sck_low();
    }

    /* 25th clock pulse: set gain to 128 (Channel A) */
    hx711_sck_high();
    volatile uint8_t d = 0; (void)d;
    hx711_sck_low();

    /* Sign-extend 24-bit to 32-bit */
    if (value & 0x800000) {
        value |= (int32_t)0xFF000000;
    }

    return value;
}

uint8_t LoadCell_WaitSettled(int32_t *settled_value, uint32_t timeout_ms) {
    uint32_t start = HAL_GetTick();
    int32_t prev = LoadCell_ReadRaw();
    uint8_t stable_count = 0;

    while ((HAL_GetTick() - start) < timeout_ms) {
        int32_t curr = LoadCell_ReadRaw();
        int32_t diff = curr - prev;
        if (diff < 0) diff = -diff;

        if (diff < SETTLING_THRESHOLD) {
            stable_count++;
            if (stable_count >= SETTLING_COUNT) {
                *settled_value = curr;
                return 1; // Settled
            }
        } else {
            stable_count = 0;
        }
        prev = curr;
    }

    *settled_value = prev;
    return 0; // Timeout — not settled
}

void LoadCell_AcquireBurst(int32_t *buffer, uint16_t count) {
    for (uint16_t i = 0; i < count; i++) {
        buffer[i] = LoadCell_ReadRaw();
    }
}

/* ══════════════════════════════════════════════════════════════════════════
 * Temperature Channel — DS18B20 1-Wire
 * ══════════════════════════════════════════════════════════════════════════ */

/* 1-Wire timing helpers (GPIO bit-banging) */
static void onewire_set_output(void) {
    GPIO_InitTypeDef gpio = {0};
    gpio.Pin  = ONEWIRE_Pin;
    gpio.Mode = GPIO_MODE_OUTPUT_OD;
    gpio.Pull = GPIO_PULLUP;
    gpio.Speed = GPIO_SPEED_FREQ_HIGH;
    HAL_GPIO_Init(ONEWIRE_GPIO_Port, &gpio);
}

static void onewire_set_input(void) {
    GPIO_InitTypeDef gpio = {0};
    gpio.Pin  = ONEWIRE_Pin;
    gpio.Mode = GPIO_MODE_INPUT;
    gpio.Pull = GPIO_PULLUP;
    HAL_GPIO_Init(ONEWIRE_GPIO_Port, &gpio);
}

static void onewire_low(void)  { HAL_GPIO_WritePin(ONEWIRE_GPIO_Port, ONEWIRE_Pin, GPIO_PIN_RESET); }
static void onewire_high(void) { HAL_GPIO_WritePin(ONEWIRE_GPIO_Port, ONEWIRE_Pin, GPIO_PIN_SET); }
static uint8_t onewire_read(void) { return HAL_GPIO_ReadPin(ONEWIRE_GPIO_Port, ONEWIRE_Pin); }

/* Microsecond delay — requires DWT cycle counter or timer */
static void delay_us(uint16_t us) {
    /* TODO: Implement using DWT->CYCCNT or a hardware timer */
    /* Placeholder using busy loop — calibrate for 170 MHz clock */
    volatile uint32_t count = us * 42; // Approximate
    while (count--) {}
}

static uint8_t onewire_reset(void) {
    onewire_set_output();
    onewire_low();
    delay_us(480);
    onewire_set_input();
    delay_us(70);
    uint8_t presence = !onewire_read();
    delay_us(410);
    return presence;
}

static void onewire_write_byte(uint8_t byte) {
    onewire_set_output();
    for (uint8_t i = 0; i < 8; i++) {
        if (byte & (1 << i)) {
            onewire_low();
            delay_us(6);
            onewire_high();
            delay_us(64);
        } else {
            onewire_low();
            delay_us(60);
            onewire_high();
            delay_us(10);
        }
    }
}

static uint8_t onewire_read_byte(void) {
    uint8_t byte = 0;
    for (uint8_t i = 0; i < 8; i++) {
        onewire_set_output();
        onewire_low();
        delay_us(6);
        onewire_set_input();
        delay_us(9);
        if (onewire_read()) {
            byte |= (1 << i);
        }
        delay_us(55);
    }
    return byte;
}

void DS18B20_Init(void) {
    onewire_reset();
}

void DS18B20_StartConversion(void) {
    onewire_reset();
    onewire_write_byte(0xCC); // Skip ROM (single device)
    onewire_write_byte(0x44); // Convert T
}

uint8_t DS18B20_IsConversionDone(void) {
    onewire_set_input();
    return onewire_read(); // Returns 1 when conversion complete
}

float DS18B20_ReadTemperature(void) {
    onewire_reset();
    onewire_write_byte(0xCC); // Skip ROM
    onewire_write_byte(0xBE); // Read Scratchpad

    uint8_t lsb = onewire_read_byte();
    uint8_t msb = onewire_read_byte();

    int16_t raw = (int16_t)((msb << 8) | lsb);
    return (float)raw * 0.0625f; // 12-bit resolution
}
