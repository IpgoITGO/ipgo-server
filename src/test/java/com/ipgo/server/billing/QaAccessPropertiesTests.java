package com.ipgo.server.billing;

import com.ipgo.server.config.QaAccessConfig;
import com.ipgo.server.config.QaAccessProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import static org.assertj.core.api.Assertions.*;

// #244 IPGO_QA_FULL_ACCESS_USER_IDS 읽기 · 시작 검사 · 시작 경고
@ExtendWith(OutputCaptureExtension.class)
class QaAccessPropertiesTests {
    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(QaAccessConfig.class);
    private static final String WARNING = "QA full access is enabled";

    @Test void emptyOrMissingSettingKeepsEveryoneOnTheirPlanWithoutWarning(CapturedOutput output) {
        runner.run(context -> assertThat(context.getBean(QaAccessProperties.class).fullAccessUserIds()).isEmpty());
        runner.withPropertyValues("ipgo.qa.full-access-user-ids=").run(context -> {
            var qa = context.getBean(QaAccessProperties.class);
            assertThat(qa.fullAccessUserIds()).isEmpty();
            assertThat(qa.fullAccess(1)).isFalse();
        });
        assertThat(output).doesNotContain(WARNING);
    }

    @Test void commaSeparatedIdsAreReadAndOnlyTheCountIsLogged(CapturedOutput output) {
        runner.withPropertyValues("ipgo.qa.full-access-user-ids= 7001, 7002 ,7002").run(context -> {
            var qa = context.getBean(QaAccessProperties.class);
            assertThat(qa.fullAccessUserIds()).containsExactlyInAnyOrder(7001L, 7002L);
            assertThat(qa.fullAccess(7001)).isTrue();
            assertThat(qa.fullAccess(7003)).isFalse();
        });
        assertThat(output).contains(WARNING + " for 2 user id(s)").doesNotContain("7001").doesNotContain("7002");
    }

    @Test void invalidIdsStopStartup() {
        for (String invalid : new String[]{"abc", "12,qa", "0", "-3", "1.5"})
            runner.withPropertyValues("ipgo.qa.full-access-user-ids=" + invalid)
                    .run(context -> assertThat(context).as(invalid).hasFailed());
    }
}
