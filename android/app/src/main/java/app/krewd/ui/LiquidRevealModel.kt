package app.krewd.ui

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Tuning for the liquid swipe actions. Same physics as the Meniscus nav bar (`io.github.zainbits.meniscus`,
 * whose engine is internal), retuned for drops that leave a sliding card. Plain numbers only, so the JVM tests
 * run the model without Android.
 */
internal object LiquidMotion {
    /** Fixed 500 Hz integration step, so 60, 90 and 120 Hz screens trace the same motion. */
    const val SUBSTEP_NANOS = 2_000_000L
    const val MAX_FRAME_NANOS = 64_000_000L

    const val EDGE_STIFFNESS = 520f
    const val EDGE_DAMPING_RATIO = 0.5f
    const val NECK_STIFFNESS = 340f
    const val NECK_DAMPING_RATIO = 0.9f
    const val PINCH_STIFFNESS = 3_200f
    const val PINCH_DAMPING_RATIO = 1f
    const val PINCH_TARGET_FRACTION = -0.4f

    /** A stretched neck snaps at this fraction of the resting gap; two drops start a bridge at [JOIN_GAP_FRACTION]. */
    const val BREAK_GAP_FRACTION = 0.6f
    const val JOIN_GAP_FRACTION = 0.85f

    /** A boundary wants to be open once its target gap passes this fraction of the resting gap. */
    const val OPEN_GAP_FRACTION = 0.5f

    /** How far an intact neck holds the facing edges together, as a fraction of the resting gap. */
    const val PULL_GAP_FRACTION = 0.35f
    const val JOIN_NECK_VELOCITY = 10f

    /** Shares of an overdrag that open the Pin–Delete and card–Pin gaps; the rest moves both drops with the card. */
    const val OVERDRAG_SPLIT = 0.2f
    const val OVERDRAG_DETACH = 0.35f

    const val REST_POSITION_PX = 0.25f
    const val REST_VELOCITY_PX = 4f
    const val FLAT_NECK_EPS_PX = 0.5f
    const val MAX_FILLET_RADIUS_PX = 20_000f
}

/** Exact one-step propagator of a unit-mass damped spring (see Meniscus' `SpringStep`). */
internal class LiquidSpring(stiffness: Float, dampingRatio: Float, stepSeconds: Double) {
    private val a11: Float
    private val a12: Float
    private val a21: Float
    private val a22: Float

    init {
        val w = sqrt(stiffness.toDouble())
        val z = dampingRatio.toDouble()
        val h = stepSeconds
        val b: DoubleArray = if (z < 1.0) {
            val wd = w * sqrt(1.0 - z * z)
            val e = exp(-z * w * h)
            val c = cos(wd * h)
            val s = sin(wd * h)
            doubleArrayOf(e * (c + z * w * s / wd), e * s / wd, -e * w * w * s / wd, e * (c - z * w * s / wd))
        } else if (z == 1.0) {
            val e = exp(-w * h)
            doubleArrayOf(e * (1.0 + w * h), e * h, -e * w * w * h, e * (1.0 - w * h))
        } else {
            val q = w * sqrt(z * z - 1.0)
            val r1 = -z * w + q
            val r2 = -z * w - q
            val e1 = exp(r1 * h)
            val e2 = exp(r2 * h)
            val dr = r1 - r2
            doubleArrayOf((r1 * e2 - r2 * e1) / dr, (e1 - e2) / dr, r1 * r2 * (e2 - e1) / dr, (r1 * e1 - r2 * e2) / dr)
        }
        a11 = b[0].toFloat()
        a12 = b[1].toFloat()
        a21 = b[2].toFloat()
        a22 = b[3].toFloat()
    }

    fun advance(x: FloatArray, v: FloatArray, i: Int, target: Float) {
        val d = x[i] - target
        val vi = v[i]
        x[i] = target + a11 * d + a12 * vi
        v[i] = a21 * d + a22 * vi
    }
}

