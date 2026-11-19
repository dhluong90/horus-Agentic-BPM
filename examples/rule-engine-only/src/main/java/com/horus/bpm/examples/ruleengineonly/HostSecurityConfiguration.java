package com.horus.bpm.examples.ruleengineonly;

import java.util.ArrayList;
import java.util.List;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

/**
 * The host's own sign-in. The engines and the cockpit add no authentication of their own: the cockpit at
 * /bpm/cockpit is reached only after this sign-in, and the engines' endpoints use the same session.
 *
 * <p>Demo users only: {@code lan} (role PRICING_AUTHOR) and {@code minh} (role PRICING_APPROVER), with the
 * password from {@code HORUS_DEMO_PASSWORD}. Without that variable nobody can sign in. A real host signs
 * users in through its identity provider instead.
 */
@Configuration(proxyBeanMethods = false)
class HostSecurityConfiguration {

    private static final Log LOG = LogFactory.getLog(HostSecurityConfiguration.class);

    @Bean
    SecurityFilterChain hostSecurity(HttpSecurity http) throws Exception {
        http.authorizeHttpRequests(requests -> requests
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .anyRequest().authenticated())
                .formLogin(Customizer.withDefaults())
                .logout(Customizer.withDefaults());
        return http.build();
    }

    @Bean
    InMemoryUserDetailsManager hostUsers(@Value("${HORUS_DEMO_PASSWORD:}") String password) {
        List<UserDetails> users = new ArrayList<>();
        if (password.isBlank()) {
            LOG.warn("HORUS_DEMO_PASSWORD is not set, so no demo user can sign in.");
        } else {
            PasswordEncoder encoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();
            String encoded = encoder.encode(password);
            users.add(User.withUsername("lan").password(encoded).roles("PRICING_AUTHOR").build());
            users.add(User.withUsername("minh").password(encoded).roles("PRICING_APPROVER").build());
        }
        return new InMemoryUserDetailsManager(users);
    }
}
