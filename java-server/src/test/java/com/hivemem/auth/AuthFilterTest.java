package com.hivemem.auth;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = {
        com.hivemem.HiveMemApplication.class,
        AuthFilterTest.AuthFilterTestConfig.class
}, properties = {
        "spring.autoconfigure.exclude="
                + "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.jooq.JooqAutoConfiguration"
})
@AutoConfigureMockMvc
class AuthFilterTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void postMcpWithoutBearerTokenReturnsUnauthorized() throws Exception {
        mockMvc.perform(post("/mcp"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void postMcpWithBearerTokenAttachesPrincipal() throws Exception {
        mockMvc.perform(post("/mcp").header(HttpHeaders.AUTHORIZATION, "Bearer good-token"))
                .andExpect(status().isOk())
                .andExpect(content().string("token-1:writer"));
    }

    @Test
    void postMcpWithLowercaseBearerTokenAttachesPrincipal() throws Exception {
        mockMvc.perform(post("/mcp").header(HttpHeaders.AUTHORIZATION, "bearer good-token"))
                .andExpect(status().isOk())
                .andExpect(content().string("token-1:writer"));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class AuthFilterTestConfig {

        @Bean
        TokenService tokenService() {
            return token -> "good-token".equals(token)
                    ? Optional.of(new AuthPrincipal("token-1", AuthRole.WRITER))
                    : Optional.empty();
        }

        @Bean
        TestMcpController testMcpController() {
            return new TestMcpController();
        }
    }

    @RestController
    static class TestMcpController {

        @PostMapping("/mcp")
        String handle(HttpServletRequest request) {
            AuthPrincipal principal = (AuthPrincipal) request.getAttribute(AuthFilter.PRINCIPAL_ATTRIBUTE);
            return principal.name() + ":" + principal.role().wireValue();
        }
    }
}
