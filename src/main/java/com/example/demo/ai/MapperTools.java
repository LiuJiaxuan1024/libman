package com.example.demo.ai;

import com.example.demo.mapper.BookInfoMapper;
import com.example.demo.mapper.BookCategoryMapper;
import com.example.demo.mapper.UserInfoMapper;
import com.example.demo.model.BookInfo;
import com.example.demo.model.BookCategory;
import com.example.demo.model.UserInfo;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
/**
 * 供 AI 模型调用的“工具集合”。
 * <p>
 * LangChain4j 会把带 {@link dev.langchain4j.agent.tool.Tool} 注解的方法暴露给模型：
 * 模型在回答过程中可主动调用这些方法来查询数据库/组合统计/回退到网络搜索。
 * <p>
 * 设计原则：
 * <ul>
 *   <li>工具方法应尽量“可预测、无副作用”（多数为查询）。</li>
 *   <li>入参要做基本兜底（空值、limit 上限等），避免模型生成异常参数导致服务出错。</li>
 *   <li>返回值尽量结构化（List/对象/可解析字符串），方便模型组织 Markdown 表格输出。</li>
 * </ul>
 */
public class MapperTools {
    private final BookInfoMapper bookInfoMapper;
    private final BookCategoryMapper categoryMapper;
    private final UserInfoMapper userInfoMapper;
    private final TavilyWebTool tavilyWebTool;

    @Autowired
    public MapperTools(BookInfoMapper bookInfoMapper, BookCategoryMapper categoryMapper, UserInfoMapper userInfoMapper, TavilyWebTool tavilyWebTool) {
        this.bookInfoMapper = bookInfoMapper;
        this.categoryMapper = categoryMapper;
        this.userInfoMapper = userInfoMapper;
        this.tavilyWebTool = tavilyWebTool;
    }

    // 简单的类别同义 & 回退映射，可扩展。
    // 用途：当用户输入的分类名在库里不存在时，让模型有“可解释的替代分类”。
    private static final Map<String, List<String>> CATEGORY_FALLBACK = Map.of(
            "文学", List.of("艺术", "文化", "历史"),
            "小说", List.of("文学", "艺术"),
            "诗歌", List.of("文学", "艺术"),
            "人工智能", List.of("计算机", "机器学习", "大数据"),
            "网络", List.of("计算机", "通信"),
            "数据库", List.of("计算机", "数据"));

    @Tool("分页列出书籍，按 id 降序。参数 offset 为起始偏移，limit 为数量。")
    public List<BookInfo> listBooksByOffset(int offset, int limit) {
        return bookInfoMapper.queryBookListByPage(offset, limit);
    }

    @Tool("获取图书总数")
    public int countBooks() {
        Integer c = bookInfoMapper.countBooks();
        return c == null ? 0 : c;
    }

    @Tool("按捐赠者用户名列出其捐赠的书籍，参数: userName=用户名, limit=返回最大数量。若用户不存在返回空列表。")
    public List<BookInfo> listDonatedBooksByUser(String userName, int limit) {
        if (userName == null || userName.isBlank()) return java.util.Collections.emptyList();
        UserInfo u = userInfoMapper.queryUserByName(userName.trim());
        if (u == null) return java.util.Collections.emptyList();
        int realLimit = Math.min(Math.max(limit, 1), 200);
        List<BookInfo> all = bookInfoMapper.queryBooksByDonor(u.getId());
        if (all == null || all.isEmpty()) return java.util.Collections.emptyList();
        return all.stream().limit(realLimit).collect(Collectors.toList());
    }

    @Tool("根据图书ID获取书籍详细")
    public BookInfo getBookById(int id) {
        return bookInfoMapper.queryBookById(id);
    }

    @Tool("根据图书ID列表批量获取书籍")
    public List<BookInfo> getBooksByIds(List<Integer> ids) {
        if (ids == null || ids.isEmpty()) return java.util.Collections.emptyList();
        return bookInfoMapper.queryBooksByIdList(ids);
    }

    @Tool("列出所有分类")
    public List<BookCategory> listCategories() {
        return categoryMapper.getAllCategories();
    }

