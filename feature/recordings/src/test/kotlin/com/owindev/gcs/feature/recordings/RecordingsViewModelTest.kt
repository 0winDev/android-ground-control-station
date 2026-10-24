package com.owindev.gcs.feature.recordings

import app.cash.turbine.test
import app.cash.turbine.testIn
import app.cash.turbine.turbineScope
import com.owindev.gcs.core.domain.recording.DeleteResult
import com.owindev.gcs.core.domain.recording.ExportTarget
import com.owindev.gcs.core.domain.recording.ImportResult
import com.owindev.gcs.core.domain.recording.ImportSource
import com.owindev.gcs.core.domain.recording.RecordingRepository
import com.owindev.gcs.core.domain.recording.RecordingSummary
import com.owindev.gcs.core.domain.recording.SaveResult
import com.owindev.gcs.core.domain.replay.ReplayFailure
import com.owindev.gcs.core.domain.replay.ReplayRefusal
import com.owindev.gcs.core.domain.replay.ReplayRepository
import com.owindev.gcs.core.domain.replay.ReplayState
import com.owindev.gcs.core.testing.MainDispatcherExtension
import com.owindev.gcs.core.testing.coVerifyNever
import com.owindev.gcs.core.testing.coVerifyOnce
import com.owindev.gcs.core.testing.verifyNever
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.shouldBeEqualTo
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

@Tag("REQ-038")
class RecordingsViewModelTest {

    @JvmField
    @RegisterExtension
    val mainDispatcher = MainDispatcherExtension()

    private val recordings = MutableStateFlow(listOf(RECORDING))
    private val repository: RecordingRepository = mockk()
    private val replay: ReplayRepository = mockk()

    private lateinit var viewModel: RecordingsViewModel

    @BeforeEach
    fun setUp() {
        every { repository.recordings } returns recordings
        viewModel = RecordingsViewModel(repository, replay)
    }

    @Test
    fun `GIVEN recordings WHEN observing the state THEN lists them`() = runTest {
        turbineScope {
            recordings.value = listOf(RECORDING)

            val result = viewModel.state.testIn(backgroundScope)

            result.expectMostRecentItem() shouldBeEqualTo RecordingsUiState(recordings = listOf(RECORDING))
        }
    }

    @Tag("REQ-037")
    @Test
    fun `GIVEN a replay that starts WHEN playing a recording THEN the operator goes to the flight screen`() = runTest {
        coEvery { repository.fileOf(NAME) } returns FILE
        every { replay.play(FILE) } returns ReplayState.Playing(FILE)
        viewModel.effect.test {
            viewModel.onPlay(NAME)

            val result = awaitItem()

            result shouldBeEqualTo RecordingsEffect.ReplayStarted
        }
    }

    @Tag("REQ-037")
    @Test
    fun `GIVEN a connected vehicle WHEN playing a recording THEN the refusal is shown and the operator stays`() =
        runTest {
            turbineScope {
                val refused = ReplayState.Refused(ReplayRefusal.VEHICLE_CONNECTED)
                coEvery { repository.fileOf(NAME) } returns FILE
                every { replay.play(FILE) } returns refused
                val state = viewModel.state.testIn(backgroundScope)
                val effects = viewModel.effect.testIn(backgroundScope)

                viewModel.onPlay(NAME)

                state.expectMostRecentItem().message shouldBeEqualTo RecordingsMessage.Replay(refused)
                effects.expectNoEvents()
            }
        }

    @Tag("REQ-037")
    @Test
    fun `GIVEN a recording that is not one WHEN playing it THEN the failure is shown`() = runTest {
        turbineScope {
            val failed = ReplayState.Failed(FILE, ReplayFailure.NOT_A_RECORDING)
            coEvery { repository.fileOf(NAME) } returns FILE
            every { replay.play(FILE) } returns failed
            val state = viewModel.state.testIn(backgroundScope)

            viewModel.onPlay(NAME)

            state.expectMostRecentItem().message shouldBeEqualTo RecordingsMessage.Replay(failed)
        }
    }

