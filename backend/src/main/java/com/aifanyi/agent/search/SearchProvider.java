package com.aifanyi.agent.search;

import java.util.List;

/**
 * 搜索提供方（harness 工具层）。
 * <p><b>绝不抛异常</b>：任何失败（未配置/网络错/额度用尽）一律返回空列表，
 * DAG 视为「无答案」走策略分支。搜索不可用是<b>正常降级</b>，不是错误。
 */
public interface SearchProvider {

    /** 对应 SearchEngines.Wire 的名字。 */
    String name();

    /**
     * 执行一次搜索。
     *
     * @param timeoutMs 本次超时（调用方传剩余预算）
     * @return 结果列表；任何失败返回空列表
     */
    List<SearchHit> search(String query, SearchConfig cfg, long timeoutMs);
}
