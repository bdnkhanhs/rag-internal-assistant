package com.example.rag.domain;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "chat_exchanges")
public class ChatExchange {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "conversation_id", nullable = false)
    private ChatConversation conversation;

    @Column(nullable = false, columnDefinition = "CLOB")
    private String question;

    @Column(nullable = false, columnDefinition = "CLOB")
    private String answer;

    @Column(nullable = false, length = 32)
    private String status;

    @Column(nullable = false, columnDefinition = "CLOB")
    private String citationsJson;

    @Column(length = 16)
    private String feedback;

    @Column(nullable = false)
    private Instant createdAt;

    protected ChatExchange() {}

    public ChatExchange(
            ChatConversation conversation, String question, String answer, String status, String citationsJson) {
        this.conversation = conversation;
        this.question = question;
        this.answer = answer;
        this.status = status;
        this.citationsJson = citationsJson;
        this.createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public ChatConversation getConversation() { return conversation; }
    public String getQuestion() { return question; }
    public String getAnswer() { return answer; }
    public String getStatus() { return status; }
    public String getCitationsJson() { return citationsJson; }
    public String getFeedback() { return feedback; }

    public void setFeedback(String feedback) { this.feedback = feedback; }
}