    @Tool("按分类名称精确或模糊查找书籍，如果该分类不存在则尝试回退同义分类。参数 name 为分类中文名称，limit 为最大返回数量。")
    public List<BookInfo> searchBooksByCategoryWithFallback(String name, int limit) {
        if (name == null || name.isBlank()) return java.util.Collections.emptyList();
        List<BookCategory> all = categoryMapper.getAllCategories();
        // 先尝试精确与包含匹配
        List<Integer> matchedIds = all.stream()
                .filter(c -> c.getCategoryName() != null && (c.getCategoryName().equalsIgnoreCase(name) || c.getCategoryName().contains(name)))
                .map(BookCategory::getId)
                .collect(Collectors.toList());
        if (matchedIds.isEmpty()) {
            // 回退映射
            List<String> fallback = CATEGORY_FALLBACK.getOrDefault(name, List.of());
            if (!fallback.isEmpty()) {
                matchedIds = all.stream()
                        .filter(c -> c.getCategoryName() != null && fallback.stream().anyMatch(f -> c.getCategoryName().contains(f)))
                        .map(BookCategory::getId)
                        .collect(Collectors.toList());
            }
        }
        if (matchedIds.isEmpty()) return java.util.Collections.emptyList();
        // 简单分页抓取较大数量后在内存中过滤。
        // 如果数据量变大，建议改为“按分类 ID 直接 SQL 过滤”以避免全量抓取。
        List<Integer> finalMatchedIds = matchedIds; // 使其 effectively final 供 lambda 使用
        List<BookInfo> batch = bookInfoMapper.queryBookListByPage(0, Math.max(limit, 200));
        List<BookInfo> filtered = batch.stream()
            .filter(b -> containsAnyCategory(b.getCategoryIds(), finalMatchedIds))
                .limit(limit)
                .collect(Collectors.toList());
        return filtered;
    }

