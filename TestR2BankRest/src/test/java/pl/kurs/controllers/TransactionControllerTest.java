package pl.kurs.controllers;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import pl.kurs.Repository.AccountRepository;
import pl.kurs.model.commands.CreateTransactionCommand;
import pl.kurs.service.AccountService;
import pl.kurs.service.TransactionService;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TransactionControllerTest {

    @Autowired
    private MockMvc postman;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private TransactionService transactionService;

    @Test
    public void testRaceConditionOnTransfers() throws Exception {
        int threadCount = 100;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        BigDecimal transferAmount = new BigDecimal("10.0");
        Long destinationAccountId = 4L; // Wszystkie przelewy idą na konto 4

        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (int i = 0; i < threadCount; i++) {
            Long sourceAccountId = (i % 3L) + 1L; // Źródło zmienia się między kontami 1, 2, 3
            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                try {
                    CreateTransactionCommand command = new CreateTransactionCommand(sourceAccountId, destinationAccountId, transferAmount, "Test Transfer");
                    transactionService.makeTransfer(command);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }, executor);
            futures.add(future);
        }

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        executor.shutdown();

        // Sprawdź saldo końcowe konta docelowego
        BigDecimal expectedBalance = accountRepository.findById(destinationAccountId).orElseThrow().getBalance();
        BigDecimal calculatedBalance = new BigDecimal("1000.0").add(transferAmount.multiply(new BigDecimal(threadCount)));
        assertEquals(calculatedBalance, expectedBalance);
    }
    @Test
    public void testDeadlocksBetweenTwoAccounts() throws Exception {
        Long account1Id = 1L;
        Long account2Id = 2L;
        BigDecimal transferAmount = new BigDecimal("100.0");
        ExecutorService executor = Executors.newFixedThreadPool(2);

        CompletableFuture<Void> transferFrom1To2 = CompletableFuture.runAsync(() -> {
            try {
                CreateTransactionCommand command = new CreateTransactionCommand(account1Id, account2Id, transferAmount, "From 1 to 2");
                transactionService.makeTransfer(command);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }, executor);

        CompletableFuture<Void> transferFrom2To1 = CompletableFuture.runAsync(() -> {
            try {
                CreateTransactionCommand command = new CreateTransactionCommand(account2Id, account1Id, transferAmount, "From 2 to 1");
                transactionService.makeTransfer(command);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }, executor);

        CompletableFuture.allOf(transferFrom1To2, transferFrom2To1).join();
        executor.shutdown();

        // Sprawdź, czy transakcje zostały zakończone bez zakleszczenia
        BigDecimal balance1 = accountRepository.findById(account1Id).orElseThrow().getBalance();
        BigDecimal balance2 = accountRepository.findById(account2Id).orElseThrow().getBalance();
        System.out.println("Balance of Account 1: " + balance1);
        System.out.println("Balance of Account 2: " + balance2);
    }
    @Test
    public void shouldHandleConcurrentTransfers() throws Exception {
        int threadCount = 100;

        ExecutorService service = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        List<AssertionError> errors = Collections.synchronizedList(new ArrayList<>());
        BigDecimal transferAmount = new BigDecimal("100.0");

        BigDecimal balanceBeforeSource = accountRepository.findById(1L).orElseThrow().getBalance();
        BigDecimal balanceBeforeDest = accountRepository.findById(2L).orElseThrow().getBalance();

        for (int i = 0; i < threadCount; i++) {
            service.submit(() -> {
                try {
                    CreateTransactionCommand command = new CreateTransactionCommand(1L, 2L, transferAmount, "test test");
                    postman.perform(post("/api/v1/transactions/transfer")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(new ObjectMapper().writeValueAsString(command))
                                    .with(csrf())
                                    .with(user("User1").roles("USER")))
                            .andExpect(status().isOk())
                            .andExpect(jsonPath("$.amount").value("100.0"))
                            .andExpect(jsonPath("$.title").value("test test"));
                } catch (AssertionError e) {
                    errors.add(e);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    latch.countDown();
                }
            });
        }
        latch.await();
        service.shutdown();

        if (!errors.isEmpty()) {
            throw new AssertionError("Errors occurred in assertions: " + errors);
        }

        BigDecimal balanceAfterSource = accountRepository.findById(1L).orElseThrow().getBalance();
        BigDecimal balanceAfterDest = accountRepository.findById(2L).orElseThrow().getBalance();

        BigDecimal expectedSourceBalance = balanceBeforeSource.subtract(transferAmount.multiply(new BigDecimal(threadCount)));
        BigDecimal expectedDestBalance = balanceBeforeDest.add(transferAmount.multiply(new BigDecimal(threadCount)));

        assertEquals(expectedSourceBalance, balanceAfterSource);
        assertEquals(expectedDestBalance, balanceAfterDest);
    }

    @Test
    public void shouldTransferAllMoneyFromAccount4and5ToAccount3InSameTime() throws Exception {
        int threadCount = 100;
        ExecutorService service = Executors.newFixedThreadPool(threadCount * 2);
        CountDownLatch latch = new CountDownLatch(threadCount * 2);
        BigDecimal transferAmountFromAccount4 = new BigDecimal("150.0"); //zostaje 5k
        BigDecimal transferAmountFromAccount5 = new BigDecimal("100.0"); //20k


        for (int i = 0; i < threadCount; i++) {
            service.submit(() -> {
                try {
                    CreateTransactionCommand commandFromAccount1 = new CreateTransactionCommand(4L, 3L, transferAmountFromAccount4, "Transfer to 3 from 4");
                    postman.perform(post("/api/v1/transactions/transfer")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(objectMapper.writeValueAsString(commandFromAccount1))
                                    .with(csrf())
                                    .with(user("User4").roles("USER")))
                            .andExpect(status().isOk())
                            .andExpect(jsonPath("$.amount").value("150.0"))
                            .andExpect(jsonPath("$.title").value("Transfer to 3 from 4"));
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    latch.countDown();
                }
            });
        }
        for (int i = 0; i < threadCount; i++) {
            service.submit(() -> {
                try {
                    CreateTransactionCommand commandFromAccount2 = new CreateTransactionCommand(5L, 3L, transferAmountFromAccount5, "Transfer to 3 from 5");
                    postman.perform(post("/api/v1/transactions/transfer")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(objectMapper.writeValueAsString(commandFromAccount2))
                                    .with(csrf())
                                    .with(user("User5").roles("USER")))
                            .andExpect(status().isOk())
                            .andExpect(jsonPath("$.amount").value("100.0"))
                            .andExpect(jsonPath("$.title").value("Transfer to 3 from 5"));
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    latch.countDown();
                }
            });
        }
        latch.await();

        BigDecimal finalBalanceDestinationAccount = accountRepository.findById(3L).orElseThrow().getBalance();
        BigDecimal finalBalanceAccount4 = accountRepository.findById(4L).orElseThrow().getBalance();
        BigDecimal finalBalanceAccount5 = accountRepository.findById(5L).orElseThrow().getBalance();


        assertEquals(new BigDecimal("40000.00"), finalBalanceDestinationAccount); // 15 + 25k
        assertEquals(new BigDecimal("5000.00"), finalBalanceAccount4);
        assertEquals(new BigDecimal("20000.00"), finalBalanceAccount5);

    }

    @Test
    void shouldMakeTransfer_whenAuthenticatedAsUser() throws Exception {
        BigDecimal transferAmount = new BigDecimal("100.0");
        BigDecimal balanceBeforeSource = accountRepository.findById(3L).orElseThrow().getBalance();
        BigDecimal balanceBeforeDest = accountRepository.findById(1L).orElseThrow().getBalance();


        CreateTransactionCommand command = new CreateTransactionCommand(3L, 1L, new BigDecimal("100.0"), "Test Transfer");
        postman.perform(post("/api/v1/transactions/transfer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(command))
                        .with(httpBasic("User3", "password3")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.amount").value("100.0"))
                .andExpect(jsonPath("$.title").value("Test Transfer"));

        BigDecimal balanceAfterSource = accountRepository.findById(3L).orElseThrow().getBalance();
        BigDecimal balanceAfterDest = accountRepository.findById(1L).orElseThrow().getBalance();

        BigDecimal expectedSourceBalance = balanceBeforeSource.subtract(transferAmount);
        BigDecimal expectedDestBalance = balanceBeforeDest.add(transferAmount);

        assertEquals(expectedSourceBalance, balanceAfterSource);
        assertEquals(expectedDestBalance, balanceAfterDest);
    }

    @Test
    @WithMockUser(username = "admin", roles = {"ADMIN"})
    void shouldFindTransactionsForUser1FromDataSqlFile() throws Exception {
        Long userId = 1L;                                                               //  User1 from data.sql

        postman.perform(get("/api/v1/transactions/search")
                        .param("userId", userId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content[0].sourceAccountId").value(userId))
                .andExpect(jsonPath("$.content[0].amount").value(100.00));
    }

    @Test
    void shouldFilterTransactionsByEndDateCorrectly() throws Exception {
        String dateTo = "2023-01-02T23:59:59";

        postman.perform(get("/api/v1/transactions/search")
                        .param("dateTo", dateTo)
                        .with(csrf())
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(2)))
                .andExpect(jsonPath("$.content[*].transactionDate", everyItem(lessThanOrEqualTo(dateTo))));
    }

    @Test
    @WithMockUser(username = "admin", roles = {"ADMIN"})
    void shouldMatchTransactionDetailsExactlyFromSqlFile() throws Exception {

        Long user1Id = 1L;
        Long user2Id = 2L;


        postman.perform(get("/api/v1/transactions/search")
                        .param("userId", user1Id.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content[?(@.sourceAccountId == 1 && @.destinationAccountId == 2 && @.amount == 100.00)]").exists())
                .andExpect(jsonPath("$.content[?(@.transactionDate == '2023-01-04T12:00:00')]").exists());


        postman.perform(get("/api/v1/transactions/search")
                        .param("userId", user2Id.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content[?(@.sourceAccountId == 2 && @.destinationAccountId == 1 && @.amount == 200.00)]").exists())
                .andExpect(jsonPath("$.content[?(@.transactionDate == '2023-01-03T13:00:00')]").exists());
    }


}