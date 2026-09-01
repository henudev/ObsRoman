#!/usr/bin/env node
// 构建前置：把仓库 docs/ 下的 API/SDK 文档与更新日志同步到前端源码，
// 保证页面渲染内容与 docs/*.md 单一来源一致。
// 本地与 Docker 构建都会执行；自动适配两种目录布局：
//   本地：frontend/scripts -> 仓库根/docs
//   Docker（context=仓库根, WORKDIR=/app）：/app/docs
import { copyFileSync, existsSync, mkdirSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const frontendDir = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const targetDir = join(frontendDir, 'src', 'docs')
mkdirSync(targetDir, { recursive: true })

// 按优先级探测 docs 目录
const candidates = [
  resolve(frontendDir, '..', 'docs'),   // 本地布局
  resolve(process.cwd(), 'docs'),       // Docker：WORKDIR=/app
  resolve(process.cwd(), '..', 'docs')  // Docker：从子目录执行
]
const repoDocs = candidates.find((dir) => existsSync(join(dir, 'API.md')))

const pairs = [
  ['API.md', 'api.md'],
  ['SDK.md', 'sdk.md'],
  ['CHANGELOG.md', 'changelog.md']
]

if (!repoDocs) {
  console.warn('[sync-docs] WARN: docs directory not found, keep existing src/docs')
} else {
  for (const [source, target] of pairs) {
    const from = join(repoDocs, source)
    const to = join(targetDir, target)
    if (existsSync(from)) {
      copyFileSync(from, to)
      console.log(`[sync-docs] ${source} -> src/docs/${target}`)
    } else if (existsSync(to)) {
      console.log(`[sync-docs] ${source} not found, keep existing src/docs/${target}`)
    } else {
      console.warn(`[sync-docs] WARN: neither ${from} nor ${to} exists`)
    }
  }
}
