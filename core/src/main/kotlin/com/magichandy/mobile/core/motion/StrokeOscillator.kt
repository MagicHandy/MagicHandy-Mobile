// Polynomial coefficients from Stephen L. Moshier's Cephes library and Go src/math/sin.go.
package com.magichandy.mobile.core.motion

import kotlin.math.PI
import kotlin.math.abs

/**
 * Deterministic octant reduction for bounded score phases. One ULP can break a symmetric fitter
 * tie.
 */
internal object StrokeOscillator {
    private val sine =
        doubleArrayOf(
            1.58962301576546568060e-10,
            -2.50507477628578072866e-8,
            2.75573136213857245213e-6,
            -1.98412698295895385996e-4,
            8.33333333332211858878e-3,
            -1.66666666666666307295e-1,
        )
    private val cosine =
        doubleArrayOf(
            -1.13585365213876817300e-11,
            2.08757008419747316778e-9,
            -2.75573141792967388112e-7,
            2.48015872888517045348e-5,
            -1.38888888888730564116e-3,
            4.16666666666665929218e-2,
        )

    fun sin(angle: Double) = evaluate(angle, false)

    fun cos(angle: Double) = evaluate(angle, true)

    private fun evaluate(angle: Double, cos: Boolean): Double {
        val x = abs(angle)
        val octant = (x * (4 / PI)).toInt().let { it + (it and 1) }
        val z =
            ((x - octant * 0.785398125648498535156) - octant * 3.77489470793079817668e-8) -
                octant * 2.69515142907905952645e-15
        val q = octant and 7
        val useCos = cos xor ((q and 3) == 2)
        val coeff = if (useCos) cosine else sine
        val z2 = z * z
        var polynomial = coeff[0]
        for (i in 1 until coeff.size) polynomial = polynomial * z2 + coeff[i]
        val value = if (useCos) 1 - 0.5 * z2 + z2 * z2 * polynomial else z + z * z2 * polynomial
        val negative = (q >= 4) xor (cos && (q and 3) == 2) xor (!cos && angle < 0)
        return if (negative) -value else value
    }
}
