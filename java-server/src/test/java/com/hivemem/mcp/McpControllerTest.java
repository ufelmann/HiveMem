package com.hivemem.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.hivemem.auth.AuthFilter;
import com.hivemem.auth.AuthPrincipal;
import com.hivemem.auth.AuthRole;
import com.hivemem.auth.TokenService;
import com.hivemem.auth.ToolPermissionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.Order;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;
import org.springframework.test.context.support.DirtiesContextBeforeModesTestExecutionListener;
import org.springframework.test.context.support.DirtiesContextTestExecutionListener;
import org.springframework.test.context.web.ServletTestExecutionListener;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.util.Optional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = McpControllerTest.TestConfig.class)
@TestExecutionListeners(
        listeners = {
                ServletTestExecutionListener.class,
                DirtiesContextBeforeModesTestExecutionListener.class,
                DependencyInjectionTestExecutionListener.class,
                DirtiesContextTestExecutionListener.class
        },
        mergeMode = TestExecutionListeners.MergeMode.REPLACE_DEFAULTS
)
@WebAppConfiguration
class McpControllerTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private AuthFilter authFilter;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(authFilter)
                .build();
    }

    @Test
    void postMcpToolsListReturnsVisibleToolsForAuthenticatedPrincipal() throws Exception {
        mockMvc.perform(post("/mcp")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer good-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"jsonrpc":"2.0","id":1,"method":"tools/list"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.tools").isArray())
                .andExpect(jsonPath("$.result.tools[0].name").value("hivemem_status"));
    }

    @Test
    void postMcpWithUnknownMethodReturnsMethodNotFoundError() throws Exception {
        mockMvc.perform(post("/mcp")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer good-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"jsonrpc":"2.0","id":2,"method":"does/not-exist"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.error.code").value(-32601));
    }

    @Test
    void postMcpToolsCallReturnsStructuredJsonContent() throws Exception {
        mockMvc.perform(post("/mcp")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer good-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "jsonrpc":"2.0",
                                  "id":3,
                                  "method":"tools/call",
                                  "params":{"name":"hivemem_status","arguments":{}}
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.content[0].type").value("json"))
                .andExpect(jsonPath("$.result.content[0].status").value("ok"))
                .andExpect(jsonPath("$.result.content[0].principal").value("token-1"));
    }

    @Test
    void postMcpToolsCallWithoutRequiredArgumentsReturnsInvalidParams() throws Exception {
        mockMvc.perform(post("/mcp")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer good-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "jsonrpc":"2.0",
                                  "id":4,
                                  "method":"tools/call",
                                  "params":{"name":"hivemem_search","arguments":{}}
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value(-32602))
                .andExpect(jsonPath("$.error.message").value("Missing query"));
    }

    @Test
    void postMcpToolsCallWithoutPermissionReturnsForbiddenError() throws Exception {
        mockMvc.perform(post("/mcp")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer reader-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "jsonrpc":"2.0",
                                  "id":5,
                                  "method":"tools/call",
                                  "params":{"name":"hivemem_add_drawer","arguments":{}}
                                }
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value(-32003))
                .andExpect(jsonPath("$.error.message").value("Tool not permitted: hivemem_add_drawer"));
    }

    @Test
    void postMcpToolsCallWithBlankToolNameReturnsInvalidParams() throws Exception {
        mockMvc.perform(post("/mcp")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer good-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "jsonrpc":"2.0",
                                  "id":6,
                                  "method":"tools/call",
                                  "params":{"name":"   ","arguments":{}}
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value(-32602))
                .andExpect(jsonPath("$.error.message").value("Missing tool name"));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    @Import({
            AuthFilter.class,
            com.hivemem.auth.RateLimiter.class,
            ToolPermissionService.class,
            ToolRegistry.class,
            McpController.class
    })
    static class TestConfig {

        @Bean
        @org.springframework.context.annotation.Primary
        TokenService tokenService() {
            return new com.hivemem.auth.support.FixedTokenService(token -> switch (token) {
                case "good-token" -> Optional.of(new AuthPrincipal("token-1", AuthRole.WRITER));
                case "reader-token" -> Optional.of(new AuthPrincipal("token-2", AuthRole.READER));
                default -> Optional.empty();
            });
        }

        @Bean
        @Order(1)
        ToolHandler statusToolHandler() {
            return new ToolHandler() {
                @Override
                public String name() {
                    return "hivemem_status";
                }

                @Override
                public String description() {
                    return "Counts of drawers, facts, tunnels, wings list, and last activity.";
                }

                @Override
                public Object call(AuthPrincipal principal, JsonNode arguments) {
                    return java.util.Map.of(
                            "type", "json",
                            "status", "ok",
                            "principal", principal.name()
                    );
                }
            };
        }

        @Bean
        @Order(2)
        ToolHandler searchToolHandler() {
            return new ToolHandler() {
                @Override
                public String name() {
                    return "hivemem_search";
                }

                @Override
                public String description() {
                    return "5-signal ranked search.";
                }

                @Override
                public Object call(AuthPrincipal principal, JsonNode arguments) {
                    if (arguments == null || !arguments.hasNonNull("query")) {
                        throw new IllegalArgumentException("Missing query");
                    }
                    return java.util.Map.of(
                            "type", "json",
                            "query", arguments.get("query").asText(),
                            "results", java.util.List.of()
                    );
                }
            };
        }
    }
}
