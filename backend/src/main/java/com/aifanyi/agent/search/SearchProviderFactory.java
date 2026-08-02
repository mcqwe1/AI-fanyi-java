package com.aifanyi.agent.search;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 按 wire 分派 SearchProvider（照 TtsProviderFactory/AsrProviderFactory 范式）。
 * 找不到实现时返回 Noop——搜索不可用永远是降级而非报错。
 */
@Slf4j
@Component
public class SearchProviderFactory {

    private final Map<String, SearchProvider> providers = new HashMap<>();
    private final NoopSearchProvider noop;

    public SearchProviderFactory(List<SearchProvider> impls, NoopSearchProvider noop) {
        this.noop = noop;
        for (SearchProvider p : impls) {
            providers.put(p.name().toUpperCase(java.util.Locale.ROOT), p);
        }
        log.info("搜索 provider 注册: {}", providers.keySet());
    }

    /** 取实现；未知 wire 一律回落 Noop（不联网降级路径）。 */
    public SearchProvider get(SearchEngines.Wire wire) {
        if (wire == null || wire == SearchEngines.Wire.NONE) {
            return noop;
        }
        return providers.getOrDefault(wire.name(), noop);
    }
}
