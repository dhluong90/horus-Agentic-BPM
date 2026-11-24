package io.github.dhluong90.bpm.examples.ruleandprocess;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.dhluong90.bpm.process.spi.TenantResolver;
import java.util.List;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** The card API refuses callers without permission, rejects invalid bodies, and never leaks an exception message. */
class CardApplicationControllerTest {

    private static final String BODY = "{\"applicationId\":\"A-1\",\"customerId\":\"C-1\"}";

    private CardApplicationService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(CardApplicationService.class);
        mvc = MockMvcBuilders.standaloneSetup(
                new CardApplicationController(service, mock(JdbcClient.class), mock(TenantResolver.class))).build();
    }

    private static UsernamePasswordAuthenticationToken caller(String authority) {
        return UsernamePasswordAuthenticationToken.authenticated("someone", "n/a",
                List.of(new SimpleGrantedAuthority(authority)));
    }

    @Test
    void a_caller_without_the_teller_role_or_start_permission_gets_403_and_nothing_is_stored() throws Exception {
        mvc.perform(post("/api/cards/applications").principal(caller("ROLE_AUDITOR"))
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
        verifyNoInteractions(service);
    }

    @Test
    void a_blank_application_id_is_400() throws Exception {
        mvc.perform(post("/api/cards/applications").principal(caller("ROLE_TELLER"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"applicationId\":\" \",\"customerId\":\"C-1\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INPUT_INVALID"));
        verifyNoInteractions(service);
    }

    @Test
    void an_unexpected_failure_is_a_problem_response_without_the_exception_message() throws Exception {
        when(service.submit(any(), any(), any()))
                .thenThrow(new IllegalStateException("jdbc:postgresql://db.internal:5432/horus password rejected"));
        mvc.perform(post("/api/cards/applications").principal(caller("processes:start"))
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.correlationId").isNotEmpty())
                .andExpect(content().string(Matchers.not(Matchers.containsString("db.internal"))));
    }
}
