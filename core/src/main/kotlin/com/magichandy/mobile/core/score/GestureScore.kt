// Ported from internal/motion/{flow,gesture}.go at 1b80d72e.
package com.magichandy.mobile.core.score

internal enum class SweepDirection {
    Even,
    Tip,
    Base,
}

/** Semantic generator parameters. A score contains no transport positions or stream timestamps. */
internal data class GestureScore(
    override val low: Int = 5,
    override val high: Int = 95,
    override val speed: Int = 25,
    val memoryCycles: Int = 8,
    val seed: UInt = 17u,
    val cycles: Int = 64,
    val focus: Int = 50,
    val width: Int = 25,
    val mix: Int = 40,
    val roam: Int = 100,
    val faster: SweepDirection = SweepDirection.Even,
    val contrast: Int = 0,
    val inertia: Int = 25,
    val rebounds: Int = 0,
    val decay: Int = 60,
    val variation: Int = 50,
) : SemanticScore {
    init {
        require(low in 0..90 && high in low + 10..100 && speed in 1..100)
        require(width in 10..high - low && cycles in 2..64 && memoryCycles in 2..32 && seed != 0u)
        require(focus in 0..100 && mix in 0..100 && roam in 0..100 && contrast in 0..80)
        require(inertia in 0..100 && rebounds in 0..4 && decay in 25..85 && variation in 0..100)
    }
}
