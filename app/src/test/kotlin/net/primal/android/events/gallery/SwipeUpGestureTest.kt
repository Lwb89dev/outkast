package net.primal.android.events.gallery

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import io.kotest.matchers.shouldBe
import net.primal.android.core.compose.RegisterComponentActivityRule
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Drives the swipe-up-for-comments gesture with synthetic touches. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "w420dp-h700dp-mdpi")
class SwipeUpGestureTest {

    private val composeRule = createComposeRule()

    @get:Rule
    val ruleChain: RuleChain = RuleChain.outerRule(RegisterComponentActivityRule()).around(composeRule)

    private var opened = 0
    private var blocked = false
    private var clicks = 0

    private fun setGalleryContent(enabled: Boolean = true) {
        composeRule.setContent {
            val swipeUp = rememberSwipeUpModifier(
                enabled = enabled,
                isBlocked = { blocked },
                onSwipeUp = { opened++ },
            )
            Box(modifier = Modifier.size(400.dp, 600.dp).then(swipeUp)) {
                Box(modifier = Modifier.fillMaxSize().background(Color.Black).clickable { clicks++ })
            }
        }
    }

    private fun swipe(start: Offset, end: Offset) {
        composeRule.onRoot().performTouchInput { swipe(start = start, end = end, durationMillis = 200) }
    }

    @Test
    fun upwardSwipeOpensTheComments() {
        setGalleryContent()

        swipe(start = Offset(200f, 500f), end = Offset(200f, 250f))

        opened shouldBe 1
    }

    @Test
    fun slightlyDiagonalUpwardSwipeStillOpensTheComments() {
        setGalleryContent()

        swipe(start = Offset(200f, 500f), end = Offset(240f, 250f))

        opened shouldBe 1
    }

    @Test
    fun shortUpwardSwipeDoesNotOpenTheComments() {
        setGalleryContent()

        swipe(start = Offset(200f, 500f), end = Offset(200f, 460f))

        opened shouldBe 0
    }

    @Test
    fun downwardSwipeDoesNotOpenTheComments() {
        setGalleryContent()

        swipe(start = Offset(200f, 250f), end = Offset(200f, 500f))

        opened shouldBe 0
    }

    @Test
    fun sidewaysSwipeIsLeftToThePager() {
        setGalleryContent()

        swipe(start = Offset(350f, 300f), end = Offset(50f, 260f))

        opened shouldBe 0
    }

    @Test
    fun upwardSwipeWhileZoomedInIsLeftToTheImage() {
        blocked = true
        setGalleryContent()

        swipe(start = Offset(200f, 500f), end = Offset(200f, 250f))

        opened shouldBe 0
    }

    @Test
    fun disabledGestureNeverOpensTheComments() {
        setGalleryContent(enabled = false)

        swipe(start = Offset(200f, 500f), end = Offset(200f, 250f))

        opened shouldBe 0
    }

    @Test
    fun tapStillReachesTheContent() {
        setGalleryContent()

        composeRule.onRoot().performTouchInput { click(Offset(200f, 300f)) }

        clicks shouldBe 1
        opened shouldBe 0
    }

    @Test
    fun twoFingerGestureIsNotASwipeUp() {
        setGalleryContent()

        composeRule.onRoot().performTouchInput {
            down(0, Offset(150f, 500f))
            down(1, Offset(250f, 500f))
            moveTo(0, Offset(150f, 250f))
            moveTo(1, Offset(250f, 250f))
            up(0)
            up(1)
        }

        opened shouldBe 0
    }
}
