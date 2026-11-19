package com.horus.bpm.cockpit.autoconfigure;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/** The starter in a host with Spring Security's default form sign-in, the usual embedded setup. */
@SpringBootTest(classes = CockpitTestHost.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class CockpitBehindHostSecurityTest {

    @Autowired
    private WebApplicationContext context;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        // As in the servlet container: the starter's frame-options filter runs before Spring Security.
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(context.getBean(CockpitFrameOptionsFilter.class))
                .apply(springSecurity())
                .build();
    }

    @Test
    void TR_SEC_09_without_a_host_session_the_host_sign_in_is_required_and_nothing_is_served() throws Exception {
        mvc.perform(get("/bpm/cockpit/index.html").header("Accept", "text/html"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"))
                .andExpect(content().string(""));
        mvc.perform(get("/bpm/cockpit/cockpit-hosts.json").header("Accept", "text/html"))
                .andExpect(status().is3xxRedirection());
    }

    @Test
    @WithMockUser(username = "lan")
    void TR_SEC_09_cockpit_framing_rule_is_same_origin_even_though_the_host_denies_framing_elsewhere() throws Exception {
        mvc.perform(get("/bpm/cockpit/index.html"))
                .andExpect(header().string("X-Frame-Options", "SAMEORIGIN"))
                .andExpect(header().string("Content-Security-Policy", containsString("frame-ancestors 'self'")));
        mvc.perform(get("/api/host/ping")).andExpect(header().string("X-Frame-Options", "DENY"));
    }

    @Test
    @WithMockUser(username = "lan")
    void CKP_001_signed_in_host_user_gets_the_cockpit_and_its_registry() throws Exception {
        mvc.perform(get("/bpm/cockpit/index.html"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Security-Policy", containsString("script-src 'self'")))
                .andExpect(content().string(containsString("cockpit-test-bundle")));
        mvc.perform(get("/bpm/cockpit/cockpit-hosts.json"))
                .andExpect(header().string("X-Frame-Options", "SAMEORIGIN"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("\"id\":\"self\"")));
    }
}
