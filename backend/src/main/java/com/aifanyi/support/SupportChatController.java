package com.aifanyi.support;

import com.aifanyi.common.BizException;
import com.aifanyi.common.R;
import com.aifanyi.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 智能客服接口（前端右下角悬浮球的后端）。
 */
@RestController
@RequestMapping("/api/support")
@RequiredArgsConstructor
public class SupportChatController {

    private final SupportChatService service;

    /** 一次提问：问题 + 此前的对话历史（[{role, content}]，可空）。 */
    public record ChatReq(String question, List<Map<String, String>> history) {
    }

    @PostMapping("/chat")
    public R<SupportChatService.Reply> chat(@RequestBody ChatReq req) {
        Long uid = SecurityUtils.currentUserId();
        if (req == null || req.question() == null || req.question().isBlank()) {
            throw new BizException("请输入问题");
        }
        String q = req.question().strip();
        if (q.length() > 1000) {
            q = q.substring(0, 1000);
        }
        return R.ok(service.chat(uid, q, req.history()));
    }
}
