package com.hivemem.sync;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class PeerClient {

    private static final Logger log = LoggerFactory.getLogger(PeerClient.class);

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public PeerClient(RestClient.Builder builder, ObjectMapper objectMapper) {
        this.restClient = builder.build();
        this.objectMapper = objectMapper;
    }

    public List<OpDto> fetchOps(String peerUrl, long since, String outboundToken) {
        try {
            JsonNode body = restClient.get()
                    .uri(peerUrl + "/sync/ops?since=" + since)
                    .header("Authorization", "Bearer " + outboundToken)
                    .retrieve()
                    .body(JsonNode.class);
            if (body == null || !body.has("ops")) return List.of();
            List<OpDto> result = new ArrayList<>();
            for (JsonNode opNode : body.get("ops")) {
                result.add(new OpDto(
                        opNode.get("seq").asLong(),
                        UUID.fromString(opNode.get("opId").asText()),
                        opNode.get("opType").asText(),
                        opNode.get("payload"),
                        OffsetDateTime.parse(opNode.get("createdAt").asText())));
            }
            return result;
        } catch (RestClientException e) {
            log.warn("fetchOps failed peer={} since={}", peerUrl, since, e);
            return List.of();
        }
    }

    public void pushOps(String peerUrl, UUID sourcePeer, List<OpDto> ops, String outboundToken) {
        try {
            Map<String, Object> body = Map.of("sourcePeer", sourcePeer.toString(), "ops", ops);
            restClient.post()
                    .uri(peerUrl + "/sync/ops")
                    .header("Authorization", "Bearer " + outboundToken)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException e) {
            log.warn("pushOps failed peer={}", peerUrl, e);
        }
    }
}
