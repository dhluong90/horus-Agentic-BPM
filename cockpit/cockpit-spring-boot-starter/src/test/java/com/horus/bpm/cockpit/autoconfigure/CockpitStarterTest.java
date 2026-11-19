package com.horus.bpm.cockpit.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.security.Principal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * The starter in a host without Spring Security: MockMvc runs no security filter, so the request's
 * principal stands for the host session.
 */
@SpringBootTest(
        classes = CockpitTestHost.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
            "horus.bpm.host-id=loans",
            "horus.bpm.cockpit.host-label=Loan origination",
            "horus.bpm.cockpit.environment=UAT",
            "horus.bpm.cockpit.default-locale=vi"
        })
class CockpitStarterTest {

    private static final Principal LAN = () -> "lan";

    @Autowired
    private WebApplicationContext context;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @Test
    void CKP_001_starter_generates_a_one_host_registry_on_the_same_origin_with_host_session() throws Exception {
        mvc.perform(get("/bpm/cockpit/cockpit-hosts.json").principal(LAN))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", containsString("application/json")))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.schemaVersion").value(1))
                .andExpect(jsonPath("$.auth.mode").value("host-session"))
                .andExpect(jsonPath("$.auth.length()").value(1))
                .andExpect(jsonPath("$.hosts.length()").value(1))
                .andExpect(jsonPath("$.hosts[0].id").value("loans"))
                .andExpect(jsonPath("$.hosts[0].label").value("Loan origination"))
                .andExpect(jsonPath("$.hosts[0].baseUrl").value(""))
                .andExpect(jsonPath("$.hosts[0].environment").value("UAT"))
                .andExpect(jsonPath("$.ui.defaultLocale").value("vi"));
    }

    @Test
    void CKP_001_cockpit_path_redirects_to_the_folder_and_the_folder_serves_index_html() throws Exception {
        mvc.perform(get("/bpm/cockpit").principal(LAN))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/bpm/cockpit/"));
        mvc.perform(get("/bpm/cockpit/").principal(LAN))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("/bpm/cockpit/index.html"));
        mvc.perform(get("/bpm/cockpit/index.html").principal(LAN))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("cockpit-test-bundle")));
    }

    @Test
    void TR_SEC_09_cockpit_page_and_assets_carry_a_strict_content_security_policy_and_nosniff() throws Exception {
        for (String path : new String[] {"/bpm/cockpit/", "/bpm/cockpit/index.html", "/bpm/cockpit/assets/app.js",
            "/bpm/cockpit/cockpit-hosts.json"}) {
            mvc.perform(get(path).principal(LAN))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Content-Security-Policy", containsString("script-src 'self'")))
                    .andExpect(header().string("Content-Security-Policy", not(containsString("unsafe-inline"))))
                    .andExpect(header().string("Content-Security-Policy", containsString("connect-src 'self'")))
                    .andExpect(header().string("Content-Security-Policy", containsString("frame-ancestors 'self'")))
                    .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                    .andExpect(header().string("Referrer-Policy", "no-referrer"))
                    .andExpect(header().string("X-Frame-Options", "SAMEORIGIN"));
        }
    }

    @Test
    void CKP_001_a_reloaded_cockpit_route_gets_index_html_and_a_missing_file_stays_404() throws Exception {
        mvc.perform(get("/bpm/cockpit/rules/card-fee").principal(LAN))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().string(containsString("cockpit-test-bundle")));
        mvc.perform(get("/bpm/cockpit/assets/missing.js").principal(LAN)).andExpect(status().isNotFound());
        mvc.perform(get("/bpm/cockpit/missing.png").principal(LAN)).andExpect(status().isNotFound());
        mvc.perform(get("/bpm/cockpit/assets/nested/route").principal(LAN)).andExpect(status().isNotFound());
    }

    @Test
    void TR_SEC_09_page_is_never_cached_and_hashed_assets_are_cached_privately() throws Exception {
        mvc.perform(get("/bpm/cockpit/index.html").principal(LAN))
                .andExpect(header().string("Cache-Control", "no-store"));
        mvc.perform(get("/bpm/cockpit/assets/app.js").principal(LAN))
                .andExpect(header().string("Cache-Control", "private, max-age=31536000, immutable"));
    }

    @Test
    void TR_SEC_09_without_a_signed_in_user_no_cockpit_file_or_registry_is_served() throws Exception {
        for (String path : new String[] {"/bpm/cockpit", "/bpm/cockpit/", "/bpm/cockpit/index.html",
            "/bpm/cockpit/assets/app.js", "/bpm/cockpit/cockpit-hosts.json"}) {
            mvc.perform(get(path))
                    .andExpect(status().isUnauthorized())
                    .andExpect(header().string("Content-Type", containsString("application/problem+json")))
                    .andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                    .andExpect(jsonPath("$.correlationId").isNotEmpty())
                    .andExpect(content().string(not(containsString("cockpit-test-bundle"))))
                    .andExpect(content().string(not(containsString("loans"))));
        }
    }

    @Test
    void TR_SEC_09_host_endpoints_outside_the_cockpit_path_are_left_to_the_host() throws Exception {
        String csp = mvc.perform(get("/api/host/ping"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getHeader("Content-Security-Policy");
        assertThat(csp).isNull();
    }
}
