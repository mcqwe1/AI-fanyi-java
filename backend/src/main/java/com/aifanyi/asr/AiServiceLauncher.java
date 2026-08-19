package com.aifanyi.asr;

import com.aifanyi.common.BizException;
import com.aifanyi.config.AifanyiProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 本地 ai-service 的按需自动拉起：用户选本地 Whisper（或需要 VAD 对轴）时，
 * 若 8001 未在监听且本机装有可选组件（ai-service/.venv），后端自己把它启动起来，
 * 用户无需手动运行 run-ai-service.bat（该脚本保留为手动备用入口）。
 * 进程随用随留：后端退出后 uvicorn 可能存活成孤儿，下次启动健康检查发现可用会直接复用，无害。
 */
@Slf4j
@Component
public class AiServiceLauncher {

    private final AifanyiProperties.Asr.Local cfg;
    private final Object lock = new Object();

    public AiServiceLauncher(AifanyiProperties props) {
        this.cfg = props.getAsr().getLocal();
    }

    /**
     * 确保本地 ai-service 可用；不可用则尝试自动启动并等待就绪。
     *
     * @param required true=面向用户的硬需求（本地转写），失败抛可读错误；
     *                 false=尽力而为（VAD 对轴），失败静默返回 false 由调用方降级
     */
    public boolean ensureRunning(boolean required) {
        if (healthy()) {
            return true;
        }
        URI uri = URI.create(cfg.getBaseUrl());
        String host = uri.getHost();
        if (!"localhost".equalsIgnoreCase(host) && !"127.0.0.1".equals(host)) {
            // 指向远程 ai-service 时无从代管，按原样报不可达
            if (required) {
                throw new BizException("本地识别服务 " + cfg.getBaseUrl() + " 不可达，请确认其已启动");
            }
            return false;
        }
        synchronized (lock) {
            if (healthy()) {
                return true;                            // 并发任务里已有人拉起
            }
            Path dir = resolveDir();
            Path python = resolvePython(dir);
            if (python == null) {
                if (required) {
                    throw new BizException("本地识别运行时缺失：完整版安装包已内置（ai-service\\python），"
                            + "精简包请先双击 setup-ai-service.bat 安装（需先装 Python 3.10~3.12），"
                            + "或改用 Groq 云端识别");
                }
                return false;
            }
            int port = uri.getPort() > 0 ? uri.getPort() : 8001;
            try {
                Path logFile = dir.resolve("ai-service.log");
                ProcessBuilder pb = new ProcessBuilder(python.toString(), "-m", "uvicorn",
                        "app.main:app", "--host", "127.0.0.1", "--port", String.valueOf(port));
                pb.directory(dir.toFile());
                pb.redirectErrorStream(true);
                pb.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()));
                Process proc = pb.start();
                log.info("自动拉起本地 ai-service：{}（端口 {}）", dir, port);

                long deadline = System.currentTimeMillis() + 60_000;
                while (System.currentTimeMillis() < deadline) {
                    if (healthy()) {
                        log.info("本地 ai-service 已就绪");
                        return true;
                    }
                    if (!proc.isAlive()) {
                        break;                          // 进程秒退（依赖损坏等），别干等
                    }
                    Thread.sleep(500);
                }
            } catch (IOException | InterruptedException e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                if (required) {
                    throw new BizException("自动启动本地识别服务失败: " + e.getMessage());
                }
                return false;
            }
            if (required) {
                throw new BizException("本地识别服务启动超时或异常退出，详见 ai-service\\ai-service.log；"
                        + "也可改用 Groq 云端识别");
            }
            return false;
        }
    }

    /** 组件目录：AI_SERVICE_DIR 环境变量优先，默认工作目录下的 ai-service（便携包布局）。 */
    private Path resolveDir() {
        String env = System.getenv("AI_SERVICE_DIR");
        Path p = env != null && !env.isBlank()
                ? Path.of(env)
                : Path.of(System.getProperty("user.dir"), "ai-service");
        return Files.isDirectory(p) ? p : null;
    }

    /** Python 解释器：内置便携运行时（python/，零安装）优先，其次 setup 装的 .venv；都没有返回 null。 */
    private Path resolvePython(Path dir) {
        if (dir == null) {
            return null;
        }
        Path bundled = dir.resolve("python").resolve("python.exe");
        if (Files.exists(bundled)) {
            return bundled;
        }
        Path venv = dir.resolve(".venv").resolve("Scripts").resolve("python.exe");
        return Files.exists(venv) ? venv : null;
    }

    private boolean healthy() {
        try {
            HttpURLConnection c = (HttpURLConnection) URI.create(
                    cfg.getBaseUrl().replaceAll("/+$", "") + "/health").toURL().openConnection();
            c.setConnectTimeout(800);
            c.setReadTimeout(1500);
            int code = c.getResponseCode();
            c.disconnect();
            return code == 200;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 本地转写实际跑在 GPU 还是 CPU。
     *
     * <p>只看 /health 里 faster-whisper 首次加载后回填的 resolved.device，
     * <b>模型没加载过时返回 null</b>（还不知道，别猜）。给「预计耗时」用：
     * 同一段音频 GPU 与 CPU 差好几倍，用一个数糊弄会把预估变成误导。
     * 结果缓存住——device 一旦定下来，除非重启 ai-service 不会变。
     */
    public String resolvedDevice() {
        if (cachedDevice != null) {
            return cachedDevice;
        }
        try {
            HttpURLConnection c = (HttpURLConnection) URI.create(
                    cfg.getBaseUrl().replaceAll("/+$", "") + "/health").toURL().openConnection();
            c.setConnectTimeout(800);
            c.setReadTimeout(1500);
            if (c.getResponseCode() != 200) {
                return null;
            }
            String body = new String(c.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            c.disconnect();
            // {"status":"ok","resolved":{"device":"cuda",...}}；resolved 为 null 表示模型还没加载
            java.util.regex.Matcher m = DEVICE_PATTERN.matcher(body);
            if (m.find()) {
                cachedDevice = m.group(1);
                return cachedDevice;
            }
        } catch (Exception e) {
            log.debug("探测 ai-service device 失败（按未知处理）: {}", e.toString());
        }
        return null;
    }

    private static final java.util.regex.Pattern DEVICE_PATTERN =
            java.util.regex.Pattern.compile("\"device\"\\s*:\\s*\"([a-zA-Z0-9_]+)\"");
    private volatile String cachedDevice;
}
