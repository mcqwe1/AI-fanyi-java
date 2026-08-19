package com.aifanyi.controller;

import com.aifanyi.common.BizException;
import com.aifanyi.common.R;
import com.aifanyi.entity.Feedback;
import com.aifanyi.mapper.FeedbackMapper;
import com.aifanyi.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 帮助中心「反馈与建议」。允许未登录提交（帮助页是公开页），登录用户自动关联。
 * 管理员在 /api/admin/feedback 查看。
 */
@RestController
@RequestMapping("/api/feedback")
@RequiredArgsConstructor
public class FeedbackController {

    private final FeedbackMapper feedbackMapper;

    public record FeedbackReq(String content, String contact) {
    }

    @PostMapping
    public R<Void> submit(@RequestBody FeedbackReq req) {
        String content = req == null || req.content() == null ? "" : req.content().trim();
        if (content.isEmpty()) {
            throw new BizException(400, "反馈内容不能为空");
        }
        if (content.length() > 2000) {
            throw new BizException(400, "反馈内容太长（最多 2000 字）");
        }
        Feedback f = new Feedback();
        f.setUserId(SecurityUtils.currentUserId());   // 未登录为 null
        f.setContact(req.contact() == null ? null : req.contact().trim());
        f.setContent(content);
        feedbackMapper.insert(f);
        return R.ok();
    }
}
