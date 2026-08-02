package com.aifanyi.tts;

import com.aifanyi.common.BizException;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 按名称路由到具体 TTS provider。Spring 自动注入所有 TtsProvider 实现。
 */
@Component
public class TtsProviderFactory {

    private final Map<String, TtsProvider> providers;

    public TtsProviderFactory(List<TtsProvider> list) {
        this.providers = list.stream()
                .collect(Collectors.toMap(p -> p.name().toLowerCase(), Function.identity()));
    }

    public TtsProvider get(String name) {
        if (name == null) {
            throw new BizException("未指定 TTS provider");
        }
        TtsProvider p = providers.get(name.toLowerCase());
        if (p == null) {
            throw new BizException("不支持的 TTS provider: " + name
                    + "，可用: " + providers.keySet());
        }
        return p;
    }
}
