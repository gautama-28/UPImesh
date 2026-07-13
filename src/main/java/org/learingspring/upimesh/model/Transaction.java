package org.learingspring.upimesh.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Permanent record of every settled transaction. Once written, never modified.
 * The packetHash is the idempotency key - uniqueness is enforced at the DB level
 * as a defense-in-depth fallback if the Redis idempotency cache ever fails.
 */
@Entity
@Table(name = "transactions",
        indexes = { @Index(name = "idx_packet_hash", columnList = "packetHash", unique = true) })
@Getter
@Setter
@NoArgsConstructor
public class Transaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 64)
    private String packetHash;

    @Column(nullable = false)
    private String senderVpa;

    @Column(nullable = false)
    private String receiverVpa;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false)
    private Instant signedAt;

    @Column(nullable = false)
    private Instant settledAt;

    @Column(nullable = false)
    private String bridgeNodeId;

    @Column(nullable = false)
    private int hopCount;

    @Enumerated(EnumType.STRING)// If not modified then it will return 0 and 1
    @Column(nullable = false)
    private Status status;

    public enum Status { SETTLED, REJECTED }
}