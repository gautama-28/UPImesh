package org.learingspring.upimesh.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.learingspring.upimesh.model.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Owns the actual settlement logic - debiting the sender, crediting the
 * receiver, and writing the permanent ledger row. Everything happens inside
 * a single @Transactional boundary: either all three writes succeed, or none do.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SettlementService {

    private final AccountRepository accountRepo;
    private final TransactionRepository txRepo;

    /**
     * Settles one payment. Throws IllegalStateException if the sender doesn't
     * have enough balance - caller is responsible for catching this and
     * recording the transaction as REJECTED rather than SETTLED.
     */
    @Transactional
    public Transaction settle(PaymentInstruction instruction, String packetHash,
                              String bridgeNodeId, int hopCount) {

        Account sender = accountRepo.findById(instruction.getSenderVpa())
                .orElseThrow(() -> new IllegalStateException("Unknown sender: " + instruction.getSenderVpa()));
        Account receiver = accountRepo.findById(instruction.getReceiverVpa())
                .orElseThrow(() -> new IllegalStateException("Unknown receiver: " + instruction.getReceiverVpa()));

        if (sender.getBalance().compareTo(instruction.getAmount()) < 0) {
            throw new IllegalStateException("Insufficient balance for " + sender.getVpa());
        }

        sender.setBalance(sender.getBalance().subtract(instruction.getAmount()));
        receiver.setBalance(receiver.getBalance().add(instruction.getAmount()));

        // save() here triggers Hibernate's @Version check on both accounts -
        // if another concurrent transaction touched either row first, this
        // throws OptimisticLockingFailureException instead of silently
        // overwriting a lost update.
        accountRepo.save(sender);
        accountRepo.save(receiver);

        Transaction tx = new Transaction();
        tx.setPacketHash(packetHash);
        tx.setSenderVpa(instruction.getSenderVpa());
        tx.setReceiverVpa(instruction.getReceiverVpa());
        tx.setAmount(instruction.getAmount());
        tx.setSignedAt(Instant.ofEpochMilli(instruction.getSignedAt()));
        tx.setSettledAt(Instant.now());
        tx.setBridgeNodeId(bridgeNodeId);
        tx.setHopCount(hopCount);
        tx.setStatus(Transaction.Status.SETTLED);

        Transaction saved = txRepo.save(tx);
        log.info("Settled: {} -> {} amount={} hash={}",
                sender.getVpa(), receiver.getVpa(), instruction.getAmount(), packetHash);

        return saved;
    }
}