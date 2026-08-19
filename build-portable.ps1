# aifanyi 便携版打包脚本(开发机运行;产物 dist\aifanyi-win64 可直接压缩分发)
# 用法: powershell -ExecutionPolicy Bypass -File build-portable.ps1
#       可选参数 -FfmpegExe / -Jdk 指定本机 ffmpeg.exe 与 JDK(>=21,需含 jlink)
#       -Quick: 增量打包(只重建 前端+后端jar+extension+portable脚本,复用包内已有的
#               runtime/bin/ai-service,约 1~2 分钟;首次打包必须用完整模式)
#       -StopRunning: 检测到便携实例正在运行时自动停掉(否则 jar 被占用会中止并提示)
param(
  [string]$FfmpegExe = "C:/Users/zyun5/AppData/Local/Microsoft/WinGet/Packages/Gyan.FFmpeg_Microsoft.Winget.Source_8wekyb3d8bbwe/ffmpeg-8.1.1-full_build/bin/ffmpeg.exe",
  [string]$Jdk = "C:/Users/zyun5/.jdks/temurin-25.0.3",
  [switch]$Quick,
  [switch]$StopRunning
)
$ErrorActionPreference = "Stop"
$root = $PSScriptRoot
$sw = [System.Diagnostics.Stopwatch]::StartNew()

if (-not (Test-Path "$Jdk/bin/java.exe")) { throw "JDK 不存在: $Jdk (用 -Jdk 指定)" }
if (-not $Quick) {
  if (-not (Test-Path $FfmpegExe)) { throw "ffmpeg.exe 不存在: $FfmpegExe (用 -FfmpegExe 指定)" }
  $FfprobeExe = $FfmpegExe -replace 'ffmpeg\.exe$', 'ffprobe.exe'
  if (-not (Test-Path $FfprobeExe)) { throw "ffprobe.exe 不存在: $FfprobeExe" }
  if (-not (Test-Path "$Jdk/bin/jlink.exe")) { throw "jlink 不存在: $Jdk/bin/jlink.exe (用 -Jdk 指定)" }
}

Write-Host "== 1/6 构建前端 =="
Push-Location "$root/frontend"
if (-not $Quick -or -not (Test-Path "$root/frontend/node_modules")) {
  npm install --no-audit --no-fund
  if ($LASTEXITCODE -ne 0) { Pop-Location; throw "npm install 失败" }
} else {
  Write-Host "  (-Quick: node_modules 已存在,跳过 npm install)"
}
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

