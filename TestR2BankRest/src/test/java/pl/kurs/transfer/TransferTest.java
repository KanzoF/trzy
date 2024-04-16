package pl.kurs.transfer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import pl.kurs.Repository.AccountRepository;
import pl.kurs.model.commands.CreateTransactionCommand;
import pl.kurs.service.AccountService;
import pl.kurs.service.TransactionService;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;


@ActiveProfiles("testTransfer")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
public class TransferTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private AccountRepository accountRepository;

    @LocalServerPort
    private int port;

    @TestConfiguration
    static class Config {
        @Bean
        public TestRestTemplate testRestTemplate() {
            return new TestRestTemplate();
        }
    }

    @Test
    public void shouldTransferAllMoneyFromAccount4and5ToAccount3InSameTime1() throws Exception {
        int threadCount = 100;
        ExecutorService service = Executors.newFixedThreadPool(threadCount * 2);
        CountDownLatch latch = new CountDownLatch(threadCount * 2);
        BigDecimal transferAmountFromAccount4 = new BigDecimal("150.0");
        BigDecimal transferAmountFromAccount5 = new BigDecimal("100.0");

        BigDecimal initialBalanceAccount3 = accountRepository.findById(3L).orElseThrow().getBalance();
        BigDecimal initialBalanceAccount4 = accountRepository.findById(4L).orElseThrow().getBalance();
        BigDecimal initialBalanceAccount5 = accountRepository.findById(5L).orElseThrow().getBalance();

        String url = "http://localhost:" + port + "/api/v1/transactions/transfer";

        for (int i = 0; i < threadCount; i++) {
            service.submit(() -> {
                try {
                    CreateTransactionCommand commandFromAccount1 = new CreateTransactionCommand(4L, 3L, transferAmountFromAccount4, "Transfer to 3 from 4");
                    ResponseEntity<String> response = restTemplate.withBasicAuth("user4", "password4")
                            .postForEntity(url, commandFromAccount1, String.class);
                    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    latch.countDown();
                }
            });

            service.submit(() -> {
                try {
                    CreateTransactionCommand commandFromAccount2 = new CreateTransactionCommand(5L, 3L, transferAmountFromAccount5, "Transfer to 3 from 5");
                    ResponseEntity<String> response = restTemplate.withBasicAuth("user5", "password5")
                            .postForEntity(url, commandFromAccount2, String.class);
                    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    latch.countDown();
                }
            });
        }
        Thread.sleep(5000);
        latch.await();

        BigDecimal finalBalanceDestinationAccount = accountRepository.findById(3L).orElseThrow().getBalance();
        BigDecimal finalBalanceAccount4 = accountRepository.findById(4L).orElseThrow().getBalance();
        BigDecimal finalBalanceAccount5 = accountRepository.findById(5L).orElseThrow().getBalance();

        BigDecimal totalTransferredFromAccount4 = transferAmountFromAccount4.multiply(new BigDecimal(threadCount));
        BigDecimal totalTransferredFromAccount5 = transferAmountFromAccount5.multiply(new BigDecimal(threadCount));

        BigDecimal expectedDestinationBalance = initialBalanceAccount3.add(totalTransferredFromAccount4).add(totalTransferredFromAccount5);
        BigDecimal expectedBalanceAccount4 = initialBalanceAccount4.subtract(totalTransferredFromAccount4);
        BigDecimal expectedBalanceAccount5 = initialBalanceAccount5.subtract(totalTransferredFromAccount5);

        assertAll("Should test all balances",
                () -> assertEquals(expectedDestinationBalance, finalBalanceDestinationAccount, "The destination account balance is incorrect"),
                () -> assertEquals(expectedBalanceAccount4, finalBalanceAccount4, "The account 4 balance is incorrect"),
                () -> assertEquals(expectedBalanceAccount5, finalBalanceAccount5, "The account 5 balance is incorrect")
        );

    }

    @Test
    public void shouldTestTransfersBetweenThreeAccountsForDeadlocks() throws Exception {
        int threadCount = 40;
        ExecutorService service = Executors.newFixedThreadPool(threadCount * 3);
        CountDownLatch latch = new CountDownLatch(threadCount * 3);
        BigDecimal transferAmount = new BigDecimal("100.0");

        Long accountIdA = 4L;
        Long accountIdB = 5L;
        Long accountIdC = 6L;

        BigDecimal initialBalanceA = accountRepository.findById(accountIdA).orElseThrow().getBalance();
        BigDecimal initialBalanceB = accountRepository.findById(accountIdB).orElseThrow().getBalance();
        BigDecimal initialBalanceC = accountRepository.findById(accountIdC).orElseThrow().getBalance();


        for (int i = 0; i < threadCount; i++) {
            // A -> B
            service.submit(() -> {
                CreateTransactionCommand commandToB = new CreateTransactionCommand(accountIdA, accountIdB, transferAmount, "Transfer to B from A");
                ResponseEntity<String> responseToB = restTemplate.withBasicAuth("user4", "password4")
                        .postForEntity("http://localhost:" + port + "/api/v1/transactions/transfer", commandToB, String.class);
                assertThat(responseToB.getStatusCode()).isEqualTo(HttpStatus.OK);
                latch.countDown();
            });

            // B -> C
            service.submit(() -> {
                CreateTransactionCommand commandToC = new CreateTransactionCommand(accountIdB, accountIdC, transferAmount, "Transfer to C from B");
                ResponseEntity<String> responseToC = restTemplate.withBasicAuth("user5", "password5")
                        .postForEntity("http://localhost:" + port + "/api/v1/transactions/transfer", commandToC, String.class);
                assertThat(responseToC.getStatusCode()).isEqualTo(HttpStatus.OK);
                latch.countDown();
            });

            // C -> A
            service.submit(() -> {
                CreateTransactionCommand commandToA = new CreateTransactionCommand(accountIdC, accountIdA, transferAmount, "Transfer to A from C");
                ResponseEntity<String> responseToA = restTemplate.withBasicAuth("user6", "password6")
                        .postForEntity("http://localhost:" + port + "/api/v1/transactions/transfer", commandToA, String.class);
                assertThat(responseToA.getStatusCode()).isEqualTo(HttpStatus.OK);
                latch.countDown();
            });
        }
        latch.await();
//        exec.shutdown();
        BigDecimal finalBalanceA = accountRepository.findById(accountIdA).orElseThrow().getBalance();
        BigDecimal finalBalanceB = accountRepository.findById(accountIdB).orElseThrow().getBalance();
        BigDecimal finalBalanceC = accountRepository.findById(accountIdC).orElseThrow().getBalance();

        assertAll("Verify all balances after circular transfers",
                () -> assertEquals(initialBalanceA, finalBalanceA, "Balance of account A should remain the same"),
                () -> assertEquals(initialBalanceB, finalBalanceB, "Balance of account B should remain the same"),
                () -> assertEquals(initialBalanceC, finalBalanceC, "Balance of account C should remain the same")
        );
    }
