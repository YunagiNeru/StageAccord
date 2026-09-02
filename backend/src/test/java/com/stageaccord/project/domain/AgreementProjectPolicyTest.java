package com.stageaccord.project.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.stageaccord.agreement.domain.AgreementAcceptancePolicy;
import com.stageaccord.schedulenotification.domain.BusinessCalendar;

class AgreementProjectPolicyTest {
    private static final Instant NOW = Instant.parse("2026-09-04T05:00:00Z");

    @Test
    void acceptanceRequiresCurrentHashFreshAuthAndTrustedTime() {
        var policy = new AgreementAcceptancePolicy();
        byte[] hash = new byte[32];
        policy.requireAcceptable(hash, hash, true, NOW.minusSeconds(60), NOW, true, true);
        assertThatThrownBy(() -> policy.requireAcceptable(hash, new byte[31], true,
                NOW.minusSeconds(60), NOW, true, true))
                .isInstanceOf(AgreementAcceptancePolicy.AgreementRuleViolation.class);
        assertThatThrownBy(() -> policy.requireAcceptable(hash, hash, true,
                NOW.minusSeconds(1801), NOW, true, true))
                .isInstanceOf(AgreementAcceptancePolicy.AgreementRuleViolation.class);
    }

    @Test
    void checkpointCannotSkipPredecessorOrMissingRequirements() {
        var project = new ProjectLifecycle();
        assertThatThrownBy(() -> project.activate(false)).isInstanceOf(ProjectLifecycle.ProjectRuleViolation.class);
        project.activate(true);
        assertThatThrownBy(() -> project.advanceCheckpoint(2, true, true))
                .isInstanceOf(ProjectLifecycle.ProjectRuleViolation.class);
        assertThatThrownBy(() -> project.advanceCheckpoint(1, false, true))
                .isInstanceOf(ProjectLifecycle.ProjectRuleViolation.class);
        project.advanceCheckpoint(1, true, true);
        assertThat(project.activeCheckpoint()).isEqualTo(2);
    }

    @Test
    void disputeReturnsOnlyToRecordedPriorStateAndCancellationNeedsEveryParty() {
        var project = new ProjectLifecycle();
        project.activate(true);
        project.hold();
        project.openDispute();
        assertThatThrownBy(() -> project.resolveDispute(ProjectLifecycle.ProjectStatus.ACTIVE))
                .isInstanceOf(ProjectLifecycle.ProjectRuleViolation.class);
        project.resolveDispute(ProjectLifecycle.ProjectStatus.ON_HOLD);
        assertThatThrownBy(() -> project.cancel(List.of(true, false)))
                .isInstanceOf(ProjectLifecycle.ProjectRuleViolation.class);
        project.cancel(List.of(true, true));
        assertThat(project.status()).isEqualTo(ProjectLifecycle.ProjectStatus.CANCELLED);
    }

    @Test
    void businessCalendarHonorsCutoffWeekendHolidayAndTimeZone() {
        var calendar = new BusinessCalendar(ZoneId.of("Asia/Tokyo"),
                Set.of(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
                        DayOfWeek.THURSDAY, DayOfWeek.FRIDAY),
                Set.of(LocalDate.of(2026, 9, 7)), LocalTime.of(15, 0));
        Instant afterFridayCutoff = Instant.parse("2026-09-04T07:00:00Z");
        assertThat(calendar.addBusinessDays(afterFridayCutoff, 1))
                .isEqualTo(Instant.parse("2026-09-08T06:00:00Z"));
    }
}
