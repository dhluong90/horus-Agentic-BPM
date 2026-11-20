/**
 * Engine-to-engine entry points: what another embedded HORUS engine uses to act on its own stored work. Not for
 * hosts. Nothing here is registered as a bean; the calling engine's wiring builds the object it needs from the Rule
 * Engine's internals and keeps it to itself (ADR-0027). Signatures use only JDK and {@code rules.api} types.
 */
package io.github.dhluong90.bpm.rules.embedded;
