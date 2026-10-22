package net.primal.android.main

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import net.primal.android.core.compose.RegisterComponentActivityRule
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Drives the edge swipe with synthetic touches. These exist because the gesture cannot be tried
 * without a device that has gesture navigation, and because the bug they pin down — the swipe
 * working once and then never again — only shows up across two consecutive gestures.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "w420dp-h700dp-mdpi")
class OutkastEdgeSwipeTest {

    private val composeRule = createComposeRule()

    @get:Rule
    val ruleChain: RuleChain = RuleChain.outerRule(RegisterComponentActivityRule()).around(composeRule)

    private val edgeZone = 28.dp
    private val screenWidthPx = 400f

    private fun setSwipeContent(
        onSwipe: (Int) -> Unit,
        content: @androidx.compose.runtime.Composable () -> Unit = {},
    ) {
        composeRule.setContent {
            val swipeModifier = rememberOutkastEdgeSwipeModifier(edgeZoneWidth = edgeZone, onSwipe = onSwipe)
            Box(modifier = Modifier.size(400.dp, 600.dp).then(swipeModifier)) { content() }
        }
    }

    private fun swipeFrom(start: Offset, end: Offset) {
        composeRule.onRoot().performTouchInput { swipe(start = start, end = end, durationMillis = 200) }
    }

    @Test
    fun swipeInwardFromTheStartEdgeStepsBackToThePreviousWorld() {
        val steps = mutableListOf<Int>()
        setSwipeContent(onSwipe = { steps += it })

        swipeFrom(start = Offset(4f, 300f), end = Offset(204f, 300f))

        steps shouldBe listOf(-1)
    }

    @Test
    fun swipeInwardFromTheEndEdgeStepsForwardToTheNextWorld() {
        val steps = mutableListOf<Int>()
        setSwipeContent(onSwipe = { steps += it })

        swipeFrom(start = Offset(screenWidthPx - 4f, 300f), end = Offset(screenWidthPx - 204f, 300f))

        steps shouldBe listOf(1)
    }

    @Test
    fun swipeThatStartsAwayFromTheEdgesIsIgnored() {
        val steps = mutableListOf<Int>()
        setSwipeContent(onSwipe = { steps += it })

        swipeFrom(start = Offset(screenWidthPx / 2, 300f), end = Offset(screenWidthPx / 2 + 200f, 300f))

        steps.shouldBeEmpty()
    }

    @Test
    fun swipeThatStartsOnTheEdgeOfTheUpperHalfIsLeftToTheSystem() {
        // The upper half of the screen belongs to the system gestures; the dedicated edge area is
        // the lower half only.
        val steps = mutableListOf<Int>()
        setSwipeContent(onSwipe = { steps += it })

        swipeFrom(start = Offset(4f, 100f), end = Offset(204f, 100f))
        swipeFrom(start = Offset(screenWidthPx - 4f, 100f), end = Offset(screenWidthPx - 204f, 100f))

        steps.shouldBeEmpty()
    }

    @Test
    fun swipeTowardsTheEdgeIsIgnored() {
        val steps = mutableListOf<Int>()
        setSwipeContent(onSwipe = { steps += it })

        // Starts just inside the start strip and heads for the edge.
        swipeFrom(start = Offset(20f, 300f), end = Offset(0f, 300f))

        steps.shouldBeEmpty()
    }

    @Test
    fun verticalDragAlongTheEdgeIsLeftToTheContent() {
        val steps = mutableListOf<Int>()
        setSwipeContent(onSwipe = { steps += it })

        swipeFrom(start = Offset(4f, 100f), end = Offset(30f, 500f))

        steps.shouldBeEmpty()
    }

    @Test
    fun shortInwardSwipeDoesNotChangeWorld() {
        val steps = mutableListOf<Int>()
        setSwipeContent(onSwipe = { steps += it })

        swipeFrom(start = Offset(4f, 300f), end = Offset(34f, 300f))

        steps.shouldBeEmpty()
    }

    @Test
    fun secondSwipeSeesTheStateOfTheComposeThatFollowedTheFirst() {
        // The regression: the gesture coroutine kept the lambda of the first composition, so every
        // swipe computed its destination from the world that was showing at startup.
        var world by mutableStateOf(0)
        val seenWorlds = mutableListOf<Int>()
        composeRule.setContent {
            val currentWorld = world
            val swipeModifier = rememberOutkastEdgeSwipeModifier(edgeZoneWidth = edgeZone) {
                seenWorlds += currentWorld
                world = currentWorld + it
            }
            Box(modifier = Modifier.size(400.dp, 600.dp).then(swipeModifier))
        }

        swipeFrom(start = Offset(screenWidthPx - 4f, 300f), end = Offset(screenWidthPx - 204f, 300f))
        composeRule.waitForIdle()
        swipeFrom(start = Offset(screenWidthPx - 4f, 300f), end = Offset(screenWidthPx - 204f, 300f))
        composeRule.waitForIdle()

        seenWorlds shouldBe listOf(0, 1)
        world shouldBe 2
    }

    @Test
    fun tapInsideTheEdgeStripStillReachesTheContentUnderIt() {
        var clicks = 0
        setSwipeContent(onSwipe = {}) {
            Box(modifier = Modifier.fillMaxSize().background(Color.Gray).clickable { clicks++ })
        }

        composeRule.onRoot().performTouchInput { click(Offset(6f, 300f)) }

        clicks shouldBe 1
    }

    @Test
    fun swipeThatTheContentAlreadyTookIsStillReportedOnlyOnce() {
        val steps = mutableListOf<Int>()
        setSwipeContent(onSwipe = { steps += it }) {
            Box(modifier = Modifier.fillMaxSize().clickable { })
        }

        swipeFrom(start = Offset(4f, 300f), end = Offset(204f, 300f))

        steps shouldBe listOf(-1)
    }
}
