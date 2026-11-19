package com.horus.bpm.examples.ruleengineonly;

import com.horus.bpm.host.autoconfigure.BpmProblemAccessDeniedHandler;
import com.horus.bpm.host.autoconfigure.BpmProblemAuthenticationEntryPoint;
import java.util.ArrayList;
import java.util.Collection;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

/**
 * The host's own security. The engines add no authentication: this host accepts bearer tokens from its
 * identity provider, and on the {@code dev} profile two demo users over HTTP basic. Engine endpoints
 * answer {@code 401} and {@code 403} as RFC 9457 problem details through the two engine handlers.
 */
@Configuration(proxyBeanMethods = false)
class HostSecurityConfiguration {

    @Bean
    SecurityFilterChain hostSecurity(
            HttpSecurity http, ObjectProvider<JwtDecoder> jwtDecoder, ObjectProvider<UserDetailsService> devUsers) {
        boolean basic = devUsers.getIfAvailable() != null;
        var entryPoint = basic
                ? new BpmProblemAuthenticationEntryPoint("Basic realm=\"rule-engine-only\"")
                : new BpmProblemAuthenticationEntryPoint();
        http.authorizeHttpRequests(requests -> requests
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .anyRequest().authenticated())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(new BpmProblemAccessDeniedHandler()));
        // Bearer tokens when an issuer is configured (SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI).
        if (jwtDecoder.getIfAvailable() != null) {
            http.oauth2ResourceServer(server -> server
                    .jwt(jwt -> jwt.jwtAuthenticationConverter(rolesAndPermissions()))
                    .authenticationEntryPoint(entryPoint));
        }
        if (basic) {
            http.httpBasic(httpBasic -> httpBasic.authenticationEntryPoint(entryPoint));
        }
        return http.build();
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
     * Two demo users for the {@code dev} profile only: Lan, a pricing analyst, and Minh, a pricing
     * approver. Their roles map to engine permissions in {@code application.yml}. Never use these
     * outside local development.
     */
    @Bean
    @Profile("dev")
    UserDetailsService devUsers() {
        return new InMemoryUserDetailsManager(
                User.withUsername("lan").password("{noop}lan").roles("PRICING_ANALYST").build(),
                User.withUsername("minh").password("{noop}minh").roles("PRICING_APPROVER").build());
    }
}
