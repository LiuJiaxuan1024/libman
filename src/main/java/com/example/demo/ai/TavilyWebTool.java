package com.example.demo.ai;

import dev.langchain4j.agent.tool.Tool;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Tavily Web Search 工具封装供 AI 模型调用。
 * 模型通过 @Tool 暴露的方法向 Tavily API 发起查询，返回结构化摘要。
 * - API Key 从环境变量 TAVILY_API_KEY 或配置属性 tavily.api.key 读取。
 * - 自动注入当前系统日期，便于时间敏感回答。
 */
@Component
public class TavilyWebTool {

    @Value("${tavily.api.key:}")
    private String propertyApiKey;

    @Value("${tavily.search.depth:basic}")
    private String defaultDepth;

    @Value("${tavily.max-results:5}")
    private int defaultMaxResults;

    private final HttpClient httpClient = HttpClient.newBuilder().build();
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * 对 Tavily 进行网络搜索，返回包含时间戳的简要回答与引用来源。
     * @param query 用户或模型提出的查询内容
     * @param maxResults 最大返回结果条数（可传 <=0 使用默认）
     * @param depth 搜索深度 basic / advanced （为空则使用默认）
     * @return 结构化文本：包含当前日期、Answer、Top Sources（标题+URL）
     */
    @Tool("执行外部网络搜索（Tavily），可查询与图书管理无关的任何信息，返回带当前日期的摘要。参数：query 查询，maxResults 最大结果条数，depth 搜索深度 basic|advanced。")
    public String tavilySearch(String query, Integer maxResults, String depth) {
        if (query == null || query.isBlank()) {
            return "查询内容为空，无法执行 Tavily 搜索";
        }
        String apiKey = resolveApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            return "未配置 Tavily API Key (请设置环境变量 TAVILY_API_KEY 或 application.properties 中 tavily.api.key)";
        }
        int limit = (maxResults == null || maxResults <= 0) ? defaultMaxResults : maxResults;
        String usedDepth = (depth == null || depth.isBlank()) ? defaultDepth : depth;
        // 构造请求体（Tavily 需要 api_key 放在 body 里）。
        String bodyJson = String.format("{\"api_key\":\"%s\",\"query\":\"%s\",\"search_depth\":\"%s\",\"include_answer\":true,\"max_results\":%d}",
                escape(apiKey), escape(query), escape(usedDepth), limit);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.tavily.com/search"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(bodyJson))
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 300) {
                return "Tavily 接口调用失败 HTTP=" + response.statusCode() + " body=" + truncate(response.body(), 300);
            }
            return formatResult(query, response.body(), limit, usedDepth);
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            return "Tavily 搜索异常: " + e.getMessage();
        }
    }

    private String resolveApiKey() {
        // 允许用环境变量快速覆盖（便于本地/容器部署）。
        String env = System.getenv("TAVILY_API_KEY");
        if (env != null && !env.isBlank()) return env;
        return propertyApiKey;
    }

    private String formatResult(String originalQuery, String json, int limit, String depth) {
        try {
            JsonNode root = mapper.readTree(json);
            String answer = root.path("answer").asText("");
            List<Map<String, String>> docs = new ArrayList<>();
            JsonNode results = root.path("results");
            if (results.isArray()) {
                for (JsonNode r : results) {
                    String title = r.path("title").asText("");
                    String url = r.path("url").asText("");
                    String content = r.path("content").asText("");
                    docs.add(Map.of(
                            "title", title,
                            "url", url,
                            "content", truncate(content, 160)
                    ));
                    if (docs.size() >= limit) break;
                }
            }
            StringBuilder sb = new StringBuilder();
            sb.append("当前日期: ").append(LocalDate.now()).append('\n');
            sb.append("原始查询: ").append(originalQuery).append('\n');
            sb.append("搜索深度: ").append(depth).append(" 结果数量: ").append(docs.size()).append('\n');
            if (!answer.isBlank()) {
                sb.append("Answer: ").append(answer).append('\n');
            }
            sb.append("Top Sources:\n");
            for (int i = 0; i < docs.size(); i++) {
                                Map<String, String> d = docs.get(i);
                                sb.append(i + 1).append('.').append(' ')
                                                .append(d.get("title")).append("\n   ")
                                                .append(d.get("url")).append("\n   摘要: ")
                                                .append(d.get("content")).append('\n');
            }
            sb.append("(数据来源：Tavily Web Search API)");
            return sb.toString();
        } catch (Exception e) {
            return "解析 Tavily 响应失败: " + e.getMessage() + " 原始片段: " + truncate(json, 300);
        }
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        return s.substring(0, max) + "...";
    }

    private String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
