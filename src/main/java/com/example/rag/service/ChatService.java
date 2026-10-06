package com.example.rag.service;

import com.example.rag.domain.ChatConversation;
import com.example.rag.domain.ChatExchange;
import com.example.rag.repository.ChatConversationRepository;
import com.example.rag.repository.ChatExchangeRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@Service
public class ChatService {
    private final ChatConversationRepository conversations;
    private final ChatExchangeRepository exchanges;
    private final RagService rag;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ChatService(
            ChatConversationRepository conversations,
            ChatExchangeRepository exchanges,
            RagService rag) {
        this.conversations = conversations;
        this.exchanges = exchanges;
        this.rag = rag;
    }

    public List<ChatConversation> listConversations(String username) {
        return conversations.findAllByUsernameOrderByUpdatedAtDesc(username);
    }

    public ChatConversation createConversation(String username) {
        return conversations.save(new ChatConversation(username));
    }

    @Transactional
    public void deleteConversation(String username, String conversationId) {
        ChatConversation conversation = conversations.findByIdAndUsername(conversationId, username)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        exchanges.deleteAllByConversation_Id(conversationId);
        conversations.delete(conversation);
    }

    public ChatExchange ask(String username, String conversationId, String question) {
        ChatConversation conversation = conversationId == null || conversationId.isBlank()
                ? new ChatConversation(username)
                : conversations.findByIdAndUsername(conversationId, username)
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        String normalized = question == null ? "" : question.trim();
        RagService.RagResult result = rag.ask(question);
        conversation.updateFromQuestion(normalized.isBlank() ? "New conversation" : normalized);
        ChatConversation savedConversation = conversations.save(conversation);
        return exchanges.save(new ChatExchange(
                savedConversation,
                normalized,
                result.answer(),
                result.status(),
                writeCitations(result.citations())));
    }

    public List<ChatExchange> getExchanges(String conversationId, String username) {
        conversations.findByIdAndUsername(conversationId, username)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        return exchanges.findAllByConversation_IdOrderByCreatedAtAsc(conversationId);
    }

    @Transactional
    public String saveFeedback(String username, Long exchangeId, String feedback) {
        ChatExchange exchange = exchanges.findOwnedExchange(exchangeId, username)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!"HELPFUL".equals(feedback) && !"NOT_HELPFUL".equals(feedback)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        exchange.setFeedback(feedback);
        exchanges.save(exchange);
        return exchange.getConversation().getId();
    }

    public List<RagService.Citation> parseCitations(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not read saved answer sources", e);
        }
    }

    private String writeCitations(List<RagService.Citation> citations) {
        try {
            return objectMapper.writeValueAsString(citations);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not save answer sources", e);
        }
    }
}
