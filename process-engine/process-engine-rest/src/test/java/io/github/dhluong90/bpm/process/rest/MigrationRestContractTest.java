package io.github.dhluong90.bpm.process.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import io.github.dhluong90.bpm.process.api.DefinitionNotFoundException;
import io.github.dhluong90.bpm.process.api.InvalidStateException;
import io.github.dhluong90.bpm.process.api.MigrationDryRun;
import io.github.dhluong90.bpm.process.api.MigrationNotFoundException;
import io.github.dhluong90.bpm.process.api.MigrationPlan;
import io.github.dhluong90.bpm.process.api.MigrationPlanRequest;
import io.github.dhluong90.bpm.process.api.MigrationService;
import io.github.dhluong90.bpm.process.api.PermissionDeniedException;
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
 * Migration plan and dry-run bodies (#74, PRE-014, PRE-174, PRE-175) as the controller and the problem handler write
 * them, validated against {@code process-engine/api/openapi.yaml}. The service is an in-memory double.
 */
class MigrationRestContractTest {

    private static final OpenApiBodies BODIES = OpenApiBodies.load(ProcessRestContractTest.CONTRACT);
    private static final String BASE = ProcessController.BASE;
    private static final String PLAN = "01JMIGRATI0NPLAN000000000A";
    private static final String NO_PERMISSION = "01JNOPERMISSI0NPLAN0000000";
    private static final Instant NOW = Instant.parse("2027-03-01T02:00:00Z");

    private final FakeMigrations migrations = new FakeMigrations();
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new MigrationController(migrations))
            .setControllerAdvice(new ProcessProblemDetails()).build();

    @Test
    void PRE_174_a_plan_answers_201_with_the_four_eyes_approval() throws Exception {
        MvcResult created = exchange(post(BASE + "/migrations").contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "plan-1")
                .content("{\"definitionKey\":\"loan-review\",\"sourceVersion\":1,\"targetVersion\":2}"));

        assertThat(created.getResponse().getStatus()).isEqualTo(201);
        assertThat(created.getResponse().getHeader("Location")).isEqualTo(BASE + "/migrations/" + PLAN);
        assertThat(created.getResponse().getContentAsString())
                .contains("\"approval\":{\"mode\":\"four-eyes\",\"secondPersonRequired\":true}");
        assertThat(migrations.requests).containsExactly(new MigrationPlanRequest("loan-review", 1, 2, "plan-1"));

        MvcResult read = exchange(get(BASE + "/migrations/" + PLAN));
        assertThat(read.getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void PRE_014_the_dry_run_report_matches_the_contract() throws Exception {
        MvcResult report = exchange(post(BASE + "/migrations/" + PLAN + ":validate"));

        assertThat(report.getResponse().getStatus()).isEqualTo(200);
        assertThat(report.getResponse().getContentAsString()).contains("\"valid\":false")
                .contains("\"code\":\"STEP_NOT_IN_TARGET\",\"nodeId\":\"legacy-review\"")
                .contains("\"instances\":{\"running\":500,\"movable\":450,\"blocked\":50}");
    }

    @Test
    void PRE_175_migration_problems_match_the_contract() throws Exception {
        assertProblem(get(BASE + "/migrations/01JOTHERTENANTPLAN00000000"), 404, "MIGRATION_NOT_FOUND");
        assertProblem(post(BASE + "/migrations/01JOTHERTENANTPLAN00000000:validate"), 404, "MIGRATION_NOT_FOUND");
        assertProblem(post(BASE + "/migrations").contentType(MediaType.APPLICATION_JSON)
                .content("{\"definitionKey\":\"other-bank\",\"sourceVersion\":1,\"targetVersion\":2}"), 404,
                "DEFINITION_NOT_FOUND");
        assertProblem(post(BASE + "/migrations").contentType(MediaType.APPLICATION_JSON)
                .content("{\"definitionKey\":\"loan-review\",\"sourceVersion\":2,\"targetVersion\":1}"), 409,
                "INVALID_STATE");
        assertProblem(post(BASE + "/migrations/" + NO_PERMISSION + ":validate"), 403, "PERMISSION_DENIED");
        // Requests that break the contract on purpose: only the answer is checked.
        for (String body : new String[] {
                "{\"definitionKey\":\"loan-review\",\"sourceVersion\":1,\"targetVersion\":2,\"tenantId\":\"bank-b\"}",
                "{\"definitionKey\":\"loan-review\",\"sourceVersion\":1,\"targetVersion\":2,\"mappings\":{}}",
                "{\"definitionKey\":\"loan-review\",\"sourceVersion\":\"1\",\"targetVersion\":2}", "[]", "not json"}) {
            MvcResult refused = mvc.perform(post(BASE + "/migrations").contentType(MediaType.APPLICATION_JSON)
                    .content(body)).andReturn();
            BODIES.assertResponseOf(refused);
            assertThat(refused.getResponse().getStatus()).as(body).isEqualTo(400);
            assertThat(refused.getResponse().getContentAsString()).contains("\"code\":\"INPUT_INVALID\"");
        }
        assertThat(migrations.requests).as("a refused body never reaches the service").noneMatch(r ->
                r.definitionKey().equals("loan-review"));
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

    /** One plan of loan-review v1 to v2 over 500 loans, 50 of them at the removed legacy-review. */
    private static final class FakeMigrations implements MigrationService {
        final List<MigrationPlanRequest> requests = new ArrayList<>();

        private static MigrationPlan plan() {
            return new MigrationPlan(PLAN, "loan-review", 1, 2, "PLANNED", "c".repeat(64),
                    MigrationPlan.Approval.FOUR_EYES, "ops-lead-thu", NOW);
        }

        @Override
        public MigrationPlan plan(MigrationPlanRequest request) {
            if (request.definitionKey().equals("other-bank")) {
                throw new DefinitionNotFoundException("No definition other-bank v1 in this tenant");
            }
            if (request.sourceVersion() == 2) {
                throw new InvalidStateException("Definition loan-review v1 is RETIRED");
            }
            requests.add(request);
            return plan();
        }

        @Override
        public MigrationPlan find(String migrationId) {
            if (!PLAN.equals(migrationId)) {
                throw new MigrationNotFoundException("No migration plan " + migrationId + " in this tenant");
            }
            return plan();
        }

        @Override
        public MigrationDryRun validate(String migrationId) {
            if (NO_PERMISSION.equals(migrationId)) {
                throw new PermissionDeniedException("Needs processes:migration:manage");
            }
            find(migrationId);
            List<String> legacy = new ArrayList<>();
            for (int i = 0; i < 50; i++) {
                legacy.add("01JLOAN" + String.format("%019d", i));
            }
            return new MigrationDryRun(PLAN, "c".repeat(64), "loan-review", 1, 2, false, NOW,
                    NOW.plusSeconds(3600), MigrationPlan.Approval.FOUR_EYES, new MigrationDryRun.Instances(500, 450, 50),
                    List.of(new MigrationDryRun.NodeMapping("legacy-review", "Legacy committee review", "userTask", 50,
                            MigrationDryRun.STEP_NOT_IN_TARGET, null, null),
                            new MigrationDryRun.NodeMapping("review", "Officer review", "userTask", 450, "SAME_ID",
                                    "review", "userTask")),
                    List.of(new MigrationDryRun.Conflict(MigrationDryRun.STEP_NOT_IN_TARGET, "legacy-review",
                            "Legacy committee review", new MigrationDryRun.InstanceSample(50, legacy, false))),
                    new MigrationDryRun.InstanceSample(450, List.of("01JLOAN0000000000000000100"), true));
        }
    }
}
