# aifanyi 发行打包:把 dist\aifanyi-win64 打成可分发 zip,自动排除 data(数据库/密钥/任务文件)。
# 用法: powershell -ExecutionPolicy Bypass -File pack-release.ps1
# staging 固定在 D 盘 dist 下(勿放 C 盘临时目录:包 7.7G,本机 C 盘常年紧张,放 C 必爆盘)。
$ErrorActionPreference = "Stop"
$root = $PSScriptRoot
$dist = Join-Path $root "dist\aifanyi-win64"
if (-not (Test-Path "$dist\app\aifanyi.jar")) { throw "dist 未构建,先运行 build-portable.ps1" }

# 清理上次异常残留的 staging(也防两个实例并行互踩)
Get-ChildItem (Join-Path $root "dist") -Filter ".pack-stage-*" -Directory -ErrorAction SilentlyContinue |
  Remove-Item -Recurse -Force -ErrorAction SilentlyContinue

$stamp = Get-Date -Format "yyyyMMdd"
$outZip = Join-Path $root "dist\aifanyi-win64-$stamp.zip"

# 临时staging:复制除 data 外的全部内容 + 一个空 data 目录(与 dist 同盘,robocopy 快且不占 C 盘)
$stage = Join-Path $root "dist\.pack-stage-$([guid]::NewGuid().ToString('N').Substring(0,8))"
New-Item -ItemType Directory $stage | Out-Null
try {
  Write-Host "1/2 复制文件(排除 data)..."
  robocopy $dist "$stage\aifanyi-win64" /E /XD "$dist\data" /NFL /NDL /NJH /NJS | Out-Null
  if ($LASTEXITCODE -ge 8) { throw "robocopy 失败 (code $LASTEXITCODE)" }
  New-Item -ItemType Directory "$stage\aifanyi-win64\data" -Force | Out-Null
  if (Test-Path $outZip) { Remove-Item $outZip -Force }
  Write-Host "2/2 压缩中(包体大,约需几分钟)..."
  # 用系统自带 bsdtar 压 zip:PS5.1 的 Compress-Archive 有 4GB 流上限,7.7G 包直接报"流太长"。
  # 必须全路径:PATH 里 Git 的 GNU tar 排在前面,而 GNU tar 不会写 zip。
  & "$env:WINDIR\System32\tar.exe" -a -cf $outZip -C $stage aifanyi-win64
  if ($LASTEXITCODE -ne 0) { throw "tar 压缩失败 (code $LASTEXITCODE)" }
} finally {
  Remove-Item -Recurse -Force $stage -ErrorAction SilentlyContinue
}
$size = [math]::Round((Get-Item $outZip).Length / 1MB)
Write-Host "发行包: $outZip (${size}MB) — data 已排除,可直接分发"
