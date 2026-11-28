package io.github.dhluong90.bpm.process.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dhluong90.bpm.process.api.Incident;
import io.github.dhluong90.bpm.process.api.IncidentQuery;
import io.github.dhluong90.bpm.process.api.ImportRequest;
import io.github.dhluong90.bpm.process.api.NodeCounts;
import io.github.dhluong90.bpm.process.api.Page;
import io.github.dhluong90.bpm.process.api.PageRequest;
import io.github.dhluong90.bpm.process.api.PermissionDeniedException;
import io.github.dhluong90.bpm.process.api.ProcessInstance;
import io.github.dhluong90.bpm.process.api.QueryInvalidException;
import io.github.dhluong90.bpm.process.api.SourceFormat;
import io.github.dhluong90.bpm.process.api.StartRequest;
import io.github.dhluong90.bpm.process.spi.HandlerResult;
import io.github.dhluong90.bpm.process.spi.ServiceCall;
import io.github.dhluong90.bpm.process.spi.ServiceTaskHandler;
import io.github.dhluong90.bpm.process.test.TestClock;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Open incidents per step in the live node counts (PRE-521) and the definition filter of the incident list (PRE-522),
 * on PostgreSQL. A loan disbursement stops on its limit check while the daily limit of account ACC-0001 is reached,
 * which opens an incident on {@code check-limit}. Bank-a runs {@code loan-disbursement} versions 1 and 2 and
 * {@code loan-repayment} version 1; bank-b runs {@code loan-disbursement} version 1.
 */
class IncidentCountsAndFiltersTest {

    private static final String[] READER = {"processes:read"};
    private static final String[] OPERATOR = {"processes:read", "processes:cancel", "processes:incident:manage"};
    private static final String DISBURSEMENT = """
            <?xml version="1.0" encoding="UTF-8"?>
            <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                              xmlns:horus="urn:horus-bpm:bpmn:extensions:v1" id="Definitions_KEY"
                              targetNamespace="urn:horus-bpm:examples" expressionLanguage="urn:horus-bpm:expr:v1">
              <bpmn:process id="KEY" name="NAME" isExecutable="true">
                <bpmn:startEvent id="start"/>
                <bpmn:serviceTask id="check-limit" horus:handler="limits.check" horus:resultVariable="limit">
                  <bpmn:extensionElements>
                    <horus:input name="accountId" value="${instance.accountId}"/>
                  </bpmn:extensionElements>
                </bpmn:serviceTask>
                <bpmn:userTask id="disburse" horus:assignee="role:LOAN_OFFICER" horus:resultVariable="disbursal"/>
                <bpmn:endEvent id="end"/>
                <bpmn:sequenceFlow id="f1" sourceRef="start" targetRef="check-limit"/>
                <bpmn:sequenceFlow id="f2" sourceRef="check-limit" targetRef="disburse"/>
                <bpmn:sequenceFlow id="f3" sourceRef="disburse" targetRef="end"/>
              </bpmn:process>
            </bpmn:definitions>
            """;

    private final TestClock clock = TestClock.at("2027-05-03T02:00:00Z");
    private TestEngine pod;
    private final List<ProcessInstance> v1 = new ArrayList<>();

    @BeforeEach
    void bankA_has_two_stopped_on_v1_one_on_v2_and_one_repayment_and_bank_b_has_one() {
        TestEngine.reset();
        pod = TestEngine.replica("pod-1", clock).handlers(new ServiceTaskHandler() {
            @Override
            public String name() {
                return "limits.check";
            }

            @Override
            public HandlerResult handle(ServiceCall call) {
                return new HandlerResult.BusinessError("LIMIT_EXCEEDED", "Daily limit reached for account ACC-0001");
            }
        }).build();
        define("bank-a", "loan-disbursement", "Loan disbursement");
        v1.add(start("bank-a", "loan-disbursement", "DISB-1"));
        clock.advance(Duration.ofMinutes(1));
        v1.add(start("bank-a", "loan-disbursement", "DISB-2"));
        clock.advance(Duration.ofMinutes(1));
        define("bank-a", "loan-disbursement", "Loan disbursement, second version");
        start("bank-a", "loan-disbursement", "DISB-3");
        clock.advance(Duration.ofMinutes(1));
        define("bank-a", "loan-repayment", "Loan repayment");
        start("bank-a", "loan-repayment", "REPAY-1");
        define("bank-b", "loan-disbursement", "Loan disbursement");
        start("bank-b", "loan-disbursement", "DISB-B-1");
        clock.advance(Duration.ofMinutes(1));
    }