//
//    @Test
//    public void shouldTransferAllMoneyFromAccount4and5ToAccount3InSameTime() throws Exception {
//        int threadCount = 30;
//        ExecutorService service = Executors.newFixedThreadPool(threadCount * 2);
//        CountDownLatch latch = new CountDownLatch(threadCount * 2);
//        BigDecimal transferAmountFromAccount4 = new BigDecimal("150.0");
//        BigDecimal transferAmountFromAccount5 = new BigDecimal("100.0");
//
//        String url = "http://localhost:" + port + "/api/v1/transactions/transfer";
//        List<Future<ResponseEntity<String>>> futures = new ArrayList<>();
//
//        for (int i = 0; i < threadCount; i++) {
//            futures.add(service.submit(() -> {
//                CreateTransactionCommand commandFromAccount1 = new CreateTransactionCommand(4L, 3L, transferAmountFromAccount4, "Transfer to 3 from 4");
//                return restTemplate.withBasicAuth("user4", "password4")
//                        .postForEntity(url, commandFromAccount1, String.class);
//            }));
//
//            futures.add(service.submit(() -> {
//                CreateTransactionCommand commandFromAccount2 = new CreateTransactionCommand(5L, 3L, transferAmountFromAccount5, "Transfer to 3 from 5");
//                return restTemplate.withBasicAuth("user5", "password5")
//                        .postForEntity(url, commandFromAccount2, String.class);
//            }));
//        }
//
//        for (Future<ResponseEntity<String>> future : futures) {
//            ResponseEntity<String> response = future.get();
//            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
//            // Możemy również sprawdzić treść odpowiedzi, jeśli oczekujemy konkretnego komunikatu lub danych JSON
//            assertThat(response.getBody()).contains("expected content or JSON structure");
//            latch.countDown();
//        }
//
//        latch.await();
//
//        BigDecimal finalBalanceDestinationAccount = accountRepository.findById(3L).orElseThrow().getBalance();
//        BigDecimal finalBalanceAccount4 = accountRepository.findById(4L).orElseThrow().getBalance();
//        BigDecimal finalBalanceAccount5 = accountRepository.findById(5L).orElseThrow().getBalance();
//
//        BigDecimal expectedBalanceAccount4 = new BigDecimal("20000.00").subtract(transferAmountFromAccount4.multiply(new BigDecimal(threadCount)));
//        BigDecimal expectedBalanceAccount5 = new BigDecimal("30000.00").subtract(transferAmountFromAccount5.multiply(new BigDecimal(threadCount)));
//        BigDecimal expectedDestinationBalance = new BigDecimal("15000.00").add(transferAmountFromAccount4.multiply(new BigDecimal(threadCount))).add(transferAmountFromAccount5.multiply(new BigDecimal(threadCount)));
//
//        assertEquals(expectedBalanceAccount5, finalBalanceAccount5);
//        assertEquals(expectedDestinationBalance, finalBalanceDestinationAccount);
//        assertEquals(expectedBalanceAccount4, finalBalanceAccount4);
//    }

