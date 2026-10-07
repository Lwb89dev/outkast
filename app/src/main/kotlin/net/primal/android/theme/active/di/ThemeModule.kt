package net.primal.android.theme.active.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.dataStoreFile
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Qualifier
import javax.inject.Singleton
import net.primal.android.core.serialization.datastore.StringSerializer
import net.primal.android.theme.active.ActiveThemeStore

@Module
@InstallIn(SingletonComponent::class)
object ThemeModule {

    @Provides
    @Singleton
    @ActiveThemeDataStore
    fun activeThemeDataStore(@ApplicationContext context: Context): DataStore<String> =
        DataStoreFactory.create(
            produceFile = { context.dataStoreFile("active_theme.txt") },
            // A theme that was never chosen reads as the default theme. Only an absent file does:
            // choosing "adjust to the system" writes an empty string, which is read back as such
            // and keeps following the system.
            serializer = StringSerializer(defaultValue = ActiveThemeStore.DEFAULT_THEME.themeName),
        )
}

@Qualifier
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.VALUE_PARAMETER)
annotation class ActiveThemeDataStore
