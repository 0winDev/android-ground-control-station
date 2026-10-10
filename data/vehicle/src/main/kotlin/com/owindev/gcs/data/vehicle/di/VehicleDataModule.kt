package com.owindev.gcs.data.vehicle.di

import com.owindev.gcs.core.domain.link.LinkRepository
import com.owindev.gcs.core.domain.vehicle.VehicleRepository
import com.owindev.gcs.core.transport.UdpTransport
import com.owindev.gcs.data.vehicle.link.DefaultLinkRepository
import com.owindev.gcs.data.vehicle.state.DefaultVehicleRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

@Module
@InstallIn(SingletonComponent::class)
abstract class VehicleDataModule {

    @Binds
    internal abstract fun bindLinkRepository(impl: DefaultLinkRepository): LinkRepository

    @Binds
    internal abstract fun bindVehicleRepository(impl: DefaultVehicleRepository): VehicleRepository

    companion object {

        /** One transport for the whole app: there is one vehicle link. */
        @Provides
        @Singleton
        fun provideUdpTransport(): UdpTransport = UdpTransport(port = UdpTransport.DEFAULT_PORT)

        /** A failure in one app-wide job must not cancel the others, hence the [SupervisorJob]. */
        @Provides
        @Singleton
        @ApplicationScope
        fun provideApplicationScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
}
