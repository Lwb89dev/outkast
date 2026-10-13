package net.primal.android.theme

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.dataStoreFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import net.primal.android.core.serialization.datastore.StringSerializer
import net.primal.android.theme.active.ActiveThemeStore
import net.primal.android.theme.domain.PrimalTheme
import net.primal.core.testing.CoroutinesTestRule
import net.primal.core.testing.advanceUntilIdleAndDelay
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@ExperimentalCoroutinesApi
@RunWith(AndroidJUnit4::class)
class ActiveThemeStoreTest {

    companion object {
        private const val DATA_STORE_FILE = "activeTheme"
    }

    @get:Rule
    val coroutinesTestRule = CoroutinesTestRule()

    private val testContext: Context = InstrumentationRegistry.getInstrumentation().targetContext

    private val persistence: DataStore<String> = DataStoreFactory.create(
        serializer = StringSerializer(),
        produceFile = { testContext.dataStoreFile(DATA_STORE_FILE) },
    )

    private fun createActiveThemeStore(): ActiveThemeStore {
        return ActiveThemeStore(
            dispatchers = coroutinesTestRule.dispatcherProvider,
            persistence = persistence,
        )
    }

    @Test
    fun `initialValue is null`() {
        val activeThemeStore = createActiveThemeStore()
        val actual = activeThemeStore.userThemeState.value
        actual.shouldBeNull()
    }

    @Test
    fun `setUserTheme stores the user theme`() =
        runTest {
            val expectedTheme = net.primal.android.theme.domain.PrimalTheme.OutkastDark
            val activeThemeStore = createActiveThemeStore()
            activeThemeStore.setUserTheme(expectedTheme.themeName)
            advanceUntilIdleAndDelay()

            val actual = activeThemeStore.userThemeState.value
            actual.shouldNotBeNull()
            actual shouldBe expectedTheme
        }

    @Test
    fun `userThemeState is null if unknown theme is saved`() =
        runTest {
            persistence.updateData { "PrimalFutureTheme" }
            val activeThemeStore = createActiveThemeStore()
            advanceUntilIdle()

            val actual = activeThemeStore.userThemeState.value
            actual.shouldBeNull()
        }

    @Test
    fun `userThemeState corresponds to saved theme`() =
        runTest {
            val expectedTheme = net.primal.android.theme.domain.PrimalTheme.OutkastLight
            persistence.updateData { expectedTheme.themeName }
            val activeThemeStore = createActiveThemeStore()
            advanceUntilIdle()

            val actual = activeThemeStore.userThemeState.value
            actual.shouldNotBeNull()
            actual shouldBe expectedTheme
        }

    @Test
    fun `legacy sunset theme migrates to OUTKAST dark`() =
        runTest {
            persistence.updateData { "sunset" }
            val activeThemeStore = createActiveThemeStore()
            advanceUntilIdle()

            val actual = activeThemeStore.userThemeState.value
            actual.shouldNotBeNull()
            actual shouldBe net.primal.android.theme.domain.PrimalTheme.OutkastDark
        }

    @Test
    fun `legacy sunrise theme migrates to OUTKAST light`() =
        runTest {
            persistence.updateData { "sunrise" }
            val activeThemeStore = createActiveThemeStore()
            advanceUntilIdle()

            val actual = activeThemeStore.userThemeState.value
            actual.shouldNotBeNull()
            actual shouldBe net.primal.android.theme.domain.PrimalTheme.OutkastLight
        }

    @Test
    fun `legacy sunset theme persists migrated value to DataStore`() =
        runTest {
            persistence.updateData { "sunset" }
            createActiveThemeStore()

            val persisted = persistence.data.first()
            persisted shouldBe "outkast-dark"
        }

    @Test
    fun `legacy sunrise theme persists migrated value to DataStore`() =
        runTest {
            persistence.updateData { "sunrise" }
            createActiveThemeStore()

            val persisted = persistence.data.first()
            persisted shouldBe "outkast-light"
        }

    /**
     * A store wired like the one `ThemeModule` provides: its serializer reads an absent file as
     * the default theme. [DATA_STORE_FILE] is not reused so these stores start from no file at all,
     * which is the state a fresh install is in.
     */
    private fun createStoreLikeProduction(fileName: String): Pair<DataStore<String>, ActiveThemeStore> {
        val productionPersistence = DataStoreFactory.create(
            serializer = StringSerializer(defaultValue = ActiveThemeStore.DEFAULT_THEME.themeName),
            produceFile = { testContext.dataStoreFile(fileName) },
        )
        val store = ActiveThemeStore(
            dispatchers = coroutinesTestRule.dispatcherProvider,
            persistence = productionPersistence,
        )
        return productionPersistence to store
    }

    @Test
    fun `OUTKAST dark is the default theme`() {
        ActiveThemeStore.DEFAULT_THEME shouldBe PrimalTheme.OutkastDark
    }

    @Test
    fun `a theme that was never chosen is the default theme`() {
        val (_, store) = createStoreLikeProduction(fileName = "neverChosenTheme")

        store.userThemeState.value shouldBe ActiveThemeStore.DEFAULT_THEME
    }

    @Test
    fun `choosing another theme replaces the default`() =
        runTest {
            val (_, store) = createStoreLikeProduction(fileName = "chosenOverDefaultTheme")

            store.setUserTheme(PrimalTheme.OutkastLight.themeName)
            advanceUntilIdleAndDelay()

            store.userThemeState.value shouldBe PrimalTheme.OutkastLight
        }

    @Test
    fun `following the system stays null instead of falling back to the default theme`() =
        runTest {
            // "Adjust to the system" is stored as an empty string. It is a choice the user made, so
            // changing the default for people who never chose must not turn it into OUTKAST Dark.
            val (persistence, _) = createStoreLikeProduction(fileName = "followSystemTheme")
            persistence.updateData { "" }

            val store = ActiveThemeStore(
                dispatchers = coroutinesTestRule.dispatcherProvider,
                persistence = persistence,
            )
            advanceUntilIdle()

            store.userThemeState.value.shouldBeNull()
        }
}
