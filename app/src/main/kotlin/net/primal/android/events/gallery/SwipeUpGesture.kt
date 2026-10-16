package net.primal.android.events.gallery

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
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

/** How far a finger has to travel upward before releasing it counts as "open the comments". */
private val SWIPE_UP_COMMIT_DISTANCE = 72.dp

/**
 * How much more vertical than horizontal a movement has to be to count as an upward swipe. Below
 * this ratio it is a sideways swipe between photos that drifted upward, and the pager keeps it.
 */
private const val VERTICAL_DOMINANCE = 1.5f

private enum class SwipeVerdict { Undecided, NotOurs, Ours }

/**
 * Builds the modifier that calls [onSwipeUp] when a single finger is swiped upward on the content
 * and released.
 *
 * It is meant for the parent of the pager and watches the events in the initial pass: the
 * photo's own zoom/pan handling sits below it and would otherwise take the vertical drag first.
 * Nothing is consumed until the finger has clearly started moving upward, so taps (which show or
 * hide the controls), double-taps (zoom), sideways swipes (next photo) and pinches all go through
 * to the content untouched.
 *
 * [isBlocked] is asked when the finger goes down. While the photo is zoomed in, an upward drag is
 * the user moving around the picture and must not leave the screen, so the caller reports that.
 */
@Composable
internal fun rememberSwipeUpModifier(
    enabled: Boolean,
    isBlocked: () -> Boolean,
    onSwipeUp: () -> Unit,
): Modifier {
    val commitDistancePx = with(LocalDensity.current) { SWIPE_UP_COMMIT_DISTANCE.toPx() }
    // Read through State so the gesture coroutine, which is started once, always calls the
    // lambdas of the latest composition and not the ones it was started with.
    val latestIsBlocked by rememberUpdatedState(isBlocked)
    val latestOnSwipeUp by rememberUpdatedState(onSwipeUp)

    return Modifier.pointerInput(enabled, commitDistancePx) {
        if (!enabled) return@pointerInput
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            if (latestIsBlocked()) return@awaitEachGesture
            if (awaitUpwardSwipe(down.id, commitDistancePx)) latestOnSwipeUp()
        }
    }
}

/**
 * Follows one finger and returns true if it ended in an upward swipe of at least
 * [commitDistancePx]. Gives up, leaving the gesture to the content, on a second finger, on a
 * movement that is mostly sideways, and on one that goes downward.
 */
@Suppress("ReturnCount") // Each early return is a distinct way of giving the gesture up.
private suspend fun AwaitPointerEventScope.awaitUpwardSwipe(pointerId: PointerId, commitDistancePx: Float): Boolean {
    var travelledX = 0f
    var travelledY = 0f
    var claimed = false

    while (true) {
        val event = awaitPointerEvent(PointerEventPass.Initial)
        if (event.changes.size > 1) return false
        val change = event.changes.firstOrNull { it.id == pointerId } ?: return false
        if (change.changedToUpIgnoreConsumed()) return claimed && -travelledY >= commitDistancePx

        val delta = change.positionChange()
        travelledX += delta.x
        travelledY += delta.y

        if (!claimed) {
            val verdict = judgeSwipe(travelledX, travelledY, viewConfiguration.touchSlop)
            if (verdict == SwipeVerdict.NotOurs) return false
            claimed = verdict == SwipeVerdict.Ours
        }
        if (claimed) change.consume()
    }
}

private fun judgeSwipe(travelledX: Float, travelledY: Float, touchSlop: Float): SwipeVerdict {
    val horizontal = abs(travelledX)
    val vertical = abs(travelledY)
    return when {
        horizontal <= touchSlop && vertical <= touchSlop -> SwipeVerdict.Undecided
        travelledY > 0f || horizontal * VERTICAL_DOMINANCE > vertical -> SwipeVerdict.NotOurs
        else -> SwipeVerdict.Ours
    }
}
