package com.freeconnect.bedrock.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.Room
import com.freeconnect.bedrock.data.db.AppDatabase
import com.freeconnect.bedrock.data.db.ServerDao
import com.freeconnect.bedrock.data.resourcepack.ResourcePackDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** DataStore extension property — one instance per process. */
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/**
 * Hilt module providing application-scoped singletons:
 * Room database, DAOs, and DataStore preferences.
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            "freeconnect_db"
        )
            // Replace with explicit Migration objects before shipping a schema change.
            .fallbackToDestructiveMigration()
            .build()

    @Provides
    @Singleton
    fun provideServerDao(db: AppDatabase): ServerDao = db.serverDao()

    @Provides
    @Singleton
    fun provideResourcePackDao(db: AppDatabase): ResourcePackDao = db.resourcePackDao()

    @Provides
    @Singleton
    fun provideDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        context.dataStore
}