    @Test
    fun `GIVEN a recording no longer there WHEN playing it THEN it is shown missing and no replay is asked`() =
        runTest {
            turbineScope {
                coEvery { repository.fileOf(NAME) } returns null
                val state = viewModel.state.testIn(backgroundScope)

                viewModel.onPlay(NAME)

                state.expectMostRecentItem().message shouldBeEqualTo RecordingsMessage.Missing(NAME)
                verifyNever {
                    replay.play(any())
                }
            }
        }

    @Test
    fun `GIVEN a recording WHEN exporting it THEN its file goes to the share sheet`() = runTest {
        coEvery { repository.fileOf(NAME) } returns FILE
        viewModel.effect.test {
            viewModel.onShare(NAME)

            val result = awaitItem()

            result shouldBeEqualTo RecordingsEffect.Share(FILE)
        }
    }

    @Test
    fun `GIVEN the recording in progress WHEN exporting it THEN it is refused and nothing goes to the share sheet`() =
        runTest {
            turbineScope {
                recordings.value = listOf(RECORDING.copy(inProgress = true))
                val state = viewModel.state.testIn(backgroundScope)
                val effects = viewModel.effect.testIn(backgroundScope)

                viewModel.onShare(NAME)

                state.expectMostRecentItem().message shouldBeEqualTo RecordingsMessage.ExportInProgress(NAME)
                effects.expectNoEvents()
            }
        }

    @Test
    fun `GIVEN a closed recording WHEN saving it THEN the screen picks a place suggested under its name`() = runTest {
        viewModel.effect.test {
            viewModel.onSaveRequested(NAME)

            val result = awaitItem()

            result shouldBeEqualTo RecordingsEffect.PickSaveTarget(NAME)
        }
    }

    @Test
    fun `GIVEN the recording in progress WHEN saving it THEN refused before any place is picked`() = runTest {
        turbineScope {
            recordings.value = listOf(RECORDING.copy(inProgress = true))
            val state = viewModel.state.testIn(backgroundScope)
            val effects = viewModel.effect.testIn(backgroundScope)

            viewModel.onSaveRequested(NAME)

            state.expectMostRecentItem().message shouldBeEqualTo RecordingsMessage.ExportInProgress(NAME)
            effects.expectNoEvents()
        }
    }

    @Test
    fun `GIVEN a place picked WHEN the copy is saved THEN it is said with the recording's name`() = runTest {
        turbineScope {
            coEvery { repository.saveCopy(NAME, TARGET) } returns SaveResult.Saved
            val state = viewModel.state.testIn(backgroundScope)

            viewModel.onSaveTargetPicked(NAME, TARGET)

            state.expectMostRecentItem().message shouldBeEqualTo RecordingsMessage.Save(NAME, SaveResult.Saved)
        }
    }

    @Test
    fun `GIVEN a place picked WHEN the copy fails THEN the failure is shown`() = runTest {
        turbineScope {
            coEvery { repository.saveCopy(NAME, TARGET) } returns SaveResult.Failed
            val state = viewModel.state.testIn(backgroundScope)

            viewModel.onSaveTargetPicked(NAME, TARGET)

            state.expectMostRecentItem().message shouldBeEqualTo RecordingsMessage.Save(NAME, SaveResult.Failed)
        }
    }

    @Test
    fun `GIVEN the picker closed WHEN saving THEN cancelled is shown and nothing is copied`() = runTest {
        turbineScope {
            val state = viewModel.state.testIn(backgroundScope)

            viewModel.onSaveTargetPicked(NAME, target = null)

            state.expectMostRecentItem().message shouldBeEqualTo RecordingsMessage.Save(NAME, SaveResult.Cancelled)
            coVerifyNever {
                repository.saveCopy(any(), any())
            }
        }
    }

    @Test
    fun `GIVEN a recording WHEN deleting it THEN asks for confirmation first`() = runTest {
        turbineScope {
            val state = viewModel.state.testIn(backgroundScope)

            viewModel.onDeleteRequested(NAME)

            state.expectMostRecentItem().pendingDelete shouldBeEqualTo NAME
            coVerifyNever {
                repository.delete(any())
            }
        }
    }

