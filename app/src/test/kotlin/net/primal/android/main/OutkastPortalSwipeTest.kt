package net.primal.android.main

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
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
 * Drives the gesture of the portal, the pill that opens Orbit. At mdpi one dp is one pixel, and the
 * scrub step is 36dp, so a stroke of N pixels crosses N / 36 worlds.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "w420dp-h700dp-mdpi")
class OutkastPortalSwipeTest {

    private val composeRule = createComposeRule()

    @get:Rule
    val ruleChain: RuleChain = RuleChain.outerRule(RegisterComponentActivityRule()).around(composeRule)

    private val scrubbedTo = mutableListOf<Int>()
    private var orbitOpened = 0
    private var clicks = 0
    private val gestureActive = mutableStateOf(false)

    private fun setPortalContent(activeIndex: Int) {
        composeRule.setContent {
            val portal = rememberOutkastPortalSwipeModifier(
                activeIndex = activeIndex,
                lastIndex = 3,
                gestureActive = gestureActive,
                onScrubToWorld = { scrubbedTo += it },
                onOpenOrbit = { orbitOpened++ },
            )
            Box(modifier = Modifier.size(240.dp, 48.dp).then(portal)) {
                Box(modifier = Modifier.fillMaxSize().background(Color.Gray).clickable { clicks++ })
            }
        }
    }

    private fun swipe(start: Offset, end: Offset) {
        composeRule.onRoot().performTouchInput { swipe(start = start, end = end, durationMillis = 200) }
    }

    @Test
    fun strokeToTheLeftRunsThroughTheNextWorldsInOrder() {
        setPortalContent(activeIndex = 0)

        swipe(start = Offset(200f, 24f), end = Offset(100f, 24f))

        scrubbedTo shouldBe listOf(1, 2)
    }

    @Test
    fun strokeToTheRightRunsBackThroughThePreviousWorlds() {
        setPortalContent(activeIndex = 3)

        swipe(start = Offset(40f, 24f), end = Offset(140f, 24f))

        scrubbedTo shouldBe listOf(2, 1)
    }

    @Test
    fun strokeStopsAtTheLastWorld() {
        setPortalContent(activeIndex = 2)

        swipe(start = Offset(230f, 24f), end = Offset(10f, 24f))

        scrubbedTo shouldBe listOf(3)
    }

    @Test
    fun strokeTowardsNowhereFromTheFirstWorldDoesNothing() {
        setPortalContent(activeIndex = 0)

        swipe(start = Offset(40f, 24f), end = Offset(200f, 24f))

        scrubbedTo.shouldBeEmpty()
    }

    @Test
    fun strokeShorterThanOneStepDoesNotChangeWorldNorOpenOrbit() {
        setPortalContent(activeIndex = 1)

        swipe(start = Offset(150f, 24f), end = Offset(130f, 24f))

        scrubbedTo.shouldBeEmpty()
        clicks shouldBe 0
        orbitOpened shouldBe 0
    }

    @Test
    fun strokeThatEndsInsideThePortalDoesNotAlsoClickIt() {
        // The portal is small, so a stroke can end inside it; the click must not fire on release.
        setPortalContent(activeIndex = 0)

        swipe(start = Offset(200f, 24f), end = Offset(120f, 24f))

        clicks shouldBe 0
    }

    @Test
    fun tapIsLeftToTheClick() {
        setPortalContent(activeIndex = 1)

        composeRule.onRoot().performTouchInput { click(Offset(120f, 24f)) }

        clicks shouldBe 1
        scrubbedTo.shouldBeEmpty()
        orbitOpened shouldBe 0
    }

    @Test
    fun dragUpOpensOrbit() {
        setPortalContent(activeIndex = 1)

        swipe(start = Offset(120f, 40f), end = Offset(120f, 0f))

        orbitOpened shouldBe 1
        scrubbedTo.shouldBeEmpty()
        clicks shouldBe 0
    }

    @Test
    fun shortDragUpDoesNotOpenOrbit() {
        setPortalContent(activeIndex = 1)

        swipe(start = Offset(120f, 30f), end = Offset(120f, 16f))

        orbitOpened shouldBe 0
    }

    @Test
    fun dragDownDoesNotOpenOrbit() {
        setPortalContent(activeIndex = 1)

        swipe(start = Offset(120f, 5f), end = Offset(120f, 45f))

        orbitOpened shouldBe 0
        clicks shouldBe 0
    }

    @Test
    fun gestureIsMarkedActiveOnlyWhileTheFingerIsDown() {
        setPortalContent(activeIndex = 0)

        // The gesture coroutine only sees the events once the test has gone idle, so the finger is
        // left down between two touch-input calls instead of reading the flag inside one.
        composeRule.onRoot().performTouchInput {
            down(Offset(200f, 24f))
            moveTo(Offset(150f, 24f))
        }
        composeRule.waitForIdle()
        val activeInTheMiddle = gestureActive.value
        composeRule.onRoot().performTouchInput { up() }
        composeRule.waitForIdle()

        activeInTheMiddle shouldBe true
        gestureActive.value shouldBe false
    }
}
