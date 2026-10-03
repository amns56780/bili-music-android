package com.bilimusic.app.di

import com.bilimusic.app.data.repository.AuthRepository
import com.bilimusic.app.data.repository.AuthRepositoryImpl
import com.bilimusic.app.data.repository.EpisodeRepository
import com.bilimusic.app.data.repository.EpisodeRepositoryImpl
import com.bilimusic.app.data.repository.ImportRepository
import com.bilimusic.app.data.repository.ImportRepositoryImpl
import com.bilimusic.app.data.repository.PlaylistRepository
import com.bilimusic.app.data.repository.PlaylistRepositoryImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindPlaylistRepository(impl: PlaylistRepositoryImpl): PlaylistRepository

    @Binds
    @Singleton
    abstract fun bindAuthRepository(impl: AuthRepositoryImpl): AuthRepository

    @Binds
    @Singleton
    abstract fun bindImportRepository(impl: ImportRepositoryImpl): ImportRepository

    @Binds
    @Singleton
    abstract fun bindEpisodeRepository(impl: EpisodeRepositoryImpl): EpisodeRepository
}
