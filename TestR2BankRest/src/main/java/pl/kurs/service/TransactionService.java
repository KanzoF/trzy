package pl.kurs.service;

import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pl.kurs.Repository.TransactionRepository;
import pl.kurs.Repository.AccountRepository;
import pl.kurs.exceptions.NotEnoughMoneyException;
import pl.kurs.exceptions.WrongOwnerException;
import pl.kurs.model.Account;
import pl.kurs.model.Transaction;
import pl.kurs.model.dto.TransactionSearchCriteria;
import pl.kurs.model.commands.CreateTransactionCommand;

import java.time.LocalDateTime;
import java.util.*;

import jakarta.persistence.criteria.Predicate;


@Service
@RequiredArgsConstructor
@Slf4j
public class TransactionService {

    private final TransactionRepository transactionRepository;
    private final AccountRepository accountRepository;

    @Transactional
    public Transaction makeTransfer(CreateTransactionCommand command) {
        log.info("Thread: {}, Starting transaction from {} to {} for amount {}", Thread.currentThread().getName(), command.getSourceAccountId(), command.getDestinationAccountId(), command.getAmount());

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String currentUsername = authentication.getName();

        Set<Long> accountIds = new HashSet<>(Arrays.asList(command.getSourceAccountId(), command.getDestinationAccountId()));
        List<Account> accounts = accountRepository.findByIdsWithLock(accountIds);

        accounts.sort(Comparator.comparing(Account::getId));

        Account sourceAccount = accounts.stream()
                .filter(a -> a.getId().equals(command.getSourceAccountId()))
                .findFirst()
                .orElseThrow(() -> new EntityNotFoundException("Source account not found"));

        Account destinationAccount = accounts.stream()
                .filter(a -> a.getId().equals(command.getDestinationAccountId()))
                .findFirst()
                .orElseThrow(() -> new EntityNotFoundException("Destination account not found"));

        if (!sourceAccount.getUsername().equals(currentUsername)) {
            throw new WrongOwnerException("User does not own the source account");
        }
        if (sourceAccount.getBalance().compareTo(command.getAmount()) < 0) {
            throw new NotEnoughMoneyException("Source account does not have enough balance");
        }
        log.info("Thread: {}, Before transaction: Account ID: {}, Balance: {}", Thread.currentThread().getName(), sourceAccount.getId(), sourceAccount.getBalance());
        sourceAccount.setBalance(sourceAccount.getBalance().subtract(command.getAmount()));
        destinationAccount.setBalance(destinationAccount.getBalance().add(command.getAmount()));
        log.info("Thread: {}, After transaction: Account ID: {}, Balance: {}", Thread.currentThread().getName(), sourceAccount.getId(), sourceAccount.getBalance());

        Transaction transaction = new Transaction();
        transaction.setSourceAccount(sourceAccount);
        transaction.setDestinationAccount(destinationAccount);
        transaction.setAmount(command.getAmount());
        transaction.setTitle(command.getTitle());
        transaction.setTransactionDate(LocalDateTime.now());

//        return transactionRepository.save(transaction);
        Transaction savedTransaction = transactionRepository.save(transaction);
        log.info("Thread: {}, Transaction completed successfully: Transaction ID {}", Thread.currentThread().getName(), savedTransaction.getId());
        return savedTransaction;
    }

    public Page<Transaction> findTransactions(TransactionSearchCriteria criteria, Pageable pageable) {
        Specification<Transaction> spec = findByCriteria(criteria);
        return transactionRepository.findAll(spec, pageable);
    }

    private Specification<Transaction> findByCriteria(TransactionSearchCriteria criteria) {

        return (root, query, criteriaBuilder) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (criteria.getUserId() != null) {
                Predicate sourceAccountPredicate = criteriaBuilder.equal(root.get("sourceAccount").get("id"), criteria.getUserId());
                Predicate destinationAccountPredicate = criteriaBuilder.equal(root.get("destinationAccount").get("id"), criteria.getUserId());
                predicates.add(criteriaBuilder.or(sourceAccountPredicate, destinationAccountPredicate));
            }
            if (criteria.getAmountFrom() != null && criteria.getAmountTo() != null) {
                predicates.add(criteriaBuilder.between(root.get("amount"), criteria.getAmountFrom(), criteria.getAmountTo()));
            }
            if (criteria.getDateFrom() != null) {
                predicates.add(criteriaBuilder.greaterThanOrEqualTo(root.get("transactionDate"), criteria.getDateFrom()));
            }
            if (criteria.getDateTo() != null) {
                LocalDateTime endOfDay = criteria.getDateTo().withHour(23).withMinute(59).withSecond(59);
                predicates.add(criteriaBuilder.lessThanOrEqualTo(root.get("transactionDate"), endOfDay));
            }

            return criteriaBuilder.and(predicates.toArray(new Predicate[0]));
        };
    }
}
