# aifanyi 便携版打包脚本(开发机运行;产物 dist\aifanyi-win64 可直接压缩分发)
# 用法: powershell -ExecutionPolicy Bypass -File build-portable.ps1
#       可选参数 -FfmpegExe / -Jdk 指定本机 ffmpeg.exe 与 JDK(>=21,需含 jlink)
param(
  [string]$FfmpegExe = "C:/Users/zyun5/AppData/Local/Microsoft/WinGet/Packages/Gyan.FFmpeg_Microsoft.Winget.Source_8wekyb3d8bbwe/ffmpeg-8.1.1-full_build/bin/ffmpeg.exe",
  [string]$Jdk = "C:/Users/zyun5/.jdks/temurin-25.0.3"
)
$ErrorActionPreference = "Stop"
$root = $PSScriptRoot

if (-not (Test-Path $FfmpegExe)) { throw "ffmpeg.exe 不存在: $FfmpegExe (用 -FfmpegExe 指定)" }
$FfprobeExe = $FfmpegExe -replace 'ffmpeg\.exe$', 'ffprobe.exe'
if (-not (Test-Path $FfprobeExe)) { throw "ffprobe.exe 不存在: $FfprobeExe" }
if (-not (Test-Path "$Jdk/bin/jlink.exe")) { throw "jlink 不存在: $Jdk/bin/jlink.exe (用 -Jdk 指定)" }

Write-Host "== 1/6 构建前端 =="
Push-Location "$root/frontend"
npm install --no-audit --no-fund
if ($LASTEXITCODE -ne 0) { Pop-Location; throw "npm install 失败" }
npm run build
if ($LASTEXITCODE -ne 0) { Pop-Location; throw "npm build 失败" }
Pop-Location

Write-Host "== 2/6 前端静态资源并入后端 =="
if (Test-Path "$root/backend/src/main/resources/static") {
  Remove-Item -Recurse -Force "$root/backend/src/main/resources/static"
}
Copy-Item -Recurse "$root/frontend/dist" "$root/backend/src/main/resources/static"

Write-Host "== 3/6 打后端 fat jar =="
$env:JAVA_HOME = $Jdk
Push-Location "$root/backend"
& .\mvnw.cmd -q -DskipTests package
if ($LASTEXITCODE -ne 0) { Pop-Location; throw "maven package 失败" }
Pop-Location
$jar = Get-ChildItem "$root/backend/target/*.jar" | Where-Object { $_.Name -notlike "*.original" } | Select-Object -First 1
if (-not $jar) { throw "target 下没找到 boot jar" }

Write-Host "== 4/6 组装 dist 目录 =="
$dist = "$root/dist/aifanyi-win64"
if (Test-Path $dist) { Remove-Item -Recurse -Force $dist }
New-Item -ItemType Directory -Force "$dist/app", "$dist/bin", "$dist/data", "$dist/ai-service/app" | Out-Null
Copy-Item $jar.FullName "$dist/app/aifanyi.jar"
Copy-Item $FfmpegExe  "$dist/bin/ffmpeg.exe"
Copy-Item $FfprobeExe "$dist/bin/ffprobe.exe"
Copy-Item "$root/ai-service/app/main.py" "$dist/ai-service/app/main.py"
Copy-Item "$root/ai-service/requirements.txt" "$dist/ai-service/requirements.txt"
Copy-Item "$root/portable/*" $dist -Force

# 本地 AI 组件（有则内置成"完整版"，无则出"精简版"提示目标机跑 setup-ai-service.bat）：
#  - ai-service/python  便携 Python 运行时（本地转写 + edge-tts 配音开箱即用）
#  - ai-service/models  离线模型库（Whisper 转写模型 + fastembed 句向量模型）
if (Test-Path "$root/ai-service/python/python.exe") {
  Write-Host "  内置便携 Python 运行时（含依赖）..."
  Copy-Item -Recurse "$root/ai-service/python" "$dist/ai-service/python"
  # 确保 edge-tts 与 fastembed 已进运行时（幂等，几秒）
  # fastembed = 全能AI翻译的术语近义发现；缺了只是该功能降级为精确匹配，不影响其他模式
  # 不要写 2>$null：PS 5.1 下重定向原生 exe 的 stderr 会把每行包成 ErrorRecord，
  # 配合 $ErrorActionPreference="Stop" 直接终止整个打包——pip 只是往 stderr 打了条
  # "new release of pip available" 通知就会炸。这里改为吞掉输出但不碰 stderr 流。
  $pipArgs = @("-m", "pip", "install", "-q", "-i", "https://pypi.tuna.tsinghua.edu.cn/simple")
  & "$dist/ai-service/python/python.exe" @pipArgs "edge-tts>=6.1.0" | Out-Null
  if ($LASTEXITCODE -ne 0) { Write-Warning "edge-tts 预装失败，目标机首次配音会提示安装" }
  & "$dist/ai-service/python/python.exe" @pipArgs "fastembed>=0.4.0" | Out-Null
  if ($LASTEXITCODE -ne 0) { Write-Warning "fastembed 预装失败，全能AI翻译的近义发现会降级（不影响翻译）" }
} else {
  Write-Warning "ai-service/python 不存在：产物为精简版，本地转写与 Edge-TTS 需目标机跑 setup-ai-service.bat"
}
if (Test-Path "$root/ai-service/models") {
  Write-Host "  内置离线模型库（Whisper + 句向量）..."
  Copy-Item -Recurse "$root/ai-service/models" "$dist/ai-service/models"
  # 句向量模型必须真的进包：它在开发机上默认落在系统临时目录，
  # 清理临时文件就会消失（本项目已实际遭遇一次）。这里显式校验，
  # 缺了就出警告而不是让用户装完才发现功能悄悄不工作。
  if (-not (Test-Path "$dist/ai-service/models/fastembed")) {
    Write-Warning "models/fastembed 缺失：全能AI翻译的近义发现将退化为精确匹配（不影响翻译）。
    补齐方法：在 ai-service 目录执行
    python -c ""from fastembed import TextEmbedding as T; T(model_name='sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2', cache_dir='models/fastembed')"""
  }
}

Write-Host "== 5/6 jlink 生成精简 JRE =="
# java.se 全集 + Unsafe(cglib/mybatis) + EC-TLS + jarfs + 中文 locale + 扩展字符集
& "$Jdk/bin/jlink.exe" --add-modules "java.se,jdk.unsupported,jdk.crypto.ec,jdk.zipfs,jdk.localedata,jdk.charsets" `
  --strip-debug --no-man-pages --no-header-files --compress zip-6 --output "$dist/runtime"
if ($LASTEXITCODE -ne 0) { throw "jlink 失败" }

Write-Host "== 6/6 完成 =="
$size = [math]::Round((Get-ChildItem $dist -Recurse | Measure-Object Length -Sum).Sum / 1MB)
Write-Host "产物: $dist (${size}MB)  → 整个文件夹压缩后即可分发,目标机器双击 start.bat"
