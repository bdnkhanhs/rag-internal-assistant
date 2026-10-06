package com.example.rag.config;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.genai.Client;
import com.google.genai.types.HttpRetryOptions;
import com.google.genai.types.HttpOptions;
import org.springframework.ai.model.google.genai.autoconfigure.chat.GoogleGenAiConnectionProperties;
import org.springframework.ai.model.google.genai.autoconfigure.embedding.GoogleGenAiEmbeddingConnectionProperties;
import org.springframework.ai.google.genai.embedding.GoogleGenAiEmbeddingConnectionDetails;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.Resource;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.InputStream;

@Configuration(proxyBeanMethods = false)
public class GenAiClientConfiguration {
    private static final int REQUEST_TIMEOUT_MILLIS = 30_000;

    @Bean
    @Primary
    Client googleGenAiClient(GoogleGenAiConnectionProperties properties) throws IOException {
        return createClient(properties.getApiKey(), properties.getProjectId(), properties.getLocation(),
                properties.isVertexAi(), properties.getCredentialsUri());
    }

    @Bean
    Client googleGenAiEmbeddingClient(GoogleGenAiEmbeddingConnectionProperties properties) throws IOException {
        return createClient(properties.getApiKey(), properties.getProjectId(), properties.getLocation(),
                properties.isVertexAi(), properties.getCredentialsUri());
    }

    @Bean
    GoogleGenAiEmbeddingConnectionDetails googleGenAiEmbeddingConnectionDetails(
            GoogleGenAiEmbeddingConnectionProperties properties,
            @Qualifier("googleGenAiEmbeddingClient") Client embeddingClient) {
        GoogleGenAiEmbeddingConnectionDetails.Builder builder =
                GoogleGenAiEmbeddingConnectionDetails.builder().genAiClient(embeddingClient);
        if (StringUtils.hasText(properties.getApiKey())) {
            builder.apiKey(properties.getApiKey());
        }
        if (StringUtils.hasText(properties.getProjectId())) {
            builder.projectId(properties.getProjectId());
        }
        if (StringUtils.hasText(properties.getLocation())) {
            builder.location(properties.getLocation());
        }
        return builder.build();
    }

    private static Client createClient(
            String apiKey, String projectId, String location, boolean vertexAi,
            Resource credentialsUri) throws IOException {
        boolean hasApiKey = StringUtils.hasText(apiKey);
        boolean hasProjectAndLocation = StringUtils.hasText(projectId) && StringUtils.hasText(location);
        Client.Builder builder = Client.builder()
                .httpOptions(HttpOptions.builder()
                        .timeout(REQUEST_TIMEOUT_MILLIS)
                        .retryOptions(HttpRetryOptions.builder().attempts(1).build())
                        .build());

        if (vertexAi) {
            Assert.isTrue(hasProjectAndLocation,
                    "Vertex AI mode requires both project-id and location to be configured.");
            configureVertexAi(builder, projectId, location, credentialsUri);
        } else if (hasApiKey) {
            builder.apiKey(apiKey);
        } else if (hasProjectAndLocation) {
            configureVertexAi(builder, projectId, location, credentialsUri);
        } else {
            throw new IllegalStateException(
                    "Google GenAI credentials are missing. For Gemini API-key mode, set GEMINI_API_KEY "
                            + "in the same shell before starting the app (PowerShell: "
                            + "$env:GEMINI_API_KEY=\"your-key\"). For Vertex AI mode, configure "
                            + "spring.ai.google.genai.project-id and spring.ai.google.genai.location.");
        }

        return builder.build();
    }

    private static void configureVertexAi(
            Client.Builder builder, String projectId, String location,
            Resource credentialsUri) throws IOException {
        Assert.hasText(projectId, "Google GenAI project-id must be set for Vertex AI mode.");
        Assert.hasText(location, "Google GenAI location must be set for Vertex AI mode.");
        builder.project(projectId).location(location).vertexAI(true);

        if (credentialsUri != null) {
            try (InputStream credentials = credentialsUri.getInputStream()) {
                builder.credentials(GoogleCredentials.fromStream(credentials));
            }
        }
    }
}
