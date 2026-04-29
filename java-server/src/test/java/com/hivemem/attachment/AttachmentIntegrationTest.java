package com.hivemem.attachment;

import com.hivemem.auth.AuthFilter;
import com.hivemem.auth.AuthPrincipal;
import com.hivemem.auth.AuthRole;
import com.hivemem.embedding.EmbeddingClient;
import com.hivemem.embedding.FixedEmbeddingClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Import(AttachmentIntegrationTest.TestConfig.class)
class AttachmentIntegrationTest {

    @TestConfiguration(proxyBeanMethods = false)
    static class TestConfig {
        @Bean @Primary
        EmbeddingClient testEmbeddingClient() { return new FixedEmbeddingClient(); }
    }

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg17")
            .withDatabaseName("hivemem").withUsername("hivemem").withPassword("hivemem")
            .withCreateContainerCmdModifier(cmd -> cmd.withHostConfig(
                    (cmd.getHostConfig() == null ? new com.github.dockerjava.api.model.HostConfig()
                            : cmd.getHostConfig()).withSecurityOpts(java.util.List.of("apparmor=unconfined"))));

    @Container
    static final GenericContainer<?> SEAWEEDFS = new GenericContainer<>(
            DockerImageName.parse("chrislusf/seaweedfs:3.68"))
            .withCommand("server -s3 -dir=/data")
            .withExposedPorts(8333)
            .withCreateContainerCmdModifier(cmd -> cmd.withHostConfig(
                    (cmd.getHostConfig() == null ? new com.github.dockerjava.api.model.HostConfig()
                            : cmd.getHostConfig()).withSecurityOpts(java.util.List.of("apparmor=unconfined"))))
            .waitingFor(Wait.forHttp("/").forPort(8333)
                    .forStatusCodeMatching(code -> code == 400 || (code >= 200 && code < 500))
                    .withStartupTimeout(Duration.ofSeconds(120)));

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", POSTGRES::getDriverClassName);
        registry.add("hivemem.attachment.enabled", () -> "true");
        registry.add("hivemem.attachment.s3-endpoint",
                () -> "http://" + SEAWEEDFS.getHost() + ":" + SEAWEEDFS.getMappedPort(8333));
        registry.add("hivemem.attachment.s3-access-key", () -> "");
        registry.add("hivemem.attachment.s3-secret-key", () -> "");
    }

    @Autowired MockMvc mockMvc;

    private static final AuthPrincipal WRITER = new AuthPrincipal("test-writer", AuthRole.WRITER);
    private static final AuthPrincipal READER = new AuthPrincipal("test-reader", AuthRole.READER);
    private static final AuthPrincipal ADMIN  = new AuthPrincipal("test-admin",  AuthRole.ADMIN);

    private UUID testCellId;

    @BeforeEach
    void setUp(@Autowired org.jooq.DSLContext dsl) {
        testCellId = (UUID) dsl.fetchOne("""
                INSERT INTO cells (content, embedding, realm, signal, topic, status, created_by, valid_from)
                VALUES ('test', array_fill(0::real, ARRAY[1024])::vector, 'test', 'facts', 'test', 'committed', 'test', now())
                RETURNING id""").get("id");
    }

    @Test
    void uploadPdfAndDownloadRoundtrip() throws Exception {
        byte[] pdf = buildPdf("Integration test PDF content");

        String body = mockMvc.perform(multipart("/api/attachments")
                        .file(new MockMultipartFile("file", "test.pdf", "application/pdf", pdf))
                        .param("cell_id", testCellId.toString())
                        .requestAttr(AuthFilter.PRINCIPAL_ATTRIBUTE, WRITER))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.mime_type").value("application/pdf"))
                .andExpect(jsonPath("$.original_filename").value("test.pdf"))
                .andExpect(jsonPath("$.s3_key_thumbnail").isNotEmpty())
                .andReturn().getResponse().getContentAsString();

        String id = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(body).get("id").asText();

        mockMvc.perform(get("/api/attachments/" + id + "/content")
                        .requestAttr(AuthFilter.PRINCIPAL_ATTRIBUTE, READER))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", org.hamcrest.Matchers.containsString("application/pdf")));
    }

    @Test
    void deduplicatesOnReupload() throws Exception {
        byte[] pdf = buildPdf("Dedup test");

        mockMvc.perform(multipart("/api/attachments")
                        .file(new MockMultipartFile("file", "dedup.pdf", "application/pdf", pdf))
                        .param("cell_id", testCellId.toString())
                        .requestAttr(AuthFilter.PRINCIPAL_ATTRIBUTE, WRITER))
                .andExpect(status().isCreated());

        mockMvc.perform(multipart("/api/attachments")
                        .file(new MockMultipartFile("file", "dedup.pdf", "application/pdf", pdf))
                        .param("cell_id", testCellId.toString())
                        .requestAttr(AuthFilter.PRINCIPAL_ATTRIBUTE, WRITER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deduplicated").value(true));
    }

    @Test
    void uploadWithoutCellIdReturns400() throws Exception {
        mockMvc.perform(multipart("/api/attachments")
                        .file(new MockMultipartFile("file", "x.pdf", "application/pdf", new byte[]{1}))
                        .requestAttr(AuthFilter.PRINCIPAL_ATTRIBUTE, WRITER))
                .andExpect(status().isBadRequest());
    }

    @Test
    void readerCannotUpload() throws Exception {
        mockMvc.perform(multipart("/api/attachments")
                        .file(new MockMultipartFile("file", "x.pdf", "application/pdf", new byte[]{1}))
                        .param("cell_id", testCellId.toString())
                        .requestAttr(AuthFilter.PRINCIPAL_ATTRIBUTE, READER))
                .andExpect(status().isForbidden());
    }

    @Test
    void nonAdminCannotDelete() throws Exception {
        byte[] pdf = buildPdf("To be deleted");
        String body = mockMvc.perform(multipart("/api/attachments")
                        .file(new MockMultipartFile("file", "del.pdf", "application/pdf", pdf))
                        .param("cell_id", testCellId.toString())
                        .requestAttr(AuthFilter.PRINCIPAL_ATTRIBUTE, WRITER))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        String id = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(body).get("id").asText();

        mockMvc.perform(delete("/api/attachments/" + id)
                        .requestAttr(AuthFilter.PRINCIPAL_ATTRIBUTE, WRITER))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminCanSoftDelete() throws Exception {
        byte[] pdf = buildPdf("To be soft deleted");
        String body = mockMvc.perform(multipart("/api/attachments")
                        .file(new MockMultipartFile("file", "soft.pdf", "application/pdf", pdf))
                        .param("cell_id", testCellId.toString())
                        .requestAttr(AuthFilter.PRINCIPAL_ATTRIBUTE, ADMIN))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        String id = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(body).get("id").asText();

        mockMvc.perform(delete("/api/attachments/" + id)
                        .requestAttr(AuthFilter.PRINCIPAL_ATTRIBUTE, ADMIN))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/attachments/" + id)
                        .requestAttr(AuthFilter.PRINCIPAL_ATTRIBUTE, READER))
                .andExpect(status().isNotFound());
    }

    private byte[] buildPdf(String text) throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(50, 700);
                cs.showText(text);
                cs.endText();
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }
}
