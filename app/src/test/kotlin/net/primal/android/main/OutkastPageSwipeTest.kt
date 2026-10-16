package net.primal.android.main

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
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
 * Drives the swipe that works from anywhere on the screen. The edge swipe cannot be relied on with
 * system gesture navigation outside a 200dp band, so this is the one most swipes go through.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "w420dp-h700dp-mdpi")
class OutkastPageSwipeTest {

    private val composeRule = createComposeRule()

    @get:Rule
    val ruleChain: RuleChain = RuleChain.outerRule(RegisterComponentActivityRule()).around(composeRule)

    private val steps = mutableListOf<Int>()
    private var clicks = 0

    private fun setPageContent(
        ignoreChildConsumption: (Int) -> Boolean = { false },
        content: @Composable () -> Unit = {},
    ) {
        composeRule.setContent {
            val modifier = rememberOutkastPageSwipeModifier(
                ignoreChildConsumption = ignoreChildConsumption,
                onSwipe = { steps += it },
            )
            Box(modifier = Modifier.size(420.dp, 700.dp).then(modifier)) { content() }
        }
    }

    private fun swipe(start: Offset, end: Offset) {
        composeRule.onRoot().performTouchInput { swipe(start = start, end = end, durationMillis = 200) }
    }

    @Composable
    private fun SidewaysScroller() {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .draggable(state = rememberDraggableState { }, orientation = Orientation.Horizontal),
        )
    }

    @Test
    fun swipeLeftAwayFromTheEdgesStepsToTheNextWorld() {
        setPageContent()

        swipe(start = Offset(300f, 350f), end = Offset(100f, 350f))

        steps shouldBe listOf(1)
    }

    @Test
    fun swipeRightAwayFromTheEdgesStepsToThePreviousWorld() {
        setPageContent()

        swipe(start = Offset(100f, 350f), end = Offset(300f, 350f))

        steps shouldBe listOf(-1)
    }

    @Test
    fun slightlyDiagonalSwipeStillCounts() {
        setPageContent()

        swipe(start = Offset(300f, 350f), end = Offset(100f, 410f))

        steps shouldBe listOf(1)
    }

    @Test
    fun shortSwipeIsIgnored() {
        setPageContent()

        swipe(start = Offset(300f, 350f), end = Offset(250f, 350f))

        steps.shouldBeEmpty()
    }

    @Test
    fun mostlyVerticalDragIsLeftToTheScrollingList() {
        setPageContent()

        swipe(start = Offset(300f, 600f), end = Offset(240f, 200f))

        steps.shouldBeEmpty()
    }

    @Test
    fun swipeTheContentAlreadyTookIsLeftToIt() {
        setPageContent { SidewaysScroller() }

        swipe(start = Offset(300f, 350f), end = Offset(100f, 350f))

        steps.shouldBeEmpty()
    }

    @Test
    fun swipeTheContentTookButCannotUseStillChangesWorld() {
        // A pager on its last page takes every sideways drag and does nothing with it.
        setPageContent(ignoreChildConsumption = { step -> step > 0 }) { SidewaysScroller() }

        swipe(start = Offset(300f, 350f), end = Offset(100f, 350f))

        steps shouldBe listOf(1)
    }

    @Test
    fun swipeTowardsTheDirectionThePagerCanStillScrollIsLeftToIt() {
        setPageContent(ignoreChildConsumption = { step -> step > 0 }) { SidewaysScroller() }

        swipe(start = Offset(100f, 350f), end = Offset(300f, 350f))

        steps.shouldBeEmpty()
    }

    @Test
    fun tapStillReachesTheContent() {
        setPageContent {
            Box(modifier = Modifier.fillMaxSize().background(Color.Gray).clickable { clicks++ })
        }

        composeRule.onRoot().performTouchInput { click(Offset(200f, 300f)) }

        clicks shouldBe 1
        steps.shouldBeEmpty()
    }

    @Test
    fun twoFingerGestureIsIgnored() {
        setPageContent()

        composeRule.onRoot().performTouchInput {
            down(0, Offset(300f, 300f))
            down(1, Offset(300f, 400f))
            moveTo(0, Offset(100f, 300f))
            moveTo(1, Offset(100f, 400f))
            up(0)
            up(1)
        }

        steps.shouldBeEmpty()
    }

    @Test
    fun swipeThatStartsInTheEdgeStripIsLeftToTheEdgeSwipe() {
        composeRule.setContent {
            val page = rememberOutkastPageSwipeModifier(
                ignoreChildConsumption = { false },
                onSwipe = { steps += it },
                edgeZoneToLeaveAlone = 28.dp,
            )
            Box(modifier = Modifier.size(420.dp, 700.dp).then(page))
        }

        swipe(start = Offset(416f, 350f), end = Offset(216f, 350f))

        steps.shouldBeEmpty()
    }

    @Test
    fun edgeAndPageSwipesTogetherReportASwipeFromTheEdgeOnlyOnce() {
        // The regression: with a pager on its last page, the page swipe ignores the consumption of
        // the sideways drag on purpose, so it also reported the swipe the edge swipe had already
        // taken, and the user skipped two worlds.
        composeRule.setContent {
            val edge = rememberOutkastEdgeSwipeModifier(edgeZoneWidth = 28.dp, onSwipe = { steps += it })
            val page = rememberOutkastPageSwipeModifier(
                ignoreChildConsumption = { true },
                onSwipe = { steps += it },
                edgeZoneToLeaveAlone = 28.dp,
            )
            Box(modifier = Modifier.size(420.dp, 700.dp).then(edge).then(page)) { SidewaysScroller() }
        }

        swipe(start = Offset(416f, 350f), end = Offset(216f, 350f))

        steps shouldBe listOf(1)
    }

    @Test
    fun pageSwipeStillWorksNextToTheEdgeStrips() {
        composeRule.setContent {
            val page = rememberOutkastPageSwipeModifier(
                ignoreChildConsumption = { false },
                onSwipe = { steps += it },
                edgeZoneToLeaveAlone = 28.dp,
            )
            Box(modifier = Modifier.size(420.dp, 700.dp).then(page))
        }

        swipe(start = Offset(380f, 350f), end = Offset(180f, 350f))

        steps shouldBe listOf(1)
    }
}
