package com.example.rag.controller;

import com.example.rag.domain.ChatConversation;
import com.example.rag.domain.ChatExchange;
import com.example.rag.domain.KnowledgeDocument;
import com.example.rag.service.ChatService;
import com.example.rag.service.DocumentService;
import com.example.rag.service.RagService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Controller
public class PageController {
    private static final Logger logger = LoggerFactory.getLogger(PageController.class);

    private final DocumentService documents;
    private final ChatService chats;

    public PageController(DocumentService documents, ChatService chats) {
        this.documents = documents;
        this.chats = chats;
    }

    @GetMapping({"/", "/dashboard"})
    String dashboard(
            @RequestParam(required = false) String conversationId,
            @RequestParam(defaultValue = "false") boolean scrollToLatest,
            Model model,
            Authentication authentication) {
        List<ChatConversation> conversations = chats.listConversations(authentication.getName());
        String selectedId = conversationId;
        if (selectedId == null && !conversations.isEmpty()) {
            selectedId = conversations.getFirst().getId();
        }
        List<ChatExchange> exchanges = selectedId == null
                ? List.of()
                : chats.getExchanges(selectedId, authentication.getName());
        List<ChatTurn> turns = exchanges.stream()
                .map(exchange -> new ChatTurn(exchange, chats.parseCitations(exchange.getCitationsJson())))
                .toList();

        model.addAttribute("username", authentication.getName());
        model.addAttribute("isAdmin", isAdmin(authentication));
        model.addAttribute("documents", documents.findAll());
        model.addAttribute("conversations", conversations);
        model.addAttribute("conversationId", selectedId);
        model.addAttribute("turns", turns);
        model.addAttribute("scrollToLatest", scrollToLatest);
        return "dashboard";
    }

    @GetMapping("/login")
    String login() { return "login"; }

    @GetMapping("/403")
    String forbidden() { return "403"; }

    @GetMapping("/admin/documents")
    String adminDocuments(
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            Model model) {
        addDocumentList(model, q, page);
        model.addAttribute("query", q == null ? "" : q);
        return "admin-documents";
    }

    @PostMapping("/admin/documents")
    String upload(
            @RequestParam("file") MultipartFile file,
            RedirectAttributes redirectAttributes) {
        try {
            documents.ingest(file);
            redirectAttributes.addFlashAttribute("success", "Document indexed and searchable.");
        } catch (IllegalArgumentException e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        } catch (Exception e) {
            logger.error("Document upload/indexing failed", e);
            redirectAttributes.addFlashAttribute(
                    "error", "The document could not be indexed. Check the file and try again.");
        }
        return "redirect:/admin/documents";
    }

    @PostMapping("/admin/documents/{id}/reindex")
    String reindex(@PathVariable Long id, RedirectAttributes redirectAttributes) {
        try {
            documents.reindex(id);
            redirectAttributes.addFlashAttribute("success", "Document index refreshed.");
        } catch (IllegalArgumentException e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        } catch (Exception e) {
            logger.error("Document re-indexing failed for document {}", id, e);
            redirectAttributes.addFlashAttribute("error", "The document could not be re-indexed.");
        }
        return "redirect:/admin/documents";
    }

    @PostMapping("/admin/documents/{id}/delete")
    String delete(@PathVariable Long id, RedirectAttributes redirectAttributes) {
        try {
            documents.delete(id);
            redirectAttributes.addFlashAttribute("success", "Document and its search index were removed.");
        } catch (Exception e) {
            logger.error("Document deletion failed for document {}", id, e);
            redirectAttributes.addFlashAttribute("error", "The document could not be removed.");
        }
        return "redirect:/admin/documents";
    }

    @GetMapping("/documents/{id}/source")
    ResponseEntity<Resource> source(@PathVariable Long id) {
        KnowledgeDocument document = documents.findDocument(id);
        Resource source = documents.getSource(id);
        String contentType = "application/octet-stream";
        try {
            String detected = Files.probeContentType(Path.of(document.getStoredPath()));
            if (detected != null) {
                contentType = detected;
            }
        } catch (Exception e) {
            logger.warn("Could not detect content type for document {}", id, e);
        }
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contentType))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline()
                        .filename(document.getOriginalFilename(), StandardCharsets.UTF_8)
                        .build()
                        .toString())
                .body(source);
    }

    @PostMapping("/chat")
    String chat(
            @RequestParam(required = false) String conversationId,
            @RequestParam("question") String question,
            Authentication authentication) {
        ChatExchange exchange = chats.ask(authentication.getName(), conversationId, question);
        return "redirect:/dashboard?conversationId=" + exchange.getConversation().getId() + "&scrollToLatest=true";
    }

    @PostMapping("/chat/new")
    String newConversation(Authentication authentication) {
        ChatConversation conversation = chats.createConversation(authentication.getName());
        return "redirect:/dashboard?conversationId=" + conversation.getId();
    }

    @PostMapping("/chat/{conversationId}/delete")
    String deleteConversation(
            @PathVariable String conversationId,
            Authentication authentication) {
        chats.deleteConversation(authentication.getName(), conversationId);
        return "redirect:/dashboard";
    }

    @PostMapping("/chat/feedback")
    String feedback(
            @RequestParam Long exchangeId,
            @RequestParam String feedback,
            Authentication authentication) {
        String conversationId = chats.saveFeedback(authentication.getName(), exchangeId, feedback);
        return "redirect:/dashboard?conversationId=" + conversationId;
    }

    private void addDocumentList(Model model, String query, int pageNumber) {
        Page<KnowledgeDocument> resultPage =
                documents.search(
                        query,
                        PageRequest.of(
                                Math.max(0, pageNumber),
                                10,
                                Sort.by(Sort.Direction.DESC, "createdAt")));
        List<KnowledgeDocument> found = resultPage.getContent();
        Map<Long, Long> sizes = new HashMap<>();
        for (KnowledgeDocument document : found) {
            sizes.put(document.getId(), documents.getFileSize(document));
        }
        model.addAttribute("documents", found);
        model.addAttribute("fileSizes", sizes);
        model.addAttribute("documentPage", resultPage);
    }

    private boolean isAdmin(Authentication authentication) {
        return authentication.getAuthorities().stream()
                .anyMatch(authority -> authority.getAuthority().equals("ROLE_ADMIN"));
    }

    public record ChatTurn(ChatExchange exchange, List<RagService.Citation> citations) {}
}
