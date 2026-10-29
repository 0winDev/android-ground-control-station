package com.owindev.gcs.data.vehicle.di

import android.content.Context
import com.owindev.gcs.core.domain.alert.AlertRepository
import com.owindev.gcs.core.domain.alert.AlertRule
import com.owindev.gcs.core.domain.attention.FlightAttentionRule
import com.owindev.gcs.core.domain.link.LinkRepository
import com.owindev.gcs.core.domain.recording.ImportRule
import com.owindev.gcs.core.domain.recording.RecordingRepository
import com.owindev.gcs.core.domain.recording.RecordingRule
import com.owindev.gcs.core.domain.replay.ReplayRepository
import com.owindev.gcs.core.domain.replay.ReplayRule
import com.owindev.gcs.core.domain.telemetry.LandedRule
import com.owindev.gcs.core.domain.telemetry.StalenessRule
import com.owindev.gcs.core.domain.telemetry.TelemetryRepository
import com.owindev.gcs.core.domain.telemetry.TrailRule
import com.owindev.gcs.core.domain.time.DeviceClock
import com.owindev.gcs.core.domain.vehicle.LinkLossRule
import com.owindev.gcs.core.domain.vehicle.VehicleRepository
import com.owindev.gcs.core.domain.vehicle.command.CommandRepository
import com.owindev.gcs.core.transport.DatagramSource
import com.owindev.gcs.core.transport.UdpTransport
import com.owindev.gcs.data.vehicle.alert.DefaultAlertRepository
import com.owindev.gcs.data.vehicle.command.CommandRoutes
import com.owindev.gcs.data.vehicle.command.DefaultCommandRepository
import com.owindev.gcs.data.vehicle.link.DefaultLinkRepository
import com.owindev.gcs.data.vehicle.link.LinkSession
import com.owindev.gcs.data.vehicle.recording.DefaultRecordingRepository
import com.owindev.gcs.data.vehicle.recording.FlightRecorder
import com.owindev.gcs.data.vehicle.recording.RECORDINGS_FOLDER
import com.owindev.gcs.data.vehicle.recording.RecordingsFolder
import com.owindev.gcs.data.vehicle.replay.DefaultReplayRepository
import com.owindev.gcs.data.vehicle.replay.PipelineClock
import com.owindev.gcs.data.vehicle.replay.TlogDatagramSource
import com.owindev.gcs.data.vehicle.state.ActiveVehicle
import com.owindev.gcs.data.vehicle.state.DefaultVehicleRepository
import com.owindev.gcs.data.vehicle.telemetry.DefaultTelemetryRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import java.time.Clock
import javax.inject.Singleton
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.plus

@Module
@InstallIn(SingletonComponent::class)
abstract class VehicleDataModule {

    @Binds
    internal abstract fun bindLinkRepository(impl: DefaultLinkRepository): LinkRepository

    @Binds
    internal abstract fun bindVehicleRepository(impl: DefaultVehicleRepository): VehicleRepository

    @Binds
    internal abstract fun bindCommandRepository(impl: DefaultCommandRepository): CommandRepository

    @Binds
    internal abstract fun bindCommandRoutes(impl: DefaultVehicleRepository): CommandRoutes

    @Binds
    internal abstract fun bindTelemetryRepository(impl: DefaultTelemetryRepository): TelemetryRepository

    @Binds
    internal abstract fun bindAlertRepository(impl: DefaultAlertRepository): AlertRepository

    @Binds
    internal abstract fun bindLiveSource(transport: UdpTransport): DatagramSource

    @Binds
    internal abstract fun bindTimeSource(clock: PipelineClock): TimeSource.WithComparableMarks

    // One provider per object of a pure Kotlin module, each where its configuration is decided.
    @Suppress("TooManyFunctions")
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

        /**
         * One clock for the whole pipeline: the device's, monotonic and counting through sleep, never wall-clock time
         * (see [ElapsedRealtimeTimeSource]), or a replay's while one plays (REQ-037).
         */
        @Provides
        @Singleton
        internal fun providePipelineClock(): PipelineClock = PipelineClock(device = ElapsedRealtimeTimeSource)

