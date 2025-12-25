package com.example.demo.ai;

import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.service.AiServices;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * AI 对话服务（后端聚合层）。
 * <p>
 * 职责：
 * <ul>
 *   <li>构建 LangChain4j 的 {@link LibraryAssistant}（绑定模型、工具、会话记忆）。</li>
 *   <li>确保每次对话有稳定的 sessionId（用于 AI 记忆与“续写补全”）。</li>
 *   <li>为已登录用户拼接“历史上下文预热”（来自 {@code ChatContextService}）。</li>
 *   <li>对模型输出做清洗（去掉可能回显的 UUID / sessionId），并在检测到截断时尝试二次补全。</li>
 *   <li>为 SSE 提供“伪流式”输出：先生成完整回复，再逐字符推送给前端。</li>
 * </ul>
 *
 * 说明：本类只做“编排”，真正的业务查询通过 {@link MapperTools} 作为工具由模型触发调用。
 */
@Service
@ConditionalOnProperty(name = "ai.enabled", havingValue = "true")
public class LibraryAiService {

    private final LibraryAssistant assistant;
    private final com.example.demo.service.ChatContextService chatContextService;

    public LibraryAiService(ChatLanguageModel chatLanguageModel, MapperTools mapperTools, com.example.demo.service.ChatContextService chatContextService) {
        // 为每个 sessionId 创建一个“窗口记忆”（最多保留最近 N 条消息）。
        // 注意：这里的记忆是模型侧的对话记忆，与 ChatContextService 的 Redis/DB 上下文是两条不同链路。
        ChatMemoryProvider memoryProvider = sessionId -> MessageWindowChatMemory.withMaxMessages(20);
        this.assistant = AiServices.builder(LibraryAssistant.class)
                .chatLanguageModel(chatLanguageModel)
                .tools(List.of(mapperTools))
                .chatMemoryProvider(memoryProvider)
                .build();
        this.chatContextService = chatContextService;
    }

