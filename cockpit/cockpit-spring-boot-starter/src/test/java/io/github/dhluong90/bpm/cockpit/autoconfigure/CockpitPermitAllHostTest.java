package io.github.dhluong90.bpm.cockpit.autoconfigure;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * A host whose security chain does not cover the cockpit path: Spring Security gives such requests an
 * anonymous authentication. The starter's own guard must still serve nothing.
 */
@SpringBootTest(classes = CockpitPermitAllHostTest.PermitAllHost.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class CockpitPermitAllHostTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class PermitAllHost {
        @Bean
        SecurityFilterChain hostSecurity(HttpSecurity http) throws Exception {
            http.authorizeHttpRequests(requests -> requests
                            .requestMatchers("/bpm/**").permitAll()
                            .anyRequest().authenticated())
                    .formLogin(Customizer.withDefaults());
            return http.build();
        }
    }

    @Autowired
    private WebApplicationContext context;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void TR_SEC_09_host_that_permits_the_cockpit_path_to_anonymous_users_still_gets_401_and_no_file() throws Exception {
        for (String path : new String[] {"/bpm/cockpit/", "/bpm/cockpit/index.html", "/bpm/cockpit/assets/app.js",
            "/bpm/cockpit/cockpit-hosts.json", "/bpm/cockpit/rules/card-fee"}) {
            mvc.perform(get(path))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                    .andExpect(content().string(not(containsString("cockpit-test-bundle"))))
                    .andExpect(content().string(not(containsString("testBundle"))));
        }
    }
}