//    @Autowired
//    private TransactionService transactionService;
//
//    @Autowired
//    private AccountRepository accountRepository;
//
//    private Authentication authentication;
//
//    @BeforeEach
//    public void setupAuthentication() {
//        List<GrantedAuthority> authorities = Collections.singletonList(new SimpleGrantedAuthority("USER"));
//        authentication = new UsernamePasswordAuthenticationToken("user6", "password6", authorities);
//        SecurityContextHolder.getContext().setAuthentication(authentication);
//    }
//
//    @Test
//    public void testMultipleTransfersRaceCondition() throws InterruptedException {
//        int threadCount = 50;
//        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
//        BigDecimal transferAmount = new BigDecimal("100.00");
//        Long accountFrom1 = 9L;
//        Long accountFrom2 = 10L;
//        Long accountTo = 8L;
//
//        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
//
//        List<Callable<Void>> tasks = new ArrayList<>();
//        for (int i = 0; i < threadCount; i++) {
//            tasks.add(() -> {
//                SecurityContext securityContext = SecurityContextHolder.createEmptyContext();
//                securityContext.setAuthentication(authentication);
//                SecurityContextHolder.setContext(securityContext);
//
//                CreateTransactionCommand commandFrom1 = new CreateTransactionCommand(accountFrom1, accountTo, transferAmount, "Transfer from 9 to 8");
//                transactionService.makeTransfer(commandFrom1);
//                return null;
//            });
//            tasks.add(() -> {
//                SecurityContext securityContext = SecurityContextHolder.createEmptyContext();
//                securityContext.setAuthentication(authentication);
//                SecurityContextHolder.setContext(securityContext);
//
//                CreateTransactionCommand commandFrom2 = new CreateTransactionCommand(accountFrom2, accountTo, transferAmount, "Transfer from 10 to 8");
//                transactionService.makeTransfer(commandFrom2);
//                return null;
//            });
//        }
//
//        executor.invokeAll(tasks);
//        executor.shutdown();
//        executor.awaitTermination(10, TimeUnit.SECONDS);
//        BigDecimal startingBalance = new BigDecimal("30000.00");
//        BigDecimal amountTransferredFromEachAccount = transferAmount.multiply(new BigDecimal(threadCount));
//        BigDecimal totalTransferAmount = amountTransferredFromEachAccount.multiply(new BigDecimal("2"));
//        BigDecimal expectedBalance = startingBalance.add(totalTransferAmount);
//
//        BigDecimal actualBalance = accountRepository.findById(accountTo).orElseThrow().getBalance();
//
//        assertEquals(expectedBalance, actualBalance, "The final balance is incorrect, indicating potential issues with transaction processing");
//        // Sprawdzanie końcowego salda konta docelowego
////        BigDecimal expectedBalance = accountRepository.findById(accountTo).orElseThrow().getBalance();
////        BigDecimal calculatedBalance = new BigDecimal("1000.00").add(transferAmount.multiply(new BigDecimal(threadCount * 2)));
////        assertEquals(0, calculatedBalance.compareTo(expectedBalance), "The final balance is incorrect, indicating potential issues with transaction processing");
//    }
//    @Test
//    public void testMultipleTransfersRaceCondition1() throws InterruptedException, ExecutionException {
//        int threadCount = 50;
//        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
//        BigDecimal transferAmount = new BigDecimal("100.00");
//        Long accountFrom1 = 9L;
//        Long accountFrom2 = 10L;
//        Long accountTo = 8L;
//
//        List<CompletableFuture<Void>> futures = new ArrayList<>();
//
//        for (int i = 0; i < threadCount; i++) {
//            CompletableFuture<Void> future1 = CompletableFuture.runAsync(() -> {
//                CreateTransactionCommand command = new CreateTransactionCommand(accountFrom1, accountTo, transferAmount, "Transfer from 9 to 8");
//                transactionService.makeTransfer(command);
//            }, executor);
//
//            CompletableFuture<Void> future2 = CompletableFuture.runAsync(() -> {
//                CreateTransactionCommand command = new CreateTransactionCommand(accountFrom2, accountTo, transferAmount, "Transfer from 10 to 8");
//                transactionService.makeTransfer(command);
//            }, executor);
//
//            futures.add(future1);
//            futures.add(future2);
//        }
//
//        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
//        executor.shutdown();
//        boolean finishedInTime = executor.awaitTermination(1, TimeUnit.MINUTES);
//
//        // Sprawdzanie czy wszystkie zadania zakończyły się w odpowiednim czasie
//        assertTrue(finishedInTime, "The operation did not complete in the expected time, possible deadlock");
//
//        // Sprawdzanie końcowego salda konta docelowego
//        BigDecimal expectedBalance = accountRepository.findById(accountTo).orElseThrow().getBalance();
//        BigDecimal calculatedBalance = new BigDecimal("1000.00").add(transferAmount.multiply(new BigDecimal(threadCount * 2)));
//        assertEquals(calculatedBalance, expectedBalance, "The final balance is incorrect, indicating potential issues with transaction processing");
//    }
//    @Test
//    public void testRaceConditionOnTransfers() throws Exception {
//        int threadCount = 100;
//        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
//        BigDecimal transferAmount = new BigDecimal("10.0");
//        Long destinationAccountId = 4L; // Wszystkie przelewy idą na konto 4
//
//        List<CompletableFuture<Void>> futures = new ArrayList<>();
//        for (int i = 0; i < threadCount; i++) {
//            Long sourceAccountId = (i % 3L) + 1L; // Źródło zmienia się między kontami 1, 2, 3
//            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
//                try {
//                    SecurityContextHolder.getContext().setAuthentication(authentication);
//                    CreateTransactionCommand command = new CreateTransactionCommand(sourceAccountId, destinationAccountId, transferAmount, "Test Transfer");
//                    transactionService.makeTransfer(command);
//                } catch (Exception e) {
//                    throw new RuntimeException(e);
//                } finally {
//                    SecurityContextHolder.clearContext(); // Oczyść kontekst po zakończeniu operacji
//                }
//            }, executor);
//            futures.add(future);
//        }
//
//        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
//        executor.shutdown();
//
//        // Sprawdź saldo końcowe konta docelowego
//        BigDecimal expectedBalance = accountRepository.findById(destinationAccountId).orElseThrow().getBalance();
//        BigDecimal calculatedBalance = new BigDecimal("1000.0").add(transferAmount.multiply(new BigDecimal(threadCount)));
//        assertEquals(calculatedBalance, expectedBalance);
//    }
//    @Test
//    public void testDeadlocksBetweenTwoAccounts() throws Exception {
//        Long account1Id = 1L;
//        Long account2Id = 2L;
//        BigDecimal transferAmount = new BigDecimal("100.0");
//        ExecutorService executor = Executors.newFixedThreadPool(2);
//
//        CompletableFuture<Void> transferFrom1To2 = CompletableFuture.runAsync(() -> {
//            try {
//                CreateTransactionCommand command = new CreateTransactionCommand(account1Id, account2Id, transferAmount, "From 1 to 2");
//                transactionService.makeTransfer(command);
//            } catch (Exception e) {
//                throw new RuntimeException(e);
//            }
//        }, executor);
//
//        CompletableFuture<Void> transferFrom2To1 = CompletableFuture.runAsync(() -> {
//            try {
//                CreateTransactionCommand command = new CreateTransactionCommand(account2Id, account1Id, transferAmount, "From 2 to 1");
//                transactionService.makeTransfer(command);
//            } catch (Exception e) {
//                throw new RuntimeException(e);
//            }
//        }, executor);
//
//        CompletableFuture.allOf(transferFrom1To2, transferFrom2To1).join();
//        executor.shutdown();
//
//        // Sprawdź, czy transakcje zostały zakończone bez zakleszczenia
//        BigDecimal balance1 = accountRepository.findById(account1Id).orElseThrow().getBalance();
//        BigDecimal balance2 = accountRepository.findById(account2Id).orElseThrow().getBalance();
//        System.out.println("Balance of Account 1: " + balance1);
//        System.out.println("Balance of Account 2: " + balance2);
//    }
}
