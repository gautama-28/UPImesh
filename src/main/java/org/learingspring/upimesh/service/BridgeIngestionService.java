package org.learingspring.upimesh.service;

import tools.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.learingspring.upimesh.crypto.HybridCryptoService;
import org.learingspring.upimesh.model.MeshPacket;
import org.learingspring.upimesh.model.PaymentInstruction;
import org.learingspring.upimesh.model.Transaction;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * THE pipeline. A real bridge node's upload lands here.
 * Steps, in order: hash ciphertext -> claim in idempotency cache ->
 * decrypt -> freshness check -> settle.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BridgeIngestionService {

    private final HybridCryptoService cryptoService;
    private final IdempotencyService idempotencyService;
    private final SettlementService settlementService;
    private final ObjectMapper objectMapper;

    @Value("${upi.mesh.packet-max-age-seconds:86400}")
    private long maxAgeSeconds;

    public IngestResult ingest(MeshPacket packet, String bridgeNodeId, int hopCount) {
        String packetHash;

        // Step 1: hash the ciphertext - this can't fail unless the packet is malformed
        try {
            packetHash = cryptoService.hashCiphertext(packet.getCiphertext());
        } catch (Exception e) {
            log.warn("Malformed packet, could not hash: {}", e.getMessage());
            return new IngestResult("INVALID", null, "Malformed packet", null);
        }

        // Step 2: claim the hash - atomic, this is what stops the duplicate-storm
        if (!idempotencyService.claim(packetHash)) {
            return new IngestResult("DUPLICATE_DROPPED", packetHash, null, null);
        }

        // Step 3: decrypt - throws if tampered (GCM auth tag fails) or malformed
        PaymentInstruction instruction;
        try {
            String plaintext = cryptoService.decrypt(packet.getCiphertext());
            instruction = objectMapper.readValue(plaintext, PaymentInstruction.class);
        } catch (Exception e) {
            log.warn("Decryption/parsing failed for hash {}: {}", packetHash, e.getMessage());
            return new IngestResult("INVALID", packetHash, "Decryption failed - tampered or corrupt", null);
        }

        // Step 4: freshness check - reject anything older than the replay window
        long ageSeconds = (Instant.now().toEpochMilli() - instruction.getSignedAt()) / 1000;
        if (ageSeconds > maxAgeSeconds) {
            return new IngestResult("INVALID", packetHash, "Packet too old, possible replay", null);
        }

        // Step 5: settle
        try {
            Transaction tx = settlementService.settle(instruction, packetHash, bridgeNodeId, hopCount);
            return new IngestResult("SETTLED", packetHash, null, tx.getId());
        } catch (IllegalStateException e) {
            log.warn("Settlement rejected for hash {}: {}", packetHash, e.getMessage());
            return new IngestResult("REJECTED", packetHash, e.getMessage(), null);
        }
    }

    @Getter
    @AllArgsConstructor
    public static class IngestResult {
        private final String outcome;       // SETTLED, DUPLICATE_DROPPED, INVALID, REJECTED
        private final String packetHash;
        private final String reason;
        private final Long transactionId;
    }
}