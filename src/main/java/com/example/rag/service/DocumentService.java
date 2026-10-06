package com.example.rag.service;

import com.example.rag.domain.KnowledgeDocument;
import com.example.rag.repository.KnowledgeDocumentRepository;
import org.apache.tika.Tika;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

@Service
public class DocumentService {
    private static final Logger logger = LoggerFactory.getLogger(DocumentService.class);
    private static final Path DATA_DIR = Path.of("data", "documents");
    private static final Path VECTOR_FILE = Path.of("data", "vectors.json");
    private static final int CHUNK_SIZE = 1400;
    private static final int OVERLAP = 200;

    private final KnowledgeDocumentRepository repository;
    private final VectorStore vectorStore;
    private final Tika tika = new Tika();

    public DocumentService(KnowledgeDocumentRepository repository, VectorStore vectorStore) {
        this.repository = repository;
        this.vectorStore = vectorStore;
        restoreVectorStore();
    }

    public List<KnowledgeDocument> findAll() {
        return repository.findAllByOrderByCreatedAtDesc();
    }

    public Page<KnowledgeDocument> search(String query, Pageable pageable) {
        if (query == null || query.isBlank()) {
            return repository.findAll(pageable);
        }
        String normalized = query.trim();
        return repository.findAllByTitleContainingIgnoreCaseOrOriginalFilenameContainingIgnoreCase(
                normalized, normalized, pageable);
    }

    public KnowledgeDocument ingest(MultipartFile file) throws IOException {
        if (file.isEmpty())
            throw new IllegalArgumentException("File is empty.");
        String filename = Optional.ofNullable(file.getOriginalFilename()).orElse("document.txt");
        String lower = filename.toLowerCase(Locale.ROOT);
        if (!(lower.endsWith(".txt") || lower.endsWith(".md") || lower.endsWith(".pdf"))) {
            throw new IllegalArgumentException("Only .txt, .md and .pdf are supported.");
        }

        Files.createDirectories(DATA_DIR);
        String id = UUID.randomUUID().toString();
        Path stored = DATA_DIR.resolve(id + "-" + sanitize(filename));
        try (InputStream upload = file.getInputStream()) {
            Files.copy(upload, stored, StandardCopyOption.REPLACE_EXISTING);
        }

        String text;

        try {
            text = extractText(stored, filename);
        } catch (org.apache.tika.exception.TikaException e) {
            Files.deleteIfExists(stored);
            throw new IllegalArgumentException(
                    "Unable to parse document: " + filename,
                    e);
        } catch (IOException e) {
            Files.deleteIfExists(stored);
            throw e;
        }

        text = text == null ? "" : text.trim();
        if (text.isBlank()) {
            Files.deleteIfExists(stored);
            throw new IllegalArgumentException("The document contains no readable text.");
        }

        KnowledgeDocument entity = repository.save(new KnowledgeDocument(
                stripExtension(filename), filename, stored.toString()));
        List<Document> chunks = createChunks(entity, filename, text);
        try {
            vectorStore.add(chunks);
            entity.setVectorIds(vectorIds(chunks));
            repository.save(entity);
            persistVectorStore();
        } catch (RuntimeException e) {
            try {
                vectorStore.delete(new Filter.Expression(
                        Filter.ExpressionType.EQ,
                        new Filter.Key("documentId"),
                        new Filter.Value(entity.getId().toString())));
            } catch (RuntimeException cleanupFailure) {
                e.addSuppressed(cleanupFailure);
            }
            repository.delete(entity);
            Files.deleteIfExists(stored);
            throw e;
        }
        return entity;
    }

    public void delete(Long id) throws IOException {
        KnowledgeDocument document = findDocument(id);
        deleteVectors(document);
        persistVectorStore();
        repository.delete(document);
        Files.deleteIfExists(Path.of(document.getStoredPath()));
    }

