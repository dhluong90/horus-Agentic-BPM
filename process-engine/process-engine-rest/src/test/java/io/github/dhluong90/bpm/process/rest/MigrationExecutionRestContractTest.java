package io.github.dhluong90.bpm.process.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import io.github.dhluong90.bpm.process.api.DryRunRequiredException;
import io.github.dhluong90.bpm.process.api.MigrationExecution;
import io.github.dhluong90.bpm.process.api.MigrationExecutionService;
import io.github.dhluong90.bpm.process.api.MigrationNotFoundException;
import io.github.dhluong90.bpm.process.api.MigrationOutcome;
import io.github.dhluong90.bpm.process.api.MigrationPlan;
import io.github.dhluong90.bpm.process.api.SelfApprovalForbiddenException;
import io.github.dhluong90.bpm.process.api.VersionConflictException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Execution bodies and problems (#90, PRE-015, PRE-195, PRE-196) as the controller and the problem handler write
 * them, validated against {@code process-engine/api/openapi.yaml}. The service is an in-memory double.
 */
class MigrationExecutionRestContractTest {

    /** The body of an approval: the hash of the plan the approver reviewed (AUD-02). */
    private static final String REVIEWED = "{\"expectedPlanHash\":\"" + "a".repeat(64) + "\"}";

    private static final OpenApiBodies BODIES = OpenApiBodies.load(ProcessRestContractTest.CONTRACT);
    private static final String BASE = ProcessController.BASE;
    private static final String PLAN = "01JMIGRATI0NPLAN000000000A";
    private static final Instant NOW = Instant.parse("2027-03-01T02:00:00Z");

    private final FakeExecutions executions = new FakeExecutions();
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new MigrationExecutionController(executions))
            .setControllerAdvice(new ProcessProblemDetails()).build();

    @Test
    void PRE_195_execution_bodies_and_problems_match_the_contract() throws Exception {
        // thu proposes: 202 with the proposal and its ETag
        MvcResult proposed = exchange(post(BASE + "/migrations/" + PLAN + ":execute")
                .contentType(MediaType.APPLICATION_JSON).header("Idempotency-Key", "propose-1")
                .content("{\"reason\":\"Retire the legacy committee step, ticket CHG-2041\"}"));
        assertThat(proposed.getResponse().getStatus()).isEqualTo(202);
        assertThat(proposed.getResponse().getHeader("ETag")).isEqualTo("\"1\"");
        assertThat(proposed.getResponse().getContentAsString()).contains("\"status\":\"PROPOSED\"")
                .contains("\"proposedBy\":\"thu\"").contains("\"approvedBy\":null")
                .contains("\"progress\":{\"total\":500,\"migrated\":0,\"failed\":0,\"skipped\":0}");

        // lan approves with the lock version; the progress and the outcomes read back
        MvcResult approved = exchange(post(BASE + "/migrations/" + PLAN + ":approve").header("If-Match", "\"1\"")
                .header("Idempotency-Key", "approve-1").contentType(MediaType.APPLICATION_JSON).content(REVIEWED));
        assertThat(approved.getResponse().getStatus()).isEqualTo(200);
        assertThat(approved.getResponse().getContentAsString()).contains("\"status\":\"APPROVED\"")
                .contains("\"approvedBy\":\"lan\"");
        assertThat(exchange(get(BASE + "/migrations/" + PLAN + "/execution")).getResponse().getContentAsString())
                .contains("\"migrationId\":\"" + PLAN + "\"");
        MvcResult failed = exchange(get(BASE + "/migrations/" + PLAN + "/instances").param("outcome", "FAILED")
                .param("limit", "1"));
        assertThat(failed.getResponse().getContentAsString()).contains("\"code\":\"STEP_NOT_IN_TARGET\"")
                .contains("\"incidentId\":\"01JINCIDENT00000000000000A\"").contains("\"nextAfter\":\"01JLOAN00000000000000000A\"");
        MvcResult rejected = exchange(post(BASE + "/migrations/" + PLAN + ":reject").header("If-Match", "\"1\"")
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Wait for the month end\"}"));
        assertThat(rejected.getResponse().getContentAsString()).contains("\"status\":\"REJECTED\"")
                .contains("\"rejectionReason\":\"Wait for the month end\"");
        assertThat(executions.calls).containsExactly("propose:Retire the legacy committee step, ticket CHG-2041",
                "approve:1", "reject:1:Wait for the month end");

        // the problems
        assertProblem(post(BASE + "/migrations/01JOTHERTENANTPLAN00000000:execute")
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"x\"}"), 404, "MIGRATION_NOT_FOUND");
        assertProblem(get(BASE + "/migrations/01JOTHERTENANTPLAN00000000/execution"), 404, "MIGRATION_NOT_FOUND");
        assertProblem(post(BASE + "/migrations/01JCONFLICTPLAN0000000000:execute")
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"x\"}"), 409, "DRY_RUN_REQUIRED");
        assertProblem(post(BASE + "/migrations/" + PLAN + ":approve").header("If-Match", "\"0\"")
                .contentType(MediaType.APPLICATION_JSON).content(REVIEWED), 409,
                "VERSION_CONFLICT");
        assertProblem(post(BASE + "/migrations/" + PLAN + ":approve").header("If-Match", "\"9\"")
                .contentType(MediaType.APPLICATION_JSON).content(REVIEWED), 403,
                "SELF_APPROVAL_FORBIDDEN");
        // requests that break the contract on purpose: only the answer is checked
        for (RequestBuilder request : List.of(
                post(BASE + "/migrations/" + PLAN + ":approve"),
                post(BASE + "/migrations/" + PLAN + ":approve").header("If-Match", "\"x\""),
                post(BASE + "/migrations/" + PLAN + ":execute").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"x\",\"tenantId\":\"bank-b\"}"),
                post(BASE + "/migrations/" + PLAN + ":execute").contentType(MediaType.APPLICATION_JSON).content("{}"),
                post(BASE + "/migrations/" + PLAN + ":execute").contentType(MediaType.APPLICATION_JSON).content("nope"),
                get(BASE + "/migrations/" + PLAN + "/instances").param("limit", "0"),
                get(BASE + "/migrations/" + PLAN + "/instances").param("limit", "many"))) {
            MvcResult refused = mvc.perform(request).andReturn();
            BODIES.assertResponseOf(refused);
            assertThat(refused.getResponse().getStatus()).isBetween(400, 428);
        }
        assertThat(executions.calls).as("a refused request never reaches the service").hasSize(3);
    }

    private void assertProblem(RequestBuilder request, int status, String code) throws Exception {
        MvcResult result = exchange(request);
        assertThat(result.getResponse().getStatus()).isEqualTo(status);
        assertThat(result.getResponse().getContentAsString()).contains("\"code\":\"" + code + "\"");
    }

    private MvcResult exchange(RequestBuilder request) throws Exception {
        MvcResult result = mvc.perform(request).andReturn();
        BODIES.assertExchange(result);
        return result;
    }

    private static MigrationExecution state(String status) {
        boolean approved = status.equals("APPROVED");
        boolean rejected = status.equals("REJECTED");
        return new MigrationExecution(PLAN, status, MigrationPlan.Approval.FOUR_EYES, 1, "thu", NOW,
                "Retire the legacy committee step, ticket CHG-2041", approved ? "lan" : null, approved ? NOW : null,
                rejected ? "lan" : null, rejected ? NOW : null, rejected ? "Wait for the month end" : null, 500, 0, 0, 0,
                null, null);
    }

    private static final class FakeExecutions implements MigrationExecutionService {
        final List<String> calls = new ArrayList<>();

        private static void plan(String id) {
            if (id.equals("01JOTHERTENANTPLAN00000000")) {
                throw new MigrationNotFoundException("No migration plan " + id + " in this tenant");
            }
        }

        @Override
        public MigrationExecution propose(String migrationId, String reason, String idempotencyKey) {
            plan(migrationId);
            if (migrationId.equals("01JCONFLICTPLAN0000000000")) {
                throw new DryRunRequiredException("The dry run of this plan reports 1 conflict(s)");
            }
            calls.add("propose:" + reason);
            return state("PROPOSED");
        }

        @Override
        public MigrationExecution approve(String migrationId, long expectedLockVersion, String idempotencyKey,
                String expectedPlanHash) {
            assertThat(expectedPlanHash).as("the reviewed plan hash reaches the service").hasSize(64);
            return approve(migrationId, expectedLockVersion, idempotencyKey);
        }

        @Override
        public MigrationExecution approve(String migrationId, long expectedLockVersion, String idempotencyKey) {
            if (expectedLockVersion == 0) {
                throw new VersionConflictException("The proposal is at lock version 1 but the request names 0");
            }
            if (expectedLockVersion == 9) {
                throw new SelfApprovalForbiddenException("The proposer of an execution cannot approve or reject it");
            }
            calls.add("approve:" + expectedLockVersion);
            return state("APPROVED");
        }

        @Override
        public MigrationExecution reject(String migrationId, long expectedLockVersion, String reason,
                String idempotencyKey) {
            calls.add("reject:" + expectedLockVersion + ":" + reason);
            return state("REJECTED");
        }

        @Override
        public MigrationExecution execution(String migrationId) {
            plan(migrationId);
            return state("APPROVED");
        }

        @Override
        public List<MigrationOutcome> outcomes(String migrationId, String outcome, String afterInstanceId, int limit) {
            plan(migrationId);
            List<MigrationOutcome> all = List.of(
                    new MigrationOutcome("01JLOAN00000000000000000A", "FAILED", "STEP_NOT_IN_TARGET",
                            "Migration could not move the instance to v2: STEP_NOT_IN_TARGET at legacy-review",
                            "01JINCIDENT00000000000000A", NOW),
                    new MigrationOutcome("01JLOAN00000000000000000B", "FAILED", "LOCK_CONFLICT",
                            "The instance was locked", "01JINCIDENT00000000000000B", NOW));
            return all.subList(0, Math.min(all.size(), limit));
        }
    }
}
