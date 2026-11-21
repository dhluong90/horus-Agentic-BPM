package io.github.dhluong90.bpm.rules.test;

import io.github.dhluong90.bpm.rules.api.SourceFormat;
import io.github.dhluong90.bpm.rules.api.ValidationFailedException;
import io.github.dhluong90.bpm.rules.api.Violation;
import io.github.dhluong90.bpm.rules.internal.eval.CompiledRuleSet;
import io.github.dhluong90.bpm.rules.internal.model.HitPolicy;
import io.github.dhluong90.bpm.rules.internal.model.Rule;
import io.github.dhluong90.bpm.rules.internal.read.ParsedRuleSet;
import io.github.dhluong90.bpm.rules.internal.read.RuleSetReader;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * A rule set file read, checked and compiled once for a test class, exactly as import does it. The step budget is
 * checked too, so a table the engine would refuse at import fails the class here.
 *
 * @param path the class path location it was read from
 * @param hash the canonical hash, the identity of the version in the report
 * @param hitPolicy the hit policy, as the engine names it
 * @param ruleIds the rule ids in document order
 * @param rules the compiled rule set
 */
record LoadedRuleSet(String path, String hash, String hitPolicy, List<String> ruleIds, CompiledRuleSet rules) {

    LoadedRuleSet {
        ruleIds = List.copyOf(ruleIds);
    }

    static LoadedRuleSet load(String path, ClassLoader loader) {
        Objects.requireNonNull(path, "path must not be null");
        byte[] source;
        try (InputStream in = loader.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("The rule set file " + path + " is not on the class path; the"
                        + " @RuleSetTest path is relative to src/test/resources");
            }
            source = in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException("The rule set file " + path + " cannot be read: " + e.getMessage(), e);
        }
        try {
            ParsedRuleSet parsed = RuleSetReader.read(formatOf(path), source);
            CompiledRuleSet rules = CompiledRuleSet.compile(parsed.model());
            rules.requireWithinStepBudget();
            List<String> ids = parsed.model().decision().table().rules().stream().map(Rule::id).toList();
            HitPolicy policy = rules.hitPolicy();
            return new LoadedRuleSet(path, parsed.canonicalHash(), policy.name().toLowerCase(Locale.ROOT), ids, rules);
        } catch (ValidationFailedException e) {
            throw new IllegalStateException("The rule set file " + path + " fails import:" + describe(e), e);
        }
    }

    private static SourceFormat formatOf(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".yaml") || lower.endsWith(".yml")) {
            return SourceFormat.YAML;
        }
        if (lower.endsWith(".json")) {
            return SourceFormat.JSON;
        }
        if (lower.endsWith(".dmn") || lower.endsWith(".xml")) {
            return SourceFormat.DMN_XML;
        }
        throw new IllegalStateException("The rule set file " + path + " has an unknown extension; use .yaml, .yml,"
                + " .json, .dmn or .xml");
    }

    /** The violations as 422 VALIDATION_FAILED lists them: rule id, column, code, position and message. */
    private static String describe(ValidationFailedException e) {
        StringBuilder text = new StringBuilder();
        if (e.violations().isEmpty()) {
            return " " + e.getMessage();
        }
        for (Violation violation : e.violations()) {
            text.append("\n  - rule ").append(violation.ruleId() == null ? "(document)" : violation.ruleId());
            if (violation.column() != null) {
                text.append(", column ").append(violation.column());
            }
            if (violation.code() != null) {
                text.append(", ").append(violation.code());
            }
            text.append(": ").append(violation.message());
        }
        return text.toString();
    }
}
