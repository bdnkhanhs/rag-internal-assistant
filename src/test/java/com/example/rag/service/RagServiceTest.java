package com.example.rag.service;

import com.google.genai.errors.ServerException;
import com.google.genai.errors.ClientException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RagServiceTest {
    private final VectorStore vectorStore = mock(VectorStore.class);
    private final ChatClient.Builder chatClientBuilder = mock(ChatClient.Builder.class);
    private final ChatClient chatClient = mock(ChatClient.class);
    private RagService service;

    @BeforeEach
    void setUp() {
        when(chatClientBuilder.build()).thenReturn(chatClient);
        service = new RagService(vectorStore, chatClientBuilder);
    }

    @Test
    void refusesWithoutCallingChatModelWhenNoRelevantChunksAreFound() {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());

        RagService.RagResult result = service.ask("unrelated question");

        assertTrue(result.refused());
        verify(vectorStore).similaritySearch(any(SearchRequest.class));
        verifyNoInteractions(chatClient);
    }

    @Test
    void identifiesGeminiServiceUnavailableErrorsNestedInSpringAiExceptions() {
        RuntimeException failure = new RuntimeException(
                "Failed to generate content",
                new ServerException(503, "UNAVAILABLE", "Model is experiencing high demand"));

        assertTrue(RagService.hasHttpStatus(failure, 503));
        assertFalse(RagService.hasHttpStatus(failure, 429));
    }

    @Test
    void identifiesUnavailableGeminiModelsAsNotFoundErrors() {
        RuntimeException failure = new RuntimeException(
                "Failed to generate content",
                new ClientException(404, "NOT_FOUND", "Model is unavailable"));

        assertTrue(RagService.hasHttpStatus(failure, 404));
        assertFalse(RagService.hasHttpStatus(failure, 503));
    }
}
