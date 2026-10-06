package com.example.rag.service;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import com.google.genai.errors.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class RagService {
    private static final Logger logger = LoggerFactory.getLogger(RagService.class);
    private static final double THRESHOLD = 0.35;
    private static final int TOP_K = 5;

    private final VectorStore vectorStore;
    private final ChatClient chatClient;

    public RagService(VectorStore vectorStore, ChatClient.Builder chatClientBuilder) {
        this.vectorStore = vectorStore;
        this.chatClient = chatClientBuilder.build();
    }

    public RagResult ask(String question) {
        String normalized = question == null ? "" : question.trim();
        if (normalized.isBlank()) {
            return new RagResult("Please enter a question.", List.of(), true, "INVALID_REQUEST");
        }
        if (normalized.length() > 1000) {
            return new RagResult(
                    "Question is too long. Please keep it under 1000 characters.",
                    List.of(), true, "INVALID_REQUEST");
        }

        long retrievalStarted = System.nanoTime();
        List<Document> matches;
        try {
            matches = vectorStore.similaritySearch(SearchRequest.builder()
                    .query(normalized)
                    .topK(TOP_K)
                    .similarityThreshold(THRESHOLD)
                    .build());
            logger.info("Knowledge retrieval completed in {} ms",
                    (System.nanoTime() - retrievalStarted) / 1_000_000);
        } catch (Exception e) {
            logger.error("Knowledge retrieval failed after {} ms",
                    (System.nanoTime() - retrievalStarted) / 1_000_000, e);
            return new RagResult(
                    "Knowledge retrieval is temporarily unavailable. Please try again.",
                    List.of(), true, "SERVICE_ERROR");
        }

        if (matches == null || matches.isEmpty()) {
            return new RagResult(
                    "I can't answer that from the internal knowledge base. Please ask a question related to the available documents.",
                    List.of(), true, "NO_SOURCES");
        }

        StringBuilder context = new StringBuilder();
        List<Citation> citations = new ArrayList<>();
        for (int i = 0; i < matches.size(); i++) {
            Document d = matches.get(i);
            Map<String, Object> m = d.getMetadata();
            String source = String.valueOf(m.getOrDefault("source", "Unknown source"));
            String chunk = String.valueOf(m.getOrDefault("chunk", "?"));
            String documentId = String.valueOf(m.getOrDefault("documentId", ""));
            citations.add(new Citation(i + 1, source, chunk, documentId, d.getText()));
            context.append("[SOURCE ").append(i + 1).append("] ")
                    .append(source).append(" (chunk ").append(chunk).append(")\n")
                    .append(d.getText()).append("\n\n");
        }

        String system = """
                You are an internal company knowledge assistant.
                Answer ONLY using the supplied CONTEXT.
                If the context does not contain enough information, say that you cannot answer from the knowledge base.
                Do not invent facts, names, dates, commands, policies, or procedures.
                Cite supporting sources inline as [1], [2], etc. Use only source numbers present in CONTEXT.
                Keep the answer concise and factual.
                """;

        String user = """
                CONTEXT:
                %s

                QUESTION:
                %s
                """.formatted(context, normalized);

        long generationStarted = System.nanoTime();
        try {
            String answer = chatClient.prompt()
                    .system(system)
                    .user(user)
                    .call()
                    .content();
            logger.info("Gemini response generated in {} ms",
                    (System.nanoTime() - generationStarted) / 1_000_000);
            if (answer == null || answer.isBlank()) {
                return new RagResult(
                        "The model returned an empty response. Please try again.",
                        citations, true, "SERVICE_ERROR");
            }
            return new RagResult(answer, citations, false, "ANSWERED");
        } catch (Exception e) {
            long durationMillis = (System.nanoTime() - generationStarted) / 1_000_000;
            if (hasHttpStatus(e, 503)) {
                logger.warn("Gemini is overloaded (HTTP 503); request failed after {} ms", durationMillis);
                return new RagResult(
                        "Gemini is experiencing high demand right now. Please try again shortly.",
                        citations, true, "SERVICE_ERROR");
            }
            if (hasHttpStatus(e, 404)) {
                logger.warn("Configured Gemini chat model is unavailable (HTTP 404); request failed after {} ms",
                        durationMillis, e);
                return new RagResult(
                        "The configured Gemini chat model is unavailable. Set GEMINI_CHAT_MODEL to a model supported by your API key and restart the application.",
                        citations, true, "SERVICE_ERROR");
            }
            logger.error("Gemini chat request failed after {} ms", durationMillis, e);
            return new RagResult(
                    "The AI service is temporarily unavailable. Please try again.",
                    citations, true, "SERVICE_ERROR");
        }
    }

    static boolean hasHttpStatus(Throwable error, int expectedStatus) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof ApiException apiException && apiException.code() == expectedStatus) {
                return true;
            }
        }
        return false;
    }

    public record Citation(int number, String source, String chunk, String documentId, String excerpt) {}
    public record RagResult(String answer, List<Citation> citations, boolean refused, String status) {
        public RagResult(String answer, List<Citation> citations, boolean refused) {
            this(answer, citations, refused, refused ? "NO_SOURCES" : "ANSWERED");
        }
    }
}
