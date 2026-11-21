package io.github.dhluong90.bpm.rules.test;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.Optional;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.api.extension.ParameterResolver;
import org.junit.platform.commons.support.AnnotationSupport;

/**
 * The extension behind {@link RuleSetTest}: loads the rule set before the first test of a class, and gives each test a
 * {@link RuleSetUnderTest} at the clock of the class or of the test. Registered by the annotation; a project never
 * names it.
 */
public final class RuleSetExtension implements BeforeAllCallback, ParameterResolver {

    private static final ExtensionContext.Namespace NAMESPACE = ExtensionContext.Namespace.create(RuleSetExtension.class);

    @Override
    public void beforeAll(ExtensionContext context) {
        RuleSetTest annotation = AnnotationSupport.findAnnotation(context.getRequiredTestClass(), RuleSetTest.class)
                .orElseThrow(() -> new IllegalStateException("RuleSetExtension needs @RuleSetTest on the test class"));
        // Fails the class before any test runs, with the violations of an import (RLE-059).
        LoadedRuleSet loaded = LoadedRuleSet.load(annotation.value(), context.getRequiredTestClass().getClassLoader());
        parseClock(annotation.clock(), "@RuleSetTest(clock)");
        context.getStore(NAMESPACE).put(LoadedRuleSet.class, loaded);
        CoverageRun.of(context).loaded(loaded);
    }

    @Override
    public boolean supportsParameter(ParameterContext parameter, ExtensionContext context) {
        return parameter.getParameter().getType() == RuleSetUnderTest.class;
    }

    @Override
    public Object resolveParameter(ParameterContext parameter, ExtensionContext context) {
        LoadedRuleSet loaded = loaded(context);
        if (loaded == null) {
            throw new ParameterResolutionException("No rule set is loaded: annotate the test class with @RuleSetTest");
        }
        String testId = context.getTestMethod()
                .map(method -> context.getRequiredTestClass().getName() + "#" + method.getName())
                .orElse(context.getRequiredTestClass().getName() + "#(class)");
        return new RuleSetUnderTest(loaded, clockOf(context), testId, CoverageRun.of(context));
    }

    private static LoadedRuleSet loaded(ExtensionContext context) {
        ExtensionContext current = context;
        while (current != null) {
            LoadedRuleSet loaded = current.getStore(NAMESPACE).get(LoadedRuleSet.class, LoadedRuleSet.class);
            if (loaded != null) {
                return loaded;
            }
            current = current.getParent().orElse(null);
        }
        return null;
    }

    /** The test's own clock, else the class's, else the default. */
    private static Instant clockOf(ExtensionContext context) {
        Optional<RuleSetClock> test = context.getTestMethod()
                .flatMap(method -> AnnotationSupport.findAnnotation(method, RuleSetClock.class));
        if (test.isPresent()) {
            return parseClock(test.get().value(), "@RuleSetClock");
        }
        Optional<RuleSetClock> onClass = AnnotationSupport.findAnnotation(context.getRequiredTestClass(),
                RuleSetClock.class);
        if (onClass.isPresent()) {
            return parseClock(onClass.get().value(), "@RuleSetClock");
        }
        return AnnotationSupport.findAnnotation(context.getRequiredTestClass(), RuleSetTest.class)
                .map(annotation -> parseClock(annotation.clock(), "@RuleSetTest(clock)"))
                .orElse(Instant.parse(RuleSetTest.DEFAULT_CLOCK));
    }

    static Instant parseClock(String text, String where) {
        try {
            return OffsetDateTime.parse(text).toInstant();
        } catch (DateTimeException e) {
            try {
                return ZonedDateTime.parse(text).toInstant();
            } catch (DateTimeException ignored) {
                throw new IllegalArgumentException(where + " must be an ISO 8601 instant with a zone, such as"
                        + " 2027-06-30T17:00:00Z, was '" + text + "'");
            }
        }
    }
}
