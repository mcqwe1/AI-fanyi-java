package com.aifanyi.storage;

import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Path;

/**
 * 文件存储抽象。MVP 用本地磁盘实现，后续可替换 MinIO/OSS。
 */
public interface StorageService {

    /** 某个任务的工作目录（不存在则创建） */
    Path taskDir(Long taskId);

    /** 保存上传的视频，返回落盘路径 */
    Path saveUpload(MultipartFile file, Long taskId) throws IOException;

    /** 在任务目录下解析一个文件名为绝对路径 */
    Path resolve(Long taskId, String filename);
}
