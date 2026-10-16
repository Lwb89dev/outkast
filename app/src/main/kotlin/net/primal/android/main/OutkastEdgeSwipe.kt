package net.primal.android.main

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.systemGestures
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/**
 * Narrowest width of the strip, along each screen edge, in which an inward swipe changes world.
 * It is the whole zone on devices without a system back gesture (three-button navigation).
 */
private val EDGE_ZONE_MIN_WIDTH = 28.dp

/**
 * How far the strip reaches past the system back-gesture area. A finger rarely lands exactly on
 * the pixel column the system reserves, so the zone has to be a little wider than that area for
 * the swipe to start where people actually put their finger.
 */
private val EDGE_ZONE_BEYOND_SYSTEM_INSET = 12.dp

/** A swipe has to travel this far inward before it counts as "change world". */
private val EDGE_SWIPE_COMMIT_DISTANCE = 64.dp

/** A swipe that starts away from the edges has to travel this far sideways before it counts. */
private val PAGE_SWIPE_COMMIT_DISTANCE = 80.dp

/**
 * How much more sideways than vertical a movement has to be to count as a swipe between worlds.
 * Anything flatter is a list being scrolled with a finger that drifts a little to the side.
 */
private const val PAGE_SWIPE_HORIZONTAL_DOMINANCE = 1.5f

/**
 * Android caps how much of each edge an app may take away from the back gesture: 200dp of height.
 * Anything taller is ignored by the system, and which part of it is ignored is not documented, so
 * the exclusion is deliberately kept inside that cap instead of spanning the whole edge.
 */
private val SYSTEM_GESTURE_EXCLUSION_MAX_HEIGHT = 200.dp

/** Which edge of the screen a swipe started on. [inward] is the sign of a horizontal drag away from it. */
private enum class ScreenEdge(val inward: Float) {
    Start(inward = 1f),
    End(inward = -1f),
}

/**
 * Width of the edge strip in which an inward swipe starts: the system back-gesture inset reported
 * by the device, plus [EDGE_ZONE_BEYOND_SYSTEM_INSET].
 *
 * The inset is read from the device instead of being hard-coded because it is not a constant: it
 * is 0 with three-button navigation and grows when the user raises the back-gesture sensitivity.
 * An exclusion narrower than the inset would leave part of it to the system, and a swipe starting
 * in that part would become a "back" instead of a change of world.
 */
@Composable
internal fun rememberOutkastEdgeZoneWidth(): Dp {
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val gestureInsets = WindowInsets.systemGestures
    val insetPx = maxOf(
        gestureInsets.getLeft(density, layoutDirection),
        gestureInsets.getRight(density, layoutDirection),
    )
    val insetDp = with(density) { insetPx.toDp() }
    return maxOf(EDGE_ZONE_MIN_WIDTH, insetDp + EDGE_ZONE_BEYOND_SYSTEM_INSET)
}

/**
 * Detects a horizontal swipe that starts in one of the two edge strips and moves inward, and
 * reports it as a step through the list of worlds: -1 for the swipe from the start edge (towards
 * the previous world), +1 for the swipe from the end edge (towards the next one).
 *
 * This is a modifier on the container of the content instead of a strip laid over it, for two
 * reasons. A strip laid over the content takes every touch that lands on it, so taps on whatever
 * sits under the strip stopped working; a modifier on a parent only watches the touches and takes
 * over once the finger has clearly started an inward swipe. And the events are watched in the
 * initial pass, before the children see them, so a list or carousel at the edge cannot start
 * scrolling first and win.
 *
 * [onSwipe] is read through [rememberUpdatedState]. It used to be captured by the gesture
 * coroutine once and never refreshed, so the destination was computed from the world that was
 * showing when the screen was first composed and the swipe stopped working after the first move.
 */
@Composable
internal fun rememberOutkastEdgeSwipeModifier(edgeZoneWidth: Dp, onSwipe: (step: Int) -> Unit): Modifier {
    val density = LocalDensity.current
    val latestOnSwipe by rememberUpdatedState(onSwipe)
    val zonePx = with(density) { edgeZoneWidth.toPx() }
    val commitDistancePx = with(density) { EDGE_SWIPE_COMMIT_DISTANCE.toPx() }

    return Modifier.pointerInput(zonePx, commitDistancePx) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val edge = edgeOf(x = down.position.x, width = size.width.toFloat(), zonePx = zonePx)
                ?: return@awaitEachGesture
            if (awaitInwardSwipe(down.id, edge, commitDistancePx)) {
                latestOnSwipe(if (edge == ScreenEdge.Start) -1 else 1)
            }
        }
    }
}

