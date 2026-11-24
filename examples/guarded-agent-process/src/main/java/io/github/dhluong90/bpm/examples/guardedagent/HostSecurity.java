package io.github.dhluong90.bpm.examples.guardedagent;

import io.github.dhluong90.bpm.host.security.BpmProblemAccessDeniedHandler;
import io.github.dhluong90.bpm.host.security.BpmProblemAuthenticationEntryPoint;
import io.github.dhluong90.bpm.process.spi.AssigneeDirectory;
import io.github.dhluong90.bpm.process.spi.RoleCatalogue;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * The host's own security, in one chain; the engines add no authentication of their own. Users sign in with the
 * host's form, and on the {@code dev} profile the demo users may also use HTTP Basic for the scripted demo. CSRF stays
 * on for every state-changing call made with a session. Engine endpoints answer {@code 401} and {@code 403} as
 * RFC 9457 problem details.
 *
 * <p>Demo users exist on the {@code dev} profile only, with the password from {@code HORUS_DEMO_PASSWORD}:
 * {@code kim} (teller, who sends applications to review), {@code thu} (compliance reviewer), {@code an}
 * (underwriter) and {@code pia} (process deployer). Their roles map to engine permissions in
 * {@code application.yml}. A real host signs users in through its identity provider instead; see
 * {@code examples/rule-and-process} for bearer tokens with an audience check.
 */
@Configuration(proxyBeanMethods = false)
class HostSecurity {

    static final String DEV_PROFILE = "dev";
    private static final String JWT = "spring.security.oauth2.resourceserver.jwt.";
    private static final Log LOG = LogFactory.getLog(HostSecurity.class);

    @Bean
    SecurityFilterChain hostSecurityChain(HttpSecurity http, Environment environment,
            BpmProblemAuthenticationEntryPoint problemEntryPoint, BpmProblemAccessDeniedHandler problemAccessDenied) {
        RequestMatcher engineApi = PathPatternRequestMatcher.withDefaults().matcher("/api/bpm/**");
        boolean dev = environment.matchesProfiles(DEV_PROFILE);
        http.authorizeHttpRequests(requests -> requests
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .anyRequest().authenticated())
                .formLogin(Customizer.withDefaults())
                .logout(Customizer.withDefaults())
                .csrf(csrf -> csrf
                        .csrfTokenRepository(csrfCookies(environment))
                        // Basic credentials carry no session cookie, so a forged cross-site request cannot use them.
                        .ignoringRequestMatchers(request -> dev && basic(request.getHeader("Authorization"))))
                .exceptionHandling(exceptions -> exceptions
                        .defaultAuthenticationEntryPointFor(problemEntryPoint, engineApi)
                        .defaultAccessDeniedHandlerFor(problemAccessDenied, engineApi));
        if (dev) {
            http.httpBasic(basic -> basic.authenticationEntryPoint(problemEntryPoint));
        }
        return http.build();
    }

    static boolean basic(String authorization) {
        return authorization != null && authorization.regionMatches(true, 0, "Basic ", 0, 6);
    }

    /**
     * The {@code XSRF-TOKEN} cookie, readable by the page, with the same {@code Secure} and {@code SameSite=Lax}
     * attributes as the session cookie ({@code server.servlet.session.cookie.*} in {@code application.yml}).
     * Browsers accept a secure cookie from {@code http://localhost}.
     */
    static CookieCsrfTokenRepository csrfCookies(Environment environment) {
        boolean secure = environment.getProperty("server.servlet.session.cookie.secure", Boolean.class, true);
        CookieCsrfTokenRepository repository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        repository.setCookieCustomizer(cookie -> cookie.secure(secure).sameSite("Lax"));
        return repository;
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

    /** The demo users, on the {@code dev} profile only; outside it nobody can sign in with the form. */
    @Bean
    InMemoryUserDetailsManager hostUsers(Environment environment, @Value("${HORUS_DEMO_PASSWORD:}") String password) {
        List<UserDetails> users = new ArrayList<>();
        if (!environment.matchesProfiles(DEV_PROFILE)) {
            return new InMemoryUserDetailsManager(users);
        }
        requireLocalDevelopment(environment);
        if (password.isBlank()) {
            LOG.warn("HORUS_DEMO_PASSWORD is not set, so no demo user can sign in.");
            return new InMemoryUserDetailsManager(users);
        }
        PasswordEncoder encoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();
        String encoded = encoder.encode(password);
        users.add(User.withUsername("kim").password(encoded).roles("TELLER").build());
        users.add(User.withUsername("thu").password(encoded).roles("COMPLIANCE_REVIEWER").build());
        users.add(User.withUsername("an").password(encoded).roles("UNDERWRITER").build());
        users.add(User.withUsername("pia").password(encoded).roles("PROCESS_DEPLOYER").build());
        return new InMemoryUserDetailsManager(users);
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
}
