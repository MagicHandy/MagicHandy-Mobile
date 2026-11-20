package com.magichandy.mobile.core.ports

/** Neutral motion coordinates retained until the LinearCmd wire boundary. */
public data class LinearPoint(public val timeMillis: Long, public val position: Double) {
    init {
        require(timeMillis >= 0 && position in 0.0..100.0)
    }
}

/** The selected feature's physical resolution and its advertised minimum command spacing. */
public data class LinearSampling(
    public val gapMillis: Long,
    public val steps: Long,
    public val preferredMillis: Long = 0,
) {
    init {
        require(gapMillis in 0..5000 && steps in 1..0xffffffffL && preferredMillis in 0..5000)
    }

    public val intervalMillis: Long = maxOf(50L, gapMillis + (gapMillis / 10).coerceIn(10, 50))
}
