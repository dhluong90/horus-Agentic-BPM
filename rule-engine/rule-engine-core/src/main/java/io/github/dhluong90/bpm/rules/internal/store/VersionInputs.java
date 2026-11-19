package com.horus.bpm.rules.internal.store;

/**
 * The declared inputs of one version that can still be evaluated (any status but {@code RETIRED}), read at start-up
 * to check that every tenant that masks an input has a masking key (RLE-035).
 *
 * @param tenantId the tenant
 * @param ruleSet the rule set name
 * @param version the version number
 * @param inputsJson the {@code inputs} array of the canonical document
 */
public record VersionInputs(String tenantId, String ruleSet, int version, String inputsJson) {
}
