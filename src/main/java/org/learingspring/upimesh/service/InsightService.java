package org.learingspring.upimesh.service;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.learingspring.upimesh.model.Transaction;
import org.learingspring.upimesh.model.TransactionRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import tools.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.client.RestClient;

/**
 * Builds a computed summary of an account's transaction activity.
 * IMPORTANT: every number here is computed in Java with BigDecimal.
 * The LLM (added in Part 2) only ever narrates these numbers - it never
 * calculates anything itself. This is the whole safety story for the insights layer.
 */
@Service
@RequiredArgsConstructor
public class InsightService {

    private final TransactionRepository txRepo;

    private final RestClient restClient = RestClient.create();

    @Value("${ollama.base-url}")
    private String ollamaBaseUrl;

    @Value("${ollama.model}")
    private String ollamaModel;

    public InsightContext buildContext(String vpa, int days) {
        Instant since = Instant.now().minus(days, ChronoUnit.DAYS);
        List<Transaction> transactions = txRepo.findByVpaSince(vpa, since);

        BigDecimal totalSent = BigDecimal.ZERO;
        BigDecimal totalReceived = BigDecimal.ZERO;
        BigDecimal largestTransaction = BigDecimal.ZERO;

        for (Transaction tx : transactions) {
            if (tx.getSenderVpa().equals(vpa)) {
                totalSent = totalSent.add(tx.getAmount());
            }
            if (tx.getReceiverVpa().equals(vpa)) {
                totalReceived = totalReceived.add(tx.getAmount());
            }
            if (tx.getAmount().compareTo(largestTransaction) > 0) {
                largestTransaction = tx.getAmount();
            }
        }

        return new InsightContext(
                vpa, days, transactions.size(),
                totalSent, totalReceived, largestTransaction,
                transactions
        );
    }

    @Getter
    @AllArgsConstructor
    public static class InsightContext {
        private final String vpa;
        private final int windowDays;
        private final int transactionCount;
        private final BigDecimal totalSent;
        private final BigDecimal totalReceived;
        private final BigDecimal largestTransaction;
        private final List<Transaction> transactions;
    }

    /**
     * Answers a natural-language question using ONLY the numbers already
     * computed in InsightContext. The LLM's job is narration, not calculation -
     * the prompt explicitly forbids it from doing its own math.
     */
    public String askQuestion(String question, InsightContext ctx) {
        String prompt = buildPrompt(question, ctx);

        String requestBody = """
                {
                  "model": "%s",
                  "prompt": %s,
                  "stream": false
                }
                """.formatted(ollamaModel, toJsonString(prompt));

        String rawResponse = restClient.post()
                .uri(ollamaBaseUrl + "/api/generate")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .body(requestBody)
                .retrieve()
                .body(String.class);

        return extractResponseText(rawResponse);
    }

    private String buildPrompt(String question, InsightContext ctx) {
        return """
                You are a helpful assistant narrating a user's payment activity.
                You must ONLY use the numbers given below. Do NOT calculate,
                estimate, or guess any numbers yourself - every figure you state
                must come directly from this data.

                Account: %s
                Time window: last %d days
                Number of transactions: %d
                Total amount sent: ₹%s
                Total amount received: ₹%s
                Largest single transaction: ₹%s

                User's question: %s

                Answer in 2-3 plain sentences, in a friendly but factual tone.
                """.formatted(
                ctx.getVpa(), ctx.getWindowDays(), ctx.getTransactionCount(),
                ctx.getTotalSent(), ctx.getTotalReceived(), ctx.getLargestTransaction(),
                question
        );
    }

    private String toJsonString(String raw) {
        // Simple escaping so the prompt can be safely embedded in JSON
        return "\"" + raw.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }

    private String extractResponseText(String rawJson) {
        try {
            JsonNode node = new tools.jackson.databind.json.JsonMapper().readTree(rawJson);
            return node.get("response").asString();
        } catch (Exception e) {
            return "Sorry, I couldn't parse the model's response.";
        }
    }
}