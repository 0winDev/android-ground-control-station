package com.owindev.gcs.core.domain.message

import com.owindev.gcs.core.domain.alert.AlertLevel
import org.amshove.kluent.shouldBeEqualTo
import org.junit.jupiter.api.Tag
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

@Tag("REQ-047")
class MessageLevelTest {

    @ParameterizedTest
    @CsvSource(
        "EMERGENCY, EMERGENCY",
        "ALERT, EMERGENCY",
        "CRITICAL, EMERGENCY",
        "ERROR, EMERGENCY",
        "WARNING, CAUTION",
        "NOTICE, ADVISORY",
        "INFO, ADVISORY",
        "DEBUG, ADVISORY",
        "UNKNOWN, ADVISORY",
    )
    fun `GIVEN a message severity WHEN shown THEN it takes its alert level`(
        severity: MessageSeverity,
        expected: AlertLevel,
    ) {
        val result = severity.level

        result shouldBeEqualTo expected
    }
}
