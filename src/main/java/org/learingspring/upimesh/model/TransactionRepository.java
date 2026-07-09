package org.learingspring.upimesh.model;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TransactionRepository extends JpaRepository<Transaction, Long> {

    // Spring Data JPA parses this method name and auto-generates the SQL:
    // SELECT * FROM transactions ORDER BY id DESC LIMIT 20
    List<Transaction> findTop20ByOrderByIdDesc();
}