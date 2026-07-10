package org.learingspring.upimesh.model;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

@Repository
public interface TransactionRepository extends JpaRepository<Transaction, Long> {

    // Spring Data JPA parses this method name and auto-generates the SQL:
    // SELECT * FROM transactions ORDER BY id DESC LIMIT 20
    List<Transaction> findTop20ByOrderByIdDesc();

    List<Transaction> findBySenderVpaAndSettledAtAfter(String senderVpa, Instant since);

    List<Transaction> findByReceiverVpaAndSettledAtAfter(String receiverVpa, Instant since);

    @Query("SELECT t FROM Transaction t WHERE (t.senderVpa = :vpa OR t.receiverVpa = :vpa) AND t.settledAt > :since")
    List<Transaction> findByVpaSince(@Param("vpa") String vpa, @Param("since") Instant since);
}