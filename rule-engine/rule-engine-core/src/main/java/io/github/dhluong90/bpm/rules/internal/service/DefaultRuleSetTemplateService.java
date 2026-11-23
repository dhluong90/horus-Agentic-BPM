package io.github.dhluong90.bpm.rules.internal.service;

import io.github.dhluong90.bpm.rules.api.PermissionDeniedException;
import io.github.dhluong90.bpm.rules.api.RuleSetTemplate;
import io.github.dhluong90.bpm.rules.api.RuleSetTemplateService;
import io.github.dhluong90.bpm.rules.api.UnauthenticatedException;
import io.github.dhluong90.bpm.rules.spi.CallerResolver;
import java.util.List;
import java.util.Objects;

/**
 * Lists the templates of a {@link RuleSetTemplates} catalogue to a caller who may author rules (PD-112, RLE-197).
 * Stateless and read-only.
 */
public final class DefaultRuleSetTemplateService implements RuleSetTemplateService {

    static final String PERMISSION_AUTHOR = "rules:author";

    private final CallerResolver callers;
    private final RuleSetTemplates catalogue;

    /**
     * Creates the service.
     *
     * @param callers resolves the caller and its permissions
     * @param catalogue the templates, read once at start-up
     */
    public DefaultRuleSetTemplateService(CallerResolver callers, RuleSetTemplates catalogue) {
        this.callers = Objects.requireNonNull(callers, "callers must not be null");
        this.catalogue = Objects.requireNonNull(catalogue, "catalogue must not be null");
    }

    @Override
    public List<RuleSetTemplate> list() {
        String caller = callers.currentCallerId();
        if (caller == null || caller.isBlank()) {
            throw new UnauthenticatedException("No caller in the security context");
        }
        if (!callers.currentPermissions().contains(PERMISSION_AUTHOR)) {
            throw new PermissionDeniedException(PERMISSION_AUTHOR);
        }
        return catalogue.list();
    }
}
