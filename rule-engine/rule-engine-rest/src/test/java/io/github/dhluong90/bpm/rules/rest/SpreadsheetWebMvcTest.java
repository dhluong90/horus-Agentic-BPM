package io.github.dhluong90.bpm.rules.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import io.github.dhluong90.bpm.rules.api.ImportRequest;
import io.github.dhluong90.bpm.rules.api.RuleSetVersion;
import io.github.dhluong90.bpm.rules.api.SourceFormat;
import io.github.dhluong90.bpm.rules.api.ValidationFailedException;
import io.github.dhluong90.bpm.rules.api.VersionService;
import io.github.dhluong90.bpm.rules.api.VersionStatus;
import io.github.dhluong90.bpm.rules.api.Violation;
import io.github.dhluong90.bpm.rules.internal.json.EngineJson;
import io.github.dhluong90.bpm.rules.spi.CallerResolver;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.JsonNode;

/** The spreadsheet round trip over Spring MVC (RLE-191, RLE-192, RLE-193, issue #39): the file, the base and the refusal. */
class SpreadsheetWebMvcTest {

    static final String BASE = "/api/bpm/rules/v1/rule-sets/card-fee/versions";

    Set<String> permissions;
    final List<String> calls = new ArrayList<>();
    final List<ImportRequest> requests = new ArrayList<>();
    boolean refuse;
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        permissions = Set.of("rules:read", "rules:author");
        CallerResolver callers = new CallerResolver() {
            @Override
            public String currentCallerId() {
                return "lan";
            }

            @Override
            public Set<String> currentPermissions() {
                return permissions;
            }
        };
        VersionService service = (VersionService) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {VersionService.class}, (self, method, args) -> {
                    switch (method.getName()) {
                        case "export" -> {
                            calls.add("export v" + args[1] + " " + args[2]);
                            return "﻿Rule,tier,fee\r\nR1,GOLD,0 VND\r\n";
                        }
                        case "importCsv" -> {
                            calls.add("importCsv base " + args[1]);
                            requests.add((ImportRequest) args[2]);
                            if (refuse) {
                                throw new ValidationFailedException(List.of(new Violation("R4", "balance",
                                        "The amount has no currency", "CSV_CELL_NO_CURRENCY")));
                            }
                            return new RuleSetVersion("card-fee", 3, VersionStatus.DRAFT, "FIRST", null, 0, "{}", "h1",
                                    SourceFormat.JSON, "s1", null, null, null, null, null, "lan",
                                    Instant.parse("2027-02-01T09:00:00Z"), null, null, List.of());
                        }
                        default -> throw new UnsupportedOperationException(method.getName());
                    }
                });
        mvc = MockMvcBuilders.standaloneSetup(new RuleSetController(service, callers))
                .setControllerAdvice(new ProblemDetails()).build();
    }

    @Test
    void RLE_191_export_csv_answers_a_utf8_file_named_after_the_version() throws Exception {
        MockHttpServletResponse response = mvc.perform(get(BASE + "/1/export").param("format", "csv")).andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getHeader("Content-Type")).isEqualTo("text/csv;charset=UTF-8");
        assertThat(response.getHeader("Content-Disposition")).isEqualTo("attachment; filename=\"card-fee-v1.csv\"");
        assertThat(response.getContentAsByteArray()).startsWith(0xEF, 0xBB, 0xBF);
        assertThat(calls).containsExactly("export v1 CSV");
    }

    @Test
    void RLE_192_a_csv_body_with_a_base_answers_201_and_reaches_the_engine_with_its_key() throws Exception {
        MockHttpServletResponse response = mvc.perform(post(BASE).param("base", "1").contentType("text/csv")
                .header("Idempotency-Key", "k-39").content("Rule,tier,fee\r\nR1,GOLD,0 VND\r\n")).andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(201);
        assertThat(response.getHeader("Location")).isEqualTo(BASE + "/3");
        assertThat(calls).containsExactly("importCsv base 1");
        assertThat(requests).singleElement().satisfies(r -> {
            assertThat(r.format()).isEqualTo(SourceFormat.CSV);
            assertThat(r.idempotencyKey()).isEqualTo("k-39");
            assertThat(new String(r.source(), StandardCharsets.UTF_8)).startsWith("Rule,tier,fee");
        });
    }

    @Test
    void RLE_192_a_csv_body_needs_a_base_and_a_base_needs_a_csv_body() throws Exception {
        for (String bad : List.of("0", "abc", "-1")) {
            MockHttpServletResponse response = mvc.perform(post(BASE).param("base", bad).contentType("text/csv")
                    .content("Rule\r\n")).andReturn().getResponse();
            assertThat(response.getStatus()).as("base=" + bad).isEqualTo(400);
        }
        assertThat(mvc.perform(post(BASE).contentType("text/csv").content("Rule\r\n")).andReturn().getResponse().getStatus())
                .isEqualTo(400);
        assertThat(mvc.perform(post(BASE).param("base", "1").contentType("application/yaml").content("ruleSet: card-fee\n"))
                .andReturn().getResponse().getStatus()).isEqualTo(400);
        assertThat(calls).isEmpty();
    }

    @Test
    void RLE_192_a_reader_is_refused_before_the_file_is_read() throws Exception {
        permissions = Set.of("rules:read");

        MockHttpServletResponse response = mvc.perform(post(BASE).param("base", "1").contentType("text/csv")
                .content("Rule\r\n")).andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(calls).isEmpty();
    }

    @Test
    void RLE_193_a_refused_cell_is_422_validation_failed_with_its_rule_column_and_code() throws Exception {
        refuse = true;

        MockHttpServletResponse response = mvc.perform(post(BASE).param("base", "1").contentType("text/csv")
                .content("Rule,balance\r\nR4,>= 50000000\r\n")).andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(422);
        JsonNode body = EngineJson.JSON.readTree(response.getContentAsString());
        assertThat(body.get("code").asString()).isEqualTo("VALIDATION_FAILED");
        assertThat(body.at("/violations/0/ruleId").asString()).isEqualTo("R4");
        assertThat(body.at("/violations/0/column").asString()).isEqualTo("balance");
        assertThat(body.at("/violations/0/code").asString()).isEqualTo("CSV_CELL_NO_CURRENCY");
        assertThat(response.getContentAsString()).doesNotContain("50000000");
    }
}
