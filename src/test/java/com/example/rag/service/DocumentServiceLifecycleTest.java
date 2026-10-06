package com.example.rag.service;

import com.example.rag.domain.KnowledgeDocument;
import com.example.rag.repository.KnowledgeDocumentRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.vectorstore.VectorStore;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DocumentServiceLifecycleTest {
    @TempDir
    Path tempDir;

    @Test
    void deletingDocumentRemovesItsStoredVectorIdsAndOriginalFile() throws Exception {
        Path storedFile = Files.writeString(tempDir.resolve("policy.txt"), "Internal policy");
        KnowledgeDocument document = new KnowledgeDocument("Policy", "policy.txt", storedFile.toString());
        setId(document, 42L);
        document.setVectorIds("chunk-a\nchunk-b");

        KnowledgeDocumentRepository repository = mock(KnowledgeDocumentRepository.class);
        VectorStore vectorStore = mock(VectorStore.class);
        when(repository.findById(42L)).thenReturn(Optional.of(document));
        DocumentService service = new DocumentService(repository, vectorStore);

        service.delete(42L);

        verify(vectorStore).delete(List.of("chunk-a", "chunk-b"));
        verify(repository).delete(document);
        assertFalse(Files.exists(storedFile));
    }

    private static void setId(KnowledgeDocument document, Long id) throws Exception {
        Field field = KnowledgeDocument.class.getDeclaredField("id");
        field.setAccessible(true);
        field.set(document, id);
    }
}