    /**
     * 保证 sessionId 非空。
     * <p>
     * sessionId 一方面作为 LangChain4j MemoryId（维持同会话记忆），
     * 另一方面用于本服务的“续写补全”与“回显清洗”。
     */
    public String ensureSessionId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return UUID.randomUUID().toString();
        }
        return sessionId;
    }

    /**
     * 同步对话：单次请求返回完整文本。
     * <p>
     * 流程：补全 sessionId → 拼接上下文预热 → 调用模型 → 清洗输出 → 如疑似截断则二次补全并合并。
     */
    public String chat(String sessionId, String message, Integer userId) {
        String sid = ensureSessionId(sessionId);
        String effectiveMessage = prepareWithPreheat(userId, message);
        String full = assistant.chat(sid, effectiveMessage);
        full = cleanReply(full, sid);
        if (!isComplete(full)) {
            // 追加补全提示，防止截断（二次调用）
            String continuationPrompt = buildContinuationPrompt(full);
            try {
                String extra = assistant.chat(sid, continuationPrompt);
                extra = cleanReply(extra, sid);
                full = mergeContinuations(full, extra);
            } catch (Exception e) {
                // 忽略补全失败，返回原始文本
            }
        }
        return full;
    }

    /**
     * SSE “伪流式”对话。
     * <p>
     * 由于上游模型/SDK 未在这里直接做 token 流式回调，本项目采用：
     * 先同步生成完整回复，再在单独线程按 code point 逐字推送。
     * <p>
     * SSE 事件名约定：
     * <ul>
     *   <li>session：服务端最终使用的 sessionId</li>
     *   <li>token：逐字/逐片段输出</li>
     *   <li>done：完整文本</li>
     *   <li>error：异常信息</li>
     * </ul>
     */
    public void simulatedStreamChat(String sessionId, String message,
                                    java.util.function.Consumer<String> sessionEventConsumer,
                                    java.util.function.Consumer<String> tokenConsumer,
                                    java.util.function.Consumer<String> completeConsumer,
                                    java.util.function.Consumer<Throwable> errorConsumer,
                                    Integer userId) {
        String sid = ensureSessionId(sessionId);
        sessionEventConsumer.accept(sid);
        // 同步生成完整回复（先预热上下文）
        String effectiveMessage = prepareWithPreheat(userId, message);
        String generated = assistant.chat(sid, effectiveMessage);
        String cleaned = cleanReply(generated, sid);
        if (!isComplete(cleaned)) {
            String continuationPrompt = buildContinuationPrompt(cleaned);
            try {
                String extra = assistant.chat(sid, continuationPrompt);
                String extraClean = cleanReply(extra, sid);
                cleaned = mergeContinuations(cleaned, extraClean);
            } catch (Exception ignore) {}
        }
        final String full = cleaned;
        new Thread(() -> {
            try {
                // 按 Unicode code point 逐字输出，过滤器可自定义修改字符
                int[] cps = full.codePoints().toArray();
                StringBuilder builder = new StringBuilder();
                for (int i = 0; i < cps.length; i++) {
                    String ch = new String(Character.toChars(cps[i]));
                    ch = filterChar(ch, builder);
                    if (ch.isEmpty()) {
                        continue; // 跳过被过滤掉的字符
                    }
                    builder.append(ch);
                    tokenConsumer.accept(ch); // 单字发送
                    Thread.sleep(25); // 控制节奏，降低卡顿感
                }
                completeConsumer.accept(builder.toString());
            } catch (Throwable e) {
                errorConsumer.accept(e);
            }
        }, "ai-stream-" + sid).start();
    }

    // 从 ChatContextService 读取历史并生成一个不可见的前缀，用于模型预热。
    // 目标：让模型“理解用户之前聊过什么”，但又尽量避免模型把历史原样复述出来。
    private String prepareWithPreheat(Integer userId, String message) {
        if (userId == null) return message;
        try {
            String ctx = chatContextService.getContextJson(userId);
            if (ctx == null || ctx.isBlank()) return message;
            // 解析 JSON，提取最近若干条文本作为上下文预热（只取角色与内容）
            com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
            java.util.List<java.util.Map<String, Object>> msgs = om.readValue(ctx, new com.fasterxml.jackson.core.type.TypeReference<>() {});
            StringBuilder sb = new StringBuilder();
            sb.append("[历史上下文（仅供模型理解，请勿在回答中原样复述）]\n");
            int taken = 0;
            for (int i = Math.max(0, msgs.size() - 20); i < msgs.size(); i++) {
                java.util.Map<String, Object> m = msgs.get(i);
                Object role = m.getOrDefault("role", "");
                Object content = m.getOrDefault("content", "");
                sb.append("[").append(role).append("]:").append(content).append("\n");
                taken++;
                if (sb.length() > 2000) break; // 限制前缀长度
            }
            sb.append("--- 以上为预热上下文结束，请据此理解用户背景后继续回答。\n用户消息:\n");
            sb.append(message == null ? "" : message);
            return sb.toString();
        } catch (Exception e) {
            return message;
        }
    }

    /**
     * 简单字符过滤：
     * 1. 去除回车符 \r
     * 2. 合并连续空格为单个空格（不影响中文标点）
     * 3. 保留换行但避免出现超过2个连续换行
     */
    private String filterChar(String ch, StringBuilder current) {
        if ("\r".equals(ch)) return "";
        // 合并多余空格
        if (" ".equals(ch)) {
            int len = current.length();
            if (len > 0 && current.charAt(len - 1) == ' ') {
                return ""; // 跳过重复空格
            }
        }
        // 限制连续换行
        if ("\n".equals(ch)) {
            int len = current.length();
            int nlCount = 0;
            for (int i = len - 1; i >= 0 && i >= len - 4; i--) { // 最多检查最近4字符
                if (current.charAt(i) == '\n') nlCount++; else break;
            }
            if (nlCount >= 2) { // 已经有2个连续换行了
                return "";
            }
        }
        return ch;
    }

    // 如果回复以 sessionId 或任意 UUID 起始，剥离它以及后续的空白。
    // 原因：某些模型/链路可能会回显 MemoryId 或内部跟踪 id，影响用户阅读。
    private String stripLeadingUuid(String text, String sid) {
        if (text == null) return "";
        String trimmed = text.trim();
        // 先匹配精确的当前 sid
        if (trimmed.startsWith(sid)) {
            return trimmed.substring(sid.length()).stripLeading();
        }
        // 再尝试任意 UUID 正则
        if (trimmed.matches("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}.*")) {
            return trimmed.replaceFirst("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\s*", "");
        }
        return text;
    }

    // 进一步清洗：头部 UUID + 任何内嵌的 sid/UUID（避免模型回声）。
    // 注意：这里是“显示层清洗”，不会影响 ChatContextService 的原始存储。
    private String cleanReply(String text, String sid) {
        if (text == null) return "";
        String original = text;
        String noHead = stripLeadingUuid(original, sid);
        // 若模型回显 sid，全部移除
        if (sid != null && !sid.isBlank()) {
            noHead = noHead.replace(sid, "");
        }
        // 通用 UUID 模式移除（可能有多个）
        noHead = noHead.replaceAll("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}", "");
        // 有些模型可能把 UUID 拆开或追加中文字符立刻跟随，这里再用前缀宽松匹配截掉开头的 UUID 字符串集合
        noHead = noHead.replaceFirst("^[0-9a-fA-F-]{36,}\s*", "");
        String cleaned = noHead.strip();
        // Debug 日志（可根据需要删除）
        if (cleaned.length() != original.length()) {
            System.out.println("[AI CLEAN] before='" + original + "' after='" + cleaned + "'");
        }
        return cleaned;
    }

    // 判断文本是否“完整”：末尾有句号/叹号/问号/中文句号，且无明显被截断的 Markdown 表格行
    private boolean isComplete(String txt) {
        if (txt == null || txt.isBlank()) return true; // 空视为完整，避免无限补全
        String t = txt.trim();
        if (t.length() < 40) return true; // 短文本不做补全
        boolean endsWithPunct = t.endsWith("。") || t.endsWith("！") || t.endsWith("！") || t.endsWith("?") || t.endsWith("？") || t.endsWith(".");
        // 检查最后一行是否是未闭合的表格行（包含 '|' 但没有换行终止标点且列数明显不足）
        String[] lines = t.split("\n");
        String lastLine = lines[lines.length - 1].trim();
        boolean probableTableFragment = lastLine.contains("|") && lastLine.length() < 15 && !endsWithPunct;
        return endsWithPunct && !probableTableFragment;
    }

    // 构造补全提示：要求继续并以总结句结束
    private String buildContinuationPrompt(String current) {
        String tail = excerptTail(current, 180);
        return "请继续补完整上文被截断的内容，延续语义并给出一个简洁的总结句。不要重复已出现的句子。上文片段：" + tail;
    }

    private String excerptTail(String txt, int maxChars) {
        if (txt == null) return "";
        int len = txt.length();
        if (len <= maxChars) return txt;
        return txt.substring(len - maxChars);
    }

    // 合并主文本与补全文：避免重复，简单去重末尾开始处的重复段落
    private String mergeContinuations(String base, String extra) {
        if (extra == null || extra.isBlank()) return base;
        String trimmedExtra = extra.trim();
        if (trimmedExtra.isEmpty()) return base;
        // 如果补全文已经被包含则直接返回
        if (base.endsWith(trimmedExtra) || base.contains(trimmedExtra)) return base;
        // 去除补全文中可能重复的开头（与 base 末尾重叠）
        int overlap = findOverlap(base, trimmedExtra);
        return overlap > 0 ? base + trimmedExtra.substring(overlap) : base + (base.endsWith("\n") ? "" : "\n") + trimmedExtra;
    }

    private int findOverlap(String base, String extra) {
        int max = Math.min(base.length(), extra.length());
        for (int i = max; i > 20; i--) { // 只检测较长的重叠，降低误判
            String tail = base.substring(base.length() - i);
            if (extra.startsWith(tail)) return i;
        }
        return 0;
    }
}
