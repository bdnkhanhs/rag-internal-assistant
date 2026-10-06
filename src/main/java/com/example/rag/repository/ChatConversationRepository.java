package com.example.rag.repository;

import com.example.rag.domain.ChatConversation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ChatConversationRepository extends JpaRepository<ChatConversation, String> {
    List<ChatConversation> findAllByUsernameOrderByUpdatedAtDesc(String username);
    Optional<ChatConversation> findByIdAndUsername(String id, String username);
}
