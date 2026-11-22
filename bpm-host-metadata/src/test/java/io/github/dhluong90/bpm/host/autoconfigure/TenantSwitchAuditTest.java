package io.github.dhluong90.bpm.host.autoconfigure;

import static io.github.dhluong90.bpm.host.autoconfigure.HostMetadataEndpointsTest.call;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import io.github.dhluong90.bpm.host.spi.TenantSwitchAudit;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * The audit record of an accepted tenant switch and the host decoder warning (#451, TR-TEN-01, TR-SEC-12). Lan's
 * token says {@code tenant_id: bank-a, allowed_tenants: [bank-a, bank-b]}.
 */
@ExtendWith(OutputCaptureExtension.class)
class TenantSwitchAuditTest {

    private static final String SWITCHING = "horus.bpm.tenancy.switching.enabled=true";
    static final List<TenantSwitchAudit.TenantSwitch> RECORDS = new CopyOnWriteArrayList<>();

    @Test
    void TR_TEN_01_an_accepted_switch_writes_one_audit_line_by_default_and_the_security_line_stays(
            CapturedOutput output) {
        TestHosts.runner().withUserConfiguration(TestHosts.RulesWithoutMapping.class).withPropertyValues(SWITCHING)
                .run(context -> {
                    // Given no host sink, the default is registered
                    assertThat(context.getBean(TenantSwitchAudit.class)).isInstanceOf(LoggingTenantSwitchAudit.class);

                    // When lan switches to bank-b
                    MvcResult switched = call(TestHosts.mockMvc(context), get("/api/bpm/me").with(lan())
                            .header("X-Tenant-Id", "bank-b"));

                    // Then one audit line with actor, both tenants, the time and the correlation id
                    String correlationId = switched.getResponse().getHeader("X-Correlation-Id");
                    assertThat(output.getAll()).containsPattern("tenant\\.switch\\.accepted actor=lan fromTenant=bank-a "
                            + "toTenant=bank-b at=\\d{4}-\\d{2}-\\d{2}T[0-9:.]+Z correlationId=" + correlationId);
                    assertThat(output.getAll().split("tenant.switch.accepted actor=", -1)).hasSize(2);
                    // and the security line is still written
                    assertThat(output).contains("tenant.switch.accepted user=lan fromTenant=bank-a "
                            + "requestedTenant=bank-b correlationId=" + correlationId);
                });
    }

    @Test
    void TR_TEN_01_a_host_sink_receives_the_record_once_per_request() {
        RECORDS.clear();
        TestHosts.runner().withUserConfiguration(TestHosts.RulesWithoutMapping.class, HostSink.class)
                .withPropertyValues(SWITCHING).run(context -> {
                    MockMvc mvc = TestHosts.mockMvc(context);

                    // When lan switches on one request and calls her own tenant on another
                    MvcResult switched = call(mvc, get("/api/bpm/me").with(lan()).header("X-Tenant-Id", "bank-b"));
                    call(mvc, get("/api/bpm/me").with(lan()));

                    // Then the host's sink holds exactly one record
                    assertThat(RECORDS).singleElement().satisfies(record -> {
                        assertThat(record.actor()).isEqualTo("lan");
                        assertThat(record.fromTenant()).isEqualTo("bank-a");
                        assertThat(record.toTenant()).isEqualTo("bank-b");
                        assertThat(record.correlationId())
                                .isEqualTo(switched.getResponse().getHeader("X-Correlation-Id"));
                    });
                });
    }

    @Test
    void TR_TEN_01_a_refused_switch_writes_no_audit_record(CapturedOutput output) {
        RECORDS.clear();
        TestHosts.runner().withUserConfiguration(TestHosts.RulesWithoutMapping.class, HostSink.class)
                .withPropertyValues(SWITCHING).run(context -> {
                    MockMvc mvc = TestHosts.mockMvc(context);

                    // When lan asks for an unlisted tenant, a malformed one, and repeats the header
                    MvcResult unlisted = call(mvc, get("/api/bpm/me").with(lan()).header("X-Tenant-Id", "bank-c"));
                    MvcResult malformed = call(mvc, get("/api/bpm/me").with(lan()).header("X-Tenant-Id", "../bank-b"));
                    MvcResult repeated = call(mvc, get("/api/bpm/me").with(lan())
                            .header("X-Tenant-Id", "bank-b", "bank-a"));

                    // Then each is 403 and no record says a switch was accepted
                    assertThat(List.of(unlisted, malformed, repeated))
                            .allSatisfy(result -> assertThat(result.getResponse().getStatus()).isEqualTo(403));
                    assertThat(RECORDS).isEmpty();
                    assertThat(output).doesNotContain("tenant.switch.accepted");
                });
    }

    @Test
    void TR_TEN_01_a_sink_that_fails_fails_the_call_so_no_switch_runs_unrecorded() {
        TestHosts.runner().withUserConfiguration(TestHosts.RulesWithoutMapping.class, FailingSink.class)
                .withPropertyValues(SWITCHING).run(context -> {
                    MockMvc mvc = TestHosts.mockMvc(context);
                    try {
                        org.assertj.core.api.Assertions.assertThatThrownBy(() -> mvc.perform(get("/api/bpm/me")
                                        .with(lan()).header("X-Tenant-Id", "bank-b")).andReturn())
                                .hasRootCauseMessage("audit store down");
                    } finally {
                        // MockMvc leaves the failed request bound to the thread; later tests must not see it
                        org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();
                        org.springframework.security.core.context.SecurityContextHolder.clearContext();
                    }
                });
    }

    @Test
    void TR_SEC_12_a_host_jwt_decoder_outside_dev_with_an_audience_is_named_in_a_start_up_warning(
            CapturedOutput output) {
        // Given a host that defines its own JwtDecoder and configures an audience, outside dev
        TestHosts.runner().withUserConfiguration(TestHosts.RulesWithoutMapping.class, HostDecoder.class)
                .withPropertyValues("horus.bpm.security.audiences=loans-bpm", "spring.profiles.active=prod")
                .run(context -> {
                    // Then it starts and one WARN names the bean and says no audience check is applied
                    assertThat(context).hasNotFailed();
                    assertThat(output).contains("jwt.decoder.audience.unchecked bean=bankTokenDecoder")
                            .contains("no audience check is applied to it");
                });
    }

    @Test
    void TR_SEC_12_no_decoder_warning_on_dev_or_without_an_audience(CapturedOutput output) {
        TestHosts.runner().withUserConfiguration(TestHosts.RulesWithoutMapping.class, HostDecoder.class)
                .withPropertyValues("horus.bpm.security.audiences=loans-bpm", "spring.profiles.active=dev")
                .run(context -> assertThat(context).hasNotFailed());
        TestHosts.runner().withUserConfiguration(TestHosts.RulesWithoutMapping.class, HostDecoder.class)
                .withPropertyValues("spring.profiles.active=prod")
                .run(context -> assertThat(context).hasNotFailed());
        assertThat(output).doesNotContain("jwt.decoder.audience.unchecked");
    }

    private static JwtRequestPostProcessor lan() {
        return jwt().jwt(token -> token.subject("lan").claim("tenant_id", "bank-a")
                        .claim("allowed_tenants", List.of("bank-a", "bank-b")))
                .authorities(new SimpleGrantedAuthority("rules:read"));
    }

    @Configuration(proxyBeanMethods = false)
    static class HostSink {
        @Bean
        TenantSwitchAudit hostAuditTrail() {
            return RECORDS::add;
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class FailingSink {
        @Bean
        TenantSwitchAudit failingAuditTrail() {
            return record -> {
                throw new IllegalStateException("audit store down");
            };
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class HostDecoder {
        @Bean
        JwtDecoder bankTokenDecoder() {
            return token -> {
                throw new org.springframework.security.oauth2.jwt.BadJwtException("test decoder");
            };
        }
    }
}
