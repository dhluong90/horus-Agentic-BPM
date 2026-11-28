package io.github.dhluong90.bpm.examples.ruleandprocess;

import io.github.dhluong90.bpm.host.security.BpmProblemAccessDeniedHandler;
import io.github.dhluong90.bpm.host.security.BpmProblemAuthenticationEntryPoint;
import io.github.dhluong90.bpm.process.spi.AssigneeDirectory;
import io.github.dhluong90.bpm.process.spi.RoleCatalogue;
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
 * <p>Demo users exist on the {@code dev} profile only: {@code lan} (roles PRICING_AUTHOR, PROCESS_DEPLOYER and PROCESS_APPROVER, allowed tenants bank-a and bank-b),
 * {@code minh} (roles PRICING_APPROVER and PROCESS_APPROVER), {@code tuan} (role TELLER), {@code an} (role CARD_OFFICER),
 * {@code hoa} (TELLER, CARD_OFFICER and BRANCH_SUPERVISOR), {@code hung} (OPERATIONS_OFFICER) and {@code quynh} (AUDITOR), with the password from
 * {@code HORUS_DEMO_PASSWORD}. On that profile they may also use HTTP Basic, for the curl walkthrough. Their roles map to engine
 * permissions in {@code application.yml}. A real host signs users in through its identity provider instead.
 */
@Configuration(proxyBeanMethods = false)
class HostSecurityConfiguration {

    static final String DEV_PROFILE = "dev";

    private static final Log LOG = LogFactory.getLog(HostSecurityConfiguration.class);
    private static final String JWT = "spring.security.oauth2.resourceserver.jwt.";

