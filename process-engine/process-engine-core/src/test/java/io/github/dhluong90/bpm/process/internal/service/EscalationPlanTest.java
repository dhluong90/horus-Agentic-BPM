package io.github.dhluong90.bpm.process.internal.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.dhluong90.bpm.process.api.WorkingCalendar;
import io.github.dhluong90.bpm.process.internal.model.horus.EscalationSpec;
import io.github.dhluong90.bpm.process.internal.model.horus.SlaSpec;
import io.github.dhluong90.bpm.process.spi.BusinessCalendar;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/**
 * PRE-178 and PRE-179: the fire time of each escalation level of the credit review {@code review-application}, on the
 * bank's calendar Monday to Friday 08:00 to 17:00 Asia/Ho_Chi_Minh.
 */
class EscalationPlanTest {

    private static final BusinessCalendar BANK = new BusinessCalendar() {
        @Override
        public Instant add(String tenantId, String calendarKey, Instant start, Duration workingTime) {
            Map<DayOfWeek, List<WorkingCalendar.Hours>> week = new EnumMap<>(DayOfWeek.class);
            for (DayOfWeek day : List.of(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY,
                    DayOfWeek.FRIDAY)) {
                week.put(day, List.of(WorkingCalendar.Hours.parse("08:00-17:00")));
            }
            return new WorkingCalendar(ZoneId.of("Asia/Ho_Chi_Minh"), week, new TreeMap<LocalDate, String>())
                    .add(start, workingTime);
        }

        @Override
        public boolean isBusinessTime(String tenantId, String calendarKey, Instant at) {
            return true;
        }
    };

    private static Instant ict(String dateTime) {
        return OffsetDateTime.parse(dateTime + "+07:00").toInstant();
    }

    private static final SlaSpec SLA = new SlaSpec(Duration.ofHours(8), "vn-banking-hours", null);

    @Test
    void PRE_178_the_first_level_with_no_wait_fires_at_the_due_time() {
        // Given a task created Thu 3 Dec 2026 15:00, due Fri 4 Dec 14:00, escalating to the supervisor at once
        EscalationSpec spec = new EscalationSpec(List.of(new EscalationSpec.Level(Duration.ZERO,
                "role:branch-supervisor")), true);

        // When the plan is worked out
        EscalationPlan plan = EscalationPlan.resolve("bank-a", spec, SLA, ict("2026-12-04T14:00:00"), BANK);

        // Then level 1 fires at 14:00 on Friday
        assertThat(plan.firstAt()).isEqualTo(ict("2026-12-04T14:00:00"));
        assertThat(plan.levels()).singleElement().extracting(EscalationPlan.Level::to).isEqualTo("role:branch-supervisor");
    }

    @Test
    void PRE_179_a_later_level_counts_its_wait_in_business_time_across_the_weekend() {
        // Given a second level 4 business hours after level 1: 3 on Friday and 1 on Monday
        EscalationSpec spec = new EscalationSpec(List.of(
                new EscalationSpec.Level(Duration.ZERO, "role:branch-supervisor"),
                new EscalationSpec.Level(Duration.ofHours(4), "role:regional-manager")), false);

        // When the plan is worked out
        EscalationPlan plan = EscalationPlan.resolve("bank-a", spec, SLA, ict("2026-12-04T14:00:00"), BANK);

        // Then level 2 fires Monday 7 Dec at 09:00, not Saturday 02:00, and the plan survives storage
        assertThat(plan.levels().get(1).at()).isEqualTo(ict("2026-12-07T09:00:00"));
        assertThat(EscalationPlan.fromJson(plan.toJson())).isEqualTo(plan);
        assertThat(plan.releaseClaim()).isFalse();
    }
}
