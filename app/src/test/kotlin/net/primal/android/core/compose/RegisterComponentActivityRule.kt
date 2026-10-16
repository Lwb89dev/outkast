package net.primal.android.core.compose

import android.app.Application
import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.test.core.app.ApplicationProvider
import org.junit.rules.ExternalResource
import org.robolectric.Shadows.shadowOf

/**
 * Makes `createComposeRule()` work in a plain Robolectric unit test.
 *
 * The compose rule launches `ComponentActivity`, which Robolectric can only start if the activity
 * is in the merged manifest. That needs `isIncludeAndroidResources`, a build-wide switch that
 * makes every unit test of the module slower; registering the one activity here keeps it local to
 * the tests that need it. Chain it *outside* the compose rule, because the rule launches the
 * activity before any `@Before` method runs.
 */
class RegisterComponentActivityRule : ExternalResource() {
    override fun before() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val component = ComponentName(context.packageName, ComponentActivity::class.java.name)
        shadowOf(context.packageManager).addActivityIfNotPresent(component)
    }
}
