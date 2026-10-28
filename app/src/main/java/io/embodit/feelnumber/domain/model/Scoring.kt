package io.embodit.feelnumber.domain.model

import java.util.Locale
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

/** How the guess compares with the truth (design `guessModel.error`). Volumes, not diameters. */
sealed class Verdict {
    /** r >= 2: "x times the real thing". */
    data class TimesBigger(val x: String) : Verdict()

    /** 1 <= r < 2: "+p %". */
    data class PercentBigger(val p: Int) : Verdict()

    /** 0.5 < r < 1: "-p %". */
    data class PercentSmaller(val p: Int) : Verdict()

    /** r <= 0.5: "only 1/x of the real thing". */
    data class FractionOf(val x: String) : Verdict()
}

object Scoring {
    /** r = guessed volume / true volume. */
    fun ratio(guessVolume: Double, trueVolume: Double): Double = guessVolume / trueVolume

    /** ★★★ 0.8–1.25, ★★ 0.5–2, ★ 0.25–4, none otherwise; boundaries count to the better band. */
    fun stars(r: Double): Int =
        when {
            r >= 0.8 && r <= 1.25 -> 3
            r >= 0.5 && r <= 2.0 -> 2
            r >= 0.25 && r <= 4.0 -> 1
            else -> 0
        }

    fun verdict(r: Double): Verdict =
        when {
            r >= 2.0 -> Verdict.TimesBigger(factor(r))
            r >= 1.0 -> Verdict.PercentBigger(((r - 1.0) * 100.0).roundToInt())
            r > 0.5 -> Verdict.PercentSmaller(((1.0 - r) * 100.0).roundToInt())
            else -> Verdict.FractionOf(factor(1.0 / r))
        }

    /** X >= 10 rounds to a whole number, X < 10 keeps one decimal (521.6 -> 522, 3.72 -> 3.7). */
    fun factor(x: Double): String =
        if (x >= 9.95) String.format(Locale.US, "%d", x.roundToInt()) else String.format(Locale.US, "%.1f", x)

    /** The "26 % in size = twice the volume" line only shows when the guess is off by 2x or more. */
    fun showsLesson(r: Double): Boolean = r >= 2.0 || r <= 0.5

    /** Mean absolute log error of the best results as a percentage of volume (deck footer "average error"). */
    fun meanErrorPercent(ratios: Collection<Double>): Int? {
        if (ratios.isEmpty()) return null
        val meanLog = ratios.map { abs(ln(it)) }.average()
        return ((Math.E.pow(meanLog) - 1.0) * 100.0).roundToInt()
    }
}

/** Best result of one question (kept): stars and the ratio that gave them. */
data class Best(val stars: Int, val ratio: Double)
