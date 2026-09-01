# 更新日志（Release Notes）

本文件是 Trace Log Service 的版本发布记录，前端页面右上角「更新日志」与本文件同步。

格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，版本号遵循语义化版本。

## [v1.2.1] - 2026-09-01 16:55

### 优化
- Dashboard 统计指标卡片重设计：语义色左色条 + 色点标签 + 渐变卡面，KPI 分层更清晰（主指标 4 张 / 次指标 5 张），悬停浮起动效
- 日志搜索筛选面板重构：快捷区间改为分段选择器（segmented control）、条件按「时间范围 / 等级与环境 / 精确匹配 / 关键词」分组、高级筛选可折叠（收起时显示生效条数角标）、新增一键重置、任意输入框回车即搜索、操作按钮右对齐

### 修复
- 更新日志弹窗宽度由 720px 调整为 920px，阅读体验更舒适

### 变更
- 版本号日期统一附时间点信息（HH:mm），右上角徽标同步展示

## [v1.2.0] - 2026-09-01 16:30

### 新增
- **Java SDK**（`sdk/java`）：零第三方依赖（JDK 17+），异步凑批写入、失败重试、队列满丢弃计数、W3C Trace Context 传播、trace_id 本地自动生成；附 10 个单元测试（内置 HTTP 假服务端）
- **Python SDK**（`sdk/python`）：零第三方依赖（Python 3.9+ 标准库），能力与 Java SDK 对齐；附 11 个单元测试与可运行示例 `example.py`
- **SDK 使用文档** `docs/SDK.md`：安装、快速开始、配置项、Spring Boot / FastAPI 集成示例、Trace 传播规范、FAQ
- 前端新增「SDK 文档」页面（`/sdk` 路由），直接渲染 docs/SDK.md

### 优化
- 补充部署文件说明：backend/.dockerignore（避免构建上下文带入 target/）、README 增加部署文件清单与常用命令

### 文档
- README 增加 SDK 与部署说明章节

## [v1.1.0] - 2026-09-01 15:40

### 新增
- 搜索页时间范围改为点击式日期时间选择器，全站时间统一为北京时区（Asia/Shanghai），服务端容器时区同步固定为 +08:00
- 页面右上角新增版本号徽标与「更新日志」弹窗（本文件）
- 新增「API 文档」页面，直接在页面上渲染完整接口文档（`/docs` 路由）
- 导出接口新增 `X-Export-Rows` 响应头，前端在导出完成后提示导出行数，0 条时明确警告
- `/health` 返回 `version` 与 `release_date`

### 优化
- 搜索页整体重构：快捷时间区间（15 分钟 / 1 小时 / 6 小时 / 24 小时）、等级与环境改为可点选标签、默认时间范围调整为最近 1 小时、布局与交互统一
- 全站配色统一为莫兰迪蓝系列（低饱和蓝灰基调），图表与日志等级颜色同步调整
- 导出为空时给出明确原因提示（当前条件未命中日志），避免"下载了空文件"的困惑

### 修复
- 修复导出文件为空的问题（根因：默认 15 分钟窗口内无日志命中；现通过默认区间扩大 + 行数提示双重解决）

### 文档
- API 文档按"功能描述 / 请求参数 / 返回参数 / 异常码 / curl 示例"五段式重构，增加版本信息与更新日期

## [v1.0.0] - 2026-09-01 14:10

### P0 初始发布
- 统一日志模型（一级字段白名单，扩展字段进 attributes）
- 单条 / 批量日志写入（批量支持部分成功，≤500 条 / ≤5 MB / 单条 64 KB）
- 内存队列异步写入 + 后台批量持久化（队列满 DROP 并计数，日志故障不影响业务）
- OpenObserve 存储适配（写入重试 / 超时分层 / 字段感知 Schema）
- trace_id 链路关联与 Trace 查询（timestamp 升序，状态判定 SUCCESS/ERROR）
- 日志多条件搜索与分页（≤7 天）
- Dashboard Overview 单页（总量 / ERROR / 错误率 / Trace 数 / 活跃服务 / 耗时 P95·P99 + 趋势 + 等级分布 + 服务 Top + 最近 ERROR）
- 流式导出 CSV / JSONL（复用搜索条件，≤24 小时 / ≤100,000 条）
- API Key 鉴权（log:write / log:read / trace:read / dashboard:read / log:export，写入 Key 可绑定 service/environment）
- /health 与 /ready（OpenObserve 故障降级 DEGRADED 不崩溃）
- 66 个单元测试 + 6 个 OpenObserve 集成测试 + Docker Compose 一键部署