        /** The device's own time, for what a paused replay must not stop (REQ-037). */
        @Provides
        fun provideDeviceClock(): DeviceClock = DeviceClock(ElapsedRealtimeTimeSource)

        /** REQ-017's default timeout (3 s); change it here to configure the link-loss watchdog. */
        @Provides
        fun provideLinkLossRule(): LinkLossRule = LinkLossRule()

        @Provides
        fun provideFlightAttentionRule(linkLossRule: LinkLossRule): FlightAttentionRule =
            FlightAttentionRule(linkLossRule)

        /** The staleness thresholds decided in #39 (REQ-026); change them here. */
        @Provides
        fun provideStalenessRule(): StalenessRule = StalenessRule()

        /** On the ground only on a fresh ON_GROUND (REQ-052), judging its age as the rest of the app does. */
        @Provides
        fun provideLandedRule(staleness: StalenessRule): LandedRule = LandedRule(staleness)

        /** The battery thresholds decided in #41 (REQ-028), judging staleness as the rest of the app does. */
        @Provides
        fun provideAlertRule(staleness: StalenessRule, timeSource: TimeSource.WithComparableMarks): AlertRule =
            AlertRule(staleness = staleness, timeSource = timeSource)

        /** The trail limits decided for v0.3 (REQ-035: 2 m, 5 000 points), trusting positions as the HUD does. */
        @Provides
        fun provideTrailRule(staleness: StalenessRule): TrailRule = TrailRule(staleness)

        /** The recording thresholds decided for v0.3 (REQ-036, REQ-039: 10 min, 256 MiB); change them here. */
        @Provides
        fun provideRecordingRule(): RecordingRule = RecordingRule()

        /** Flight logs in the app's private files (S-5), one folder for the recorder, the replay and the list. */
        @Provides
        @Singleton
        internal fun provideRecordingsFolder(
            @ApplicationContext context: Context,
            rule: RecordingRule,
        ): RecordingsFolder = RecordingsFolder(directory = File(context.filesDir, RECORDINGS_FOLDER), rule = rule)

        /**
         * Flight logs written on `Dispatchers.IO`, stamped with the wall clock. Only the live link is recorded, so its
         * link loss is timed on the device clock, never a replay's.
         */
        @Provides
        @Singleton
        internal fun provideFlightRecorder(
            folder: RecordingsFolder,
            rule: RecordingRule,
            activeVehicle: ActiveVehicle,
            @ApplicationScope scope: CoroutineScope,
        ): FlightRecorder = FlightRecorder(
            folder = folder,
            rule = rule,
            activeVehicle = activeVehicle,
            timeSource = ElapsedRealtimeTimeSource,
            clock = Clock.systemDefaultZone(),
            scope = scope + Dispatchers.IO,
        )

        /** Imports are held to the recordings limit (REQ-038, REQ-039), whatever their source declares. */
        @Provides
        fun provideImportRule(recordingRule: RecordingRule): ImportRule =
            ImportRule(maxBytes = recordingRule.maxTotalBytes)

        @Provides
        @Singleton
        internal fun provideRecordingRepository(
            folder: RecordingsFolder,
            recorder: FlightRecorder,
            replay: ReplayRepository,
            rule: ImportRule,
        ): RecordingRepository = DefaultRecordingRepository(
            folder = folder,
            recorder = recorder,
            replay = replay,
            rule = rule,
            ioDispatcher = Dispatchers.IO,
        )

        // One parameter per collaborator of the repository, as its constructor has.
        @Suppress("LongParameterList")
        @Provides
        @Singleton
        internal fun provideReplayRepository(
            session: LinkSession,
            vehicleRepository: VehicleRepository,
            clock: PipelineClock,
            folder: RecordingsFolder,
            linkLossRule: LinkLossRule,
            @ApplicationScope scope: CoroutineScope,
        ): ReplayRepository = DefaultReplayRepository(
            session = session,
            vehicleRepository = vehicleRepository,
            clock = clock,
            folder = folder,
            rule = ReplayRule(linkLossRule),
            replaySource = { recording, replayClock, onGap ->
                TlogDatagramSource(recording, replayClock, ioDispatcher = Dispatchers.IO, onGap = onGap)
            },
            scope = scope,
        )
    }
}
