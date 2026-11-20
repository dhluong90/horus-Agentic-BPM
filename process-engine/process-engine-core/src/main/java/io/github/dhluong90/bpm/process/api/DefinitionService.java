package io.github.dhluong90.bpm.process.api;

/**
 * Imports process definitions. Safe for concurrent use.
 *
 * <p>Transactions: parsing and validation run before any transaction opens; the insert joins the caller's
 * transaction when there is one. Never calls the network.
 */
public interface DefinitionService {

    /**
     * Imports a definition document as the next version of its key in the caller's tenant. Needs
     * {@code processes:deploy}. The version is {@code DRAFT}, or {@code ACTIVE} with approver
     * {@code system:dev-auto-approve} when {@code horus.bpm.processes.definitions-auto-approve} is on, which the
     * starter accepts on the {@code dev} profile only (PRE-022). A document whose canonical hash equals the
     * latest version of its key creates no new version; that version is returned.
     *
     * @param request the document
     * @return the stored version
     * @throws BpmnUnsafeXmlException when the XML has a DOCTYPE or entity, or exceeds the depth limit
     * @throws BpmnUnsupportedElementException naming every element outside the supported subset
     * @throws ValidationFailedException when the graph is not executable
     * @throws DefinitionTooLargeException when a size limit is exceeded
     */
    DefinitionVersion importDefinition(ImportRequest request);

    /**
     * Reads one version of a definition in the caller's tenant. Needs {@code processes:read}.
     *
     * @param key the definition key
     * @param version the version number, from 1
     * @return the version, without its source document
     * @throws DefinitionNotFoundException when the tenant has no such version
     */
    DefinitionVersion version(String key, int version);
}
