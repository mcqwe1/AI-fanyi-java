#!/bin/sh
set -e

# 公开部署时若未提供 JWT 密钥,启动时随机生成一个。
#
# 为什么必须这么做:application.yml 里的兜底密钥
# (dev-only-secret-change-me-...)就写在公开仓库里。便携版靠 start.bat 每台机器
# 首启生成随机密钥规避,但容器里 start.bat 不会跑,直接落到兜底值——等于任何人
# 都能拿这个公开密钥签出任意用户的 token,绕过登录。
#
# 代价:随机密钥每次重启都变,已签发的 token 全部失效,用户需要重新登录。
# 想让登录态跨重启保留,就在 Railway 的 Variables 里显式设置 AIFANYI_JWT_SECRET
# (任意 ≥32 字节的随机串),本段即自动跳过。
if [ -z "$AIFANYI_JWT_SECRET" ]; then
  AIFANYI_JWT_SECRET="$(head -c 48 /dev/urandom | base64 | tr -d '\n')"
  export AIFANYI_JWT_SECRET
  echo "[entrypoint] 未设置 AIFANYI_JWT_SECRET,已生成随机密钥;重启后登录态会失效。" >&2
  echo "[entrypoint] 需要稳定登录态请在 Railway Variables 里设置 AIFANYI_JWT_SECRET。" >&2
fi

exec java -jar /app/app.jar "$@"
