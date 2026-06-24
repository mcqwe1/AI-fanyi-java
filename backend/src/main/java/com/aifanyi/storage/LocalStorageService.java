package com.aifanyi.storage;

import com.aifanyi.common.BizException;
import com.aifanyi.config.AifanyiProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@Slf4j
@Service
public class LocalStorageService implements StorageService {

    private final Path root;

    public LocalStorageService(AifanyiProperties props) {
        String r = props.getStorage().getRoot();
        if (!StringUtils.hasText(r)) {
            r = System.getProperty("java.io.tmpdir") + "/aifanyi-data";
        }
        this.root = Paths.get(r).toAbsolutePath().normalize();
    }

    @Override
    public Path taskDir(Long taskId) {
        Path dir = root.resolve("tasks").resolve(String.valueOf(taskId));
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new BizException("创建任务目录失败: " + e.getMessage());
        }
        return dir;
    }

    @Override
    public Path saveUpload(MultipartFile file, Long taskId) throws IOException {
        String original = StringUtils.cleanPath(
                file.getOriginalFilename() == null ? "video" : file.getOriginalFilename());
        String ext = StringUtils.getFilenameExtension(original);
        String name = "source" + (ext == null ? "" : "." + ext);
        Path target = taskDir(taskId).resolve(name);
        file.transferTo(target);
        log.info("已保存上传文件: {} ({} bytes)", target, file.getSize());
        return target;
    }

    @Override
    public Path resolve(Long taskId, String filename) {
        return taskDir(taskId).resolve(filename);
    }
}
