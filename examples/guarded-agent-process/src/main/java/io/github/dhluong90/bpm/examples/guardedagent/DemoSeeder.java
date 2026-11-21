package io.github.dhluong90.bpm.examples.guardedagent;

import io.github.dhluong90.bpm.process.api.DefinitionService;
import io.github.dhluong90.bpm.process.api.DefinitionVersion;
import io.github.dhluong90.bpm.rules.api.ImportRequest;
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
import org.springframework.core.io.ClassPathResource;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Dev profile only: publishes the {@code loan-document-guardrails} rule set through the normal maker-checker path
 * (one seed identity requests, another approves), then imports the {@code loan-document-review} process, which the
 * dev profile activates on import. The order matters: a process whose guardrail rule set is not published is refused
 * at deploy (PRE-016). A real host publishes through the cockpit or a pipeline.
 */
@Component
@Profile("dev")
class DemoSeeder {

    static final String RULE_SET = "loan-document-guardrails";
    private static final Log LOG = LogFactory.getLog(DemoSeeder.class);

    private final VersionService ruleSets;
    private final DefinitionService definitions;

    DemoSeeder(VersionService ruleSets, DefinitionService definitions) {
        this.ruleSets = ruleSets;
        this.definitions = definitions;
    }

    @EventListener(ApplicationReadyEvent.class)
    void seed() {
        publishGuardrails();
        DefinitionVersion process = as("seed-deployer", "processes:deploy", () -> definitions.importDefinition(
                new io.github.dhluong90.bpm.process.api.ImportRequest(
                        io.github.dhluong90.bpm.process.api.SourceFormat.BPMN_XML,
                        resource("processes/loan-document-review.bpmn"), "seed-loan-document-review")));
        LOG.info("Process " + process.key() + " v" + process.version() + " " + process.status());
    }

    private void publishGuardrails() {
        RuleSetVersion draft;
        try {
            draft = as("seed-author", "rules:author", () -> ruleSets.importRuleSet(
                    ImportRequest.yaml(resource("rules/" + RULE_SET + ".yaml"))));
        } catch (RuleSetExistsException e) {
            LOG.info("Rule set " + RULE_SET + " exists; not seeded again");
            return;
        }
        RuleSetVersion requested = as("seed-author", "rules:author",
                () -> ruleSets.requestPublish(RULE_SET, draft.version(), WriteOptions.none()));
        RuleSetVersion published = as("seed-approver", "rules:approve", () -> ruleSets.approve(RULE_SET,
                draft.version(), new WriteOptions(null, requested.lockVersion(), null)
                        .expectingContent(requested.canonicalHash())));
        LOG.info("Rule set " + RULE_SET + " v" + published.version() + " " + published.status()
                + " (requested by seed-author, approved by seed-approver)");
    }

    private static byte[] resource(String path) {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
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
