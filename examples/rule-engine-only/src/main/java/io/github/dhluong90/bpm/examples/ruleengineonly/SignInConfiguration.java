package io.github.dhluong90.bpm.examples.ruleengineonly;

import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.io.Resource;

/**
 * The host's sign-in page (the cockpit library has none: in embedded mode the host owns sign-in).
 *
 * <ul>
 *   <li>{@code host.sign-in.template}: the HTML template, {@code classpath:/sign-in/sign-in.html} by default.
 *       A host copies it to change the page; the styles and script are under {@code /sign-in/}.
 *   <li>The service name and environment shown on the card are the cockpit's
 *       ({@code horus.bpm.cockpit.host-label}, {@code horus.bpm.cockpit.environment}).
 *   <li>Demo user names, never passwords, appear only on the {@code dev} profile and only when the demo
 *       password is set, so that they can actually sign in.
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
class SignInConfiguration {

    @Bean
    SignInPage signInPage(Environment environment,
            @Value("${host.sign-in.template:classpath:/sign-in/sign-in.html}") Resource template,
            @Value("${horus.bpm.cockpit.host-label:}") String serviceLabel,
            @Value("${horus.bpm.cockpit.environment:}") String serviceEnvironment,
            @Value("${HORUS_DEMO_PASSWORD:}") String demoPassword) {
        boolean demo = environment.matchesProfiles(HostSecurityConfiguration.DEV_PROFILE) && !demoPassword.isBlank();
        List<SignInPage.DemoUser> users = demo ? HostSecurityConfiguration.DEMO_USERS : List.of();
        return new SignInPage(template, serviceLabel, serviceEnvironment, users);
    }
}
