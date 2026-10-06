package com.example.rag.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "chat_conversations")
public class ChatConversation {
    @Id
    @Column(length = 36)
    private String id;

    @Column(nullable = false, length = 80)
    private String username;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected ChatConversation() {}

    public ChatConversation(String username) {
        this.id = UUID.randomUUID().toString();
        this.username = username;
        this.title = "New conversation";
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public String getId() { return id; }
    public String getUsername() { return username; }
    public String getTitle() { return title; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void updateFromQuestion(String question) {
        if ("New conversation".equals(title)) {
            title = question.length() > 80 ? question.substring(0, 77) + "..." : question;
        }
        updatedAt = Instant.now();
    }
}