    private boolean containsAnyCategory(String categoryIdsJson, List<Integer> targetIds) {
        if (categoryIdsJson == null || categoryIdsJson.isBlank() || targetIds == null || targetIds.isEmpty()) return false;
        String trimmed = categoryIdsJson.trim();
        // 去掉可能的方括号与引号
        if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
            trimmed = trimmed.substring(1, trimmed.length() - 1);
        }
        if (trimmed.isBlank()) return false;
        String[] parts = trimmed.split(",");
        for (String p : parts) {
            String token = p.replaceAll("[^0-9]", "").trim();
            if (token.isEmpty()) continue;
            try {
                int id = Integer.parseInt(token);
                if (targetIds.contains(id)) return true;
            } catch (NumberFormatException ignored) {}
        }
        return false;
    }

    @Tool("根据用户名查找用户（用于查询捐赠者信息等）")
    public UserInfo getUserByName(String userName) {
        return userInfoMapper.queryUserByName(userName);
    }

    // ================= 新增 AI 工具方法 =================

    @Tool("按标题或部分标题模糊搜索书籍，参数 title 为关键字，limit 为最大返回数")
    public List<BookInfo> searchBooksByTitle(String title, int limit) {
        if (title == null || title.isBlank()) return java.util.Collections.emptyList();
        // 简单抓取一批再在内存中过滤（可替换为专用 SQL LIKE 查询）
        List<BookInfo> batch = bookInfoMapper.queryBookListByPage(0, Math.max(limit, 200));
        String t = title.trim().toLowerCase();
        return batch.stream()
                .filter(b -> {
                    String name = b.getBookName();
                    return name != null && name.toLowerCase().contains(t);
                })
                .limit(limit)
                .collect(Collectors.toList());
    }

    @Tool("推荐随机书籍，返回 limit 本当前库中存在的书（简单随机抽样）")
    public List<BookInfo> recommendRandomBooks(int limit) {
        int total = countBooks();
        if (total == 0 || limit <= 0) return java.util.Collections.emptyList();
        int fetch = Math.min(Math.max(limit * 4, limit), 300); // 抓取一批做随机
        List<BookInfo> batch = bookInfoMapper.queryBookListByPage(0, fetch);
        java.util.Collections.shuffle(batch);
        return batch.stream().limit(limit).collect(Collectors.toList());
    }

    @Tool("根据多个分类名称列出属于这些分类之一的书籍（任意匹配），limit 为返回条数上限")
    public List<BookInfo> searchBooksByAnyCategories(List<String> categoryNames, int limit) {
        if (categoryNames == null || categoryNames.isEmpty()) return java.util.Collections.emptyList();
        List<BookCategory> all = categoryMapper.getAllCategories();
        List<Integer> wanted = all.stream()
                .filter(c -> c.getCategoryName() != null && categoryNames.stream().anyMatch(n -> c.getCategoryName().contains(n)))
                .map(BookCategory::getId)
                .collect(Collectors.toList());
        if (wanted.isEmpty()) return java.util.Collections.emptyList();
        List<BookInfo> batch = bookInfoMapper.queryBookListByPage(0, Math.max(limit, 300));
        return batch.stream()
                .filter(b -> containsAnyCategory(b.getCategoryIds(), wanted))
                .limit(limit)
                .collect(Collectors.toList());
    }

    @Tool("生成全库简要统计与分类覆盖摘要，包含总数与每个分类出现次数")
    public String summarizeLibrary() {
        int total = countBooks();
        List<BookCategory> allCats = categoryMapper.getAllCategories();
        List<BookInfo> sample = bookInfoMapper.queryBookListByPage(0, Math.min(Math.max(total, 300), 1000));
        // 统计分类出现频次
        java.util.Map<Integer, Integer> freq = new java.util.HashMap<>();
        for (BookInfo b : sample) {
            List<Integer> ids = extractCategoryIds(b.getCategoryIds());
            for (Integer id : ids) {
                freq.merge(id, 1, Integer::sum);
            }
        }
        StringBuilder sb = new StringBuilder();
        sb.append("图书总数：").append(total).append("\n分类覆盖：\n");
        for (BookCategory c : allCats) {
            int count = freq.getOrDefault(c.getId(), 0);
            sb.append("- ").append(c.getCategoryName()).append(": ").append(count).append("本\n");
        }
        sb.append("可进一步根据标题或分类进行更具体查询。\n");
        return sb.toString();
    }

    @Tool("获取指定书籍的面向用户的详细描述（含分类名、标签、简介拼接），用于回答用户关于某本书的查询")
    public String getBookDetailRich(int id) {
        BookInfo b = bookInfoMapper.queryBookById(id);
        if (b == null) return "未找到该书";
        StringBuilder sb = new StringBuilder();
        sb.append("书名: ").append(nullSafe(b.getBookName())).append('\n');
        sb.append("作者: ").append(nullSafe(b.getAuthor())).append('\n');
        sb.append("出版社: ").append(nullSafe(b.getPublish())).append('\n');
        if (b.getPrice() != null) sb.append("价格: ¥").append(b.getPrice()).append('\n');
        sb.append("状态: ").append(statusText(b.getStatus())).append('\n');
        sb.append("分类ID数组: ").append(nullSafe(b.getCategoryIds())).append('\n');
        sb.append("标签: ").append(nullSafe(b.getTags())).append('\n');
        sb.append("简介: ").append(nullSafe(b.getDescription())).append('\n');
        return sb.toString();
    }

    @Tool("先在本地库按标题查找，若无结果则回退到 Tavily 网页搜索并返回摘要（参数：title, maxResults），该工具也可以用于查询与书籍无关的问题")
    public String findBookWithWebFallback(String title, int maxResults) {
        if (title == null || title.isBlank()) return "查询内容为空";
        int limit = Math.max(1, maxResults);
        List<com.example.demo.model.BookInfo> local = searchBooksByTitle(title, limit);
        if (local != null && !local.isEmpty()) {
            // 构建简要表格
            StringBuilder sb = new StringBuilder();
            sb.append("在本地馆藏中找到如下匹配（优先展示前 ").append(limit).append(" 条）：\n");
            sb.append("|书名|作者|出版社|分类|简介|\n");
            for (com.example.demo.model.BookInfo b : local) {
                sb.append("|")
                        .append(nullSafe(b.getBookName())).append("|")
                        .append(nullSafe(b.getAuthor())).append("|")
                        .append(nullSafe(b.getPublish())).append("|")
                        .append(nullSafe(b.getCategoryIds())).append("|")
                        .append(nullSafe(b.getDescription())).append("|\n");
            }
            return sb.toString();
        }
        // 本地未命中，尝试网络回退
        return tavilyWebTool.tavilySearch(title, limit, "basic");
    }

    private String nullSafe(String s) { return s == null ? "(无)" : s; }
    private String statusText(Integer st) {
        if (st == null) return "未知";
        return switch (st) {
            case 1 -> "在馆";
            case 2 -> "已借出";
            case 0 -> "已删除";
            default -> "未知";
        };
    }

    private List<Integer> extractCategoryIds(String json) {
        if (json == null || json.isBlank()) return java.util.Collections.emptyList();
        String t = json.trim();
        if (t.startsWith("[") && t.endsWith("]")) {
            t = t.substring(1, t.length() - 1);
        }
        if (t.isBlank()) return java.util.Collections.emptyList();
        List<Integer> out = new java.util.ArrayList<>();
        for (String part : t.split(",")) {
            String num = part.replaceAll("[^0-9]", "").trim();
            if (num.isEmpty()) continue;
            try { out.add(Integer.parseInt(num)); } catch (NumberFormatException ignore) {}
        }
        return out;
    }
}
