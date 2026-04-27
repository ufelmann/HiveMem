package com.hivemem.sync;

import com.hivemem.auth.AuthFilter;
import com.hivemem.auth.AuthPrincipal;
import com.hivemem.auth.AuthRole;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/admin/peers")
public class PeerAdminController {

    private final SyncPeerRepository syncPeerRepository;

    public PeerAdminController(SyncPeerRepository syncPeerRepository) {
        this.syncPeerRepository = syncPeerRepository;
    }

    @GetMapping
    public ResponseEntity<?> listPeers(HttpServletRequest request) {
        if (!isAdmin(request)) return forbidden();
        List<Map<String, Object>> peers = syncPeerRepository.listPeers();
        return ResponseEntity.ok(Map.of("peers", peers));
    }

    @PostMapping
    public ResponseEntity<?> addPeer(@RequestBody AddPeerRequest body, HttpServletRequest request) {
        if (!isAdmin(request)) return forbidden();
        Map<String, Object> result = syncPeerRepository.addPeer(body.peerUuid(), body.peerUrl(), body.outboundToken());
        return ResponseEntity.ok(result);
    }

    @DeleteMapping("/{peerUuid}")
    public ResponseEntity<?> removePeer(@PathVariable UUID peerUuid, HttpServletRequest request) {
        if (!isAdmin(request)) return forbidden();
        boolean removed = syncPeerRepository.removePeer(peerUuid);
        return ResponseEntity.ok(Map.of("peer_uuid", peerUuid.toString(), "removed", removed));
    }

    private static boolean isAdmin(HttpServletRequest request) {
        AuthPrincipal principal = (AuthPrincipal) request.getAttribute(AuthFilter.PRINCIPAL_ATTRIBUTE);
        return principal != null && principal.role() == AuthRole.ADMIN;
    }

    private static ResponseEntity<?> forbidden() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "admin role required"));
    }

    public record AddPeerRequest(UUID peerUuid, String peerUrl, String outboundToken) {}
}
