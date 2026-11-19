package io.github.dhluong90.bpm.examples.ruleandprocess;

import com.horus.bpm.rules.api.ImportRequest;
import com.horus.bpm.rules.api.RuleSetExistsException;
import com.horus.bpm.rules.api.RuleSetVersion;
import com.horus.bpm.rules.api.VersionService;
import com.horus.bpm.rules.api.WriteOptions;
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
 * Dev profile only: publishes the {@code card-fee} rule set once, through the normal maker-checker path, with two
 * seed identities (one requests, the other approves). Host code; a real host publishes through the cockpit or a
 * pipeline.
 */
@Component
@Profile("dev")
class DevRuleSetSeeder {

    private static final Log LOG = LogFactory.getLog(DevRuleSetSeeder.class);
    private static final String RULE_SET = "card-fee";

    private final VersionService versions;

    DevRuleSetSeeder(VersionService versions) {
        this.versions = versions;
    }

    @EventListener(ApplicationReadyEvent.class)
    void seed() {
        byte[] source;
        try (InputStream in = new ClassPathResource("rules/card-fee.yaml").getInputStream()) {
            source = in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        RuleSetVersion draft;
        try {
            draft = as("seed-author", "rules:author", () -> versions.importRuleSet(ImportRequest.yaml(source)));
        } catch (RuleSetExistsException e) {
            LOG.info("Rule set " + RULE_SET + " exists; not seeded again");
            return;
        }
        as("seed-author", "rules:author", () -> versions.requestPublish(RULE_SET, draft.version(), WriteOptions.none()));
        RuleSetVersion published = as("seed-approver", "rules:approve",
                () -> versions.approve(RULE_SET, draft.version(), WriteOptions.none()));
        LOG.info("Rule set " + RULE_SET + " v" + published.version() + " " + published.status()
                + " (requested by seed-author, approved by seed-approver)");
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
