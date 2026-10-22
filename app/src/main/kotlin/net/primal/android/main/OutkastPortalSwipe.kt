package net.primal.android.main

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/**
 * How far a finger has to travel along the portal for the next world to come up. It is deliberately
 * short, about a third of the portal's own width: the point of dragging on the portal is to cross
 * several worlds with one stroke, so a full-width stroke has to cover all of them.
 */
private val PORTAL_SCRUB_STEP_DISTANCE = 36.dp

/** An upward drag on the portal has to travel this far before it opens Orbit. */
private val PORTAL_OPEN_ORBIT_DISTANCE = 32.dp

/** Which way a drag that started on the portal turned out to go. */
private enum class PortalAxis { Sideways, Vertical }

/** The axis a drag was decided to follow, with how far the finger had travelled when it was decided. */
private class PortalClaim(val axis: PortalAxis, val travelledX: Float, val travelledY: Float)

/** What the gesture needs to know about the screen, read when it needs it so it is never stale. */
private class PortalGestureConfig(
    val activeIndex: () -> Int,
    val lastIndex: Int,
    val stepPx: Float,
    val openOrbitPx: Float,
    val onScrubToWorld: (index: Int) -> Unit,
    val onOpenOrbit: () -> Unit,
)

/**
 * The gesture of the portal, the small pill at the bottom of the screen that opens Orbit:
 *
 * - dragging sideways along it scrubs through the worlds, live and without lifting the finger:
 *   every [PORTAL_SCRUB_STEP_DISTANCE] of travel moves one world, so one long stroke crosses all of
 *   them. The direction is the one of the swipe on the content: the finger moving left goes to the
 *   next world, right to the previous one.
 * - dragging up opens Orbit, as it always did (any other vertical drag is swallowed, so it does not
 *   open Orbit through the click either).
 * - a tap is not handled here; it stays with the click of the portal.
 *
 * [onScrubToWorld] receives the index (into the list of worlds) to show, and is called only when
 * that index changes. It is an absolute position taken from the start of the stroke, not a step
 * from the world on screen, so a fast stroke that crosses two thresholds between two frames, or a
 * call that lands before the screen has recomposed, cannot make it overshoot or lose a world.
 * [activeIndex] is the index showing when the stroke starts.
 *
 * The events are followed in the initial pass, before the portal's click sees them, and are
 * consumed once the stroke has been recognised. That is what cancels the click: the portal is
 * small, so a short stroke can end inside it and would otherwise also open Orbit on release.
 *
 * While a gesture that started on the portal is in progress, [gestureActive] is set, so the swipe
 * that works from anywhere on the screen leaves it alone (see rememberOutkastPageSwipeModifier).
 */
@Composable
internal fun rememberOutkastPortalSwipeModifier(
    activeIndex: Int,
    lastIndex: Int,
    gestureActive: MutableState<Boolean>,
    onScrubToWorld: (index: Int) -> Unit,
    onOpenOrbit: () -> Unit,
): Modifier {
    val density = LocalDensity.current
    // Read through State so the gesture coroutine, started once, sees the latest values (see
    // rememberOutkastEdgeSwipeModifier).
    val latestActiveIndex by rememberUpdatedState(activeIndex)
    val latestOnScrubToWorld by rememberUpdatedState(onScrubToWorld)
    val latestOnOpenOrbit by rememberUpdatedState(onOpenOrbit)
    val stepPx = with(density) { PORTAL_SCRUB_STEP_DISTANCE.toPx() }
    val openOrbitPx = with(density) { PORTAL_OPEN_ORBIT_DISTANCE.toPx() }

    return Modifier.pointerInput(stepPx, openOrbitPx, lastIndex) {
        val config = PortalGestureConfig(
            activeIndex = { latestActiveIndex },
            lastIndex = lastIndex,
            stepPx = stepPx,
            openOrbitPx = openOrbitPx,
            onScrubToWorld = { latestOnScrubToWorld(it) },
            onOpenOrbit = { latestOnOpenOrbit() },
        )
        awaitEachGesture { followPortalGesture(config, gestureActive) }
    }
}