    @Test
    fun `GIVEN a delete asked WHEN cancelled THEN nothing is deleted`() = runTest {
        turbineScope {
            val state = viewModel.state.testIn(backgroundScope)
            viewModel.onDeleteRequested(NAME)

            viewModel.onDeleteCancelled()

            state.expectMostRecentItem().pendingDelete shouldBeEqualTo null
            coVerifyNever {
                repository.delete(any())
            }
        }
    }

    @Test
    fun `GIVEN a delete asked WHEN confirmed THEN it is deleted with no message`() = runTest {
        turbineScope {
            coEvery { repository.delete(NAME) } returns DeleteResult.Deleted
            val state = viewModel.state.testIn(backgroundScope)
            viewModel.onDeleteRequested(NAME)

            viewModel.onDeleteConfirmed()

            state.expectMostRecentItem() shouldBeEqualTo RecordingsUiState(recordings = listOf(RECORDING))
            coVerifyOnce {
                repository.delete(NAME)
            }
        }
    }

    @Test
    fun `GIVEN the recording in progress WHEN its delete is confirmed THEN the refusal is shown`() = runTest {
        turbineScope {
            coEvery { repository.delete(NAME) } returns DeleteResult.InProgress
            val state = viewModel.state.testIn(backgroundScope)
            viewModel.onDeleteRequested(NAME)

            viewModel.onDeleteConfirmed()

            state.expectMostRecentItem().message shouldBeEqualTo RecordingsMessage.Delete(DeleteResult.InProgress)
        }
    }

    @Tag("REQ-039")
    @Test
    fun `GIVEN an import that made room WHEN it ends THEN shows its name and the recordings deleted`() = runTest {
        turbineScope {
            val imported = ImportResult.Imported(IMPORTED_NAME, deleted = listOf(NAME))
            coEvery { repository.import(SOURCE) } returns imported
            val state = viewModel.state.testIn(backgroundScope)

            viewModel.onImport(SOURCE)

            state.expectMostRecentItem().message shouldBeEqualTo RecordingsMessage.Import(imported)
        }
    }

    @Test
    fun `GIVEN an import running WHEN importing again THEN the second is ignored`() = runTest {
        turbineScope {
            val pending = CompletableDeferred<ImportResult>()
            coEvery { repository.import(SOURCE) } coAnswers { pending.await() }
            val state = viewModel.state.testIn(backgroundScope)
            viewModel.onImport(SOURCE)

            viewModel.onImport(SOURCE)

            state.expectMostRecentItem().importing shouldBeEqualTo true
            coVerifyOnce {
                repository.import(SOURCE)
            }
        }
    }

    @Test
    fun `GIVEN a message WHEN dismissed THEN it is gone`() = runTest {
        turbineScope {
            coEvery { repository.import(SOURCE) } returns ImportResult.NotATlog
            val state = viewModel.state.testIn(backgroundScope)
            viewModel.onImport(SOURCE)

            viewModel.onMessageDismissed()

            state.expectMostRecentItem() shouldBeEqualTo RecordingsUiState(recordings = listOf(RECORDING))
        }
    }

    private object EmptySource : ImportSource {
        override val displayName: String? = null
        override val declaredBytes: Long? = null

        override fun open(): InputStream = ByteArray(0).inputStream()
    }

    private object DiscardTarget : ExportTarget {
        override fun open(): OutputStream = OutputStream.nullOutputStream()
    }

    private companion object {
        val TARGET: ExportTarget = DiscardTarget
        const val NAME = "2026-10-20_14-30-05.tlog"
        const val IMPORTED_NAME = "qgroundcontrol.tlog"
        val FILE = File(NAME)
        val SOURCE: ImportSource = EmptySource
        val RECORDING = RecordingSummary(
            name = NAME,
            startMillis = 1_792_091_306_747L,
            duration = 10.seconds,
            sizeBytes = 49_741,
            inProgress = false,
            replaying = false,
        )
    }
}
