package com.example.rag.repository;

import com.example.rag.domain.KnowledgeDocument;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;

public interface KnowledgeDocumentRepository extends JpaRepository<KnowledgeDocument, Long> {
    List<KnowledgeDocument> findAllByOrderByCreatedAtDesc();

    Page<KnowledgeDocument> findAllByTitleContainingIgnoreCaseOrOriginalFilenameContainingIgnoreCase(
            String title, String originalFilename, Pageable pageable);
}