    private void define(String tenant, String key, String name) {
        pod.identity.as(tenant, "pipeline", "processes:deploy", "processes:start", "processes:read");
        String xml = DISBURSEMENT.replace("KEY", key).replace("NAME", name).strip();
        pod.services.importDefinition(new ImportRequest(SourceFormat.BPMN_XML, xml.getBytes(StandardCharsets.UTF_8), null));
    }

    private ProcessInstance start(String tenant, String key, String reference) {
        pod.identity.as(tenant, "teller", "processes:start", "processes:read");
        return pod.services.start(new StartRequest(key, reference, Map.of("accountId", "ACC-0001"), null));
    }

    private NodeCounts counts(String tenant, String key, int version) {
        pod.identity.as(tenant, "supervisor", READER);
        clock.advance(Duration.ofSeconds(30));
        return pod.queries.liveNodeCounts(key, version);
    }

    private Page<Incident> list(String tenant, IncidentQuery query) {
        pod.identity.as(tenant, "supervisor", READER);
        return pod.services.incidents(query);
    }

    // ---- PRE-521 ----------------------------------------------------------------------------------------------

    @Test
    void PRE_521_each_node_counts_the_open_incidents_of_its_own_version() {
        // Given loan-disbursement v1 with two stopped cases and v2 with one, all at check-limit
        // When a supervisor reads the live counts of each version
        // Then each version counts only its own open incidents, whether or not a live token is counted there
        assertThat(counts("bank-a", "loan-disbursement", 1).openIncidents()).isEqualTo(Map.of("check-limit", 2L));
        assertThat(counts("bank-a", "loan-disbursement", 2).openIncidents()).isEqualTo(Map.of("check-limit", 1L));
        assertThat(counts("bank-a", "loan-repayment", 1).openIncidents()).isEqualTo(Map.of("check-limit", 1L));
    }

    @Test
    void PRE_521_a_resolved_incident_is_no_longer_counted() {
        // Given DISB-1 is cancelled, which resolves its incident as INSTANCE_CANCELLED
        pod.identity.as("bank-a", "ops.lan", OPERATOR);
        pod.services.cancel(v1.get(0).id(), pod.services.instance(v1.get(0).id()).lockVersion(), "Customer withdrew",
                "c-1");

        // When the live counts are read again after the cache expired
        NodeCounts after = counts("bank-a", "loan-disbursement", 1);

        // Then one open incident is left
        assertThat(after.openIncidents()).isEqualTo(Map.of("check-limit", 1L));
    }

    @Test
    void PRE_521_another_tenants_incidents_are_never_counted_or_revealed() {
        // Given bank-b has one open incident on its own loan-disbursement v1, and bank-a has two on its v1
        // When each tenant reads version 1
        NodeCounts bankB = counts("bank-b", "loan-disbursement", 1);
        NodeCounts bankA = counts("bank-a", "loan-disbursement", 1);

        // Then each sees its own count only, whichever was read first
        assertThat(bankB.openIncidents()).isEqualTo(Map.of("check-limit", 1L));
        assertThat(bankA.openIncidents()).isEqualTo(Map.of("check-limit", 2L));
        // And a version or key only bank-a has is not found for bank-b
        assertThatThrownBy(() -> counts("bank-b", "loan-repayment", 1)).extracting("code")
                .isEqualTo("DEFINITION_NOT_FOUND");
        assertThatThrownBy(() -> counts("bank-b", "loan-disbursement", 2)).extracting("code")
                .isEqualTo("DEFINITION_NOT_FOUND");
    }

    @Test
    void PRE_521_a_node_without_an_incident_is_absent_and_the_read_needs_the_read_permission() {
        // Given a supervisor without processes:read
        pod.identity.as("bank-a", "intruder", "processes:start");

        // Then the counts are refused, and a version with no incident has an empty map
        assertThatThrownBy(() -> pod.queries.liveNodeCounts("loan-disbursement", 1))
                .isInstanceOf(PermissionDeniedException.class);
        pod.identity.as("bank-a", "ops.lan", OPERATOR);
        for (ProcessInstance instance : v1) {
            pod.services.cancel(instance.id(), pod.services.instance(instance.id()).lockVersion(), "Withdrawn",
                    "c-" + instance.id());
        }
        assertThat(counts("bank-a", "loan-disbursement", 1).openIncidents()).isEmpty();
    }

    // ---- PRE-522 ----------------------------------------------------------------------------------------------

