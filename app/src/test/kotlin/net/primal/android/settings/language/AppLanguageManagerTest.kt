package net.primal.android.settings.language

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import androidx.test.core.app.ApplicationProvider
import io.kotest.matchers.shouldBe
import java.util.Locale
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AppLanguageManagerTest {

    @Test
    fun systemDefault_usesThePerAppLocaleFromTheContext() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        AppLanguageManager.setLanguage(context, AppLanguage.SYSTEM_DEFAULT)
        Resources.getSystem().configuration.setLocale(Locale.ENGLISH)
        val configuration = Configuration(context.resources.configuration).apply {
            setLocale(Locale.FRENCH)
        }
        val perAppContext = context.createConfigurationContext(configuration)

        val wrapped = AppLanguageManager.wrap(perAppContext)

        Locale.getDefault() shouldBe Locale.FRENCH
        wrapped.resources.configuration.locales[0] shouldBe Locale.FRENCH
    }
}
