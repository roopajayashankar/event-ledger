package com.eventledger.account.service;

import com.eventledger.account.api.dto.AccountResponse;
import com.eventledger.account.api.dto.AccountResponse.TransactionView;
import com.eventledger.account.api.dto.BalanceResponse;
import com.eventledger.account.domain.Account;
import com.eventledger.account.domain.LedgerTransaction;
import com.eventledger.account.domain.TransactionType;
import com.eventledger.account.repository.AccountRepository;
import com.eventledger.account.repository.LedgerTransactionRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns account state. Applies transactions idempotently by {@code eventId} and
 * computes balances by aggregation so they cannot drift.
 */
@Service
public class AccountService {

    private static final Logger log = LoggerFactory.getLogger(AccountService.class);

    private final AccountRepository accounts;
    private final LedgerTransactionRepository transactions;

    public AccountService(AccountRepository accounts, LedgerTransactionRepository transactions) {
        this.accounts = accounts;
        this.transactions = transactions;
    }

    /**
     * Applies a transaction idempotently. Replaying a known {@code eventId} is a
     * no-op that returns the stored transaction and the current balance — it
     * never double-applies. Idempotency is backed by the {@code eventId} primary
     * key, so even a concurrent duplicate is caught at the persistence layer.
     */
    @Transactional
    public ApplyResult apply(String accountId, String eventId, TransactionType type,
                             BigDecimal amount, String currency) {
        Optional<LedgerTransaction> existing = transactions.findById(eventId);
        if (existing.isPresent()) {
            LedgerTransaction tx = existing.get();
            log.debug("Duplicate eventId={} for account {} — no-op replay", eventId, accountId);
            return new ApplyResult(tx, balanceOf(tx.getAccountId()), false);
        }

        // Establish the account's currency on first use, or enforce it thereafter.
        Account account = accounts.findById(accountId).orElse(null);
        if (account == null) {
            account = accounts.save(new Account(accountId, currency, Instant.now()));
        } else if (!account.getCurrency().equals(currency)) {
            throw new CurrencyMismatchException(accountId, account.getCurrency(), currency);
        }

        // No overdraft guard: a DEBIT may drive the balance negative. This is a
        // ledger, not an authorization system (documented assumption, CLAUDE.md).
        LedgerTransaction tx =
                new LedgerTransaction(eventId, accountId, type, amount, currency, Instant.now());
        try {
            transactions.saveAndFlush(tx);
        } catch (DataIntegrityViolationException race) {
            // A concurrent insert won the race for this eventId. Idempotent
            // outcome: treat ours as a duplicate and return the stored result.
            LedgerTransaction stored = transactions.findById(eventId).orElseThrow(() -> race);
            log.debug("Concurrent duplicate eventId={} for account {} — no-op replay", eventId, accountId);
            return new ApplyResult(stored, balanceOf(accountId), false);
        }

        log.info("Applied {} {} {} to account {} (eventId={})",
                type, amount, currency, accountId, eventId);
        return new ApplyResult(tx, balanceOf(accountId), true);
    }

    @Transactional(readOnly = true)
    public BalanceResponse getBalance(String accountId) {
        Account account = accounts.findById(accountId)
                .orElseThrow(() -> new AccountNotFoundException(accountId));
        return new BalanceResponse(accountId, balanceOf(accountId), account.getCurrency());
    }

    @Transactional(readOnly = true)
    public AccountResponse getAccount(String accountId) {
        Account account = accounts.findById(accountId)
                .orElseThrow(() -> new AccountNotFoundException(accountId));
        List<TransactionView> recent =
                transactions.findTop20ByAccountIdOrderByAppliedAtDesc(accountId).stream()
                        .map(t -> new TransactionView(
                                t.getEventId(), t.getType(), t.getAmount(),
                                t.getCurrency(), t.getAppliedAt()))
                        .toList();
        return new AccountResponse(
                accountId,
                account.getCurrency(),
                balanceOf(accountId),
                transactions.countByAccountId(accountId),
                recent);
    }

    /** Net balance = sum(CREDIT) - sum(DEBIT); order-independent by construction. */
    private BigDecimal balanceOf(String accountId) {
        return nz(transactions.sumAmount(accountId, TransactionType.CREDIT))
                .subtract(nz(transactions.sumAmount(accountId, TransactionType.DEBIT)));
    }

    private static BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    /** Outcome of {@link #apply}: the (new or existing) transaction, the
     * resulting balance, and whether this call applied it ({@code true}) or hit
     * an idempotent replay ({@code false}). */
    public record ApplyResult(LedgerTransaction transaction, BigDecimal balance, boolean applied) {
    }
}
