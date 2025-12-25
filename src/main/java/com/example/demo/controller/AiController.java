package com.example.demo.controller;

import com.example.demo.ai.LibraryAiService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import jakarta.servlet.http.HttpSession;
import com.example.demo.common.Constants;
import com.example.demo.service.ChatContextService;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
import java.util.HashMap;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@ConditionalOnProperty(name = "ai.enabled", havingValue = "true")
@RequestMapping("/ai")
/**
 * AI 对话接口。
 * <p>
 * - {@code POST /ai/chat}：同步返回完整回复。
 * - {@code POST /ai/chat/stream}：SSE 形式输出（事件：session/token/done/error）。
 * <p>
 * 上下文：如果用户已登录，会把 user/assistant 消息写入 {@link ChatContextService}，
 * 供下一轮对话在 {@link com.example.demo.ai.LibraryAiService} 中进行“上下文预热”。
 */
public class AiController {

    private final LibraryAiService aiService;
    private final ChatContextService chatContextService;

    public AiController(LibraryAiService aiService, ChatContextService chatContextService) {
        this.aiService = aiService;
        this.chatContextService = chatContextService;
    }

    @PostMapping("/chat")
    public Map<String, Object> chat(@RequestBody Map<String, Object> body, HttpSession session) {
        String sessionId = body == null ? null : String.valueOf(body.getOrDefault("sessionId", ""));
        String message = body == null ? null : String.valueOf(body.getOrDefault("message", ""));
        Map<String, Object> resp = new HashMap<>();
        if (message == null || message.isBlank()) {
            resp.put("status", "FAIL");
            resp.put("errorMessage", "message 不能为空");
            return resp;
        }
        Integer userId = extractUserId(session);
        if (userId != null) {
            chatContextService.appendMessage(userId, "user", message);
        }
        String ensured = aiService.ensureSessionId(sessionId);
        String reply = aiService.chat(ensured, message, userId);
        if (userId != null) {
            chatContextService.appendMessage(userId, "assistant", reply);
        }
        resp.put("status", "SUCCESS");
        resp.put("sessionId", ensured);
        resp.put("reply", reply);
        return resp;
    }

    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestBody Map<String, Object> body, HttpSession session) {
        String sessionId = body == null ? null : String.valueOf(body.getOrDefault("sessionId", ""));
        String message = body == null ? null : String.valueOf(body.getOrDefault("message", ""));
        SseEmitter emitter = new SseEmitter(0L); // 不超时：由前端自行关闭连接
        if (message == null || message.isBlank()) {
            try {
                emitter.send(SseEmitter.event().name("error").data("message 不能为空"));
                emitter.complete();
            } catch (Exception ignored) {}
            return emitter;
        }
        Integer userId = extractUserId(session);
        if (userId != null) {
            chatContextService.appendMessage(userId, "user", message);
        }
        aiService.simulatedStreamChat(sessionId, message,
                sid -> safeSend(emitter, SseEmitter.event().name("session").data(sid)),
                token -> safeSend(emitter, SseEmitter.event().name("token").data(token)),
                full -> {
                    if (userId != null) {
                        chatContextService.appendMessage(userId, "assistant", full);
                    }
                    safeSend(emitter, SseEmitter.event().name("done").data(full));
                    emitter.complete();
                },
                err -> {
                    safeSend(emitter, SseEmitter.event().name("error").data(err.getMessage() == null ? err.toString() : err.getMessage()));
                    emitter.complete();
                }
                , userId
        );
        return emitter;
    }

    private void safeSend(SseEmitter emitter, SseEmitter.SseEventBuilder event) {
        try {
            emitter.send(event);
        } catch (Exception e) {
            emitter.completeWithError(e);
        }
    }

    private Integer extractUserId(HttpSession session) {
        if (session == null) return null;
        Object obj = session.getAttribute(Constants.SESSION_USER_KEY);
        if (obj instanceof com.example.demo.model.UserInfo u) {
            return u.getId();
        }
        return null;
    }
}
