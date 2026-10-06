package com.example.rag.service;

import com.example.rag.domain.ChatConversation;
import com.example.rag.domain.ChatExchange;
import com.example.rag.repository.ChatConversationRepository;
import com.example.rag.repository.ChatExchangeRepository;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ChatServiceTest {
    private final ChatConversationRepository conversations = mock(ChatConversationRepository.class);
    private final ChatExchangeRepository exchanges = mock(ChatExchangeRepository.class);
    private final RagService rag = mock(RagService.class);
    private final ChatService service = new ChatService(conversations, exchanges, rag);

    @Test
    void savesQuestionAnswerAndCitationsToAConversation() {
        when(conversations.save(any(ChatConversation.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(exchanges.save(any(ChatExchange.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(rag.ask("Where is the handbook?")).thenReturn(new RagService.RagResult(
                "In the portal [1].",
                List.of(new RagService.Citation(1, "Handbook", "2", "7", "Open the portal.")),
                false,
                "ANSWERED"));

        ChatExchange exchange = service.ask("alice", null, "Where is the handbook?");

        assertEquals("Where is the handbook?", exchange.getConversation().getTitle());
        assertEquals("ANSWERED", exchange.getStatus());
        assertEquals("Handbook", service.parseCitations(exchange.getCitationsJson()).getFirst().source());
        verify(conversations).save(any(ChatConversation.class));
        verify(exchanges).save(any(ChatExchange.class));
    }

    @Test
    void doesNotAllowAskingInAnotherUsersConversation() {
        when(conversations.findByIdAndUsername("private-chat", "alice")).thenReturn(Optional.empty());

        assertThrows(ResponseStatusException.class,
                () -> service.ask("alice", "private-chat", "Show me the other user's chat"));

        verifyNoInteractions(rag);
        verifyNoInteractions(exchanges);
    }

    @Test
    void deletesOwnedConversationAndItsExchanges() {
        ChatConversation conversation = new ChatConversation("alice");
        when(conversations.findByIdAndUsername(conversation.getId(), "alice"))
                .thenReturn(Optional.of(conversation));

        service.deleteConversation("alice", conversation.getId());

        verify(exchanges).deleteAllByConversation_Id(conversation.getId());
        verify(conversations).delete(conversation);
    }

    @Test
    void doesNotDeleteAnotherUsersConversation() {
        when(conversations.findByIdAndUsername("private-chat", "alice")).thenReturn(Optional.empty());

        assertThrows(ResponseStatusException.class,
                () -> service.deleteConversation("alice", "private-chat"));

        verifyNoInteractions(exchanges);
    }
}
