package com.example.bai4;

import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/chat")
@RequiredArgsConstructor
public class ChatController {
    private final ChatClient chatClient;

    @PostMapping
    public String chat(@RequestBody ChatRequest request) {
        String conversationId = request.conversationId();
        if (conversationId == null || conversationId.isBlank()) {
            conversationId = UUID.randomUUID().toString();
        }
        final String sessionId = conversationId;
        return chatClient
                .prompt()
                .user(request.message())
                .advisors(advisorSpec -> advisorSpec.param(
                        "chat_memory_conversation_id",
                        sessionId
                ))
                .call()
                .content();
    }
}
