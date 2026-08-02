package com.aifanyi.controller;

import com.aifanyi.common.R;
import com.aifanyi.controller.dto.TtsDtos.DubReq;
import com.aifanyi.controller.dto.TtsDtos.PreviewReq;
import com.aifanyi.entity.TranslationTask;
import com.aifanyi.mapper.TranslationTaskMapper;
import com.aifanyi.security.SecurityUtils;
import com.aifanyi.service.SettingsService;
import com.aifanyi.service.TaskService;
import com.aifanyi.service.TtsService;
import com.aifanyi.tts.TtsEngines;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * TTS 配音接口：音色列表 / 试听 / 发起配音。
 */
@RestController
@RequestMapping("/api/tasks/{id}/tts")
@RequiredArgsConstructor
public class TtsController {

    private final TaskService taskService;
    private final TtsService ttsService;
    private final SettingsService settings;
    private final TranslationTaskMapper taskMapper;

    @GetMapping("/voices")
    public R<List<TtsEngines.VoiceInfo>> voices(@PathVariable Long id) {
        Long uid = SecurityUtils.currentUserId();
        taskService.getOwned(id, uid); // 校验归属
        return R.ok(ttsService.voices(uid));
    }

    /** 音色试听：用任务里一句译文合成，返回 mp3。 */
    @PostMapping("/preview")
    public ResponseEntity<byte[]> preview(@PathVariable Long id,
                                          @RequestBody(required = false) PreviewReq req) {
        Long uid = SecurityUtils.currentUserId();
        TranslationTask task = taskService.getOwned(id, uid);
        byte[] audio = ttsService.previewSample(task, uid,
                req == null ? null : req.voice(),
                req == null ? null : req.speed());
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("audio/mpeg"))
                .body(audio);
    }

    /** 发起配音（异步）：先同步校验 TTS 配置与可配音条件（任何不满足都在此抛错、绝不改动任务状态），
     *  通过后再存音色/语速/原声设置并触发 DUBBING 流水线。 */
    @PostMapping("/dub")
    public R<Void> dub(@PathVariable Long id, @RequestBody(required = false) DubReq req) {
        Long uid = SecurityUtils.currentUserId();
        TranslationTask task = ttsService.requireDubbable(taskService.getOwned(id, uid));
        // 前置校验 TTS 配置：未选引擎/未配置完整立即抛错，任务纹丝不动（不进流水线、不改状态）
        settings.effectiveTts(uid);
        String voice = req == null ? null : req.voice();
        if (voice == null || voice.isBlank()) {
            List<TtsEngines.VoiceInfo> vs = ttsService.voices(uid);
            voice = vs.isEmpty() ? "alloy" : vs.get(0).value();
        }
        task.setTtsVoice(voice);
        task.setTtsSpeed(req == null || req.speed() == null ? "1.0" : String.valueOf(req.speed()));
        task.setTtsKeepOriginal(req != null && Boolean.TRUE.equals(req.keepOriginal()) ? 1 : 0);
        taskMapper.updateById(task);
        ttsService.dubAsync(id, uid);
        return R.ok();
    }
}
