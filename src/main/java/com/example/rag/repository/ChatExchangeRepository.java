package com.example.rag.repository;

import com.example.rag.domain.ChatExchange;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ChatExchangeRepository extends JpaRepository<ChatExchange, Long> {
    List<ChatExchange> findAllByConversation_IdOrderByCreatedAtAsc(String conversationId);
    void deleteAllByConversation_Id(String conversationId);

    @Query("select e from ChatExchange e where e.id = :id and e.conversation.username = :username")
    Optional<ChatExchange> findOwnedExchange(@Param("id") Long id, @Param("username") String username);
}
