<script setup>
import { computed, onActivated, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { apiExport, apiRequest, downloadBlob } from '../api'
import { fmtNumber } from '../charts'
import { fmtBj, minutesAgoBjIso } from '../bjtime'
import DateTimePicker from '../components/DateTimePicker.vue'

// 单根组件（keep-alive 要求）；显式命名供 <keep-alive include> 匹配
defineOptions({ name: 'SearchView' })

const route = useRoute()
const router = useRouter()

const LEVELS = ['TRACE', 'DEBUG', 'INFO', 'WARN', 'ERROR', 'FATAL']
const ENVIRONMENTS = ['local', 'dev', 'test', 'staging', 'prod']

const QUICK_RANGES = [
  { label: '15 分钟', minutes: 15 },
  { label: '1 小时', minutes: 60 },
  { label: '6 小时', minutes: 360 },
  { label: '24 小时', minutes: 1440 },
  { label: '7 天', minutes: 10080 }
]

const form = reactive({
  // 默认时间范围与 Dashboard 一致：最近 7 天
  startTime: minutesAgoBjIso(10080),
  endTime: '',
  service: '',
  environment: [],
  levels: [],
  type: '',
  traceId: route.query.trace_id || '',
  requestId: '',
  userId: '',
  apiKeyAk: '',
  keyword: '',
  page: 1,
  size: 50
})

const showAdvanced = ref(false)
const activeQuick = ref(10080)

const result = ref(null)
const loading = ref(false)
const errorText = ref('')
const exporting = ref('')
const toast = ref('')
let toastTimer = null

function showToast(text, warn) {
  toast.value = text
  clearTimeout(toastTimer)
  toastTimer = setTimeout(() => (toast.value = ''), 4200)
}

/** 已生效的高级筛选数量（收起时在开关上提示） */
const advancedActiveCount = computed(() => {
  let count = 0
  if (form.levels.length) count++
  if (form.environment.length) count++
  if (form.service) count++
  if (form.type) count++
  if (form.traceId) count++
  if (form.requestId) count++
  if (form.userId) count++
  if (form.apiKeyAk) count++
  return count
})

function applyQuickRange(minutes) {
  activeQuick.value = minutes
  form.startTime = minutesAgoBjIso(minutes)
  form.endTime = ''
}

function onCustomTime() {
  activeQuick.value = null
}

function resetFilters() {
  form.startTime = minutesAgoBjIso(10080)
  form.endTime = ''
  form.service = ''
  form.environment = []
  form.levels = []
  form.type = ''
  form.traceId = ''
  form.requestId = ''
  form.userId = ''
  form.apiKeyAk = ''
  form.keyword = ''
  form.page = 1
  activeQuick.value = 10080
  search(1)
}

async function search(page) {
  form.page = page || 1
  loading.value = true
  errorText.value = ''
  try {
    result.value = await apiRequest('POST', '/api/v1/logs/search', payload())
  } catch (e) {
    errorText.value = `${e.message}（code=${e.code}）`
    result.value = null
  } finally {
    loading.value = false
  }
}

function payload() {
  return {
    start_time: form.startTime,
    end_time: form.endTime || undefined,
    service: form.service ? [form.service.trim()] : null,
    environment: form.environment.length ? form.environment : null,
    level: form.levels.length ? form.levels : null,
    type: form.type ? [form.type.trim()] : null,
    trace_id: form.traceId || null,
    request_id: form.requestId || null,
    user_id: form.userId || null,
    api_key_ak: form.apiKeyAk || null,
    keyword: form.keyword || null,
    page: form.page,
    size: form.size
  }
}

async function doExport(format) {
  exporting.value = format
  errorText.value = ''
  try {
    const { blob, rows } = await apiExport(payload(), format)
    const stamp = new Date().toISOString().replace(/[-:T]/g, '').slice(0, 14)
    downloadBlob(blob, `logs-${stamp}.${format}`)
    if (rows === 0) {
      showToast('导出完成：当前条件没有命中任何日志（文件仅含表头）。请扩大时间范围或减少过滤条件。', true)
    } else {
      showToast(`导出完成：共 ${fmtNumber(rows)} 条日志已下载（${format.toUpperCase()}）。`)
    }
  } catch (e) {
    errorText.value = `导出失败：${e.message}（code=${e.code}）`
  } finally {
    exporting.value = ''
  }
}

function goTrace(log) {
  router.push({ path: `/trace/${log.trace_id}` })
}

function totalPages() {
  if (!result.value) return 1
  return Math.max(1, Math.ceil(result.value.total / result.value.size))
}

function toggle(list, value) {
  const index = list.indexOf(value)
  if (index >= 0) list.splice(index, 1)
  else list.push(value)
}

/** 应用路由 query 携带的过滤条件（Dashboard 跳转 / 直达链接）；返回是否有条件被应用 */
function applyRouteQuery() {
  const query = route.query
  let applied = false
  if (query.trace_id) { form.traceId = String(query.trace_id); applied = true }
  if (query.start) { form.startTime = String(query.start); activeQuick.value = null; applied = true }
  if (query.end) { form.endTime = String(query.end); applied = true }
  if (query.levels) { form.levels = String(query.levels).split(',').filter(Boolean); applied = true }
  if (query.service) { form.service = String(query.service); applied = true }
  if (query.environment) { form.environment = String(query.environment).split(',').filter(Boolean); applied = true }
  if (query.api_key_ak) { form.apiKeyAk = String(query.api_key_ak); applied = true }
  return applied
}

// 组件被 keep-alive 缓存：初次进入与再次激活（如从 Trace 页返回）都会触发
// - 路由携带过滤条件（Dashboard 跳转 / 直达链接）→ 应用并搜索
// - 无条件返回 → 保留上次的筛选与结果状态
onActivated(() => {
  const applied = applyRouteQuery()
  // 直接点「日志搜索」进入（URL 未带 levels，如从 Trace 页返回）时，重置日志等级为不选，
  // 避免 keep-alive 保留上次选中的等级；Dashboard 跳转携带的 levels 仍会生效
  if (!route.query.levels) {
    form.levels = []
  }
  if (applied || !result.value) {
    search(1)
  }
})
</script>

<template>
  <div class="search-page">
  <h1 class="page-title">日志搜索</h1>

  <div v-if="errorText" class="error-banner">{{ errorText }}</div>
  <div v-if="toast" class="toast" :class="{ warn: toast.startsWith('导出完成：当前') }">{{ toast }}</div>

  <div class="search-layout">
  <!-- 左侧：筛选 -->
  <aside class="card search-filters">
    <!-- 时间范围 -->
    <div class="filter-section">
      <div class="filter-title">时间范围 <span class="muted" style="font-weight:400;letter-spacing:0">北京时间 · 默认最近 7 天</span></div>
      <div class="filter-row">
        <div class="segmented">
          <button
            v-for="range in QUICK_RANGES" :key="range.minutes" type="button"
            :class="{ active: activeQuick === range.minutes }"
            @click="applyQuickRange(range.minutes)"
          >{{ range.label }}</button>
        </div>
        <DateTimePicker v-model="form.startTime" placeholder="开始时间" @update:model-value="onCustomTime" />
        <span class="muted">至</span>
        <DateTimePicker v-model="form.endTime" placeholder="现在" @update:model-value="onCustomTime" />
        <span class="spacer" style="flex:1"></span>
        <button type="button" class="filter-toggle" @click="showAdvanced = !showAdvanced">
          {{ showAdvanced ? '收起筛选 ▲' : '更多筛选 ▼' }}
          <span v-if="!showAdvanced && advancedActiveCount" class="badge">{{ advancedActiveCount }}</span>
        </button>
      </div>
    </div>

    <div class="filter-divider"></div>

    <!-- 高级筛选：等级 / 环境 -->
    <div v-show="showAdvanced" class="filter-section">
      <div class="filter-grid">
        <div class="filter-cell">
          <label>日志等级（可多选）</label>
          <div class="chips">
            <button
              v-for="lv in LEVELS" :key="lv" type="button"
              class="chip" :class="{ active: form.levels.includes(lv) }"
              @click="toggle(form.levels, lv)"
            >{{ lv }}</button>
          </div>
        </div>
        <div class="filter-cell">
          <label>Environment（可多选）</label>
          <div class="chips">
            <button
              v-for="env in ENVIRONMENTS" :key="env" type="button"
              class="chip" :class="{ active: form.environment.includes(env) }"
              @click="toggle(form.environment, env)"
            >{{ env }}</button>
          </div>
        </div>
        <div class="filter-cell">
          <label>Type</label>
          <input v-model="form.type" placeholder="application" @keyup.enter="search(1)" />
        </div>
      </div>
    </div>

    <!-- 高级筛选：精确匹配 -->
    <div v-show="showAdvanced">
      <div class="filter-divider"></div>
      <div class="filter-section">
        <div class="filter-title">精确匹配</div>
        <div class="filter-grid">
          <div class="filter-cell">
            <label>Service</label>
            <input v-model="form.service" placeholder="order-service" @keyup.enter="search(1)" />
          </div>
          <div class="filter-cell">
            <label>trace_id</label>
            <input v-model="form.traceId" class="mono" placeholder="32 位十六进制" @keyup.enter="search(1)" />
          </div>
          <div class="filter-cell">
            <label>request_id / user_id</label>
            <div style="display:flex; gap:8px">
              <input v-model="form.requestId" placeholder="req_001" @keyup.enter="search(1)" />
              <input v-model="form.userId" placeholder="10001" @keyup.enter="search(1)" />
            </div>
          </div>
          <div class="filter-cell">
            <label>接入应用（api_key_ak）</label>
            <input v-model="form.apiKeyAk" class="mono" placeholder="OB-…" @keyup.enter="search(1)" />
          </div>
        </div>
      </div>
    </div>

    <!-- 关键词（高频条件，始终可见） -->
    <div class="filter-divider"></div>
    <div class="filter-section" style="padding-bottom:0">
      <div class="filter-title">关键词 <span class="optional">匹配 message / event / attributes，大小写不敏感</span></div>
      <input
        v-model="form.keyword" placeholder="如：timeout、订单号、异常关键字…"
        style="width:100%" @keyup.enter="search(1)"
      />
    </div>

    <!-- 操作区 -->
    <div class="filter-actions">
      <button class="ghost" @click="resetFilters">重置</button>
      <span class="muted" style="font-size:12px">每页</span>
      <select v-model.number="form.size" style="width:auto">
        <option :value="20">20</option>
        <option :value="50">50</option>
        <option :value="100">100</option>
        <option :value="500">500</option>
      </select>
      <span class="spacer"></span>
      <button class="ghost" :disabled="exporting !== ''" @click="doExport('csv')">
        {{ exporting === 'csv' ? '导出中…' : '⬇ CSV' }}
      </button>
      <button class="ghost" :disabled="exporting !== ''" @click="doExport('jsonl')">
        {{ exporting === 'jsonl' ? '导出中…' : '⬇ JSONL' }}
      </button>
      <button class="primary" :disabled="loading" @click="search(1)">{{ loading ? '搜索中…' : '搜索' }}</button>
    </div>
  </aside>

  <!-- 右侧：结果列表 -->
  <section class="card search-results">
    <div v-if="result" class="muted" style="margin-bottom: 10px">
      命中 <b>{{ fmtNumber(result.total) }}</b> 条，第 {{ result.page }} / {{ totalPages() }} 页
      <span v-if="form.startTime" style="margin-left: 12px">
        范围：{{ fmtBj(form.startTime) }} ~ {{ form.endTime ? fmtBj(form.endTime) : '现在' }}（北京时间）
      </span>
    </div>
    <table class="data-table">
      <colgroup>
        <col style="width: 172px" />
        <col style="width: 76px" />
        <col style="width: 130px" />
        <col style="width: 120px" />
        <col />
        <col style="width: 116px" />
        <col style="width: 64px" />
        <col style="width: 110px" />
        <col style="width: 260px" />
      </colgroup>
      <thead>
        <tr>
          <th>时间（北京）</th><th>等级</th><th>服务</th><th>事件</th><th>消息</th>
          <th>Trace</th><th>耗时</th><th>接入</th><th>Attributes</th>
        </tr>
      </thead>
      <tbody>
        <tr v-if="result && !result.logs.length">
          <td colspan="9" class="muted">没有符合条件的日志，请调整时间范围或过滤条件</td>
        </tr>
        <tr v-for="log in result?.logs || []" :key="log.timestamp + log.trace_id" class="clickable" @click="goTrace(log)">
          <td class="mono" :title="fmtBj(log.timestamp)">{{ fmtBj(log.timestamp) }}</td>
          <td><span :class="'level-tag level-' + log.level">{{ log.level }}</span></td>
          <td :title="log.service">{{ log.service }}</td>
          <td class="mono" :title="log.event || ''">{{ log.event || '-' }}</td>
          <td :title="log.message">{{ log.message }}</td>
          <td class="mono" :title="log.trace_id">{{ log.trace_id?.slice(0, 12) }}…</td>
          <td :title="log.duration_ms != null ? log.duration_ms + ' ms' : ''">{{ log.duration_ms ?? '-' }}</td>
          <td class="mono" :title="log.api_key_ak || ''">{{ log.api_key_ak || '-' }}</td>
          <td class="mono" :title="log.attributes ? JSON.stringify(log.attributes) : ''">
            {{ log.attributes ? JSON.stringify(log.attributes) : '-' }}
          </td>
        </tr>
      </tbody>
    </table>

    <div v-if="result" class="pagination">
      <button class="ghost" :disabled="result.page <= 1" @click="search(result.page - 1)">上一页</button>
      <span class="muted">{{ result.page }} / {{ totalPages() }}</span>
      <button class="ghost" :disabled="result.page >= totalPages()" @click="search(result.page + 1)">下一页</button>
    </div>
  </section>
  </div>
  </div>
</template>
