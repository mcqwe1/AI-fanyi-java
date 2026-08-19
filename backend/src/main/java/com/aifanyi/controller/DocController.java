package com.aifanyi.controller;

import com.aifanyi.common.BizException;
import com.aifanyi.common.R;
import com.aifanyi.controller.dto.DocDtos.DocItem;
import com.aifanyi.security.SecurityUtils;
import com.aifanyi.service.DocTranslateService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

/** 文档翻译模式：上传 → 异步翻译（进度轮询）→ 下载同格式译文。 */
@RestController
@RequestMapping("/api/doc")
@RequiredArgsConstructor
public class DocController {

    private final DocTranslateService service;

    @PostMapping("/upload")
    public R<DocItem> upload(@RequestPart("file") MultipartFile file,
                             @RequestParam(defaultValue = "中文") String targetLang,
                             @RequestParam(required = false) String stylePrompt,
                             @RequestParam(required = false) String pdfMode) {
        if (file == null || file.isEmpty()) {
            throw new BizException("文件为空");
        }
        return R.ok(service.upload(SecurityUtils.currentUserId(), file, targetLang, stylePrompt, pdfMode));
    }

    @GetMapping("/list")
    public R<List<DocItem>> list() {
        return R.ok(service.list(SecurityUtils.currentUserId()));
    }

    @GetMapping("/{id}")
    public R<DocItem> get(@PathVariable Long id) {
        return R.ok(service.get(SecurityUtils.currentUserId(), id));
    }

    @GetMapping("/{id}/download")
    public ResponseEntity<FileSystemResource> download(@PathVariable Long id) {
        Object[] pn = service.download(SecurityUtils.currentUserId(), id);
        FileSystemResource res = new FileSystemResource((Path) pn[0]);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename((String) pn[1], StandardCharsets.UTF_8).build().toString())
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(res);
    }

    @GetMapping("/{id}/segments")
    public R<com.aifanyi.controller.dto.DocDtos.DocCompare> segments(@PathVariable Long id) {
        return R.ok(service.compare(SecurityUtils.currentUserId(), id));
    }

    /**
     * 内联预览（对照页 iframe 用）：kind=source 原文件 / output 译文文件。
     * Content-Disposition: inline 让浏览器用原生查看器渲染 PDF/HTML。
     * iframe 带不了 Authorization 头，前端以 ?access_token= 携带凭证。
     */
    @GetMapping("/{id}/file")
    public ResponseEntity<FileSystemResource> file(@PathVariable Long id,
                                                   @RequestParam(defaultValue = "output") String kind) {
        Object[] pn = service.fileFor(SecurityUtils.currentUserId(), id, kind);
        String name = (String) pn[1];
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline()
                        .filename(name == null ? "document" : name, StandardCharsets.UTF_8)
                        .build().toString())
                .contentType(inlineMediaType(name))
                .body(new FileSystemResource((Path) pn[0]));
    }

    /** 文档封面：PDF 渲染第一页 PNG；非 PDF 404（前端按格式渲染 preview 文本）。 */
    @GetMapping("/{id}/thumbnail")
    public ResponseEntity<FileSystemResource> thumbnail(@PathVariable Long id) {
        Path png = service.ensureThumbnail(SecurityUtils.currentUserId(), id);
        if (png == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=86400")
                .body(new FileSystemResource(png));
    }

    /** 内联预览的 Content-Type：决定浏览器是渲染（pdf/html/文本）还是下载。 */
    private static MediaType inlineMediaType(String filename) {
        String n = filename == null ? "" : filename.toLowerCase(java.util.Locale.ROOT);
        if (n.endsWith(".pdf")) {
            return MediaType.APPLICATION_PDF;
        }
        if (n.endsWith(".html") || n.endsWith(".htm")) {
            return new MediaType("text", "html", StandardCharsets.UTF_8);
        }
        if (n.endsWith(".txt") || n.endsWith(".md") || n.endsWith(".markdown")
                || n.endsWith(".srt") || n.endsWith(".vtt") || n.endsWith(".ass")
                || n.endsWith(".json")) {
            return new MediaType("text", "plain", StandardCharsets.UTF_8);
        }
        return MediaType.APPLICATION_OCTET_STREAM;
    }

    @DeleteMapping("/{id}")
    public R<Void> remove(@PathVariable Long id) {
        service.remove(SecurityUtils.currentUserId(), id);
        return R.ok();
    }
}
