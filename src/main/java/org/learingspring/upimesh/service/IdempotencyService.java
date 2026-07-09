package org.learingspring.upimesh.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;

/**
 * Redis-backed idempotency cache. The contract:
 *   - claim(hash) returns true on first call, false on every call after that
 *     (within the TTL window)
 *   - the operation is atomic - even if 100 threads call claim(hash) at the
 *     exact same instant, exactly one returns true

 * This is what kills the "three bridges deliver simultaneously" problem.
 * Redis SETNX (via setIfAbsent) is atomic at the Redis server level, so this
 * is safe even across multiple app instances, not just multiple threads.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class IdempotencyService {

    private final StringRedisTemplate redisTemplate;

    @Value("${upi.mesh.idempotency-ttl-seconds:86400}")
    private long ttlSeconds;

    /**
     * Try to claim a hash. Returns true if this caller is the first; false if
     * someone else already claimed it (i.e. the packet is a duplicate).
     */
    public boolean claim(String packetHash) {
        Boolean firstClaim = redisTemplate.opsForValue()
                .setIfAbsent(packetHash, Instant.now().toString(), Duration.ofSeconds(ttlSeconds));

        boolean claimed = Boolean.TRUE.equals(firstClaim);
        if (!claimed) {
            log.info("Duplicate packet detected, hash already claimed: {}", packetHash);
        }
        return claimed;
    }
    public long size() {
        return redisTemplate.getConnectionFactory().getConnection().serverCommands().dbSize();
    }

    /** Demo/test helper - clears the entire idempotency cache. */
    public void clear() {
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushDb();
    }
}