    @Test
    void PRE_522_the_incident_list_keeps_the_versions_of_one_key_or_one_version() {
        // Given bank-a has 4 open incidents: 3 on loan-disbursement (2 on v1, 1 on v2) and 1 on loan-repayment
        assertThat(list("bank-a", IncidentQuery.open()).items()).hasSize(4);

        // When the supervisor filters by key, by key and version, and by an unknown key or version
        Page<Incident> byKey = list("bank-a", new IncidentQuery("OPEN", null, "loan-disbursement", null));
        Page<Incident> byVersion = list("bank-a", new IncidentQuery("OPEN", null, "loan-disbursement", 1));
        Page<Incident> repayment = list("bank-a", new IncidentQuery("OPEN", null, "loan-repayment", 1));

        // Then each list holds exactly those incidents, newest first
        assertThat(byKey.items()).hasSize(3).extracting(Incident::createdAt)
                .isSortedAccordingTo(java.util.Comparator.reverseOrder());
        assertThat(byVersion.items()).hasSize(2).extracting(Incident::instanceId)
                .containsExactlyInAnyOrder(v1.get(0).id(), v1.get(1).id());
        assertThat(repayment.items()).hasSize(1);
        assertThat(list("bank-a", new IncidentQuery("OPEN", null, "card-dispute", null)).items()).isEmpty();
        assertThat(list("bank-a", new IncidentQuery("OPEN", null, "loan-disbursement", 9)).items()).isEmpty();
        assertThat(list("bank-a", new IncidentQuery("RESOLVED", null, "loan-disbursement", 1)).items()).isEmpty();
    }

    @Test
    void PRE_522_a_filtered_list_pages_with_a_cursor_bound_to_its_filter() {
        // Given the two open incidents of v1, read one at a time
        IncidentQuery firstQuery = new IncidentQuery("OPEN", new PageRequest(1, null), "loan-disbursement", 1);
        Page<Incident> first = list("bank-a", firstQuery);
        Page<Incident> second = list("bank-a", new IncidentQuery("OPEN", new PageRequest(1, first.nextCursor()),
                "loan-disbursement", 1));

        // Then the pages hold the two incidents, newest first, with no cursor on the last
        assertThat(first.items()).hasSize(1);
        assertThat(second.items()).hasSize(1).first().isNotEqualTo(first.items().get(0));
        assertThat(first.items().get(0).createdAt()).isAfterOrEqualTo(second.items().get(0).createdAt());
        assertThat(second.nextCursor()).isNull();
        // And the cursor of one filter is refused for another filter, and for the unfiltered list
        String cursor = first.nextCursor();
        assertThatThrownBy(() -> list("bank-a", new IncidentQuery("OPEN", new PageRequest(1, cursor),
                "loan-disbursement", 2))).isInstanceOf(QueryInvalidException.class);
        assertThatThrownBy(() -> list("bank-a", new IncidentQuery("OPEN", new PageRequest(1, cursor),
                "loan-repayment", null))).isInstanceOf(QueryInvalidException.class);
        assertThatThrownBy(() -> list("bank-a", new IncidentQuery("OPEN", new PageRequest(1, cursor))))
                .isInstanceOf(QueryInvalidException.class);
    }

    @Test
    void PRE_522_a_version_without_a_key_or_below_one_is_refused() {
        assertThatThrownBy(() -> list("bank-a", new IncidentQuery("OPEN", null, null, 1)))
                .isInstanceOf(QueryInvalidException.class).hasMessageContaining("definitionKey");
        assertThatThrownBy(() -> list("bank-a", new IncidentQuery("OPEN", null, "loan-disbursement", 0)))
                .isInstanceOf(QueryInvalidException.class);
        assertThatThrownBy(() -> list("bank-a", new IncidentQuery("OPEN", null, "  ", null)))
                .isInstanceOf(QueryInvalidException.class);
    }

    @Test
    void PRE_522_another_tenants_incidents_never_appear_in_a_filtered_list() {
        // Given bank-b has one incident on loan-disbursement v1 and none on loan-repayment
        Page<Incident> bankB = list("bank-b", new IncidentQuery("OPEN", null, "loan-disbursement", 1));
        Page<Incident> bankBRepayment = list("bank-b", new IncidentQuery("OPEN", null, "loan-repayment", null));
        Page<Incident> bankBv2 = list("bank-b", new IncidentQuery("OPEN", null, "loan-disbursement", 2));

        // Then bank-b sees its own one, and nothing of bank-a's versions
        assertThat(bankB.items()).hasSize(1);
        assertThat(bankB.items().get(0).instanceId()).doesNotContain(v1.get(0).id());
        assertThat(bankBRepayment.items()).isEmpty();
        assertThat(bankBv2.items()).isEmpty();
    }
}
