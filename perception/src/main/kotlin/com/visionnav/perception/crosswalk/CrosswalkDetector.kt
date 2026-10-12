package com.visionnav.perception.crosswalk

import com.visionnav.core.vision.CrosswalkObservation
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Classical-CV **"possible crosswalk"** heuristic (Phase 6C). It produces single-frame *evidence*
 * only — never a confirmed crossing. Persistence over frames and fusion with mapped crossings are
 * applied downstream; the UI must word it as "possible crosswalk".
 *
 * Idea: when a pedestrian faces a zebra crossing, the painted bars appear as roughly **horizontal**
 * bright bands stacked vertically in the lower part of the upright image (a "ladder" receding into
 * the distance). Along any vertical scanline through that region the luma profile alternates
 * bright/dark with roughly regular band widths.
 *
 * Algorithm, on the lower [Config.regionFraction] of the upright luma image:
 * 1. Sample [Config.columns] vertical scanlines across the central [Config.columnSpanFraction] of
 *    the width; each profile averages ±[Config.columnHalfWidth] px horizontally, then a 3-tap
 *    vertical smoothing.
 * 2. Per scanline: require strong contrast (p80 − p20 ≥ [Config.minContrast] and
 *    (p80+1)/(p20+1) ≥ [Config.minContrastRatio]); binarise at the p20/p80 midpoint; run-length
 *    encode, absorbing runs shorter than [Config.minBandPx] (noise).
 * 3. Periodicity: among *interior* runs (not touching the region edges) require
 *    ≥ [Config.minLightBands] light bands and ≥ 2 dark gaps, coefficient of variation of light and
 *    of dark widths ≤ [Config.maxWidthCv] (perspective makes near bands thicker, so this is
 *    tolerant) and a light/dark width ratio within [Config.dutyRange].
 * 4. Cross-column consistency: consecutive passing scanlines must see the *same* bands — best
 *    agreement of their binary profiles over small vertical shifts (allowing ~20° tilt) must
 *    average ≥ [Config.minAgreement]. This rejects texture/noise that happens to pass step 3.
 * 5. Accept when ≥ [Config.minColumnFraction] of scanlines pass.
 *
 * Known false positives: railings/fence shadows, stairs, striped road paint (e.g. hatched areas).
 * Known misses: crossings seen at a strong angle, faded paint, night, very wide-angle lenses.
 *
 * Pure Kotlin, JVM-testable.
 */
class CrosswalkDetector(private val config: Config = Config()) {

    data class Config(
        val regionFraction: Float = 0.45f,
        val columns: Int = 9,
        val columnSpanFraction: Float = 0.8f,
        val columnHalfWidth: Int = 2,
        val minContrast: Float = 40f,
        val minContrastRatio: Float = 1.5f,
        val minBandPx: Int = 2,
        val minLightBands: Int = 3,
        val maxWidthCv: Float = 0.6f,
        val dutyRange: ClosedFloatingPointRange<Float> = 0.3f..3.5f,
        val minColumnFraction: Float = 0.5f,
        val minAgreement: Float = 0.75f,
        /** tan of the maximum stripe tilt tolerated between adjacent scanlines (~20°). */
        val maxTiltTan: Float = 0.36f,
        val minRegionRows: Int = 16,
    )

    /** Per-scanline analysis result (exposed for tests/debug overlay). */
    data class ColumnResult(
        val x: Int,
        val passed: Boolean,
        val lightBands: Int,
        val contrastRatio: Float,
        val widthCv: Float,
        val binary: BooleanArray?,
    )

    /**
     * @param luma upright luma image, row-major, stride == [width].
     * @return evidence, or null when the region does not look like a zebra crossing.
     */
    fun detect(luma: ByteArray, width: Int, height: Int): CrosswalkObservation? {
        val cols = analyzeColumns(luma, width, height) ?: return null
        val passing = cols.filter { it.passed }
        if (passing.size < max(2, ceil(cols.size * config.minColumnFraction).toInt())) return null

        val agreements = passing.zipWithNext { a, b ->
            val maxShift = max(1, ceil(abs(b.x - a.x) * config.maxTiltTan).toInt())
            bestAgreement(a.binary!!, b.binary!!, maxShift)
        }
        val agreement = agreements.average().toFloat()
        if (agreement < config.minAgreement) return null

        val colFraction = passing.size.toFloat() / cols.size
        val contrastScore = ((median(passing.map { it.contrastRatio }) - 1f) / 2f).coerceIn(0f, 1f)
        val regularity = (1f - passing.map { it.widthCv }.average().toFloat() / config.maxWidthCv).coerceIn(0f, 1f)
        val agreementScore = ((agreement - config.minAgreement) / (1f - config.minAgreement)).coerceIn(0f, 1f)
        val score = (0.35f * colFraction + 0.25f * contrastScore + 0.15f * regularity + 0.25f * agreementScore)
            .coerceIn(0f, 1f)
        val stripes = median(passing.map { it.lightBands.toFloat() }).toInt()
        return CrosswalkObservation(score = score, stripeCount = stripes)
    }

