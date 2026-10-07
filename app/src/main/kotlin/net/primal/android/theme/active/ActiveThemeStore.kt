package net.primal.android.theme.active

import androidx.datastore.core.DataStore
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.runBlocking
import net.primal.android.theme.active.di.ActiveThemeDataStore
import net.primal.android.theme.domain.PrimalTheme
import net.primal.core.utils.coroutines.DispatcherProvider

@Singleton
class ActiveThemeStore @Inject constructor(
    dispatchers: DispatcherProvider,
    @ActiveThemeDataStore private val persistence: DataStore<String>,
) {

    private val scope = CoroutineScope(dispatchers.io())

    val userThemeState: StateFlow<PrimalTheme?> = persistence.data
        .map { rawName -> PrimalTheme.valueOf(themeName = rawName) }
        .stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue = initialValue(),
        )

    private fun initialValue(): PrimalTheme? =
        runBlocking {
            val rawName = persistence.data.first()
            val theme = PrimalTheme.valueOf(themeName = rawName)
            if (theme != null && theme.themeName != rawName) {
                persistence.updateData { theme.themeName }
            }
            theme
        }

    suspend fun setUserTheme(theme: String) {
        persistence.updateData { theme }
    }

    companion object {
        /**
         * The theme a user sees until they pick another one. It is also what the theme store's
         * DataStore reads as when its file does not exist yet (see `ThemeModule`), which is why
         * a fresh install, or an existing one that never opened the appearance settings, starts
         * here instead of following the system.
         */
        val DEFAULT_THEME: PrimalTheme = PrimalTheme.DarkPixel
    }
}