/**
 * One gesture on the portal, from the finger going down to it being lifted. [gestureActive] is
 * released in a finally block: if the pointer input is restarted in the middle of a stroke the
 * coroutine is cancelled, and a flag left set would switch the swipe from anywhere off for good.
 */
private suspend fun AwaitPointerEventScope.followPortalGesture(
    config: PortalGestureConfig,
    gestureActive: MutableState<Boolean>,
) {
    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
    gestureActive.value = true
    try {
        followClaimedPortalGesture(down.id, config)
    } finally {
        gestureActive.value = false
    }
}

private suspend fun AwaitPointerEventScope.followClaimedPortalGesture(
    pointerId: PointerId,
    config: PortalGestureConfig,
) {
    val claim = awaitPortalClaim(pointerId) ?: return
    when (claim.axis) {
        PortalAxis.Sideways -> scrubWorlds(pointerId, claim.travelledX, config)
        PortalAxis.Vertical -> if (awaitUpwardDrag(pointerId, claim.travelledY, config.openOrbitPx)) {
            config.onOpenOrbit()
        }
    }
}

/**
 * Follows the finger until it has moved past the touch slop, then decides which way the drag goes
 * and claims it by consuming the event. Returns null for a gesture that is not ours: a tap (the
 * finger is released before moving), or a second finger.
 */
@Suppress("ReturnCount") // Each early return is a distinct way of giving the gesture up.
private suspend fun AwaitPointerEventScope.awaitPortalClaim(pointerId: PointerId): PortalClaim? {
    var travelledX = 0f
    var travelledY = 0f

    while (true) {
        val event = awaitPointerEvent(PointerEventPass.Initial)
        if (event.changes.size > 1) return null
        val change = event.changes.firstOrNull { it.id == pointerId } ?: return null
        if (change.changedToUpIgnoreConsumed()) return null

        val delta = change.positionChange()
        travelledX += delta.x
        travelledY += delta.y
        if (abs(travelledX) <= viewConfiguration.touchSlop && abs(travelledY) <= viewConfiguration.touchSlop) {
            continue
        }

        change.consume()
        val axis = if (abs(travelledX) > abs(travelledY)) PortalAxis.Sideways else PortalAxis.Vertical
        return PortalClaim(axis, travelledX, travelledY)
    }
}

/**
 * Follows a sideways stroke and asks for the world under it, until the finger is lifted.
 * The finger moving left (negative travel) goes towards the next world.
 */
@Suppress("ReturnCount") // Each early return is a distinct way of the stroke ending.
private suspend fun AwaitPointerEventScope.scrubWorlds(
    pointerId: PointerId,
    startTravelX: Float,
    config: PortalGestureConfig,
) {
    val startIndex = config.activeIndex()
    var travelledX = startTravelX
    var requestedIndex = startIndex

    while (true) {
        val event = awaitPointerEvent(PointerEventPass.Initial)
        if (event.changes.size > 1) return
        val change = event.changes.firstOrNull { it.id == pointerId } ?: return
        val delta = change.positionChange()
        change.consume()
        if (change.changedToUpIgnoreConsumed()) return

        travelledX += delta.x
        val target = (startIndex + (-travelledX / config.stepPx).toInt()).coerceIn(0, config.lastIndex)
        if (target == requestedIndex) continue
        requestedIndex = target
        config.onScrubToWorld(target)
    }
}

/** Follows a vertical drag until the finger is lifted; true if it went far enough up to open Orbit. */
@Suppress("ReturnCount") // Each early return is a distinct way of giving the gesture up.
private suspend fun AwaitPointerEventScope.awaitUpwardDrag(
    pointerId: PointerId,
    startTravelY: Float,
    openOrbitPx: Float,
): Boolean {
    var travelledY = startTravelY

    while (true) {
        val event = awaitPointerEvent(PointerEventPass.Initial)
        if (event.changes.size > 1) return false
        val change = event.changes.firstOrNull { it.id == pointerId } ?: return false
        val delta = change.positionChange()
        change.consume()
        if (change.changedToUpIgnoreConsumed()) return travelledY <= -openOrbitPx

        travelledY += delta.y
    }
}
