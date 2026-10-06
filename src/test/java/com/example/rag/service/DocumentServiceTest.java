package com.example.rag.service;

import com.example.rag.repository.KnowledgeDocumentRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.vectorstore.VectorStore;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

class DocumentServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void extractsUtf8TextFileContentDirectly() throws Exception {
        String content = "Internal RAG Demo Knowledge Base\nChính sách nội bộ.";
        Path file = tempDir.resolve("sample.txt");
        Files.writeString(file, content, StandardCharsets.UTF_8);
        DocumentService service = newService();

        assertEquals(content, service.extractText(file, file.getFileName().toString()));
    }

    @Test
    void extractsMarkdownContentDirectly() throws Exception {
        String content = "# Internal knowledge\n\nMarkdown **text**.";
        Path file = tempDir.resolve("sample.md");
        Files.writeString(file, content, StandardCharsets.UTF_8);
        DocumentService service = newService();

        assertEquals(content, service.extractText(file, file.getFileName().toString()));
    }

    private DocumentService newService() {
        return new DocumentService(mock(KnowledgeDocumentRepository.class), mock(VectorStore.class));
    }
}
