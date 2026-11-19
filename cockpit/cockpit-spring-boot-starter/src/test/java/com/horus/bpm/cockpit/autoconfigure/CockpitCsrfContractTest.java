package com.horus.bpm.cockpit.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
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
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;

/**
 * The host-session CSRF contract (DESIGN section 13): the host keeps its token in the readable cookie
 * XSRF-TOKEN (configured here the way that works on Spring Security 6.5 and 7), the starter makes sure the
 * cookie exists once the cockpit page
 * has loaded, and a mutating call with that value in X-XSRF-TOKEN is accepted.
 */
@SpringBootTest(classes = CockpitCsrfContractTest.SpaCsrfHost.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class CockpitCsrfContractTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class SpaCsrfHost {
        @Bean
        SecurityFilterChain hostSecurity(HttpSecurity http) throws Exception {
            http.authorizeHttpRequests(requests -> requests.anyRequest().authenticated())
                    .formLogin(Customizer.withDefaults())
                    .csrf(csrf -> csrf
                            .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                            .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()));
            return http.build();
        }

        @RestController
        static class HostCommand {
            @PostMapping("/api/host/command")
            String command() {
                return "done";
            }
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
    void CKP_001_loading_the_cockpit_page_sets_a_readable_xsrf_cookie_and_a_mutating_call_with_its_header_is_accepted()
            throws Exception {
        Cookie xsrf = mvc.perform(get("/bpm/cockpit/index.html").with(user("lan")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getCookie("XSRF-TOKEN");

        assertThat(xsrf).as("XSRF-TOKEN cookie after the cockpit page").isNotNull();
        assertThat(xsrf.isHttpOnly()).as("the cockpit must be able to read it").isFalse();

        mvc.perform(post("/api/host/command").with(user("lan")).cookie(xsrf).header("X-XSRF-TOKEN", xsrf.getValue()))
                .andExpect(status().isOk());
    }

    @Test
    void CKP_001_mutating_call_without_the_xsrf_header_is_refused_by_the_host() throws Exception {
        Cookie xsrf = mvc.perform(get("/bpm/cockpit/index.html").with(user("lan")))
                .andReturn().getResponse().getCookie("XSRF-TOKEN");

        mvc.perform(post("/api/host/command").with(user("lan")).cookie(xsrf))
                .andExpect(status().isForbidden());
    }
}