/**
 * Three pills on one horizontal axis, in pixels from the row's left edge: slot 0 is the card's liquid end (its
 * right edge is the card's right edge, which follows the finger), slots 1 and 2 are the Pin and Delete drops.
 * Closed, the drops sit squashed and fused under the card's end. As the card slides left they swell out of it,
 * stretch on a capillary neck and pinch off (Delete first, then Pin); closing merges them back.
 * Drop edges and necks are springs; bonds with hysteresis decide when a neck snaps or a new bridge forms.
 */
internal class LiquidRevealModel {
    private val step = LiquidMotion.SUBSTEP_NANOS / 1e9
    private val edgeSpring = LiquidSpring(LiquidMotion.EDGE_STIFFNESS, LiquidMotion.EDGE_DAMPING_RATIO, step)
    private val neckSpring = LiquidSpring(LiquidMotion.NECK_STIFFNESS, LiquidMotion.NECK_DAMPING_RATIO, step)
    private val pinchSpring = LiquidSpring(LiquidMotion.PINCH_STIFFNESS, LiquidMotion.PINCH_DAMPING_RATIO, step)

    var width = 0f
        private set

    /** Cap radius of every pill (half the drop diameter). */
    var radius = 0f
        private set
    private var gap = 0f

    /** The card's right edge. */
    var edge = 0f
        private set

    private val targetLeft = FloatArray(SLOTS)
    private val targetRight = FloatArray(SLOTS)
    private val left = FloatArray(SLOTS)
    private val leftV = FloatArray(SLOTS)
    private val right = FloatArray(SLOTS)
    private val rightV = FloatArray(SLOTS)
    private val neck = FloatArray(BOUNDARIES)
    private val neckV = FloatArray(BOUNDARIES)
    private val bonded = BooleanArray(BOUNDARIES)
    private val open = BooleanArray(BOUNDARIES)
    private var pendingNanos = 0L

    /** Grows by one each time a neck snaps; the row turns it into a haptic tick. */
    var pinchCount = 0
        private set

    var isAtRest = true
        private set

    /** How far the card slides to show both drops at rest: margin + two drops + two gaps. */
    val revealWidth: Float get() = 4f * radius + 3f * gap

    /** Sets the geometry and snaps to the closed layout if it changed. */
    fun configure(widthPx: Float, dropDiameterPx: Float, gapPx: Float) {
        val r = dropDiameterPx * 0.5f
        if (widthPx == width && r == radius && gapPx == gap) return
        width = widthPx
        radius = r
        gap = gapPx
        edge = widthPx
        layoutTargets()
        snapToTarget()
    }

    /** Moves the card's right edge; the drops follow on the next [step]. */
    fun setEdge(edgePx: Float) {
        if (edgePx == edge || width == 0f) return
        edge = edgePx
        layoutTargets()
        isAtRest = false
    }

    fun snapToTarget() {
        for (i in 0 until SLOTS) {
            left[i] = targetLeft[i]
            right[i] = targetRight[i]
            leftV[i] = 0f
            rightV[i] = 0f
        }
        for (j in 0 until BOUNDARIES) {
            bonded[j] = !open[j]
            neck[j] = if (bonded[j]) radius else LiquidMotion.PINCH_TARGET_FRACTION * radius
            neckV[j] = 0f
        }
        pendingNanos = 0L
        isAtRest = true
    }

    /** Advances by one display frame. Returns true while still moving. */
    fun step(frameNanos: Long): Boolean {
        if (isAtRest) return false
        pendingNanos += frameNanos.coerceIn(0L, LiquidMotion.MAX_FRAME_NANOS)
        while (pendingNanos >= LiquidMotion.SUBSTEP_NANOS) {
            substep()
            pendingNanos -= LiquidMotion.SUBSTEP_NANOS
        }
        if (isSettled()) {
            for (i in 1 until SLOTS) {
                left[i] = targetLeft[i]
                right[i] = targetRight[i]
                leftV[i] = 0f
                rightV[i] = 0f
            }
            isAtRest = true
        }
        return !isAtRest
    }

    fun isBonded(j: Int): Boolean = bonded[j]
    fun neck(j: Int): Float = neck[j]
    fun center(i: Int): Float = (left[i] + right[i]) * 0.5f
    fun targetCenter(i: Int): Float = (targetLeft[i] + targetRight[i]) * 0.5f

