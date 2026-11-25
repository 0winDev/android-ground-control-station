package com.owindev.gcs.feature.hud

import com.owindev.gcs.core.domain.battery.FailsafeStatus
import com.owindev.gcs.core.domain.preflight.BatteryUnknownCause
import com.owindev.gcs.core.domain.preflight.PreflightColour
import com.owindev.gcs.core.domain.preflight.PreflightItem
import com.owindev.gcs.core.domain.preflight.PreflightKind
import com.owindev.gcs.core.domain.preflight.PreflightReason
import java.util.stream.Stream
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import org.amshove.kluent.shouldBeEqualTo
import org.junit.jupiter.api.Tag
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource

/** Each pre-flight reason in its own words, with its values (REQ-048): a warning must say its true reason. */
@Tag("REQ-048")
class ChecklistTextTest {

    @ParameterizedTest
    @MethodSource("reasonsInWords")
    internal fun `GIVEN a checklist item WHEN writing its reason THEN it has its own words`(case: ReasonInWords) {
        val item = ChecklistItem(PreflightItem(case.kind, case.colour, case.reason), reasonAge = case.age)

        val result = item.reasonWords()

        result shouldBeEqualTo case.words
    }

    /** One reason and the words it must show. */
    internal data class ReasonInWords(
        val kind: PreflightKind,
        val colour: PreflightColour,
        val reason: PreflightReason,
        val age: Duration?,
        val words: Words?,
    )

    private companion object {
        const val PRE_ARM_TEXT = "PreArm: GPS 1: Bad fix"
        val AGE = 6.seconds

        @JvmStatic
        fun reasonsInWords(): Stream<Arguments> = Stream.of(
            red(PreflightKind.GPS, PreflightReason.NoFix3d, Words(R.string.hud_reason_no_fix_3d)),
            red(PreflightKind.HOME, PreflightReason.HomeUnknown, Words(R.string.hud_reason_home_unknown)),
            red(
                PreflightKind.VEHICLE_PREARM,
                PreflightReason.PreArmFailing(text = null, current = false),
                Words(R.string.hud_reason_prearm_failing_no_text),
            ),
            red(
                PreflightKind.VEHICLE_PREARM,
                PreflightReason.PreArmFailing(PRE_ARM_TEXT, current = true),
                Words(R.string.hud_reason_prearm_failing, listOf(PRE_ARM_TEXT, AGE)),
                age = AGE,
            ),
            red(
                PreflightKind.VEHICLE_PREARM,
                PreflightReason.PreArmFailing(PRE_ARM_TEXT, current = false),
                Words(R.string.hud_reason_prearm_failing_old, listOf(PRE_ARM_TEXT, AGE)),
                age = AGE,
            ),
            amber(
                PreflightReason.BatteryUnknown(BatteryUnknownCause.NO_MONITOR),
                Words(R.string.hud_reason_battery_no_monitor),
            ),
            amber(
                PreflightReason.BatteryUnknown(BatteryUnknownCause.NO_READING),
                Words(R.string.hud_reason_battery_no_reading),
            ),
            amber(
                PreflightReason.FailsafeNotArmed(
                    low = FailsafeStatus.WARN_ONLY,
                    critical = FailsafeStatus.NO_THRESHOLD,
                ),
                Words(
                    R.string.hud_reason_failsafe_not_armed,
                    listOf(Words(R.string.hud_failsafe_warn_only), Words(R.string.hud_failsafe_no_threshold)),
                ),
            ),
            Arguments.of(
                ReasonInWords(PreflightKind.LINK, PreflightColour.GREEN, PreflightReason.Ok, age = null, words = null),
            ),
        )

        fun red(kind: PreflightKind, reason: PreflightReason, words: Words, age: Duration? = null): Arguments =
            Arguments.of(ReasonInWords(kind, PreflightColour.RED, reason, age, words))

        fun amber(reason: PreflightReason, words: Words): Arguments =
            Arguments.of(ReasonInWords(PreflightKind.BATTERY_LIMITS, PreflightColour.AMBER, reason, age = null, words))
    }
}
