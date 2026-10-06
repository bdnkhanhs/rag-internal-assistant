package com.example.rag.controller;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:rag-ui-tests;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.ai.google.genai.api-key=test-key",
        "spring.ai.google.genai.embedding.api-key=test-key"
})
class PageControllerTest {
    @Autowired
    private WebApplicationContext context;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    @WithMockUser(username = "alice", roles = "USER")
    void rendersTheConversationAssistantForSignedInUsers() throws Exception {
        mvc.perform(get("/dashboard"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("What would you like to know?")))
                .andExpect(content().string(containsString("Recent chats")));
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void rendersDocumentAdministrationForAdmins() throws Exception {
        mvc.perform(get("/admin/documents"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Knowledge documents")))
                .andExpect(content().string(containsString(">Upload</button>")));
    }
}
