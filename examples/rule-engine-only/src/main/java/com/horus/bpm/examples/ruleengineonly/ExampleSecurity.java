package com.horus.bpm.examples.ruleengineonly;

import com.horus.bpm.rules.spi.TenantResolver;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

/**
 * The host's own security, as a real host would have it: HTTP Basic with demo users for the scripted
 * demo. A production host uses its identity provider instead; the engine only needs the two beans the
 * starter reads, a {@link TenantResolver} and (from Spring Security) the caller and its authorities.
 *
 * <p>Each demo user carries {@code rules:*} permissions and one {@code tenant:<id>} authority. The
 * password comes from {@code HORUS_DEMO_PASSWORD}; without it no demo user exists and every API call
 * is refused.
 */
@Configuration(proxyBeanMethods = false)
public class ExampleSecurity {

    private static final String TENANT_PREFIX = "tenant:";

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .anyRequest().authenticated())
                .httpBasic(Customizer.withDefaults())
                // A stateless API called with Basic credentials, not a browser session.
                .csrf(csrf -> csrf.ignoringRequestMatchers("/api/**"))
                .build();
    }

    @Bean
    InMemoryUserDetailsManager demoUsers(@Value("${example.demo-password:}") String password) {
        List<UserDetails> users = new ArrayList<>();
        if (!password.isBlank()) {
            PasswordEncoder encoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();
            String hash = encoder.encode(password);
            users.add(user("alice", hash, "bank-a", "rules:author", "rules:approve", "rules:read", "rules:evaluate"));
            users.add(user("bob", hash, "bank-a", "rules:approve", "rules:read", "rules:evaluate"));
            users.add(user("teller", hash, "bank-a", "rules:evaluate"));
            users.add(user("carol", hash, "tenant-b", "rules:author", "rules:approve", "rules:read", "rules:evaluate"));
            users.add(user("dave", hash, "tenant-b", "rules:approve", "rules:read", "rules:evaluate"));
        }
        return new InMemoryUserDetailsManager(users);
    }

    /** The tenant is the {@code tenant:<id>} authority of the authenticated user, never a request field. */
    @Bean
    TenantResolver tenantResolver() {
        return () -> {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            if (authentication == null) {
                return null;
            }
            return authentication.getAuthorities().stream().map(GrantedAuthority::getAuthority)
                    .filter(a -> a.startsWith(TENANT_PREFIX)).map(a -> a.substring(TENANT_PREFIX.length()))
                    .findFirst().orElse(null);
        };
    }

    private static UserDetails user(String name, String passwordHash, String tenant, String... permissions) {
        String[] authorities = new String[permissions.length + 1];
        System.arraycopy(permissions, 0, authorities, 0, permissions.length);
        authorities[permissions.length] = TENANT_PREFIX + tenant;
        return User.withUsername(name).password(passwordHash).authorities(authorities).build();
    }
}