    /** Scanline analysis; null if the image is too small to analyse. */
    fun analyzeColumns(luma: ByteArray, width: Int, height: Int): List<ColumnResult>? {
        require(luma.size >= width * height) { "luma buffer too small" }
        val y0 = (height * (1f - config.regionFraction)).toInt().coerceIn(0, height)
        val rows = height - y0
        if (rows < config.minRegionRows || width < config.columns) return null

        val span = width * config.columnSpanFraction
        val start = (width - span) / 2f
        val profile = FloatArray(rows)
        val smooth = FloatArray(rows)
        return (0 until config.columns).map { c ->
            val x = (start + span * (c + 0.5f) / config.columns).toInt().coerceIn(0, width - 1)
            val x0 = max(0, x - config.columnHalfWidth)
            val x1 = min(width - 1, x + config.columnHalfWidth)
            for (r in 0 until rows) {
                val base = (y0 + r) * width
                var s = 0
                for (xx in x0..x1) s += luma[base + xx].toInt() and 0xFF
                profile[r] = s.toFloat() / (x1 - x0 + 1)
            }
            for (r in 0 until rows) {
                val a = profile[max(0, r - 1)]
                val b = profile[min(rows - 1, r + 1)]
                smooth[r] = (a + profile[r] + b) / 3f
            }
            analyzeProfile(x, smooth)
        }
    }

    private fun analyzeProfile(x: Int, p: FloatArray): ColumnResult {
        val sorted = p.copyOf().also { it.sort() }
        val p20 = sorted[(0.2f * (sorted.size - 1)).toInt()]
        val p80 = sorted[(0.8f * (sorted.size - 1)).toInt()]
        val ratio = (p80 + 1f) / (p20 + 1f)
        fun fail(bands: Int = 0, cv: Float = Float.NaN) = ColumnResult(x, false, bands, ratio, cv, null)
        if (p80 - p20 < config.minContrast || ratio < config.minContrastRatio) return fail()

        val thr = (p20 + p80) / 2f
        val binary = BooleanArray(p.size) { p[it] > thr }
        val runs = cleanRuns(binary)
        if (runs.size < 3) return fail()

        val interior = runs.subList(1, runs.size - 1)
        val light = interior.filter { it.light }.map { it.length.toFloat() }
        val dark = interior.filter { !it.light }.map { it.length.toFloat() }
        if (light.size < config.minLightBands || dark.size < 2) return fail(light.size)

        val cv = max(cv(light), cv(dark))
        val duty = light.average().toFloat() / dark.average().toFloat()
        if (cv > config.maxWidthCv || duty !in config.dutyRange) return fail(light.size, cv)

        // Rebuild the cleaned binary profile for cross-column comparison.
        val cleaned = BooleanArray(p.size)
        var pos = 0
        for (run in runs) {
            for (i in pos until pos + run.length) cleaned[i] = run.light
            pos += run.length
        }
        return ColumnResult(x, true, light.size, ratio, cv, cleaned)
    }

    private data class Run(val light: Boolean, var length: Int)

    /** Run-length encodes [b], absorbing runs shorter than minBandPx into their neighbours. */
    private fun cleanRuns(b: BooleanArray): List<Run> {
        val runs = ArrayList<Run>()
        for (v in b) {
            val last = runs.lastOrNull()
            if (last != null && last.light == v) last.length++ else runs += Run(v, 1)
        }
        var changed = true
        while (changed && runs.size > 2) {
            changed = false
            // Shortest interior run below the minimum gets absorbed (flipped and merged).
            var idx = -1
            for (i in 1 until runs.size - 1) {
                if (runs[i].length < config.minBandPx && (idx < 0 || runs[i].length < runs[idx].length)) idx = i
            }
            if (idx >= 0) {
                val merged = runs[idx - 1].length + runs[idx].length + runs[idx + 1].length
                runs[idx - 1].length = merged
                runs.removeAt(idx + 1)
                runs.removeAt(idx)
                changed = true
            }
        }
        return runs
    }

    private companion object {
        fun cv(v: List<Float>): Float {
            val m = v.average()
            if (m <= 0.0) return Float.MAX_VALUE
            val variance = v.sumOf { (it - m) * (it - m) } / v.size
            return (sqrt(variance) / m).toFloat()
        }

        fun median(v: List<Float>): Float {
            val s = v.sorted()
            return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2f
        }

        /** Best fraction of matching rows between [a] and [b] over vertical shifts in [-maxShift, maxShift]. */
        fun bestAgreement(a: BooleanArray, b: BooleanArray, maxShift: Int): Float {
            var best = 0f
            for (s in -maxShift..maxShift) {
                var match = 0
                var total = 0
                for (i in a.indices) {
                    val j = i + s
                    if (j < 0 || j >= b.size) continue
                    total++
                    if (a[i] == b[j]) match++
                }
                if (total > 0) best = max(best, match.toFloat() / total)
            }
            return best
        }
    }
}