# 替换 app\ 前必须确保没有便携实例占着 jar(Windows 下运行中的 jar 文件无法覆盖)
# 同时要停掉它自动拉起的 ai-service(python.exe 锁着 site-packages 的 .pyd,不停删不掉)
$distWin = ($dist -replace '/', '\').ToLower()
$running = @(Get-CimInstance Win32_Process |
  Where-Object { ($_.Name -eq 'java.exe' -or $_.Name -eq 'python.exe') -and
                 $_.CommandLine -and $_.CommandLine.ToLower().Contains($distWin) })
if ($running.Count -gt 0) {
  $pidList = ($running | ForEach-Object { $_.ProcessId }) -join ", "
  if ($StopRunning) {
    Write-Host "  停止正在运行的便携实例 (PID $pidList) ..."
    foreach ($p in $running) { try { Stop-Process -Id $p.ProcessId -Force -ErrorAction Stop } catch {} }
    Start-Sleep 2
  } else {
    throw "便携实例正在运行 (PID $pidList),jar 被占用无法替换。先运行 dist\aifanyi-win64\stop.bat,或给本脚本加 -StopRunning"
  }
}

if ($Quick) {
  # 增量模式的前提:包里已有完整的 runtime/ffmpeg/ai-service(这些占了完整打包的绝大部分时间)
  foreach ($need in @("runtime\bin\java.exe", "bin\ffmpeg.exe")) {
    if (-not (Test-Path (Join-Path $dist $need))) {
      throw "-Quick 需要现成的完整包(缺 $need),请先跑一次完整打包"
    }
  }
}

# Never delete the whole bundle here: data/ contains the user database, task media,
# subtitles and secrets. A rebuild must replace only application-owned directories.
# The old implementation removed $dist recursively and therefore erased all user data
# whenever the portable bundle was rebuilt before the next restart.
$replaceDirs = @("app", "extension")
if (-not $Quick) { $replaceDirs += @("bin", "runtime", "ai-service") }
if (Test-Path $dist) {
  foreach ($dir in $replaceDirs) {
    $path = Join-Path $dist $dir
    if (Test-Path $path) { Remove-Item -Recurse -Force $path }
  }
}
New-Item -ItemType Directory -Force "$dist/app", "$dist/data" | Out-Null
Copy-Item $jar.FullName "$dist/app/aifanyi.jar"
Copy-Item "$root/portable/*" $dist -Force
# 狐译浏览器扩展随包分发：用户在 chrome://extensions「加载已解压的扩展程序」直接选这个目录，
# 网页版「划词翻译」页的 zip 下载（/api/ext/download）也从这里现打包
Copy-Item -Recurse "$root/extension" "$dist/extension"

if (-not $Quick) {
New-Item -ItemType Directory -Force "$dist/bin", "$dist/ai-service/app" | Out-Null
Copy-Item $FfmpegExe  "$dist/bin/ffmpeg.exe"
Copy-Item $FfprobeExe "$dist/bin/ffprobe.exe"
Copy-Item "$root/ai-service/app/main.py" "$dist/ai-service/app/main.py"
Copy-Item "$root/ai-service/app/pdf_runner.py" "$dist/ai-service/app/pdf_runner.py"
Copy-Item "$root/ai-service/requirements.txt" "$dist/ai-service/requirements.txt"

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
  # babeldoc = PDF 版式保持翻译（版面模型重排版）；缺了 PDF 会退回内置 pdfbox 盖白重画引擎
  & "$dist/ai-service/python/python.exe" @pipArgs "babeldoc>=0.6.4" | Out-Null
  if ($LASTEXITCODE -ne 0) { Write-Warning "babeldoc 预装失败，PDF 翻译将退回内置引擎（排版质量下降）" }

  # BabelDOC 离线资产（版面 ONNX 模型 + 嵌入字体 + cmap，约 340MB）。
  # 它的缓存目录写死在 ~/.cache/babeldoc，没法指到包内，所以随包带官方离线 zip，
  # 目标机首次 PDF 翻译时由 pdf_runner.ensure_assets() 从本地还原——不联网、不等下载。
  $assetDir = "$dist/ai-service/babeldoc-assets"
  if (-not (Test-Path "$assetDir/*.zip")) {
    Write-Host "  生成 BabelDOC 离线资产包（首次约需几分钟下载）..."
    New-Item -ItemType Directory -Force $assetDir | Out-Null
    & "$dist/ai-service/python/python.exe" -c "from pathlib import Path; from babeldoc.assets import assets; assets.generate_offline_assets_package(Path(r'$assetDir'))" | Out-Null
    if ($LASTEXITCODE -ne 0) {
      Write-Warning "BabelDOC 离线资产生成失败：目标机首次 PDF 翻译需联网下载约 340MB"
    }
  } else {
    Write-Host "  BabelDOC 离线资产已存在，跳过"
  }
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

}

if ($Quick) {
  Write-Host "== 5/6 跳过 jlink(-Quick 复用已有 runtime) =="
} else {
  Write-Host "== 5/6 jlink 生成精简 JRE =="
  # java.se 全集 + Unsafe(cglib/mybatis) + EC-TLS + jarfs + 中文 locale + 扩展字符集
  & "$Jdk/bin/jlink.exe" --add-modules "java.se,jdk.unsupported,jdk.crypto.ec,jdk.zipfs,jdk.localedata,jdk.charsets" `
    --strip-debug --no-man-pages --no-header-files --compress zip-6 --output "$dist/runtime"
  if ($LASTEXITCODE -ne 0) { throw "jlink 失败" }
}

Write-Host "== 6/6 完成 =="
$elapsed = [math]::Round($sw.Elapsed.TotalSeconds)
if ($Quick) {
  # 全量 du 扫 8GB 目录要十几秒,增量模式没必要
  Write-Host "产物: $dist  用时 ${elapsed}s (增量更新了 app/extension/portable 脚本)  → 双击 start.bat 启动"
} else {
  $size = [math]::Round((Get-ChildItem $dist -Recurse | Measure-Object Length -Sum).Sum / 1MB)
  Write-Host "产物: $dist (${size}MB)  用时 ${elapsed}s  → 整个文件夹压缩后即可分发,目标机器双击 start.bat"
}
