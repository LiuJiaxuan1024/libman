package com.example.demo.ai;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;

/**
 * AI 相关 Bean 配置。
 * <p>
 * 该项目使用 LangChain4j，并通过 OpenAI 兼容接口接入 DeepSeek。
 * 只有在 {@code ai.enabled=true} 时才会装配这些 Bean（避免在未配置 Key 时启动失败）。
 * <p>
 * 配置来源：优先环境变量，其次 application.properties 默认值。
 */
@Configuration
@ConditionalOnProperty(name = "ai.enabled", havingValue = "true")
public class AiConfig {

    @Value("${DEEPSEEK_API_KEY:}")
    private String deepseekApiKey;

    // 可通过环境变量覆盖，默认 DeepSeek OpenAI 兼容地址
    @Value("${DEEPSEEK_BASE_URL:https://api.deepseek.com/v1}")
    private String deepseekBaseUrl;

    // 模型名称可通过环境变量覆盖
    @Value("${DEEPSEEK_MODEL:deepseek-chat}")
    private String modelName;

    // 模型温度（创意度），范围通常 0.0 - 1.0，可在 application.properties 里用 ai.temperature=0.9 调整
    @Value("${ai.temperature:0.2}")
    private double temperature;

    // 最大生成 token 数（防止回答被截断，可适当提高）。具体上限受模型和账户限制。
    @Value("${ai.max-tokens:4096}")
    private Integer maxTokens;

    @Bean
    public ChatLanguageModel chatLanguageModel() {
        // 启用 AI 功能时，必须提供 API Key；否则直接报错，避免运行中出现更隐蔽的问题。
        if (deepseekApiKey == null || deepseekApiKey.isBlank()) {
            throw new IllegalStateException("启用 ai.enabled=true 时必须提供 DEEPSEEK_API_KEY 环境变量");
        }
        return OpenAiChatModel.builder()
                .apiKey(deepseekApiKey)
                .baseUrl(deepseekBaseUrl)
                .modelName(modelName)
            .temperature(temperature)
            .maxTokens(maxTokens)
                .timeout(java.time.Duration.ofSeconds(60))
                .build();
    }

    @Bean
    public StreamingChatLanguageModel streamingChatLanguageModel() {
        // 供“流式”接口使用（本项目后端采用“伪流式”：先同步生成全文，再逐字 SSE 推送）。
        if (deepseekApiKey == null || deepseekApiKey.isBlank()) {
            throw new IllegalStateException("启用 ai.enabled=true 时必须提供 DEEPSEEK_API_KEY 环境变量");
        }
        return OpenAiStreamingChatModel.builder()
                .apiKey(deepseekApiKey)
                .baseUrl(deepseekBaseUrl)
                .modelName(modelName)
            .temperature(temperature)
            .maxTokens(maxTokens)
                .timeout(java.time.Duration.ofSeconds(60))
                .build();
    }
}
