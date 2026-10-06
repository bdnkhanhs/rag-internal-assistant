package com.example.rag.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "knowledge_documents")
public class KnowledgeDocument {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(nullable = false, length = 255)
    private String originalFilename;

    @Column(nullable = false, length = 500)
    private String storedPath;

    @Lob
    private String vectorIds = "";

    @Column(nullable = false)
    private Instant createdAt;

    protected KnowledgeDocument() {}

    public KnowledgeDocument(String title, String originalFilename, String storedPath) {
        this.title = title;
        this.originalFilename = originalFilename;
        this.storedPath = storedPath;
        this.createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getTitle() { return title; }
    public String getOriginalFilename() { return originalFilename; }
    public String getStoredPath() { return storedPath; }
    public Instant getCreatedAt() { return createdAt; }
    public String getVectorIds() { return vectorIds; }
    public void setVectorIds(String vectorIds) { this.vectorIds = vectorIds; }
}