private fun edgeOf(x: Float, width: Float, zonePx: Float): ScreenEdge? =
    when {
        zonePx <= 0f -> null
        x <= zonePx -> ScreenEdge.Start
        x >= width - zonePx -> ScreenEdge.End
        else -> null
    }

/**
 * Follows one finger that went down on [edge] and returns true if it was released after an inward
 * swipe of at least [commitDistancePx].
 *
 * The swipe is only claimed (its events consumed, so nothing underneath scrolls with it) once the
 * finger has moved past the touch slop mostly sideways and towards the inside of the screen. A
 * movement that starts out vertical is a list scroll and is left alone, and so is a movement
 * towards the edge, which is not ours.
 */
@Suppress("ReturnCount") // Each early return is a distinct way of giving the gesture up.
private suspend fun AwaitPointerEventScope.awaitInwardSwipe(
    pointerId: PointerId,
    edge: ScreenEdge,
    commitDistancePx: Float,
): Boolean {
    var travelledX = 0f
    var travelledY = 0f
    var claimed = false

    while (true) {
        val event = awaitPointerEvent(PointerEventPass.Initial)
        // A second finger is a pinch or a multi-touch gesture, not a swipe between worlds.
        if (event.changes.size > 1) return false
        val change = event.changes.firstOrNull { it.id == pointerId } ?: return false
        if (change.changedToUpIgnoreConsumed()) return claimed && travelledX * edge.inward >= commitDistancePx

        val delta = change.positionChange()
        travelledX += delta.x
        travelledY += delta.y

        if (!claimed) {
            val verdict = judgeMovement(travelledX, travelledY, edge, viewConfiguration.touchSlop)
            if (verdict == MovementVerdict.NotOurs) return false
            claimed = verdict == MovementVerdict.Ours
        }
        if (claimed) change.consume()
    }
}

private enum class MovementVerdict { Undecided, NotOurs, Ours }

private fun judgeMovement(travelledX: Float, travelledY: Float, edge: ScreenEdge, touchSlop: Float): MovementVerdict {
    val horizontal = abs(travelledX)
    val vertical = abs(travelledY)
    return when {
        vertical > touchSlop && vertical > horizontal -> MovementVerdict.NotOurs
        horizontal <= touchSlop -> MovementVerdict.Undecided
        travelledX * edge.inward < 0f -> MovementVerdict.NotOurs
        else -> MovementVerdict.Ours
    }
}

/**
 * Detects a horizontal swipe that starts anywhere on the content and reports it as a step through
 * the list of worlds: +1 when the finger moves left (towards the next world), -1 when it moves
 * right (towards the previous one), the same way a pager turns pages.
 *
 * This is what makes the swipe usable with system gesture navigation. The edge strips handled by
 * [rememberOutkastEdgeSwipeModifier] can only be protected from the back gesture over 200dp of
 * each edge, so a swipe that starts at any other height on the edge is taken by the system as
 * "back" (it was measured on a Samsung phone: from the second world it went back to the first,
 * and from the first it left the app). A swipe that starts a little inside the screen is never
 * taken by the system.
 *
 * Unlike the edge swipe, this one watches the events in the main pass, after the children, and
 * gives way to them: a photo carousel inside a note, or a horizontal list, that takes the swipe
 * keeps it. The one exception is [ignoreChildConsumption], for a scroller that takes every
 * sideways drag but has nothing left to scroll in that direction — the section pager of Discover
 * on its last page — which would otherwise trap the user in that world.
 *
 * A swipe that starts inside the edge strips of width [edgeZoneToLeaveAlone] is not handled here:
 * it belongs to the edge swipe. Without this, on that last page both detectors would report the
 * same swipe — the edge one because it consumed the events first, this one because it ignores
 * that consumption on purpose — and the user would skip two worlds instead of one.
 */