    /** Share (0..1) of drop [i]'s diameter that shows beyond the card's edge. */
    fun exposure(i: Int): Float {
        if (radius <= 0f) return 0f
        val l = max(renderedLeft(i), edge)
        return ((renderedRight(i) - l) / (2f * radius)).coerceIn(0f, 1f)
    }

    /** Whether anything shows beyond the card. */
    val isVisible: Boolean get() = renderedRight(SLOTS - 1) > edge + 0.5f || !isAtRest

    /** Rendered pills, 2 floats each: left, right (a drop never draws narrower than a circle). */
    fun fillSlots(out: FloatArray) {
        for (i in 0 until SLOTS) {
            out[i * 2] = renderedLeft(i)
            out[i * 2 + 1] = renderedRight(i)
        }
    }

    /** Colour blend half-width at each junction: the neck, or half the gap once pinched. */
    fun fillBlends(out: FloatArray) {
        for (j in 0 until BOUNDARIES) {
            val halfGap = (renderedLeft(j + 1) - renderedRight(j)) * 0.5f
            out[j] = max(max(halfGap, neck[j].coerceIn(0f, radius)), 0.5f)
        }
    }

    /** Rendered bridges, 4 floats each: see [bridgeGeometry]. */
    fun fillBridges(out: FloatArray) {
        for (j in 0 until BOUNDARIES) {
            val n = neck[j]
            bridgeGeometry(
                capA = renderedRight(j) - radius,
                capB = renderedLeft(j + 1) + radius,
                r = radius,
                neck = if (n > 0f) min(n, radius) else -1f,
                out = out,
                offset = j * 4,
            )
        }
    }

    private fun renderedLeft(i: Int): Float = min(left[i], center(i) - radius)
    private fun renderedRight(i: Int): Float = max(right[i], center(i) + radius)

    private fun layoutTargets() {
        val d = 2f * radius
        val g = gap
        val limit = width - g
        val space = max(limit - edge, 0f)
        // The space beyond the card fills in order: both drops grow to full size (fused), then Delete parts from
        // Pin, then Pin parts from the card. A gap only opens between full-size drops, so each neck snaps once.
        val w = min(space * 0.5f, d)
        // Pulled past the full reveal, the drops trail the card part of the way and spread out, like stretched
        // elastic; the springs make them lag and wobble, and they ease back when the card settles.
        val over = max(space - 2f * d - 2f * g, 0f)
        val split = (space - 2f * d).coerceIn(0f, g) + LiquidMotion.OVERDRAG_SPLIT * over
        val detach = max(space - 2f * d - g, 0f).coerceAtMost(g) + LiquidMotion.OVERDRAG_DETACH * over
        val trail = over - (LiquidMotion.OVERDRAG_SPLIT + LiquidMotion.OVERDRAG_DETACH) * over
        // The card's liquid end: its left side hides under the card, its right cap ends at the card's edge.
        targetLeft[0] = min(0f, edge - d)
        targetRight[0] = edge
        // A squashed drop still draws as a full circle, so shift it left until that circle stays under the card.
        val shift = (d - w) * 0.5f
        targetRight[2] = limit - shift - trail
        targetLeft[2] = targetRight[2] - w
        targetRight[1] = targetLeft[2] - split
        targetLeft[1] = targetRight[1] - w
        left[0] = targetLeft[0]
        right[0] = targetRight[0]
        open[0] = detach >= LiquidMotion.OPEN_GAP_FRACTION * g
        open[1] = split >= LiquidMotion.OPEN_GAP_FRACTION * g
    }

