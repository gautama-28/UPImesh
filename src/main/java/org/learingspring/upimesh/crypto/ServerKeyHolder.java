package org.learingspring.upimesh.crypto;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import org.springframework.stereotype.Component;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Base64;

/**
 * Holds the server's RSA-2048 keypair for the lifetime of the application.
 * Generated fresh on every startup - in production this would live in an
 * HSM/KMS instead of memory.
 */
@Component
@Getter
public class ServerKeyHolder {

    private PrivateKey privateKey;
    private PublicKey publicKey;

    @PostConstruct
    public void generateKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair pair = generator.generateKeyPair();
        this.privateKey = pair.getPrivate();
        this.publicKey = pair.getPublic();
    }

    public String getPublicKeyBase64() {
        return Base64.getEncoder().encodeToString(publicKey.getEncoded());
    }
}