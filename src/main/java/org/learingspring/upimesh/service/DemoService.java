package org.learingspring.upimesh.service;

import lombok.RequiredArgsConstructor;
import org.learingspring.upimesh.crypto.HybridCryptoService;
import org.learingspring.upimesh.model.MeshPacket;
import org.learingspring.upimesh.model.PaymentInstruction;
import tools.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/**
 * Simulates a sender's phone: builds a PaymentInstruction, hashes the PIN,
 * serializes to JSON, hybrid-encrypts it, and wraps it in a MeshPacket.
 * In a real deployment, this exact logic would run on the Android app instead.
 */
@Service
@RequiredArgsConstructor
public class DemoService {

    private final HybridCryptoService cryptoService;
    private final ObjectMapper objectMapper;

    public MeshPacket createPacket(String senderVpa, String receiverVpa,
                                   BigDecimal amount, String pin, int ttl) throws Exception {

        PaymentInstruction instruction = new PaymentInstruction(
                senderVpa,
                receiverVpa,
                amount,
                hashPin(pin),
                UUID.randomUUID().toString(),
                Instant.now().toEpochMilli()
        );

        String plaintextJson = objectMapper.writeValueAsString(instruction);
        String ciphertext = cryptoService.encrypt(plaintextJson);

        return new MeshPacket(
                UUID.randomUUID().toString(),
                ttl,
                Instant.now().toEpochMilli(),
                ciphertext
        );
    }

    /** Never send the raw PIN - always hash it before it leaves the "phone". */
    private String hashPin(String pin) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(pin.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(hash);
    }
}