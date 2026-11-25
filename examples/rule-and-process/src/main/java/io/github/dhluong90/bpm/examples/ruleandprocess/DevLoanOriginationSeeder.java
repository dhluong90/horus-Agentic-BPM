package io.github.dhluong90.bpm.examples.ruleandprocess;

import io.github.dhluong90.bpm.process.api.DefinitionService;
import io.github.dhluong90.bpm.process.api.ImportRequest;
import io.github.dhluong90.bpm.rules.api.RuleSetExistsException;
import io.github.dhluong90.bpm.rules.api.RuleSetVersion;
import io.github.dhluong90.bpm.rules.api.VersionService;
import io.github.dhluong90.bpm.rules.api.WriteOptions;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.function.Supplier;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ResourceLoader;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Dev profile only: publishes the four rule sets of {@code loan-origination} through the normal maker-checker path (one
 * seed identity requests, another approves), then imports the process. The process is imported here and not listed in
 * {@code definitions.locations} because its agent step names a guardrail rule set, and a definition is refused until
 * that rule set is published. Host code; a real host publishes through the cockpit or a pipeline.
 */
@Component
@Profile("dev")
class DevLoanOriginationSeeder {

    private static final Log LOG = LogFactory.getLog(DevLoanOriginationSeeder.class);
    static final List<String> RULE_SETS = List.of("kyc-loan-guardrails", "credit-risk-grade", "approval-authority",
            "loan-pricing");
    static final String MODEL = "classpath:processes/loan-origination.bpmn";

    private final VersionService versions;
    private final DefinitionService definitions;
    private final ResourceLoader resources;

    DevLoanOriginationSeeder(VersionService versions, DefinitionService definitions, ResourceLoader resources) {
        this.versions = versions;
        this.definitions = definitions;
        this.resources = resources;
    }

    @EventListener(ApplicationReadyEvent.class)
    void seed() {
        for (String ruleSet : RULE_SETS) {
            byte[] source = read("classpath:rules/" + ruleSet + ".yaml");
            try {
                RuleSetVersion draft = as("seed-author", "rules:author", () -> versions.importRuleSet(
                        io.github.dhluong90.bpm.rules.api.ImportRequest.yaml(source)));
                RuleSetVersion requested = as("seed-author", "rules:author",
                        () -> versions.requestPublish(ruleSet, draft.version(), WriteOptions.none()));
                as("seed-approver", "rules:approve", () -> versions.approve(ruleSet, draft.version(),
                        new WriteOptions(null, requested.lockVersion(), null).expectingContent(requested.canonicalHash())));
                LOG.info("Rule set " + ruleSet + " v" + draft.version() + " PUBLISHED (requested by seed-author, "
                        + "approved by seed-approver)");
            } catch (RuleSetExistsException e) {
                LOG.info("Rule set " + ruleSet + " exists; not seeded again");
            }
        }
        var imported = as("seed-deployer", "processes:deploy",
                () -> definitions.importDefinition(ImportRequest.bpmn(read(MODEL))));
        LOG.info("Process definition " + imported.key() + " v" + imported.version() + " " + imported.status()
                + " for the dev tenant from " + MODEL);
    }

    private byte[] read(String location) {
        try (InputStream in = resources.getResource(location).getInputStream()) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static <T> T as(String user, String permission, Supplier<T> work) {
        TestingAuthenticationToken token = new TestingAuthenticationToken(user, "n/a",
                List.of(new SimpleGrantedAuthority(permission)));
        token.setAuthenticated(true);
        SecurityContextHolder.getContext().setAuthentication(token);
        try {
            return work.get();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}
