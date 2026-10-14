package com.owindev.gcs.data.vehicle.di

import com.owindev.gcs.core.domain.alert.AlertRepository
import com.owindev.gcs.core.domain.alert.AlertRule
import com.owindev.gcs.core.domain.link.LinkRepository
import com.owindev.gcs.core.domain.telemetry.StalenessRule
import com.owindev.gcs.core.domain.telemetry.TelemetryRepository
import com.owindev.gcs.core.domain.vehicle.LinkLossRule
import com.owindev.gcs.core.domain.vehicle.VehicleRepository
import com.owindev.gcs.core.transport.UdpTransport
import com.owindev.gcs.data.vehicle.alert.DefaultAlertRepository
import com.owindev.gcs.data.vehicle.link.DefaultLinkRepository
import com.owindev.gcs.data.vehicle.state.DefaultVehicleRepository
import com.owindev.gcs.data.vehicle.telemetry.DefaultTelemetryRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlin.time.TimeSource
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

    @Binds
    internal abstract fun bindTelemetryRepository(impl: DefaultTelemetryRepository): TelemetryRepository

    @Binds
    internal abstract fun bindAlertRepository(impl: DefaultAlertRepository): AlertRepository

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

        /** Monotonic and counting through device sleep, never wall-clock time (see [ElapsedRealtimeTimeSource]). */
        @Provides
        fun provideTimeSource(): TimeSource.WithComparableMarks = ElapsedRealtimeTimeSource

        /** REQ-017's default timeout (3 s); change it here to configure the link-loss watchdog. */
        @Provides
        fun provideLinkLossRule(): LinkLossRule = LinkLossRule()

        /** The staleness thresholds decided in #39 (REQ-026); change them here. */
        @Provides
        fun provideStalenessRule(): StalenessRule = StalenessRule()

        /** The battery thresholds decided in #41 (REQ-028), judging staleness as the rest of the app does. */
        @Provides
        fun provideAlertRule(staleness: StalenessRule): AlertRule = AlertRule(staleness)
    }
}
