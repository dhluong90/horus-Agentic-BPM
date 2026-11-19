/**
 * REST controllers of the Rule Engine under {@code /api/bpm/rules/v1}. Bodies are read and written
 * with the engine's own JSON mapper, so host Jackson settings never change engine payloads (ADR-0021).
 */
package io.github.dhluong90.bpm.rules.rest;
