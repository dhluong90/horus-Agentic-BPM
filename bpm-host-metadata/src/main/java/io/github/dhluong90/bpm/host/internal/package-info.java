/**
 * Shared internals of the host module: the problem body and correlation ids. Public only so the
 * {@code security} and {@code autoconfigure} packages can share them; no compatibility promise, and hosts
 * never import it (ADR-0016).
 */
package io.github.dhluong90.bpm.host.internal;
