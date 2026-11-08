package com.owindev.gcs

import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/** Which last exits are logged (REQ-046); reasons from `ApplicationExitInfo` (10, 13, 1). */
@Tag("REQ-046")
class LastExitNoteTest {

    @Test
    fun `GIVEN a stop from the Task Manager or force stop WHEN noted THEN it says the user stopped it`() {
        val reason = REASON_USER_REQUESTED

        val result = lastExitNote(reason)

        result shouldBeEqualTo "by the user (Task Manager or force stop)"
    }

    @Test
    fun `GIVEN a kill by an OEM's battery manager WHEN noted THEN it says the system stopped it`() {
        val reason = REASON_OTHER

        val result = lastExitNote(reason)

        result shouldBeEqualTo "by the system (an OEM's battery manager or similar)"
    }

    @Test
    fun `GIVEN an ordinary exit WHEN noted THEN nothing is logged`() {
        val reason = REASON_EXIT_SELF

        val result = lastExitNote(reason)

        result.shouldBeNull()
    }
}

private const val REASON_EXIT_SELF = 1
private const val REASON_USER_REQUESTED = 10
private const val REASON_OTHER = 13
