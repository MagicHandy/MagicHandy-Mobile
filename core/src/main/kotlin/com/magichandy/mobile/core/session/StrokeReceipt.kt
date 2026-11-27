package com.magichandy.mobile.core.session

import com.magichandy.mobile.core.api.CommandReceipt
import com.magichandy.mobile.core.api.ControlKind
import com.magichandy.mobile.core.api.Limits
import com.magichandy.mobile.core.api.StrokeGroupReceipt
import com.magichandy.mobile.core.api.StrokePatternReceipt
import com.magichandy.mobile.core.api.ValueChange
import com.magichandy.mobile.core.score.LayeredScore
import com.magichandy.mobile.core.score.SemanticScore
import kotlin.math.roundToInt

/** Describe the accepted score. An identical pace never stands in for a change of geometry. */
internal fun SemanticScore.strokeReceipt(
    previous: SemanticScore?,
    previousPace: Int?,
    limits: Limits,
): CommandReceipt? {
    val pace = minOf(speed, limits.topSpeed.value)
    val changes =
        if (previousPace == pace) emptyList()
        else listOf(ValueChange(ControlKind.Pace, previousPace, pace))
    val pattern = patternReceipt(limits).takeUnless { it == previous?.patternReceipt(limits) }
    return if (changes.isEmpty() && pattern == null) null else CommandReceipt(changes, pattern)
}

private fun SemanticScore.patternReceipt(limits: Limits): StrokePatternReceipt {
    fun mark(position: Int) =
        limits.strokeLow.value +
            ((limits.strokeHigh.value - limits.strokeLow.value) * position / 100.0).roundToInt()
    val low = mark(this.low)
    val high = mark(this.high)
    val groups =
        when {
            this !is LayeredScore -> emptyList()
            this.groups.isNotEmpty() ->
                this.groups.map { StrokeGroupReceipt(it.count, mark(it.bottom), mark(it.top)) }
            floor == this.high - this.low -> listOf(StrokeGroupReceipt(1, low, high))
            else -> emptyList()
        }
    return StrokePatternReceipt(low, high, groups)
}
