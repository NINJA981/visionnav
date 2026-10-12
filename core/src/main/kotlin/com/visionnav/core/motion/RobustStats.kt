package com.visionnav.core.motion

import kotlin.math.abs

/** Robust statistics used by motion estimation. All functions are pure. */
object RobustStats {

    /** Median of [values]; null for an empty list. Even-sized lists average the two middle values. */
    fun median(values: List<Double>): Double? {
        if (values.isEmpty()) return null
        val s = values.sorted()
        val m = s.size / 2
        return if (s.size % 2 == 1) s[m] else (s[m - 1] + s[m]) / 2.0
    }

    /**
     * Theil–Sen line fit: slope = median of all pairwise slopes (pairs with equal x are skipped),
     * intercept = median of `y - slope·x`. Tolerates up to ~29 % outliers, which matters because a
     * single bad detector box must not flip a lateral-motion verdict. O(n²); n ≤ ~30 here.
     *
     * @return null if fewer than two distinct x values exist.
     */
    fun theilSen(xs: DoubleArray, ys: DoubleArray): Fit? {
        require(xs.size == ys.size) { "xs and ys must have the same size" }
        val slopes = ArrayList<Double>(xs.size * (xs.size - 1) / 2)
        for (i in xs.indices) {
            for (j in i + 1 until xs.size) {
                val dx = xs[j] - xs[i]
                if (dx != 0.0) slopes += (ys[j] - ys[i]) / dx
            }
        }
        val slope = median(slopes) ?: return null
        val intercept = median(xs.indices.map { ys[it] - slope * xs[it] })!!
        val residualMad = median(xs.indices.map { abs(ys[it] - (slope * xs[it] + intercept)) })!!
        return Fit(slope, intercept, residualMad)
    }

    /**
     * @param residualMad median absolute residual around the fitted line (robust spread).
     */
    data class Fit(val slope: Double, val intercept: Double, val residualMad: Double)

    /** Counts of step-wise changes above a deadband. */
    data class StepSigns(val positive: Int, val negative: Int) {
        val moving: Int get() = positive + negative

        /** Fraction of moving steps that are positive; 0 when nothing moved. */
        val positiveFraction: Double get() = if (moving == 0) 0.0 else positive.toDouble() / moving

        /** Fraction of moving steps that are negative; 0 when nothing moved. */
        val negativeFraction: Double get() = if (moving == 0) 0.0 else negative.toDouble() / moving

        /** Fraction of moving steps agreeing with the sign of [direction]; 0 if direction is 0. */
        fun agreementWith(direction: Double): Double = when {
            direction > 0 -> positiveFraction
            direction < 0 -> negativeFraction
            else -> 0.0
        }
    }

    /** Signs of consecutive differences of [ys], ignoring steps with |Δ| ≤ [deadband]. */
    fun stepSigns(ys: DoubleArray, deadband: Double): StepSigns {
        var pos = 0
        var neg = 0
        for (i in 1 until ys.size) {
            val d = ys[i] - ys[i - 1]
            if (d > deadband) pos++ else if (d < -deadband) neg++
        }
        return StepSigns(pos, neg)
    }
}
