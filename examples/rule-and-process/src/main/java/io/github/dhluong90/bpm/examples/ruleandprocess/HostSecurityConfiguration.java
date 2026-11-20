package io.github.dhluong90.bpm.examples.ruleandprocess;

import io.github.dhluong90.bpm.host.autoconfigure.BpmProblemAccessDeniedHandler;
import io.github.dhluong90.bpm.host.autoconfigure.BpmProblemAuthenticationEntryPoint;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.AndRequestMatcher;
import org.springframework.security.web.util.matcher.NegatedRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * The host's own security, in one chain. The engines and the cockpit add no authentication of their own.
 *
 * <ul>
 *   <li><b>Session sign-in</b> with the host's form ({@code /login}). The embedded cockpit and the engine
 *       endpoints use the same session. CSRF protection stays on for every state-changing call made with
 *       the session; the token is in the {@code XSRF-TOKEN} cookie for the cockpit to send back as
 *       {@code X-XSRF-TOKEN}.
 *   <li><b>Bearer tokens</b> from the identity provider, when an issuer, JWK set or public key is
 *       configured. The host must then name its own audience (TR-SEC-12), and a token issued for another
 *       host is {@code 401}. Bearer calls carry no cookie, so CSRF does not apply to them.
 * </ul>
 *
 * <p>Engine endpoints under {@code /api/bpm/**} answer {@code 401} and {@code 403} as RFC 9457 problem
 * details; every other path keeps the browser behaviour of a redirect to the sign-in page.
 *
 * <p>The tenant comes from the engines' default resolver: the {@code tenant_id} claim of a token. The demo
 * users carry no tenant, so {@code application-dev.yml} sets {@code horus.bpm.tenancy.dev-tenant: bank-a},
 * which the engines accept on the {@code dev} profile only (PD-079).
 *
 * <p>Demo users exist on the {@code dev} profile only: {@code lan} (roles PRICING_AUTHOR and PROCESS_DEPLOYER),
 * {@code minh} (role PRICING_APPROVER), {@code tuan} (role TELLER), {@code an} (role CARD_OFFICER) and {@code hoa} (TELLER, CARD_OFFICER and BRANCH_SUPERVISOR), with the password from
 * {@code HORUS_DEMO_PASSWORD}. On that profile they may also use HTTP Basic, for the curl walkthrough. Their roles map to engine
 * permissions in {@code application.yml}. A real host signs users in through its identity provider instead.
 */
@Configuration(proxyBeanMethods = false)
class HostSecurityConfiguration {

    static final String DEV_PROFILE = "dev";

    private static final Log LOG = LogFactory.getLog(HostSecurityConfiguration.class);
    private static final String JWT = "spring.security.oauth2.resourceserver.jwt.";

    @Bean
    SecurityFilterChain hostSecurity(HttpSecurity http, ObjectProvider<JwtDecoder> jwtDecoder, Environment environment) {
        requireAudience(environment);
        RequestMatcher engineApi = PathPatternRequestMatcher.withDefaults().matcher("/api/bpm/**");
        BpmProblemAuthenticationEntryPoint problemEntryPoint = new BpmProblemAuthenticationEntryPoint();
        http.authorizeHttpRequests(requests -> requests
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .anyRequest().authenticated())
                .formLogin(Customizer.withDefaults())
                .logout(Customizer.withDefaults())
                .csrf(csrf -> csrf
                        .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                        .csrfTokenRequestHandler(new SpaCsrfTokenRequestHandler())
                        .ignoringRequestMatchers(request -> bearerRequest(request)
                                || (environment.matchesProfiles(DEV_PROFILE) && basicRequest(request))))
                // Only pages are remembered for the redirect after sign-in, so an anonymous API call creates no session
                .requestCache(cache -> cache.requestCache(pagesOnly(engineApi)))
                .exceptionHandling(exceptions -> exceptions
                        .defaultAuthenticationEntryPointFor(problemEntryPoint, engineApi)
                        .defaultAccessDeniedHandlerFor(new BpmProblemAccessDeniedHandler(), engineApi));
        if (environment.matchesProfiles(DEV_PROFILE)) {
            // Local development only: the curl walkthrough of the README signs the demo users in with Basic
            // credentials. Each call carries its credentials and no cookie, so CSRF does not apply to it.
            http.httpBasic(basic -> basic.authenticationEntryPoint(problemEntryPoint));
        }
        if (jwtDecoder.getIfAvailable() != null) {
            http.oauth2ResourceServer(server -> server
                    .jwt(jwt -> jwt.jwtAuthenticationConverter(rolesAndPermissions()))
                    .authenticationEntryPoint(problemEntryPoint));
        }
        return http.build();
    }

    /**
     * The page to return to after sign-in. Engine calls, background calls (X-Requested-With) and the
     * browser's automatic favicon request are never remembered: otherwise the sign-in page's own
     * {@code /favicon.ico} request replaces the page the user asked for, such as {@code /bpm/cockpit/}.
     * Spring Security's default matcher skips the last two; a custom matcher must keep doing so.
     */
    static HttpSessionRequestCache pagesOnly(RequestMatcher engineApi) {
        RequestMatcher favicon = PathPatternRequestMatcher.withDefaults().matcher("/favicon.*");
        RequestMatcher backgroundCall = request -> "XMLHttpRequest".equals(request.getHeader("X-Requested-With"));
        HttpSessionRequestCache requestCache = new HttpSessionRequestCache();
        requestCache.setRequestMatcher(new AndRequestMatcher(
                new NegatedRequestMatcher(engineApi),
                new NegatedRequestMatcher(favicon),
                new NegatedRequestMatcher(backgroundCall)));
        return requestCache;
    }

    /** A request authenticated by a bearer token sends no cookie, so a forged cross-site request cannot use it. */
    static boolean bearerRequest(HttpServletRequest request) {
        String authorization = request.getHeader("Authorization");
        return authorization != null && authorization.regionMatches(true, 0, "Bearer ", 0, 7);
    }

    /** A request with Basic credentials sends no session cookie; accepted on the {@code dev} profile only. */
    static boolean basicRequest(HttpServletRequest request) {
        String authorization = request.getHeader("Authorization");
        return authorization != null && authorization.regionMatches(true, 0, "Basic ", 0, 6);
    }

    /**
     * The demo users, on the {@code dev} profile only. Outside it the store is empty, so nobody can sign in
     * with the form and Spring Boot generates no default user either.
     */
    @Bean
    InMemoryUserDetailsManager hostUsers(Environment environment, @Value("${HORUS_DEMO_PASSWORD:}") String password) {
        List<UserDetails> users = new ArrayList<>();
        if (!environment.matchesProfiles(DEV_PROFILE)) {
            return new InMemoryUserDetailsManager(users);
        }
        requireLocalDevelopment(environment);
        if (password.isBlank()) {
            LOG.warn("HORUS_DEMO_PASSWORD is not set, so no demo user can sign in.");
        } else {
            PasswordEncoder encoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();
            String encoded = encoder.encode(password);
            // The walkthrough of the README: lan authors rules and deploys processes, minh approves rules,
            // tuan submits card applications, an reviews fees as card officer.
            users.add(User.withUsername("lan").password(encoded).roles("PRICING_AUTHOR", "PROCESS_DEPLOYER").build());
            users.add(User.withUsername("minh").password(encoded).roles("PRICING_APPROVER").build());
            users.add(User.withUsername("tuan").password(encoded).roles("TELLER").build());
            users.add(User.withUsername("an").password(encoded).roles("CARD_OFFICER").build());
            // A branch supervisor holds both roles and may cancel: the four-eyes review refuses her on her own applications.
            users.add(User.withUsername("hoa").password(encoded).roles("TELLER", "CARD_OFFICER", "BRANCH_SUPERVISOR").build());
        }
        return new InMemoryUserDetailsManager(users);
    }

    /**
     * Stops a {@code dev} start-up that looks like a shared environment: another profile active beside
     * {@code dev} (other than {@code test}), or a token issuer that is not on this machine.
     */
    static void requireLocalDevelopment(Environment environment) {
        for (String profile : environment.getActiveProfiles()) {
            if (!profile.equals(DEV_PROFILE) && !profile.equals("test")) {
                throw new IllegalStateException("The dev profile turns on demo users and must run alone, but profile '"
                        + profile + "' is active too. Remove dev outside local development.");
            }
        }
        String issuer = environment.getProperty(JWT + "issuer-uri", "");
        if (!issuer.isBlank()) {
            String host = URI.create(issuer).getHost();
            if (host == null || !(host.equals("localhost") || host.equals("127.0.0.1") || host.equals("[::1]"))) {
                throw new IllegalStateException("The dev profile turns on demo users, but the token issuer " + issuer
                        + " is not on this machine. Remove dev outside local development.");
            }
        }
    }

    /**
     * TR-SEC-12: whenever the host accepts bearer tokens it must name its own audience, so a token minted for
     * another client of the same identity provider is refused.
     */
    static void requireAudience(Environment environment) {
        boolean acceptsTokens = List.of("issuer-uri", "jwk-set-uri", "public-key-location").stream()
                .anyMatch(name -> !environment.getProperty(JWT + name, "").isBlank());
        if (acceptsTokens && environment.getProperty(JWT + "audiences", "").isBlank()) {
            throw new IllegalStateException("The host accepts bearer tokens but names no audience. Set "
                    + "SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_AUDIENCES to this host's audience, so a token "
                    + "issued for another host is refused.");
        }
    }

    /**
     * Token claim {@code roles} becomes {@code ROLE_*} authorities, mapped to engine permissions by
     * {@code horus.bpm.rules.security.role-permissions}; claim {@code permissions} carries engine
     * permission strings directly.
     */
    static JwtAuthenticationConverter rolesAndPermissions() {
        JwtGrantedAuthoritiesConverter roles = new JwtGrantedAuthoritiesConverter();
        roles.setAuthoritiesClaimName("roles");
        roles.setAuthorityPrefix("ROLE_");
        JwtGrantedAuthoritiesConverter permissions = new JwtGrantedAuthoritiesConverter();
        permissions.setAuthoritiesClaimName("permissions");
        permissions.setAuthorityPrefix("");
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            Collection<GrantedAuthority> authorities = new ArrayList<>(roles.convert(jwt));
            authorities.addAll(permissions.convert(jwt));
            return authorities;
        });
        return converter;
    }
}
