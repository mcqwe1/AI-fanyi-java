package com.aifanyi.asr;

import com.aifanyi.common.BizException;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 按名称路由到具体 ASR provider。Spring 自动注入所有 AsrProvider 实现。
 */
@Component
public class AsrProviderFactory {

    private final Map<String, AsrProvider> providers;

    public AsrProviderFactory(List<AsrProvider> list) {
        this.providers = list.stream()
                .collect(Collectors.toMap(p -> p.name().toLowerCase(), Function.identity()));
    }

    public AsrProvider get(String name) {
        if (name == null) {
            throw new BizException("未指定 ASR provider");
        }
        AsrProvider p = providers.get(name.toLowerCase());
        if (p == null) {
            throw new BizException("不支持的 ASR provider: " + name
                    + "，可用: " + providers.keySet());
        }
        return p;
    }
}
