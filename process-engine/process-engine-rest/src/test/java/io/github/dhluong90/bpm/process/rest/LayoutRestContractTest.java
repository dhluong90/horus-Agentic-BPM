package io.github.dhluong90.bpm.process.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import io.github.dhluong90.bpm.process.api.DefinitionNotFoundException;
import io.github.dhluong90.bpm.process.api.DefinitionRef;
import io.github.dhluong90.bpm.process.api.LayoutInvalidException;
import io.github.dhluong90.bpm.process.api.LayoutRevision;
import io.github.dhluong90.bpm.process.api.LayoutService;
import io.github.dhluong90.bpm.process.api.PermissionDeniedException;
import io.github.dhluong90.bpm.process.api.VersionConflictException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Layout bodies and problems (#102, PRE-026) as the controller and the problem handler write them, validated against
 * {@code process-engine/api/openapi.yaml}. The service is an in-memory double holding four revisions of
 * {@code kyc-onboarding} v2.
 */
class LayoutRestContractTest {

    private static final OpenApiBodies BODIES = OpenApiBodies.load(ProcessRestContractTest.CONTRACT);
    private static final String LAYOUT = ProcessController.BASE + "/definitions/kyc-onboarding/versions/2/layout";
    private static final Instant NOW = Instant.parse("2027-03-01T02:00:00Z");
    private static final String BODY = "{\"schemaVersion\":1,\"nodes\":{\"start\":{\"x\":40,\"y\":120}}}";

    private final FakeLayouts layouts = new FakeLayouts();
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new LayoutController(layouts))
            .setControllerAdvice(new ProcessProblemDetails()).build();

    @Test
    void PRE_026_layout_bodies_and_problems_match_the_contract() throws Exception {
        // carol saves with If-Match "3": revision 4, with its ETag
        MvcResult saved = exchange(put(LAYOUT).contentType(MediaType.APPLICATION_JSON).header("If-Match", "\"3\"")
                .header("Idempotency-Key", "save-1").content(BODY));
        assertThat(saved.getResponse().getStatus()).isEqualTo(200);
        assertThat(saved.getResponse().getHeader("ETag")).isEqualTo("\"4\"");
        assertThat(saved.getResponse().getContentAsString()).contains("\"revision\":4").contains("\"createdBy\":\"carol\"")
                .contains("\"layout\":{\"nodes\":{\"start\"");
        assertThat(layouts.saved).containsExactly("3:" + BODY);

        // the latest, a named revision, none yet, and the list with its cursor
        MvcResult latest = exchange(get(LAYOUT));
        assertThat(latest.getResponse().getHeader("ETag")).isEqualTo("\"4\"");
        assertThat(exchange(get(LAYOUT).param("revision", "2")).getResponse().getStatus()).isEqualTo(200);
        MvcResult none = exchange(get(ProcessController.BASE + "/definitions/kyc-onboarding/versions/1/layout"));
        assertThat(none.getResponse().getStatus()).isEqualTo(204);
        assertThat(none.getResponse().getHeader("ETag")).isEqualTo("\"0\"");
        MvcResult page = exchange(get(LAYOUT + "/revisions").param("limit", "2"));
        assertThat(page.getResponse().getContentAsString()).contains("\"nextAfter\":2").doesNotContain("\"layout\":");
        assertThat(exchange(get(LAYOUT + "/revisions").param("after", "2").param("limit", "2")).getResponse()
                .getContentAsString()).contains("\"nextAfter\":null");
    }

    @Test
    void PRE_026_layout_problems_name_the_code_and_the_failing_paths() throws Exception {
        assertProblem(put(LAYOUT).contentType(MediaType.APPLICATION_JSON).header("If-Match", "\"2\"").content(BODY), 409,
                "VERSION_CONFLICT");
        MvcResult invalid = assertProblem(put(LAYOUT).contentType(MediaType.APPLICATION_JSON).header("If-Match", "\"3\"")
                .content("{\"schemaVersion\":1,\"nodes\":{\"ghost-step\":{\"x\":1,\"y\":2}}}"), 422, "LAYOUT_INVALID");
        assertThat(invalid.getResponse().getContentAsString())
                .contains("\"paths\":[\"$.nodes.ghost-step names a step that is not in the definition\"]");
        assertProblem(put(ProcessController.BASE + "/definitions/other-bank/versions/1/layout")
                .contentType(MediaType.APPLICATION_JSON).header("If-Match", "\"0\"").content(BODY), 404,
                "DEFINITION_NOT_FOUND");
        assertProblem(put(LAYOUT).contentType(MediaType.APPLICATION_JSON).header("If-Match", "\"0\"")
                .header("X-Role", "viewer").content(BODY), 403, "PERMISSION_DENIED");
        assertProblem(get(LAYOUT).param("revision", "9"), 404, "LAYOUT_NOT_FOUND");
        // requests that break the contract on purpose: only the answer is checked
        MvcResult noMatch = mvc.perform(put(LAYOUT).contentType(MediaType.APPLICATION_JSON).content(BODY)).andReturn();
        BODIES.assertResponseOf(noMatch);
        assertThat(noMatch.getResponse().getStatus()).isEqualTo(428);
        MvcResult badMatch = mvc.perform(put(LAYOUT).contentType(MediaType.APPLICATION_JSON).header("If-Match", "\"x\"")
                .content(BODY)).andReturn();
        BODIES.assertResponseOf(badMatch);
        assertThat(badMatch.getResponse().getStatus()).isEqualTo(400);
        MvcResult badRevision = mvc.perform(get(LAYOUT).param("revision", "0")).andReturn();
        BODIES.assertResponseOf(badRevision);
        assertThat(badRevision.getResponse().getStatus()).isEqualTo(400);
        assertThat(layouts.saved).as("a refused request never reaches a write").isEmpty();
    }

    private MvcResult assertProblem(RequestBuilder request, int status, String code) throws Exception {
        MvcResult result = exchange(request);
        assertThat(result.getResponse().getStatus()).isEqualTo(status);
        assertThat(result.getResponse().getContentAsString()).contains("\"code\":\"" + code + "\"");
        return result;
    }

    private MvcResult exchange(RequestBuilder request) throws Exception {
        MvcResult result = mvc.perform(request).andReturn();
        BODIES.assertExchange(result);
        return result;
    }

    private static LayoutRevision revision(int n, boolean withDocument) {
        return new LayoutRevision(n, withDocument ? "{\"nodes\":{\"start\":{\"x\":40,\"y\":120}},\"schemaVersion\":1}" : null,
                "a".repeat(64), "b".repeat(64), "API", "carol", NOW.plusSeconds(n));
    }

    /** Four revisions of kyc-onboarding v2; v1 has none; another bank's versions are not found. */
    private static final class FakeLayouts implements LayoutService {
        final List<String> saved = new ArrayList<>();

        private static void version(DefinitionRef ref) {
            if (ref.key().equals("other-bank")) {
                throw new DefinitionNotFoundException("No definition other-bank v1 in this tenant");
            }
        }

        @Override
        public LayoutRevision saveLayout(DefinitionRef ref, long expectedRevision, String layoutJson, String key) {
            version(ref);
            if (expectedRevision == 0 && ref.version() == 2) {
                throw new PermissionDeniedException("Needs processes:author");
            }
            if (expectedRevision != 3) {
                throw new VersionConflictException("The layout is at revision 3 but the save was made over revision "
                        + expectedRevision);
            }
            if (layoutJson.contains("ghost-step")) {
                throw new LayoutInvalidException("The layout is not valid for this version: 1 problem(s)",
                        List.of("$.nodes.ghost-step names a step that is not in the definition"));
            }
            saved.add(expectedRevision + ":" + layoutJson);
            return new LayoutRevision(4, "{\"nodes\":{\"start\":{\"x\":40,\"y\":120}},\"schemaVersion\":1}", "a".repeat(64),
                    "b".repeat(64), "API", "carol", NOW);
        }

        @Override
        public Optional<LayoutRevision> latestLayout(DefinitionRef ref) {
            version(ref);
            return ref.version() == 2 ? Optional.of(revision(4, true)) : Optional.empty();
        }

        @Override
        public Optional<LayoutRevision> layoutRevision(DefinitionRef ref, int revision) {
            version(ref);
            return revision <= 4 ? Optional.of(revision(revision, true)) : Optional.empty();
        }

        @Override
        public List<LayoutRevision> layoutRevisions(DefinitionRef ref, int afterRevision, int limit) {
            version(ref);
            List<LayoutRevision> out = new ArrayList<>();
            for (int n = afterRevision + 1; n <= 4 && out.size() < limit; n++) {
                out.add(revision(n, false));
            }
            return out;
        }
    }
}