    @Bean
    SecurityFilterChain hostSecurity(HttpSecurity http, ObjectProvider<JwtDecoder> jwtDecoder, Environment environment,
            BpmProblemAuthenticationEntryPoint problemEntryPoint, BpmProblemAccessDeniedHandler problemAccessDenied) {
        requireAudience(environment);
        // Bearer requests skip CSRF only when this host validates tokens: without a decoder a junk bearer
        // header must not turn the CSRF check off for a request that is really authenticated by the session.
        boolean acceptsTokens = jwtDecoder.getIfAvailable() != null;
        RequestMatcher engineApi = PathPatternRequestMatcher.withDefaults().matcher("/api/bpm/**");
        // The engine module's beans, built from horus.bpm.security.challenge, so the chain and the engine endpoints
        // send the same challenge.
        http.authorizeHttpRequests(requests -> requests
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers(prometheusOnManagementPort(environment)).permitAll()
                        .anyRequest().authenticated())
                .formLogin(Customizer.withDefaults())
                .logout(Customizer.withDefaults())
                .csrf(csrf -> csrf
                        .csrfTokenRepository(csrfCookies(environment))
                        .csrfTokenRequestHandler(new SpaCsrfTokenRequestHandler())
                        .ignoringRequestMatchers(request -> (acceptsTokens && bearerRequest(request))
                                || (environment.matchesProfiles(DEV_PROFILE) && basicRequest(request))))
                // Only pages are remembered for the redirect after sign-in, so an anonymous API call creates no session
                .requestCache(cache -> cache.requestCache(pagesOnly(engineApi)))
                .exceptionHandling(exceptions -> exceptions
                        .defaultAuthenticationEntryPointFor(problemEntryPoint, engineApi)
                        .defaultAccessDeniedHandlerFor(problemAccessDenied, engineApi));
        if (environment.matchesProfiles(DEV_PROFILE)) {
            // Local development only: the curl walkthrough of the README signs the demo users in with Basic
            // credentials. Each call carries its credentials and no cookie, so CSRF does not apply to it.
            http.httpBasic(basic -> basic.authenticationEntryPoint(problemEntryPoint));
        }
        if (acceptsTokens) {
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

    /**
     * The {@code XSRF-TOKEN} cookie, readable by the page as the cockpit needs, with the same {@code Secure}
     * and {@code SameSite=Lax} attributes as the session cookie ({@code server.servlet.session.cookie.*} in
     * {@code application.yml}). Browsers accept a secure cookie from {@code http://localhost}.
     */
    static CookieCsrfTokenRepository csrfCookies(Environment environment) {
        boolean secure = environment.getProperty("server.servlet.session.cookie.secure", Boolean.class, true);
        CookieCsrfTokenRepository repository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        repository.setCookieCustomizer(cookie -> cookie.secure(secure).sameSite("Lax"));
        return repository;
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
    InMemoryUserDetailsManager hostUsers(Environment environment, @Value("${HORUS_DEMO_PASSWORD:}") String password,
            @Value("${horus.bpm.tenancy.dev-tenant:}") String devTenant) {
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
            // lan makes: she authors rules and deploys processes. minh checks: he approves them. tuan submits card
            // applications, an reviews the fees as card officer. The engines refuse an approval by the person who made
            // the change, whatever roles that person holds (four eyes by person), so lan can never approve her own work.
            users.add(User.withUsername("lan").password(encoded).roles("PRICING_AUTHOR", "PROCESS_DEPLOYER",
                    "PROCESS_APPROVER").build());
            users.add(User.withUsername("minh").password(encoded).roles("PRICING_APPROVER", "PROCESS_APPROVER").build());
            users.add(User.withUsername("tuan").password(encoded).roles("TELLER").build());
            users.add(User.withUsername("an").password(encoded).roles("CARD_OFFICER", "KYC_OFFICER", "DISPUTES_OFFICER",
                    "PAYMENT_OFFICER", "PRICING_OFFICER", "CREDIT_ANALYST", "CREDIT_MANAGER").build());
            // A branch supervisor holds both roles and may cancel: the four-eyes review refuses her on her own applications.
            users.add(User.withUsername("hoa").password(encoded).roles("TELLER", "CARD_OFFICER", "BRANCH_SUPERVISOR",
                    "DISPUTES_SENIOR", "CREDIT_MANAGER", "CREDIT_COMMITTEE").build());
            // Operations runs live cases: correlates messages, broadcasts signals, migrates instances and proposes
            // calendars. hoa is the second holder of migration and calendar approval.
            users.add(User.withUsername("hung").password(encoded).roles("OPERATIONS_OFFICER", "OPS", "AML_OFFICER",
                    "SANCTIONS_OFFICER", "COMPLIANCE_REVIEWER").build());
            // The auditor reads rules, processes and the decision log, and changes nothing.
            users.add(User.withUsername("quynh").password(encoded).roles("AUDITOR").build());
        }
        // lan may switch between bank-a and bank-b in the cockpit (horus.bpm.tenancy.switching in application-dev.yml).
        return new DemoUserStore(users, devTenant, java.util.Map.of("lan", List.of("bank-a", "bank-b")));
    }

    /**
     * Who may be given a role task by reassign or delegate (PD-110): the roles of this host's user store, in the
     * {@code dev} tenant. Without this bean the engine refuses every such move with {@code 409 ASSIGNEE_NOT_ALLOWED}.
     */
    @Bean
    AssigneeDirectory hostAssigneeDirectory(InMemoryUserDetailsManager hostUsers,
            @Value("${horus.bpm.tenancy.dev-tenant:}") String devTenant) {
        return new UserStoreAssigneeDirectory(hostUsers, devTenant);
    }

    /**
     * The roles an escalation may name, checked at approval (PRE-180): those of the demo users, in the {@code dev}
     * tenant. Without this bean the engine checks no role.
     */
    @Bean
    RoleCatalogue hostRoleCatalogue(@Value("${horus.bpm.tenancy.dev-tenant:}") String devTenant) {
        return new DemoRoleCatalogue(devTenant);
    }

    /**
     * Stops a {@code dev} start-up that looks like a shared environment: another profile active beside
     * {@code dev} (other than {@code test}), or a token issuer, key set or public key that is not on this machine.
     */
    static void requireLocalDevelopment(Environment environment) {
        for (String profile : environment.getActiveProfiles()) {
            if (!profile.equals(DEV_PROFILE) && !profile.equals("test")) {
                throw new IllegalStateException("The dev profile turns on demo users and must run alone, but profile '"
                        + profile + "' is active too. Remove dev outside local development.");
            }
        }
        for (String name : List.of("issuer-uri", "jwk-set-uri")) {
            String uri = environment.getProperty(JWT + name, "");
            if (!uri.isBlank() && !onThisMachine(uri)) {
                throw new IllegalStateException("The dev profile turns on demo users, but the token " + name + " " + uri
                        + " is not on this machine. Remove dev outside local development.");
            }
        }
        // A fixed public key is local only as a file or classpath resource; one fetched from a URL is refused.
        String key = environment.getProperty(JWT + "public-key-location", "");
        if (!key.isBlank() && !(key.startsWith("file:") || key.startsWith("classpath:") || onThisMachine(key))) {
            throw new IllegalStateException("The dev profile turns on demo users, but tokens are also accepted with the "
                    + "public key " + key + ", which is not on this machine. Remove dev outside local development.");
        }
    }

    private static boolean onThisMachine(String uri) {
        String host;
        try {
            host = URI.create(uri).getHost();
        } catch (IllegalArgumentException malformed) {
            return false;
        }
        return host != null && (host.equals("localhost") || host.equals("127.0.0.1") || host.equals("[::1]"));
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

    /**
     * The Prometheus scrape, only when it arrives on the management port ({@code management.server.port}, which
     * deploy/observability sets and does not publish outside the compose network or the cluster). On the application's
     * own port the endpoint stays behind the sign-in, so exposing it never opens it to the host's users' network.
     */
    static RequestMatcher prometheusOnManagementPort(Environment environment) {
        RequestMatcher path = PathPatternRequestMatcher.withDefaults().matcher("/actuator/prometheus");
        return request -> {
            Integer management = environment.getProperty("local.management.port", Integer.class);
            return management != null && request.getLocalPort() == management && path.matches(request);
        };
    }
}
