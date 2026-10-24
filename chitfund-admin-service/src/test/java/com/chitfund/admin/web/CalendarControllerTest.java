package com.chitfund.admin.web;

import com.chitfund.admin.support.FakeChit;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

import java.security.Principal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Spring-free: the business date and holiday screens forward to the chit service under the right path, and reject malformed input first. */
class CalendarControllerTest {

    private final FakeChit chit = new FakeChit();
    private final CalendarController controller = new CalendarController(chit);
    private final Principal admin = () -> "admin";

    @Test
    void theBusinessDateIsReadFromTheChitService() {
        controller.businessDate();
        assertThat(chit.last().method()).isEqualTo(HttpMethod.GET);
        assertThat(chit.last().path()).isEqualTo("/business-date");
    }

    @Test
    void settingTheDateNeedsAValidDayAndForwardsItWithTheActor() {
        assertThatThrownBy(() -> controller.setBusinessDate(null, null, admin)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> controller.setBusinessDate(Map.of("businessDate", "02/11/2026"), null, admin))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("2026-11-02");
        assertThat(chit.calls).isEmpty();

        controller.setBusinessDate(Map.of("businessDate", "2026-11-09"), "ravi", admin);
        assertThat(chit.last().method()).isEqualTo(HttpMethod.PUT);
        assertThat(chit.last().path()).isEqualTo("/business-date");
        assertThat(chit.last().body()).isEqualTo(Map.of("businessDate", "2026-11-09"));
        assertThat(chit.last().actor()).isEqualTo("ravi");

        controller.setBusinessDate(Map.of("businessDate", "2026-11-10"), null, admin);
        assertThat(chit.last().actor()).isEqualTo("admin");
    }

    @Test
    void workingDayAndHolidayReadsAreForwarded() {
        controller.workingDay("2026-11-14");
        assertThat(chit.last().path()).isEqualTo("/business-date/working-day");
        assertThat(chit.last().query()).containsEntry("date", "2026-11-14");
        controller.holidays(2026);
        assertThat(chit.last().path()).isEqualTo("/holidays");
        assertThat(chit.last().query()).containsEntry("year", 2026);
        controller.holidays(null);
        assertThat(chit.last().query().get("year")).isNull();
        assertThatThrownBy(() -> controller.workingDay("tomorrow")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void addingAHolidayNeedsADateAndAName() {
        int before = chit.calls.size();
        assertThatThrownBy(() -> controller.putHoliday("2026-11-14", Map.of(), null, admin)).hasMessageContaining("name");
        assertThatThrownBy(() -> controller.putHoliday("2026-11-14", Map.of("name", "  "), null, admin)).hasMessageContaining("name");
        assertThatThrownBy(() -> controller.putHoliday("14-11-2026", Map.of("name", "Diwali"), null, admin)).isInstanceOf(IllegalArgumentException.class);
        assertThat(chit.calls).hasSize(before);

        controller.putHoliday("2026-11-08", Map.of("name", " Diwali "), "meena", admin);
        assertThat(chit.last().method()).isEqualTo(HttpMethod.PUT);
        assertThat(chit.last().path()).isEqualTo("/holidays/2026-11-08");
        assertThat(chit.last().body()).isEqualTo(Map.of("name", "Diwali"));
        assertThat(chit.last().actor()).isEqualTo("meena");
    }

    @Test
    void removingAHolidayIsADelete() {
        controller.deleteHoliday("2026-11-08", null, admin);
        assertThat(chit.last().method()).isEqualTo(HttpMethod.DELETE);
        assertThat(chit.last().path()).isEqualTo("/holidays/2026-11-08");
        assertThat(chit.last().body()).isNull();
        assertThatThrownBy(() -> controller.deleteHoliday("../x", null, admin)).isInstanceOf(IllegalArgumentException.class);
    }
}
