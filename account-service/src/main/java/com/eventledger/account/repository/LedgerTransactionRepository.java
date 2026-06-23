package com.eventledger.account.repository;

import com.eventledger.account.domain.LedgerTransaction;
import com.eventledger.account.domain.TransactionType;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LedgerTransactionRepository extends JpaRepository<LedgerTransaction, String> {

    /**
     * Sum of amounts for one direction. Returns {@code null} when the account
     * has no rows of that type — callers coalesce to zero. The net balance is
     * computed as {@code sumAmount(CREDIT) - sumAmount(DEBIT)}.
     */
    @Query("select sum(t.amount) from LedgerTransaction t "
            + "where t.accountId = :accountId and t.type = :type")
    BigDecimal sumAmount(@Param("accountId") String accountId, @Param("type") TransactionType type);

    /** Most recent applied transactions for the account-detail view. */
    List<LedgerTransaction> findTop20ByAccountIdOrderByAppliedAtDesc(String accountId);

    long countByAccountId(String accountId);
}