    private fun substep() {
        val pull = LiquidMotion.PULL_GAP_FRACTION * gap
        val breakGap = LiquidMotion.BREAK_GAP_FRACTION * gap
        val joinGap = LiquidMotion.JOIN_GAP_FRACTION * gap

        for (j in 0 until BOUNDARIES) {
            val g = left[j + 1] - right[j]
            if (bonded[j] && open[j] && (g >= breakGap || neck[j] <= 0f)) {
                bonded[j] = false
                pinchCount++
            } else if (!bonded[j] && !open[j] && g <= joinGap) {
                bonded[j] = true
                neck[j] = max(neck[j], 0f)
                neckV[j] = max(neckV[j], LiquidMotion.JOIN_NECK_VELOCITY * radius)
            }
        }

        for (i in 1 until SLOTS) {
            var tl = targetLeft[i]
            var tr = targetRight[i]
            // An intact neck holds the facing edges together, so a drop stretches before it lets go.
            // The card's end follows the finger, so the drop next to it takes the whole pull.
            if (bonded[i - 1] && open[i - 1]) tl -= if (i == 1) pull else pull * 0.5f
            if (i < SLOTS - 1 && bonded[i] && open[i]) tr += pull * 0.5f
            edgeSpring.advance(left, leftV, i, tl)
            edgeSpring.advance(right, rightV, i, tr)
        }

        for (j in 0 until BOUNDARIES) {
            if (bonded[j]) neckSpring.advance(neck, neckV, j, neckTarget(j, breakGap))
            else pinchSpring.advance(neck, neckV, j, LiquidMotion.PINCH_TARGET_FRACTION * radius)
        }
    }

    private fun neckTarget(j: Int, breakGap: Float): Float =
        if (open[j]) radius * (1f - (left[j + 1] - right[j]) / breakGap).coerceIn(0f, 1f) else radius

    private fun isSettled(): Boolean {
        val pe = LiquidMotion.REST_POSITION_PX
        val ve = LiquidMotion.REST_VELOCITY_PX
        for (i in 1 until SLOTS) {
            if (abs(left[i] - targetLeft[i]) > pe || abs(leftV[i]) > ve) return false
            if (abs(right[i] - targetRight[i]) > pe || abs(rightV[i]) > ve) return false
        }
        val breakGap = LiquidMotion.BREAK_GAP_FRACTION * gap
        for (j in 0 until BOUNDARIES) {
            if (bonded[j]) {
                // A held, half-stretched neck may rest too; only a moving one keeps the frames coming.
                if (abs(neck[j] - neckTarget(j, breakGap)) > pe || abs(neckV[j]) > ve) return false
            } else if (neck[j] > 0f) {
                return false
            }
        }
        return true
    }

    companion object {
        const val SLOTS = 3
        const val BOUNDARIES = SLOTS - 1

        /**
         * Capillary bridge between facing semicircular caps of radius [r] centred at x = [capA] and [capB]: a box
         * minus two circular fillets tangent to both caps, leaving a neck of half-height [neck] at the midpoint.
         * Writes (midX, halfWidth, filletRadius, neck); neck < 0 means no bridge, filletRadius 0 means a flat box.
         * Overlapping caps that are still bonded get a flat box between their centres, which fills the notch.
         */
        fun bridgeGeometry(capA: Float, capB: Float, r: Float, neck: Float, out: FloatArray, offset: Int) {
            val half = (capB - capA) * 0.5f
            out[offset] = (capA + capB) * 0.5f
            if (neck <= 0f) {
                none(out, offset)
                return
            }
            if (half <= 0f) {
                flat(-half, r, out, offset)
                return
            }
            if (r - neck <= LiquidMotion.FLAT_NECK_EPS_PX) {
                flat(half, r, out, offset)
                return
            }
            // Tangency: half² + (neck + k)² = (r + k)²  ⇒  k = (half² + neck² − r²) / (2 (r − neck)).
            val k = (half * half + neck * neck - r * r) / (2f * (r - neck))
            if (k <= 0f) {
                flat(half, r, out, offset)
                return
            }
            if (k > LiquidMotion.MAX_FILLET_RADIUS_PX) {
                flat(half, r, out, offset)
                return
            }
            out[offset + 1] = half * k / (r + k)
            out[offset + 2] = k
            out[offset + 3] = neck
        }

        private fun none(out: FloatArray, offset: Int) {
            out[offset + 1] = 0f
            out[offset + 2] = 0f
            out[offset + 3] = -1f
        }

        private fun flat(half: Float, r: Float, out: FloatArray, offset: Int) {
            out[offset + 1] = half
            out[offset + 2] = 0f
            out[offset + 3] = r
        }
    }
}
