package io.github.dhluong90.bpm.examples.guardedagent;

import io.github.dhluong90.bpm.host.spi.EngineAccess;
import io.github.dhluong90.bpm.host.spi.EngineAccessDeniedException;
import io.github.dhluong90.bpm.process.api.ProcessEngine;
import io.github.dhluong90.bpm.process.api.ProcessInstance;
import io.github.dhluong90.bpm.process.api.StartRequest;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * The host's own API over its seeded loan applications (#66). Sending an application to review starts the
 * {@code loan-document-review} process with the application as its business key, in the same transaction that links
 * the application to its instance. The tenant is the caller's, never a request value.
 */
@RestController
class LoanApplications {

    /** The process an application is reviewed by. */
    static final String PROCESS = "loan-document-review";

    private final JdbcClient jdbc;
    private final ProcessEngine processes;
    private final EngineAccess access;

    LoanApplications(JdbcClient jdbc, ProcessEngine processes, EngineAccess access) {
        this.jdbc = jdbc;
        this.processes = processes;
        this.access = access;
    }

    /** One application as the host lists it. */
    record Application(String id, String applicant, long amountMinor, String currency, int documentCount,
            String instanceId) {
    }

    @GetMapping("/loan-applications")
    public List<Application> list() {
        String tenant = access.requirePermission("processes:read").tenant();
        return jdbc.sql("SELECT id, applicant, amount_minor, currency, document_count, instance_id FROM loan_application "
                        + "WHERE tenant_id = ? ORDER BY id LIMIT 100")
                .param(tenant)
                .query((rs, n) -> new Application(rs.getString("id"), rs.getString("applicant"),
                        rs.getLong("amount_minor"), rs.getString("currency"), rs.getInt("document_count"),
                        rs.getString("instance_id")))
                .list();
    }

    /**
     * Sends an application to its document review. Sending it again returns the instance it already has: the
     * application id is the business key, which the engine keeps unique per tenant (PRE-002).
     */
    @PostMapping("/loan-applications/{id}:review")
    @Transactional
    public ProcessInstance review(@PathVariable String id) {
        String tenant = access.requirePermission("processes:start").tenant();
        Application application = jdbc.sql("SELECT id, applicant, amount_minor, currency, document_count, instance_id "
                        + "FROM loan_application WHERE tenant_id = ? AND id = ? FOR UPDATE")
                .params(tenant, id)
                .query((rs, n) -> new Application(rs.getString("id"), rs.getString("applicant"),
                        rs.getLong("amount_minor"), rs.getString("currency"), rs.getInt("document_count"),
                        rs.getString("instance_id")))
                .optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No loan application " + id));
        ProcessInstance instance = processes.start(new StartRequest(PROCESS, application.id(), Map.of(
                "applicationId", application.id(), "amountMinor", application.amountMinor(),
                "currency", application.currency(), "documentCount", application.documentCount()), null));
        jdbc.sql("UPDATE loan_application SET instance_id = ? WHERE tenant_id = ? AND id = ?")
                .params(instance.id(), tenant, id).update();
        return instance;
    }

    /** A caller without the permission, or without a tenant, gets the engines' status and code. */
    @org.springframework.web.bind.annotation.ExceptionHandler(EngineAccessDeniedException.class)
    org.springframework.http.ResponseEntity<org.springframework.http.ProblemDetail> denied(
            EngineAccessDeniedException e) {
        org.springframework.http.ProblemDetail problem = org.springframework.http.ProblemDetail.forStatus(e.status());
        problem.setProperty("code", e.code());
        return org.springframework.http.ResponseEntity.status(e.status()).body(problem);
    }
}
