package com.example.rag.config;

import org.junit.jupiter.api.Test;
import org.springframework.ai.google.genai.embedding.GoogleGenAiEmbeddingConnectionDetails;
import org.springframework.ai.model.google.genai.autoconfigure.chat.GoogleGenAiConnectionProperties;
import org.springframework.ai.model.google.genai.autoconfigure.embedding.GoogleGenAiEmbeddingConnectionProperties;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenAiClientConfigurationTest {
    @Test
    void explainsHowToConfigureMissingGenAiCredentials() {
        GenAiClientConfiguration configuration = new GenAiClientConfiguration();
        GoogleGenAiEmbeddingConnectionProperties properties = new GoogleGenAiEmbeddingConnectionProperties();

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> configuration.googleGenAiEmbeddingClient(properties));

        assertTrue(error.getMessage().contains("GEMINI_API_KEY"));
        assertTrue(error.getMessage().contains("Vertex AI"));
    }

    @Test
    void sharesTimeoutConfiguredClientWithEmbeddingModel() throws Exception {
        GenAiClientConfiguration configuration = new GenAiClientConfiguration();
        GoogleGenAiConnectionProperties chatProperties = new GoogleGenAiConnectionProperties();
        chatProperties.setApiKey("test-chat-api-key");
        GoogleGenAiEmbeddingConnectionProperties embeddingProperties =
                new GoogleGenAiEmbeddingConnectionProperties();
        embeddingProperties.setApiKey("test-embedding-api-key");

        try (var client = configuration.googleGenAiClient(chatProperties);
             var embeddingClient = configuration.googleGenAiEmbeddingClient(embeddingProperties)) {

            assertNotNull(client);
            GoogleGenAiEmbeddingConnectionDetails details =
                    configuration.googleGenAiEmbeddingConnectionDetails(embeddingProperties, embeddingClient);

            assertSame(embeddingClient, details.getGenAiClient());
        }
    }
}