@Composable
internal fun rememberOutkastPageSwipeModifier(
    ignoreChildConsumption: (step: Int) -> Boolean,
    onSwipe: (step: Int) -> Unit,
    edgeZoneToLeaveAlone: Dp = 0.dp,
): Modifier {
    val density = LocalDensity.current
    // Read through State so the gesture coroutine, started once, calls the lambdas of the latest
    // composition (see rememberOutkastEdgeSwipeModifier).
    val latestOnSwipe by rememberUpdatedState(onSwipe)
    val latestIgnoreChildConsumption by rememberUpdatedState(ignoreChildConsumption)
    val commitDistancePx = with(density) { PAGE_SWIPE_COMMIT_DISTANCE.toPx() }
    val edgeZonePx = with(density) { edgeZoneToLeaveAlone.toPx() }

    return Modifier.pointerInput(commitDistancePx, edgeZonePx) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            if (edgeOf(x = down.position.x, width = size.width.toFloat(), zonePx = edgeZonePx) != null) {
                return@awaitEachGesture
            }
            val step = awaitPageSwipe(down.id, commitDistancePx) { latestIgnoreChildConsumption(it) }
            if (step != 0) latestOnSwipe(step)
        }
    }
}

/**
 * Follows one finger and returns the step through the worlds it ended in: +1, -1, or 0 when it
 * was not a swipe between worlds (too short, mostly vertical, taken by a child, or multi-touch).
 */
@Suppress("ReturnCount") // Each early return is a distinct way of giving the gesture up.
private suspend fun AwaitPointerEventScope.awaitPageSwipe(
    pointerId: PointerId,
    commitDistancePx: Float,
    ignoreChildConsumption: (step: Int) -> Boolean,
): Int {
    var travelledX = 0f
    var travelledY = 0f
    var claimed = false

    while (true) {
        val event = awaitPointerEvent()
        if (event.changes.size > 1) return 0
        val change = event.changes.firstOrNull { it.id == pointerId } ?: return 0
        if (change.changedToUpIgnoreConsumed()) {
            return if (claimed && abs(travelledX) >= commitDistancePx) stepFor(travelledX) else 0
        }

        // Taken from the raw positions, not positionChange(): that is zero once a child has
        // consumed the change, and the movement is still needed to tell which way it went.
        travelledX += change.position.x - change.previousPosition.x
        travelledY += change.position.y - change.previousPosition.y

        if (change.isConsumed && !ignoreChildConsumption(stepFor(travelledX))) return 0

        if (!claimed) {
            val verdict = judgePageMovement(travelledX, travelledY, viewConfiguration.touchSlop)
            if (verdict == MovementVerdict.NotOurs) return 0
            claimed = verdict == MovementVerdict.Ours
        }
        if (claimed) change.consume()
    }
}

/** +1 (next world) for a finger that moved left, -1 (previous) for one that moved right, 0 for neither. */
private fun stepFor(travelledX: Float): Int =
    when {
        travelledX < 0f -> 1
        travelledX > 0f -> -1
        else -> 0
    }

private fun judgePageMovement(travelledX: Float, travelledY: Float, touchSlop: Float): MovementVerdict {
    val horizontal = abs(travelledX)
    val vertical = abs(travelledY)
    return when {
        horizontal <= touchSlop && vertical <= touchSlop -> MovementVerdict.Undecided
        horizontal > vertical * PAGE_SWIPE_HORIZONTAL_DOMINANCE -> MovementVerdict.Ours
        else -> MovementVerdict.NotOurs
    }
}

/**
 * Keeps the system back gesture away from the middle of each edge strip, so that a swipe starting
 * there reaches [rememberOutkastEdgeSwipeModifier] instead of being taken as "back".
 *
 * Draws nothing and takes no touches: it only tells the system where the app wants the edge.
 * Outside this band the back gesture keeps working as usual, which is also how the user leaves
 * the app from the first world.
 */
@Composable
internal fun OutkastEdgeGestureExclusion(edgeZoneWidth: Dp, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize()) {
        EdgeExclusionBand(
            modifier = Modifier.align(Alignment.CenterStart),
            width = edgeZoneWidth,
        )
        EdgeExclusionBand(
            modifier = Modifier.align(Alignment.CenterEnd),
            width = edgeZoneWidth,
        )
    }
}

@Composable
private fun EdgeExclusionBand(modifier: Modifier, width: Dp) {
    Box(
        modifier = modifier
            .width(width)
            .height(SYSTEM_GESTURE_EXCLUSION_MAX_HEIGHT)
            .systemGestureExclusion(),
    )
}
