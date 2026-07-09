package org.learingspring.upimesh.crypto;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

@Service
@RequiredArgsConstructor
@Slf4j
public class HybridCryptoService {

    private final ServerKeyHolder serverKeyHolder;

    private static final int RSA_KEY_BYTES = 256;   // 2048-bit RSA = 256 bytes
    private static final int AES_KEY_BITS = 256;
    private static final int GCM_IV_BYTES = 12;      // standard GCM IV size
    private static final int GCM_TAG_BITS = 128;     // GCM auth tag size

    private final SecureRandom random = new SecureRandom();

    /**
     * Hybrid-encrypts a plaintext payload using the server's RSA public key.
     * Wire format: [256B RSA-encrypted AES key][12B IV][AES-GCM ciphertext + 16B tag]
     * Returns the whole thing as a single Base64 string.
     */
    public String encrypt(String plaintext) throws Exception {
        // 1. Generate a fresh AES-256 key for THIS packet only
        KeyGenerator keyGen = KeyGenerator.getInstance("AES");
        keyGen.init(AES_KEY_BITS);
        SecretKey aesKey = keyGen.generateKey();

        // 2. Generate a random IV (nonce) for AES-GCM
        byte[] iv = new byte[GCM_IV_BYTES];
        random.nextBytes(iv);

        // 3. Encrypt the plaintext with AES-256-GCM
        Cipher aesCipher = Cipher.getInstance("AES/GCM/NoPadding");
        GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_BITS, iv);
        aesCipher.init(Cipher.ENCRYPT_MODE, aesKey, gcmSpec);
        byte[] aesCiphertext = aesCipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

        // 4. Encrypt the AES key itself with the server's RSA public key
        Cipher rsaCipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding");
        rsaCipher.init(Cipher.ENCRYPT_MODE, serverKeyHolder.getPublicKey());
        byte[] encryptedAesKey = rsaCipher.doFinal(aesKey.getEncoded());

        // 5. Concatenate: [RSA-encrypted AES key][IV][AES ciphertext+tag]
        ByteBuffer buffer = ByteBuffer.allocate(encryptedAesKey.length + iv.length + aesCiphertext.length);
        buffer.put(encryptedAesKey);
        buffer.put(iv);
        buffer.put(aesCiphertext);

        return Base64.getEncoder().encodeToString(buffer.array());
    }


    /*
     * Reverses encrypt(): unwraps the AES key with the server's RSA private key,
     * then decrypts the payload. Throws if the packet was tampered with -
     * AES-GCM's auth tag check fails automatically on any modification.
     */
    public String decrypt(String base64Ciphertext) throws Exception {
        byte[] blob = Base64.getDecoder().decode(base64Ciphertext);

        // Split the blob back into its three parts
        byte[] encryptedAesKey = new byte[RSA_KEY_BYTES];
        byte[] iv = new byte[GCM_IV_BYTES];
        byte[] aesCiphertext = new byte[blob.length - RSA_KEY_BYTES - GCM_IV_BYTES];

        ByteBuffer buffer = ByteBuffer.wrap(blob);
        buffer.get(encryptedAesKey);
        buffer.get(iv);
        buffer.get(aesCiphertext);

        // 1. Unwrap the AES key using the server's RSA private key
        Cipher rsaCipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding");
        rsaCipher.init(Cipher.DECRYPT_MODE, serverKeyHolder.getPrivateKey());
        byte[] aesKeyBytes = rsaCipher.doFinal(encryptedAesKey);
        SecretKey aesKey = new SecretKeySpec(aesKeyBytes, "AES");

        // 2. Decrypt the payload - this throws if the GCM tag doesn't verify (tampering)
        Cipher aesCipher = Cipher.getInstance("AES/GCM/NoPadding");
        GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_BITS, iv);
        aesCipher.init(Cipher.DECRYPT_MODE, aesKey, gcmSpec);
        byte[] plaintextBytes = aesCipher.doFinal(aesCiphertext);

        return new String(plaintextBytes, StandardCharsets.UTF_8);
    }

    /*
     * SHA-256 hash of the ciphertext - this is the idempotency key.
     * Hashing the ciphertext (not the plaintext, not the packetId) means we can
     * dedupe BEFORE spending CPU on RSA decryption, and it can't be spoofed by
     * a malicious intermediate rewriting the packetId.
     */

    public String hashCiphertext(String base64Ciphertext) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hashBytes = digest.digest(base64Ciphertext.getBytes(StandardCharsets.UTF_8));

        StringBuilder hex = new StringBuilder();
        for (byte b : hashBytes) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }
}