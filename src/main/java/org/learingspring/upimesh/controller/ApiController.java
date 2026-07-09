package org.learingspring.upimesh.controller;

import lombok.RequiredArgsConstructor;
import org.learingspring.upimesh.crypto.ServerKeyHolder;
import org.learingspring.upimesh.model.*;
import org.learingspring.upimesh.service.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.*;

/**
 * Public REST surface. Three groups:
 *   /api/server-key, /api/demo/send  -> demo helpers (simulate a sender phone)
 *   /api/mesh/*                       -> mesh simulator controls
 *   /api/bridge/ingest                -> THE production endpoint
 *   /api/accounts, /api/transactions  -> read-only data for the dashboard
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ApiController {

    private final ServerKeyHolder serverKey;
    private final DemoService demo;
    private final MeshSimulatorService mesh;
    private final BridgeIngestionService bridge;
    private final AccountRepository accountRepo;
    private final TransactionRepository txRepo;
    private final IdempotencyService idempotency;

    // ------------------------------------------------------------------ key

    @GetMapping("/server-key")
    public Map<String, String> getServerPublicKey() {
        return Map.of(
                "publicKey", serverKey.getPublicKeyBase64(),
                "algorithm", "RSA-2048 / OAEP-SHA256",
                "hybridScheme", "RSA-OAEP encrypts an AES-256-GCM session key"
        );
    }

    // ---------------------------------------------------------------- demo

    @PostMapping("/demo/send")
    public ResponseEntity<?> demoSend(@RequestBody DemoSendRequest req) throws Exception {
        MeshPacket packet = demo.createPacket(
                req.senderVpa, req.receiverVpa, req.amount, req.pin,
                req.ttl == null ? 5 : req.ttl);

        String startDevice = req.startDevice == null ? "phone-alice" : req.startDevice;
        mesh.inject(startDevice, packet);

        return ResponseEntity.ok(Map.of(
                "packetId", packet.getPacketId(),
                "ciphertextPreview", packet.getCiphertext().substring(0, 64) + "...",
                "ttl", packet.getTtl(),
                "injectedAt", startDevice
        ));
    }

    // -------------------------------------------------------------- mesh sim

    @GetMapping("/mesh/state")
    public Map<String, Object> meshState() {
        List<Map<String, Object>> deviceData = new ArrayList<>();
        for (VirtualDevice d : mesh.getDevices()) {
            deviceData.add(Map.of(
                    "deviceId", d.getDeviceId(),
                    "hasInternet", d.isHasInternet(),
                    "packetCount", d.packetCount(),
                    "packetIds", d.getHeldPackets().stream()
                            .map(p -> p.getPacketId().substring(0, 8))
                            .toList()
            ));
        }
        return Map.of(
                "devices", deviceData,
                "idempotencyCacheSize", idempotency.size()
        );
    }

    @PostMapping("/mesh/gossip")
    public Map<String, Object> meshGossip() {
        MeshSimulatorService.GossipResult r = mesh.gossipOnce();
        return Map.of(
                "transfers", r.getTransfers(),
                "deviceCounts", r.getDeviceCounts()
        );
    }

    /**
     * All bridge nodes simultaneously "walk outside" and upload everything
     * they hold. Uploaded in PARALLEL to actually exercise concurrent
     * idempotency - this is the moment the duplicate-storm case gets tested.
     */
    @PostMapping("/mesh/flush")
    public Map<String, Object> meshFlush() {
        List<MeshSimulatorService.BridgeUpload> uploads = mesh.collectBridgeUploads();

        List<Map<String, Object>> results = new ArrayList<>();
        uploads.parallelStream().forEach(up -> {
            BridgeIngestionService.IngestResult r =
                    bridge.ingest(up.getPacket(), up.getBridgeNodeId(), 5 - up.getPacket().getTtl());
            synchronized (results) {
                results.add(Map.of(
                        "bridgeNode", up.getBridgeNodeId(),
                        "packetId", up.getPacket().getPacketId().substring(0, 8),
                        "outcome", r.getOutcome(),
                        "reason", r.getReason() == null ? "" : r.getReason(),
                        "transactionId", r.getTransactionId() == null ? -1 : r.getTransactionId()
                ));
            }
        });

        return Map.of(
                "uploadsAttempted", uploads.size(),
                "results", results
        );
    }

    @PostMapping("/mesh/reset")
    public Map<String, Object> meshReset() {
        mesh.resetMesh();
        idempotency.clear();
        return Map.of("status", "mesh and idempotency cache cleared");
    }

    // -------------------------------------------------------------- bridge

    /**
     * THE PRODUCTION ENDPOINT. In a real deployment, the Android app's
     * bridge logic POSTs here whenever the device has internet and is
     * holding mesh packets.
     */
    @PostMapping("/bridge/ingest")
    public ResponseEntity<?> ingest(
            @RequestBody MeshPacket packet,
            @RequestHeader(value = "X-Bridge-Node-Id", defaultValue = "unknown") String bridgeNodeId,
            @RequestHeader(value = "X-Hop-Count", defaultValue = "0") int hopCount) {

        BridgeIngestionService.IngestResult r = bridge.ingest(packet, bridgeNodeId, hopCount);
        return ResponseEntity.ok(r);
    }

    // ------------------------------------------------------------- accounts

    @GetMapping("/accounts")
    public List<Account> listAccounts() {
        return accountRepo.findAll();
    }

    @GetMapping("/transactions")
    public List<Transaction> listTransactions() {
        return txRepo.findTop20ByOrderByIdDesc();
    }

    public static class DemoSendRequest {
        public String senderVpa;
        public String receiverVpa;
        public BigDecimal amount;
        public String pin;
        public Integer ttl;
        public String startDevice;
    }
}