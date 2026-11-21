/**
 * Problem responses for the host's own security filter chain, the public types a host wires next to its
 * Spring Security configuration. Covered by the same compatibility promise as the {@code spi} package
 * (ADR-0016); unlike it, these types implement Spring Security interfaces, because that is where a host
 * plugs them in.
 *
 * <ul>
 *   <li>{@link io.github.dhluong90.bpm.host.security.BpmProblemAuthenticationEntryPoint}: {@code 401} with
 *       {@code code: UNAUTHENTICATED} and the challenge of {@code horus.bpm.security.challenge}.
 *   <li>{@link io.github.dhluong90.bpm.host.security.BpmProblemAccessDeniedHandler}: {@code 403} with
 *       {@code code: PERMISSION_DENIED}.
 * </ul>
 *
 * <p>The module registers one bean of each, so a chain injects them and sends the same challenge as the engine
 * endpoints; a host bean of the same type replaces the default.
 */
package io.github.dhluong90.bpm.host.security;
