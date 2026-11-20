// Ported from internal/motion/{flow,flow_layers,flow_phrase}.go and internal/chat/layered.go at
// 1b80d72e.
package com.magichandy.mobile.core.score

internal sealed interface SemanticScore {
    val low: Int
    val high: Int
    val speed: Int
}

internal enum class LayerAxis {
    Range,
    Center,
    Pace,
}

internal enum class LayerShape {
    Wave,
    Drift,
    Alternate,
}

internal enum class RegionCycle {
    UpDown,
    DownUp,
}

internal data class FlowLayer(
    val axis: LayerAxis,
    val amount: Int,
    val period: Int,
    val phase: Int = 0,
    val shape: LayerShape = LayerShape.Wave,
    val lowRate: Int = 0,
    val inverted: Boolean = false,
) {
    init {
        require(amount in 0..100 && period in 2..32 && phase in 0..100 && lowRate in 0..100)
    }
}

internal data class PhraseGroup(
    val bottom: Int,
    val top: Int,
    val count: Int,
    val rate: Int,
    val cycle: RegionCycle? = null,
) {
    init {
        require(bottom in 0..90 && top in bottom + 10..100 && count in 1..8 && rate in 1..100)
    }
}

internal data class LayeredScore(
    override val low: Int = 5,
    override val high: Int = 95,
    override val speed: Int = 25,
    val floor: Int = 20,
    val ceiling: Int = 0,
    val anchor: Int = 50,
    val memory: Int = 10,
    val paceVariation: Int = 12,
    val drift: Boolean = true,
    val softness: Int = 0,
    val cadence: Int = 0,
    val seed: UInt = 17u,
    val upRate: Int = 100,
    val downRate: Int = 100,
    val cycles: Int = 64,
    val layers: List<FlowLayer> =
        listOf(
            FlowLayer(LayerAxis.Center, 35, 14, 17, LayerShape.Drift),
            FlowLayer(LayerAxis.Pace, 20, 22, 53, LayerShape.Drift),
        ),
    val groups: List<PhraseGroup> = emptyList(),
) : SemanticScore {
    init {
        require(low in 0..90 && high in low + 10..100 && speed in 1..100 && seed != 0u)
        require(floor in 10..high - low && (ceiling == 0 || ceiling in floor..high - low))
        require(
            anchor in 0..100 &&
                memory in 2..32 &&
                paceVariation in 0..40 &&
                softness in 0..100 &&
                cadence in 0..100
        )
        require(upRate in 1..100 && downRate in 1..100 && cycles in 2..128)
        require(layers.size <= 3 && layers.distinctBy { it.axis }.size == layers.size)
        if (groups.isNotEmpty()) {
            require(
                groups.size <= 6 &&
                    groups.sumOf { it.count } <= 24 &&
                    layers.all { it.axis == LayerAxis.Pace }
            )
            require(groups.minOf { it.bottom } == low && groups.maxOf { it.top } == high)
            require(groups.all { it.cycle != null } || groups.all { it.cycle == null })
            if (groups.first().cycle == null)
                require(
                    groups.indices.all { i ->
                        groups[(i + groups.size - 1) % groups.size].top - groups[i].bottom >= 10
                    }
                )
        }
    }
}

internal fun SemanticScore.atSpeed(pace: Int): SemanticScore =
    when (this) {
        is GestureScore -> copy(speed = pace)
        is LayeredScore -> copy(speed = pace)
    }

internal fun SemanticScore.holding(speed: Int): LayeredScore {
    val span = high - low
    val width = if (this is GestureScore) span else minOf(span, maxOf(20, span / 2))
    val low = ((low + high) / 2 - width / 2).coerceIn(low, high - width)
    return LayeredScore(
        low,
        low + width,
        kotlin.math.floor(speed * 0.4 + 0.5).toInt().coerceIn(1, 20),
        width,
        width,
        paceVariation = 0,
        layers = emptyList(),
    )
}
