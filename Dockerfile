# syntax=docker/dockerfile:1

# 多阶段构建,供 Railway 等 PaaS 使用。
#
# 存在的原因:前端产物 backend/src/main/resources/static/ 被 .gitignore 排除在版本库外
# (本地由 build-portable.ps1 拷入)。若直接对 backend/ 目录做构建,打出来的 jar 里没有
# index.html,访问根路径就是 404——这正是之前部署后"找不到可用的接口"的原因。
# 这里在构建时现编译前端并塞进 jar,产物依旧不进版本库。
#
# ⚠ Railway 必须把 Root Directory 设为仓库根(留空)才会用到本文件;
#   若仍指向 backend/,Railway 看不到本文件也看不到 frontend/。

# ---- 阶段 1:编译前端 ----
FROM node:20-alpine AS web
WORKDIR /web
# 先只拷依赖清单:依赖没变时这一层命中缓存,不必每次重装 node_modules
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY frontend/ ./
RUN npm run build

# ---- 阶段 2:编译后端并打 jar ----
FROM maven:3.9-eclipse-temurin-21 AS api
WORKDIR /build
# 同理:pom 没变时依赖层命中缓存,免去每次部署重下几百个 jar
COPY backend/pom.xml ./
RUN mvn -B -q dependency:go-offline
COPY backend/src ./src
# 前端产物落进 static/,随 jar 一起打包;Spring Boot 会把 index.html 作为根路径首页,
# 前端 axios 的 baseURL 是相对路径 '/api',同源直接打通,无需额外配置
COPY --from=web /web/dist ./src/main/resources/static
RUN mvn -B -DskipTests package

# ---- 阶段 3:运行 ----
FROM eclipse-temurin:21-jre
# 视频转码、音频抽取、配音合成都要调 ffmpeg/ffprobe,JRE 基础镜像里没有。
# 用静态编译版而非 apt install:后者会连带拖进 libsdl2/x11-common/libxrandr 等
# 一整套 X11 依赖(约 200MB),无头服务器上一个都用不到。
# 二进制名保持 ffmpeg/ffprobe 且在 PATH 上,FfmpegService.deriveFfprobe 的默认推导即可命中。
COPY --from=mwader/static-ffmpeg:7.1 /ffmpeg /ffprobe /usr/local/bin/
WORKDIR /app
COPY --from=api /build/target/*.jar app.jar
# H2 数据库文件与用户上传都落在这里。容器文件系统是临时的,重新部署即清零;
# 要保留数据需在 Railway 挂 Volume 到 /app/data,或用 AIFANYI_DB_URL 切到外部 MySQL。
ENV AIFANYI_STORAGE_ROOT=/app/data
RUN mkdir -p /app/data
# 仅作声明。实际端口由 PaaS 注入的 PORT 决定(见 application.yml 的 server.port)
EXPOSE 8080
# sed 去 CR:仓库在 Windows 上维护,带 CRLF 的脚本在 Linux 里会报 "not found"
COPY docker-entrypoint.sh /usr/local/bin/
RUN sed -i 's/\r$//' /usr/local/bin/docker-entrypoint.sh \
    && chmod +x /usr/local/bin/docker-entrypoint.sh
ENTRYPOINT ["/usr/local/bin/docker-entrypoint.sh"]
