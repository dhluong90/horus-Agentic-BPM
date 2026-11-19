package com.horus.bpm.rules.internal.read;

import com.horus.bpm.rules.internal.model.Definitions;

/**
 * A source document read into the decision model, with its canonical form and hashes.
 *
 * @param model the DMN-shaped model
 * @param canonicalJson the RFC 8785 form of the document
 * @param canonicalHash SHA-256 of {@code canonicalJson}
 */
public record ParsedRuleSet(Definitions model, String canonicalJson, String canonicalHash) {
}
