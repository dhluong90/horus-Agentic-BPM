package io.github.dhluong90.bpm.process.api;

/**
 * What a modeller may place on the canvas: only the building blocks the host registered (PRE-032, PE-F-52). Needs
 * {@code processes:author}. The tenant and the caller come from the host's resolvers, never from a request value, and
 * the read writes nothing.
 */
public interface StepCatalogue {

    /**
     * Lists the host's registered service handlers, agent adapters, decision and guardrail rule sets, calendars and
     * roles for the caller's tenant. A list the host did not register is empty, never missing.
     *
     * @return the catalogue; names and schemas only, never a configuration value, an endpoint or a credential
     * @throws UnauthenticatedException when there is no caller or tenant
     * @throws PermissionDeniedException when the caller lacks {@code processes:author}
     */
    StepCatalogueView stepCatalogue();
}
