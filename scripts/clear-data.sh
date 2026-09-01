#!/usr/bin/env bash
# 清空 Trace Log 全部数据（OpenObserve 数据卷级清理）。
#
# 为什么不用 DELETE stream API：
#   OpenObserve 删除流是异步后台任务，"being deleted" 状态可能持续数分钟以上，
#   期间该流名的写入会被拒绝。整卷清理（down -v）立即得到干净的空系统。
#
# 用法：
#   ./scripts/clear-data.sh
set -euo pipefail

cd "$(dirname "$0")/.."

echo "==> 停止并移除容器与数据卷（OpenObserve 数据将被彻底清空）"
docker compose down -v

echo "==> 重新启动服务"
docker compose up -d

echo "==> 等待 backend 就绪（最多 120s）"
for i in $(seq 1 60); do
  if curl -sf -m 3 http://localhost:8080/ready >/dev/null 2>&1; then
    break
  fi
  sleep 2
done

echo "==> 状态"
docker compose ps --format '{{.Name}} {{.Status}}'
curl -s http://localhost:8080/health; echo
curl -s http://localhost:8080/ready; echo
echo "==> 完成：所有日志数据已清空，写入新日志将自动重建流"
