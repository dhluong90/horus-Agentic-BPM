/**
 * Engine-to-engine entry points: what another embedded HORUS engine uses to act on its own stored work. Not for
 * hosts. The entry point is never a bean; the only route to it is a one-time {@link
 * io.github.dhluong90.bpm.rules.embedded.EmbeddedEngineGrant#claim claim} by the calling engine's registered claimant,
 * which keeps it to itself (ADR-0027). Signatures use only JDK and {@code rules.api} types.
 */
package io.github.dhluong90.bpm.rules.embedded;
