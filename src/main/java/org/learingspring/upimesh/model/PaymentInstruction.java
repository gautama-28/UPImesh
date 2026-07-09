package org.learingspring.upimesh.model;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * The actual payment payload, only ever seen in decrypted form on the backend.
 * This is what gets JSON-serialized, then hybrid-encrypted into MeshPacket.ciphertext.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class PaymentInstruction {

    private String senderVpa;
    private String receiverVpa;
    private BigDecimal amount;
    private String pinHash;    // never the raw PIN, always pre-hashed by the sender
    private String nonce;      // UUID - makes two identical-amount payments have different ciphertexts
    private long signedAt;     // epoch millis - used for the 24h replay-window check
}