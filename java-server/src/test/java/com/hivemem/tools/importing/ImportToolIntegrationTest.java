package com.hivemem.tools.importing;

import com.hivemem.auth.AuthPrincipal;
import com.hivemem.auth.AuthRole;
import com.hivemem.auth.RateLimiter;
import com.hivemem.auth.TokenService;
import com.hivemem.embedding.EmbeddingClient;
import com.hivemem.embedding.FixedEmbeddingClient;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Optional;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(SpringExtension.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(ImportToolIntegrationTest.TestConfig.class)
@Testcontainers
class ImportToolIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg17")
            .withDatabaseName("hivemem")
            .withUsername("hivemem")
            .withPassword("hivemem")
            .withCreateContainerCmdModifier(cmd -> cmd.withHostConfig(
                    (cmd.getHostConfig() == null
                            ? new com.github.dockerjava.api.model.HostConfig()
                            : cmd.getHostConfig())
                            .withSecurityOpts(java.util.List.of("apparmor=unconfined"))));

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", POSTGRES::getDriverClassName);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private DSLContext dslContext;

    @Autowired
    private RateLimiter rateLimiter;

    @MockBean(name = "httpEmbeddingClient")
    private EmbeddingClient httpEmbeddingClient;

    @BeforeEach
    void resetDatabase() {
        rateLimiter.clearAll();
        dslContext.execute("TRUNCATE TABLE agent_diary, drawer_references, references_, blueprints, identity, agents, facts, tunnels, drawers CASCADE");
    }

    @Test
    void mineSingleMarkdownFileCreatesSingleDrawer() throws Exception {
        Path file = Files.createTempFile("hivemem-import", ".md");
        Files.writeString(file, "# Test Document\n\nThis is a test file for mining.");

        try {
            mockMvc.perform(post("/mcp")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer admin-token")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                      "jsonrpc":"2.0",
                                      "id":1,
                                      "method":"tools/call",
                                      "params":{
                                        "name":"hivemem_mine_file",
                                        "arguments":{
                                          "file_path":"%s",
                                          "wing":"docs",
                                          "hall":"test"
                                        }
                                      }
                                    }
                                    """.formatted(json(file))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.result.content[0].drawers_created").value(1))
                    .andExpect(jsonPath("$.result.content[0].drawer_id").isString())
                    .andExpect(jsonPath("$.result.content[0].file").value(file.toString()));

            Number drawerCount = (Number) dslContext.fetchValue("SELECT count(*) FROM drawers");
            assertNotNull(drawerCount);
            assertEquals(1, drawerCount.intValue());

            org.jooq.Record row = dslContext.fetchOne("""
                    SELECT content, source, wing, hall
                    FROM drawers
                    WHERE source = ?
                    """, file.toString());
            assertNotNull(row);
            assertEquals("docs", row.get("wing", String.class));
            assertEquals("test", row.get("hall", String.class));
            assertEquals(file.toString(), row.get("source", String.class));
            org.junit.jupiter.api.Assertions.assertTrue(row.get("content", String.class).contains("Test Document"));
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void mineDirectoryProcessesSupportedFilesRecursively() throws Exception {
        Path directory = Files.createTempDirectory("hivemem-import-dir");
        Path nested = Files.createDirectories(directory.resolve("sub"));
        Files.writeString(directory.resolve("readme.md"), "# Readme\n\nProject documentation.");
        Files.writeString(directory.resolve("notes.txt"), "Some notes about the project.");
        Files.writeString(directory.resolve("config.yaml"), "key: value\nname: test");
        Files.writeString(nested.resolve("deep.md"), "# Deep File\n\nNested content.");
        Files.writeString(directory.resolve("skip.py"), "print('skip me')");

        try {
            mockMvc.perform(post("/mcp")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer admin-token")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                      "jsonrpc":"2.0",
                                      "id":2,
                                      "method":"tools/call",
                                      "params":{
                                        "name":"hivemem_mine_directory",
                                        "arguments":{
                                          "dir_path":"%s",
                                          "wing":"import-test"
                                        }
                                      }
                                    }
                                    """.formatted(json(directory))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.result.content[0].files_processed").value(4))
                    .andExpect(jsonPath("$.result.content[0].drawers_created").value(4))
                    .andExpect(jsonPath("$.result.content[0].errors", hasSize(0)));

            Number drawerCount = (Number) dslContext.fetchValue(
                    "SELECT count(*) FROM drawers WHERE wing = ?",
                    "import-test"
            );
            assertNotNull(drawerCount);
            assertEquals(4, drawerCount.intValue());
        } finally {
            deleteTree(directory);
        }
    }

    @Test
    void mineEmptyFileSkipsDrawerCreation() throws Exception {
        Path file = Files.createTempFile("hivemem-import-empty", ".md");
        Files.writeString(file, "");

        try {
            mockMvc.perform(post("/mcp")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer admin-token")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                      "jsonrpc":"2.0",
                                      "id":3,
                                      "method":"tools/call",
                                      "params":{
                                        "name":"hivemem_mine_file",
                                        "arguments":{
                                          "file_path":"%s"
                                        }
                                      }
                                    }
                                    """.formatted(json(file))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.result.content[0].drawers_created").value(0))
                    .andExpect(jsonPath("$.result.content[0].drawer_id").value(nullValue()));

            Number drawerCount = (Number) dslContext.fetchValue("SELECT count(*) FROM drawers");
            assertNotNull(drawerCount);
            assertEquals(0, drawerCount.intValue());
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void mineDirectoryHonorsExtensionFiltering() throws Exception {
        Path directory = Files.createTempDirectory("hivemem-import-ext");
        Files.writeString(directory.resolve("code.py"), "def hello():\n    pass\n");
        Files.writeString(directory.resolve("readme.md"), "# Readme");

        try {
            mockMvc.perform(post("/mcp")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer admin-token")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                      "jsonrpc":"2.0",
                                      "id":4,
                                      "method":"tools/call",
                                      "params":{
                                        "name":"hivemem_mine_directory",
                                        "arguments":{
                                          "dir_path":"%s",
                                          "extensions":[".py"]
                                        }
                                      }
                                    }
                                    """.formatted(json(directory))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.result.content[0].files_processed").value(1))
                    .andExpect(jsonPath("$.result.content[0].drawers_created").value(1))
                    .andExpect(jsonPath("$.result.content[0].errors", hasSize(0)));

            org.jooq.Record row = dslContext.fetchOne("SELECT source FROM drawers");
            assertNotNull(row);
            assertEquals(directory.resolve("code.py").toString(), row.get("source", String.class));
        } finally {
            deleteTree(directory);
        }
    }

    @Test
    void mineDirectoryRejectsSymlinkEscapeTargets() throws Exception {
        Path directory = Files.createTempDirectory("hivemem-import-safe");
        Path outsideRoot = Files.createTempDirectory("hivemem-import-outside");
        Path outsideFile = Files.writeString(outsideRoot.resolve("secret.md"), "# Secret\n\nDo not import.");
        Path symlink = directory.resolve("escape.md");
        Files.createSymbolicLink(symlink, outsideFile);

        try {
            mockMvc.perform(post("/mcp")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer admin-token")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                      "jsonrpc":"2.0",
                                      "id":5,
                                      "method":"tools/call",
                                      "params":{
                                        "name":"hivemem_mine_directory",
                                        "arguments":{
                                          "dir_path":"%s",
                                          "wing":"import-test"
                                        }
                                      }
                                    }
                                    """.formatted(json(directory))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.result.content[0].files_processed").value(1))
                    .andExpect(jsonPath("$.result.content[0].drawers_created").value(0))
                    .andExpect(jsonPath("$.result.content[0].errors", hasSize(1)));

            Number drawerCount = (Number) dslContext.fetchValue("SELECT count(*) FROM drawers");
            assertNotNull(drawerCount);
            assertEquals(0, drawerCount.intValue());
        } finally {
            Files.deleteIfExists(symlink);
            Files.deleteIfExists(outsideFile);
            Files.deleteIfExists(directory);
            Files.deleteIfExists(outsideRoot);
        }
    }

    @Test
    void mineDirectoryRejectsBlankExtensionFilter() throws Exception {
        Path directory = Files.createTempDirectory("hivemem-import-invalid-ext");
        Files.writeString(directory.resolve("readme.md"), "# Readme");

        try {
            mockMvc.perform(post("/mcp")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer admin-token")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                      "jsonrpc":"2.0",
                                      "id":6,
                                      "method":"tools/call",
                                      "params":{
                                        "name":"hivemem_mine_directory",
                                        "arguments":{
                                          "dir_path":"%s",
                                          "extensions":[""]
                                        }
                                      }
                                    }
                                    """.formatted(json(directory))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value(-32602))
                    .andExpect(jsonPath("$.error.message").value("Invalid extensions"));
        } finally {
            deleteTree(directory);
        }
    }

    @Test
    void readerCannotCallImportTools() throws Exception {
        Path file = Files.createTempFile("hivemem-import-denied", ".md");
        Files.writeString(file, "# Test");

        try {
            mockMvc.perform(post("/mcp")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer reader-token")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                      "jsonrpc":"2.0",
                                      "id":7,
                                      "method":"tools/call",
                                      "params":{
                                        "name":"hivemem_mine_file",
                                        "arguments":{
                                          "file_path":"%s"
                                        }
                                      }
                                    }
                                    """.formatted(json(file))))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value(-32003))
                    .andExpect(jsonPath("$.error.message").value("Tool not permitted: hivemem_mine_file"));
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void mineFileRejectsMissingFileInsideAllowedDirectory() throws Exception {
        Path missingFile = Path.of("/tmp", "missing-import-" + System.nanoTime() + ".md");

        mockMvc.perform(post("/mcp")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer admin-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "jsonrpc":"2.0",
                                  "id":8,
                                  "method":"tools/call",
                                  "params":{
                                    "name":"hivemem_mine_file",
                                    "arguments":{
                                      "file_path":"%s"
                                    }
                                  }
                                }
                                """.formatted(json(missingFile))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value(-32602))
                .andExpect(jsonPath("$.error.message").value("Path is not a file"));
    }

    private static String json(Path path) {
        return path.toString().replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var stream = Files.walk(root)) {
            stream.sorted(Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    });
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestConfig {

        @Bean
        @org.springframework.context.annotation.Primary
        TokenService tokenService() {
            return new com.hivemem.auth.support.FixedTokenService(token -> switch (token) {
                case "writer-token" -> Optional.of(new AuthPrincipal("writer-1", AuthRole.WRITER));
                case "agent-token" -> Optional.of(new AuthPrincipal("agent-1", AuthRole.AGENT));
                case "admin-token" -> Optional.of(new AuthPrincipal("admin-1", AuthRole.ADMIN));
                case "reader-token" -> Optional.of(new AuthPrincipal("reader-1", AuthRole.READER));
                default -> Optional.empty();
            });
        }

        @Bean
        @org.springframework.context.annotation.Primary
        EmbeddingClient embeddingClient() {
            return new FixedEmbeddingClient();
        }
    }
}
