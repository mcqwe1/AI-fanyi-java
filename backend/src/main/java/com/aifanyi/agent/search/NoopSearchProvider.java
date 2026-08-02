package com.aifanyi.agent.search;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 不联网的搜索实现：恒返回空列表。
 * <p>这不是「占位符」而是<b>正式的降级路径</b>——用户没配搜索引擎、引擎不可用、
 * 或显式选择不联网时都走它，DAG 据此进入「无答案 → 上下文证据挖掘 → 策略五选一」分支。
 * 也让节点③④能在零外部依赖下完整验证。
 */
@Component
public class NoopSearchProvider implements SearchProvider {

    @Override
    public String name() {
        return SearchEngines.Wire.NONE.name();
    }

    @Override
    public List<SearchHit> search(String query, SearchConfig cfg, long timeoutMs) {
        return List.of();
    }
}
