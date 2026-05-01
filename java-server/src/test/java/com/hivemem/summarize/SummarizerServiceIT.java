package com.hivemem.summarize;

import com.hivemem.auth.AuthPrincipal;
import com.hivemem.auth.AuthRole;
import com.hivemem.embedding.EmbeddingClient;
import com.hivemem.embedding.FixedEmbeddingClient;
import com.hivemem.sync.InstanceConfig;
import com.hivemem.sync.OpLogWriter;
import com.hivemem.sync.PeerClient;
import com.hivemem.sync.PushDispatcher;
import com.hivemem.sync.SyncOpsRepository;
import com.hivemem.sync.SyncPeerRepository;
import com.hivemem.write.WriteToolRepository;
import com.hivemem.write.WriteToolService;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@Testcontainers
class SummarizerServiceIT {

    @Container
    static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("pgvector/pgvector:pg17")
            .withDatabaseName("hivemem").withUsername("hivemem").withPassword("hivemem")
            .withCreateContainerCmdModifier(cmd -> cmd.withHostConfig(
                    (cmd.getHostConfig() == null ? new com.github.dockerjava.api.model.HostConfig()
                            : cmd.getHostConfig()).withSecurityOpts(java.util.List.of("apparmor=unconfined"))));

    private DSLContext dsl;

    @BeforeEach
    void setUp() {
        org.flywaydb.core.Flyway.configure()
                .dataSource(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword())
                .locations("classpath:db/migration").load().migrate();
        DataSource ds = new DriverManagerDataSource(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword());
        dsl = DSL.using(ds, SQLDialect.POSTGRES);
        dsl.execute("DELETE FROM cells");
        dsl.execute("DELETE FROM summarize_usage");
        dsl.execute("DELETE FROM ops_log");
        // Seed instance_identity so OpLogWriter works
        dsl.execute("INSERT INTO instance_identity (id, instance_id) VALUES (1, gen_random_uuid()) ON CONFLICT DO NOTHING");
    }

    @Test
    void summarizesLongCell_endToEnd() throws Exception {
        // --- Seed: a long cell with needs_summary tag, no embedding, no summary ---
        UUID id = UUID.randomUUID();
        try (Connection c = DriverManager.getConnection(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword());
             Statement st = c.createStatement()) {
            String content = "a".repeat(2000);
            st.execute(
                    "INSERT INTO cells (id, content, realm, signal, status, tags, embedding, created_at, valid_from) "
                            + "VALUES ('" + id + "', '" + content + "', 'test', 'facts', 'committed', "
                            + "ARRAY['needs_summary']::text[], NULL, now(), now())");
        }

        // --- Build mock Anthropic response ---
        String anthropicResp = """
                {
                  "id":"msg_x","type":"message","model":"haiku",
                  "usage":{"input_tokens":2000,"output_tokens":50},
                  "content":[{"type":"text","text":"{\\"summary\\":\\"Mocked summary about a long cell.\\",\\"key_points\\":[\\"a\\"],\\"insight\\":\\"i\\",\\"tags\\":[\\"x\\"]}"}]
                }
                """;

        // Build a RestClient.Builder, bind MockRestServiceServer FIRST, then construct
        // AnthropicSummarizer with configureRequestFactory=false so the mock binding survives.
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://api.anthropic.com/v1/messages"))
                .andRespond(withSuccess(anthropicResp, MediaType.APPLICATION_JSON));

        // --- Build the service stack ---
        SummarizerRepository repo = new SummarizerRepository(dsl);
        WriteToolRepository writeRepo = new WriteToolRepository(dsl);

        EmbeddingClient embedding = new FixedEmbeddingClient();

        // InstanceConfig backed by the real testcontainer DB
        InstanceConfig instanceConfig = new InstanceConfig(dsl);
        // initialize() is package-private (@PostConstruct); invoke via reflection
        var initMethod = InstanceConfig.class.getDeclaredMethod("initialize");
        initMethod.setAccessible(true);
        initMethod.invoke(instanceConfig);

        OpLogWriter opLogWriter = new OpLogWriter(dsl, instanceConfig, new ObjectMapper());

        // PushDispatcher: mock its dependencies so dispatch() is a no-op
        SyncPeerRepository peerRepo = mock(SyncPeerRepository.class);
        SyncOpsRepository syncOpsRepo = mock(SyncOpsRepository.class);
        PeerClient peerClient = mock(PeerClient.class);
        PushDispatcher pushDispatcher = new PushDispatcher(peerRepo, syncOpsRepo, peerClient, instanceConfig);

        org.springframework.context.ApplicationEventPublisher noopPublisher = e -> {};

        WriteToolService writeService = new WriteToolService(
                writeRepo, embedding, opLogWriter, pushDispatcher, noopPublisher);

        // AnthropicSummarizer with configureRequestFactory=false — uses mock-bound RestClient
        AnthropicSummarizer anthropic = new AnthropicSummarizer(
                builder, "test-key", "claude-haiku-4-5-20251001", 30, 8000, false);

        SummarizeBudgetTracker budget = new SummarizeBudgetTracker(dsl, 10.00);

        // --- Execute the same sequence as SummarizerService.summarizeOne ---
        var snap = repo.findCellSnapshot(id).orElseThrow(
                () -> new AssertionError("Cell not found — check status/valid_until conditions"));
        var result = anthropic.summarize(snap.content());
        budget.recordCall(result.inputTokens(), result.outputTokens());
        var reviseResult = writeService.reviseCell(
                new AuthPrincipal("system-summarizer", AuthRole.ADMIN),
                id, snap.content(), result.summary());
        // Remove needs_summary from old cell and from the new revision (which inherits tags)
        repo.removeNeedsSummaryTag(id);
        Object newIdObj = reviseResult.get("new_id");
        if (newIdObj != null) {
            repo.removeNeedsSummaryTag(UUID.fromString(newIdObj.toString()));
        }

        server.verify();

        // --- Assertions ---
        // reviseCell creates a NEW row; look for the latest committed cell with a summary
        try (Connection c = DriverManager.getConnection(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword());
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT summary, embedding IS NOT NULL AS has_embedding, "
                             + "'needs_summary' = ANY(tags) AS has_needs_summary_tag "
                             + "FROM cells WHERE summary IS NOT NULL AND valid_until IS NULL "
                             + "ORDER BY created_at DESC LIMIT 1")) {
            assertTrue(rs.next(), "Expected a revised cell row with a summary");
            assertEquals("Mocked summary about a long cell.", rs.getString("summary"));
            assertTrue(rs.getBoolean("has_embedding"), "Embedding should be present after revise");
            assertFalse(rs.getBoolean("has_needs_summary_tag"),
                    "needs_summary tag should be absent on the new revision");
        }

        // Budget row was recorded
        try (Connection c = DriverManager.getConnection(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword());
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT total_calls, total_input_tokens FROM summarize_usage")) {
            assertTrue(rs.next(), "Expected a summarize_usage row");
            assertEquals(1, rs.getInt("total_calls"));
            assertEquals(2000, rs.getInt("total_input_tokens"));
        }
    }
}
