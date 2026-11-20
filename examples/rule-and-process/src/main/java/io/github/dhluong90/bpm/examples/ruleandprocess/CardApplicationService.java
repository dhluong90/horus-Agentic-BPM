package io.github.dhluong90.bpm.examples.ruleandprocess;

import io.github.dhluong90.bpm.process.api.ProcessEngine;
import io.github.dhluong90.bpm.process.api.ProcessInstance;
import io.github.dhluong90.bpm.process.api.StartRequest;
import io.github.dhluong90.bpm.process.spi.TenantResolver;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The host's own use case: store the application, then start the review process, in one transaction. When a
 * synchronous step fails the engine throws, and the application row rolls back with the engine rows.
 */
@Service
class CardApplicationService {

    static final String PROCESS = "card-fee-review";

    private final JdbcClient jdbc;
    private final ProcessEngine processes;
    private final TenantResolver tenants;
    private final Clock clock = Clock.systemUTC();

    CardApplicationService(JdbcClient jdbc, ProcessEngine processes, TenantResolver tenants) {
        this.jdbc = jdbc;
        this.processes = processes;
        this.tenants = tenants;
    }

    @Transactional
    ProcessInstance submit(String applicationId, String customerId, String idempotencyKey) {
        String tenant = tenants.currentTenantId();
        jdbc.sql("INSERT INTO card_application (tenant_id, application_id, customer_id, status, submitted_at) "
                        + "VALUES (?, ?, ?, 'SUBMITTED', ?) ON CONFLICT (tenant_id, application_id) DO NOTHING")
                .params(tenant, applicationId, customerId, clock.instant().atOffset(ZoneOffset.UTC))
                .update();
        return processes.start(StartRequest.of(PROCESS).withBusinessKey(applicationId)
                .withVariables(Map.of("applicationId", applicationId, "customerId", customerId))
                .withIdempotencyKey(idempotencyKey));
    }
}
