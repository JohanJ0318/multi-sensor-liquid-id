package com.group17.liquidsensor.data

import kotlin.random.Random

/**
 * Generates realistic fake [MeasurementRecord] objects for demo / UI testing
 * when the physical STM32 device is not connected.
 *
 * Cycles through three easy-to-source household liquids, adding ±3 % noise
 * to each value. Every ~5th cycle the two models "disagree" so the Model
 * Comparison screen shows both the agreement and disagreement states.
 */
object DemoDataGenerator {

    // ── Known substance profiles ──────────────────────────────────────────────

    private data class Profile(
        val name: String,
        val conductivity: Float,   // µS/cm
        val density: Float,        // g/mL
        val temperature: Float     // °C
    )

    private val profiles = listOf(
        // Order MUST match model_params.h class indices (alphabetical labels).
        // conductivity = real measured µS/cm (2026-07-18 collection).
        // density = accurate mass of a 100 mL pour in grams (density g/mL × 100),
        // matching the firmware's clamp centers in Mass_ConditionForDemo (main.c);
        // keep the two in sync so a live sample lands on its cluster center.
        Profile("Coke",            conductivity = 19000f, density = 104f, temperature = 21.0f),
        Profile("Distilled Water", conductivity = 10f,    density = 100f, temperature = 20.3f),
        Profile("Glycerin",        conductivity = 1f,     density = 126f, temperature = 20.4f),
        Profile("Isopropyl",       conductivity = 2f,     density = 81f,  temperature = 20.0f),
        Profile("Salt Water",      conductivity = 1080f,  density = 103f, temperature = 20.8f),
        Profile("Tap Water",       conductivity = 215f,   density = 100f, temperature = 21.0f),
    )

    /** Reference centroid data exposed for the Feature Space scatter chart. */
    data class SubstanceCluster(
        val index: Int,
        val name: String,
        val conductivity: Float,  // µS/cm centroid
        val density: Float        // g/mL centroid
    )

    val clusters: List<SubstanceCluster> =
        profiles.mapIndexed { i, p -> SubstanceCluster(i, p.name, p.conductivity, p.density) }

    /** Maps substance index → name; inject into DeviceState when demo starts. */
    val substanceLabels: Map<Int, String> =
        profiles.mapIndexed { i, p -> i to p.name }.toMap()

    // ── State ─────────────────────────────────────────────────────────────────

    private var substanceIndex = 0
    private var recordNumber   = 0L

    // ── Public API ────────────────────────────────────────────────────────────

    /** Produce the next fake record and advance the internal cycle. */
    fun nextRecord(): MeasurementRecord {
        val idx     = substanceIndex
        val profile = profiles[idx]
        substanceIndex = (substanceIndex + 1) % profiles.size
        recordNumber++

        val cond = profile.conductivity.noisy(0.03f)
        val dens = profile.density.noisy(0.005f)
        val temp = profile.temperature.noisy(0.01f)

        // Models agree ~80 % of the time; occasionally disagree for demo variety
        val knnIdx = idx
        val ncIdx  = if (recordNumber % 5 == 0L) (idx + 1) % profiles.size else idx
        val knnConf = Random.nextFloat() * 0.18f + 0.80f   // 0.80 – 0.98
        val ncConf  = Random.nextFloat() * 0.22f + 0.70f   // 0.70 – 0.92

        return MeasurementRecord(
            recordNumber        = recordNumber,
            timestamp           = System.currentTimeMillis(),
            isHistorical        = false,
            conductivityRaw     = cond,           // already µS/cm
            densityRaw          = dens * 1000f,   // raw in mg/mL
            temperatureRaw      = temp,
            conductivityFeature = cond,
            densityFeature      = dens,
            temperatureFeature  = temp,
            validityStatus      = ValidityStatus.OK,
            knnPredictionIndex  = knnIdx,
            knnConfidence       = knnConf,
            ncPredictionIndex   = ncIdx,
            ncConfidence        = ncConf,
            knnPredictionName   = profiles[knnIdx].name,
            ncPredictionName    = profiles[ncIdx].name
        )
    }

    /** Reset cycle state (call when demo mode is stopped and restarted). */
    fun reset() {
        substanceIndex = 0
        recordNumber   = 0L
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private fun Float.noisy(fraction: Float): Float =
        this * (1f + (Random.nextFloat() - 0.5f) * 2f * fraction)
}
