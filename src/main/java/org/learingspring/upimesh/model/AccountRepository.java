package org.learingspring.upimesh.model;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AccountRepository extends JpaRepository<Account, String> {
    // JpaRepository<Account, String> means:
    //   Account = the entity type
    //   String  = the type of its @Id field (vpa is a String)
    // findAll(), findById(), save(), delete() etc. all come free from this.
}