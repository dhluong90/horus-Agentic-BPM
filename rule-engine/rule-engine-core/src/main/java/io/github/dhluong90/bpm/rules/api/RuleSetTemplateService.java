package io.github.dhluong90.bpm.rules.api;

import java.util.List;

/**
 * The rule set templates the host offers (PD-112, RLE-197). Templates belong to the host, not to a tenant: they are
 * read from the engine's built-in set and the host's configured folder, and listing them writes nothing.
 */
public interface RuleSetTemplateService {

    /**
     * Lists the templates: the built-in ones the host has not hidden, then the host's own in file name order.
     *
     * @return the templates; never null
     * @throws UnauthenticatedException when there is no caller in the security context
     * @throws PermissionDeniedException when the caller lacks {@code rules:author}
     */
    List<RuleSetTemplate> list();
}