    public KnowledgeDocument reindex(Long id) throws IOException {
        KnowledgeDocument document = findDocument(id);
        Path stored = Path.of(document.getStoredPath());
        if (!Files.isRegularFile(stored)) {
            throw new IOException("The original uploaded file is missing.");
        }
        String text;
        try {
            text = extractText(stored, document.getOriginalFilename());
        } catch (org.apache.tika.exception.TikaException e) {
            throw new IllegalArgumentException("Unable to parse the stored document.", e);
        }
        text = text == null ? "" : text.trim();
        if (text.isBlank()) {
            throw new IllegalArgumentException("The document contains no readable text.");
        }
        List<Document> chunks = createChunks(document, document.getOriginalFilename(), text);
        if (document.getVectorIds() == null || document.getVectorIds().isBlank()) {
            deleteVectors(document);
        }
        vectorStore.add(chunks);
        if (document.getVectorIds() != null && !document.getVectorIds().isBlank()) {
            deleteVectors(document);
        }
        document.setVectorIds(vectorIds(chunks));
        persistVectorStore();
        return repository.save(document);
    }

    public Resource getSource(Long id) {
        KnowledgeDocument document = findDocument(id);
        Path path = Path.of(document.getStoredPath());
        if (!Files.isRegularFile(path)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return new FileSystemResource(path);
    }

    public KnowledgeDocument findDocument(Long id) {
        return repository.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    public long getFileSize(KnowledgeDocument document) {
        try {
            return Files.size(Path.of(document.getStoredPath()));
        } catch (IOException e) {
            logger.warn("Could not read stored document size for document {}", document.getId(), e);
            return 0;
        }
    }

    private List<Document> createChunks(KnowledgeDocument entity, String filename, String text) {
        List<Document> chunks = new ArrayList<>();
        int start = 0;
        int chunkNo = 1;
        while (start < text.length()) {
            int end = Math.min(text.length(), start + CHUNK_SIZE);
            String chunk = text.substring(start, end).trim();
            if (!chunk.isBlank()) {
                Map<String, Object> metadata = new HashMap<>();
                metadata.put("documentId", entity.getId().toString());
                metadata.put("source", entity.getTitle());
                metadata.put("filename", filename);
                metadata.put("chunk", chunkNo++);
                chunks.add(new Document(chunk, metadata));
            }
            if (end == text.length())
                break;
            start = Math.max(end - OVERLAP, start + 1);
        }
        return chunks;
    }

    private String vectorIds(List<Document> chunks) {
        List<String> ids = new ArrayList<>(chunks.size());
        for (Document chunk : chunks) {
            ids.add(chunk.getId());
        }
        return String.join("\n", ids);
    }

    private void deleteVectors(KnowledgeDocument document) {
        String storedIds = document.getVectorIds();
        if (storedIds != null && !storedIds.isBlank()) {
            vectorStore.delete(Arrays.asList(storedIds.split("\\R")));
        } else {
            vectorStore.delete(new Filter.Expression(
                    Filter.ExpressionType.EQ,
                    new Filter.Key("documentId"),
                    new Filter.Value(document.getId().toString())));
        }
    }

    String extractText(Path stored, String filename) throws IOException, org.apache.tika.exception.TikaException {
        String lower = filename.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".txt") || lower.endsWith(".md")) {
            return Files.readString(stored, StandardCharsets.UTF_8);
        }
        try (InputStream in = Files.newInputStream(stored)) {
            return tika.parseToString(in);
        }
    }

    public void restoreVectorStore() {
        try {
            if (vectorStore instanceof SimpleVectorStore simple && Files.exists(VECTOR_FILE)) {
                simple.load(VECTOR_FILE.toFile());
            }
        } catch (Exception ignored) {
            logger.error("Could not restore the vector store from {}", VECTOR_FILE, ignored);
        }
    }

    private void persistVectorStore() {
        try {
            Files.createDirectories(VECTOR_FILE.getParent());
            if (vectorStore instanceof SimpleVectorStore simple) {
                simple.save(VECTOR_FILE.toFile());
            }
        } catch (IOException e) {
            throw new IllegalStateException("Could not persist vector store", e);
        }
    }

    private static String sanitize(String filename) {
        return filename.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private static String stripExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot > 0 ? filename.substring(0, dot) : filename;
    }
}
