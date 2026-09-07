<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { apiRequest } from '../api'
import { LEVEL_COLORS, PALETTE, fmtNumber, useChart } from '../charts'
import { fmtBj, minutesAgoBjIso, toBjIso } from '../bjtime'
import DateTimePicker from '../components/DateTimePicker.vue'

const router = useRouter()

// 与日志搜索保持一致的时间范围（快捷区间 + 自定义开始/结束）
const QUICK_RANGES = [
  { label: '15 分钟', minutes: 15 },
  { label: '1 小时', minutes: 60 },
  { label: '6 小时', minutes: 360 },
  { label: '24 小时', minutes: 1440 }
]

const filters = reactive({
  startTime: minutesAgoBjIso(60),
  endTime: '',
  environment: '',
  service: ''
})
const activeQuick = ref(60)

const loading = ref(false)
const errorText = ref('')
const overview = ref(null)
const trend = ref([])
const levels = ref([])
const ranking = ref([])
const recentErrors = ref([])

const trendChart = useChart()
const levelChart = useChart()
const rankChart = useChart()

const rangeText = computed(() => {
  const r = QUICK_RANGES.find((q) => q.minutes === activeQuick.value)
  return r ? r.label : '自定义范围'
})

function windowStartIso() {
  return filters.startTime
}

function windowEndIso() {
  return filters.endTime || toBjIso(new Date())
}

/** 选择快捷区间：确定起始时间，结束默认「现在」（与搜索页一致） */
function applyQuickRange(minutes) {
  activeQuick.value = minutes
  filters.startTime = minutesAgoBjIso(minutes)
  filters.endTime = ''
  refresh()
}

/** 自定义时间（点开日期选择器即退出快捷区间） */
function onCustomTime() {
  activeQuick.value = null
  refresh()
}

function body() {
  return {
    start_time: windowStartIso(),
    end_time: windowEndIso(),
    environment: filters.environment || null,
    service: filters.service || null
  }
}

async function refresh() {
  loading.value = true
  errorText.value = ''
  try {
    const payload = body()
    const [ov, tr, lv, rk] = await Promise.all([
      apiRequest('POST', '/api/v1/dashboard/overview', payload),
      apiRequest('POST', '/api/v1/dashboard/log-trend', payload),
      apiRequest('POST', '/api/v1/dashboard/level-distribution', payload),
      apiRequest('POST', '/api/v1/dashboard/service-ranking', payload)
    ])
    overview.value = ov
    trend.value = tr || []
    levels.value = lv || []
    ranking.value = rk || []
    const errors = await apiRequest('POST', '/api/v1/dashboard/recent-errors', {
      ...payload,
      size: 10
    })
    recentErrors.value = errors?.logs || []
    renderCharts()
  } catch (e) {
    errorText.value = `${e.message}（code=${e.code}）— 请检查右上角 API Key 与后端 /ready 状态`
  } finally {
    loading.value = false
  }
}

function renderCharts() {
  trendChart.mount(document.getElementById('trend-chart'), {
    tooltip: { trigger: 'axis' },
    grid: { left: 48, right: 16, top: 24, bottom: 28 },
    xAxis: { type: 'category', data: trend.value.map((p) => p.time), boundaryGap: false },
    yAxis: { type: 'value' },
    series: [{
      name: '日志数量',
      type: 'line',
      data: trend.value.map((p) => p.count),
      smooth: true,
      symbol: 'none',
      areaStyle: { opacity: 0.14 },
      lineStyle: { color: PALETTE.primaryDeep },
      color: PALETTE.primaryDeep
    }]
  }, onTrendClick)

  levelChart.mount(document.getElementById('level-chart'), {
    tooltip: { trigger: 'item' },
    series: [{
      name: '日志等级',
      type: 'pie',
      radius: ['45%', '72%'],
      label: { formatter: '{b}: {c}' },
      data: levels.value.map((l) => ({
        name: l.level,
        value: l.count,
        itemStyle: { color: LEVEL_COLORS[l.level] || '#64748b' }
      }))
    }]
  }, onLevelClick)

  const top = ranking.value.slice(0, 10)
  rankChart.mount(document.getElementById('rank-chart'), {
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: 'shadow' },
      formatter: (params) => {
        const item = top[params[0].dataIndex]
        if (!item) return ''
        return `${item.service}<br/>日志量: ${fmtNumber(item.total)}<br/>ERROR: ${fmtNumber(item.errors)}<br/>错误率: ` +
          `${(item.error_rate * 100).toFixed(2)}%`
      }
    },
    grid: { left: 140, right: 24, top: 16, bottom: 28 },
    xAxis: { type: 'value' },
    yAxis: { type: 'category', data: top.map((s) => s.service).reverse(), inverse: false },
    series: [{
      name: '日志量',
      type: 'bar',
      data: top.map((s) => s.total).reverse(),
      itemStyle: { color: PALETTE.primary, borderRadius: [0, 4, 4, 0] },
      barMaxWidth: 18
    }]
  }, onRankClick)
}

function onTrendClick(params) {
  const point = trend.value[params.dataIndex]
  if (!point) return
  const start = new Date(point.bucket)
  const end = new Date(point.bucket + 60_000)
  goSearch({ start, end, preset: { levels: [], service: '' } })
}

function onLevelClick(params) {
  goSearch({ start: null, end: null, preset: { levels: [params.name], service: '' } })
}

function onRankClick(params) {
  const item = ranking.value[params.dataIndex]
  if (!item) return
  goSearch({ start: null, end: null, preset: { levels: [], service: item.service } })
}

function goSearch({ start, end, preset }) {
  const query = {}
  if (start) query.start = toBjIso(start)
  if (end) query.end = toBjIso(end)
  if (preset.levels.length) query.levels = preset.levels.join(',')
  if (preset.service) query.service = preset.service
  if (filters.environment) query.environment = filters.environment
  router.push({ path: '/search', query })
}

function goTrace(log) {
  router.push({ path: `/trace/${log.trace_id}` })
}

function gotoErrorSearch() {
  goSearch({ start: null, end: null, preset: { levels: ['ERROR', 'FATAL'], service: '' } })
}

function gotoErrorRateKpi() {
  goSearch({ start: null, end: null, preset: { levels: ['ERROR', 'FATAL'], service: '' } })
}

function gotoTraceKpi() {
  goSearch({ start: windowStartIso(), end: windowEndIso(), preset: { levels: [], service: '' } })
}

function gotoTotalKpi() {
  goSearch({ start: windowStartIso(), end: windowEndIso(), preset: { levels: [], service: '' } })
}

let timer = null
onMounted(() => {
  refresh()
  timer = setInterval(refresh, 30_000)
  window.addEventListener('resize', onResize)
})
onBeforeUnmount(() => {
  clearInterval(timer)
  window.removeEventListener('resize', onResize)
  trendChart.dispose()
  levelChart.dispose()
  rankChart.dispose()
})
function onResize() {
  trendChart.resize()
  levelChart.resize()
  rankChart.resize()
}
</script>

<template>
  <h1 class="page-title">Dashboard · Overview</h1>

  <div v-if="errorText" class="error-banner">{{ errorText }}</div>

  <div class="card">
    <div class="toolbar">
      <div class="field" style="min-width: 360px">
        <label>时间范围</label>
        <div style="display:flex; align-items:center; gap:8px; flex-wrap:wrap">
          <div class="segmented">
            <button
              v-for="range in QUICK_RANGES" :key="range.minutes" type="button"
              :class="{ active: activeQuick === range.minutes }"
              @click="applyQuickRange(range.minutes)"
            >{{ range.label }}</button>
          </div>
          <DateTimePicker v-model="filters.startTime" placeholder="开始时间" @update:model-value="onCustomTime" />
          <span class="muted">至</span>
          <DateTimePicker v-model="filters.endTime" placeholder="现在" @update:model-value="onCustomTime" />
        </div>
      </div>
      <div class="field">
        <label>Environment</label>
        <select v-model="filters.environment" @change="refresh">
          <option value="">全部</option>
          <option v-for="env in ['local', 'dev', 'test', 'staging', 'prod']" :key="env" :value="env">{{ env }}</option>
        </select>
      </div>
      <div class="field">
        <label>Service（空 = 全部）</label>
        <select v-model="filters.service" @change="refresh">
          <option value="">全部</option>
          <option v-for="s in ranking" :key="s.service" :value="s.service">{{ s.service }}</option>
        </select>
      </div>
      <div class="field">
        <label>&nbsp;</label>
        <button class="primary" :disabled="loading" @click="refresh">{{ loading ? '加载中…' : '刷新' }}</button>
      </div>
      <span class="muted" style="margin-left:auto">范围：{{ rangeText }} · 30s 自动刷新</span>
    </div>

    <div class="kpi-grid">
      <div class="kpi clickable accent-total" @click="gotoTotalKpi">
        <div class="kpi-top"><span class="kpi-dot" style="background:#7d97ad"></span><span class="label">日志总量</span></div>
        <div class="value">{{ fmtNumber(overview?.total_logs) }}</div>
      </div>
      <div class="kpi clickable accent-error" @click="gotoErrorSearch">
        <div class="kpi-top"><span class="kpi-dot" style="background:#b07a7e"></span><span class="label">ERROR 数量</span></div>
        <div class="value error">{{ fmtNumber(overview?.error_logs) }}</div>
      </div>
      <div class="kpi clickable accent-rate" @click="gotoErrorRateKpi">
        <div class="kpi-top"><span class="kpi-dot" style="background:#8a5a5e"></span><span class="label">错误率</span></div>
        <div class="value error">{{ overview ? (overview.error_rate * 100).toFixed(2) + '%' : '-' }}</div>
      </div>
      <div class="kpi clickable accent-trace" @click="gotoTraceKpi">
        <div class="kpi-top"><span class="kpi-dot" style="background:#5f7d95"></span><span class="label">Trace 数量</span></div>
        <div class="value">{{ fmtNumber(overview?.trace_count) }}</div>
      </div>
    </div>

    <div class="kpi-sub">
      <div class="kpi accent-warn"><div class="kpi-top"><span class="kpi-dot" style="background:#c2ab84"></span><span class="label">WARN 数量</span></div><div class="value warn">{{ fmtNumber(overview?.warn_logs) }}</div></div>
      <div class="kpi accent-services"><div class="kpi-top"><span class="kpi-dot" style="background:#8ba888"></span><span class="label">活跃服务</span></div><div class="value">{{ fmtNumber(overview?.active_services) }}</div></div>
      <div class="kpi accent-duration"><div class="kpi-top"><span class="kpi-dot" style="background:#93a5b1"></span><span class="label">平均耗时</span></div><div class="value">{{ overview?.avg_duration_ms ?? '-' }} <span class="muted" style="font-size:12px">ms</span></div></div>
      <div class="kpi accent-p95"><div class="kpi-top"><span class="kpi-dot" style="background:#a9bcca"></span><span class="label">P95 耗时</span></div><div class="value">{{ overview?.p95_duration_ms ?? '-' }} <span class="muted" style="font-size:12px">ms</span></div></div>
      <div class="kpi accent-p99"><div class="kpi-top"><span class="kpi-dot" style="background:#b3bfc7"></span><span class="label">P99 耗时</span></div><div class="value">{{ overview?.p99_duration_ms ?? '-' }} <span class="muted" style="font-size:12px">ms</span></div></div>
    </div>
  </div>

  <div class="card">
    <h3>日志趋势（点击数据点跳转日志搜索）</h3>
    <div id="trend-chart" class="chart"></div>
  </div>

  <div class="grid-2">
    <div class="card">
      <h3>日志等级分布（点击跳转）</h3>
      <div id="level-chart" class="chart"></div>
    </div>
    <div class="card">
      <h3>服务日志 Top（点击跳转）</h3>
      <div id="rank-chart" class="chart"></div>
    </div>
  </div>

  <div class="card">
    <h3>
      最近 ERROR 日志
      <button class="ghost" style="float:right" @click="gotoErrorSearch">查看全部 ERROR</button>
    </h3>
    <table class="data-table">
      <colgroup>
        <col style="width: 172px" />
        <col style="width: 76px" />
        <col style="width: 140px" />
        <col style="width: 140px" />
        <col />
        <col style="width: 120px" />
      </colgroup>
      <thead>
        <tr>
          <th>时间</th><th>等级</th><th>服务</th><th>事件</th><th>消息</th><th>Trace</th>
        </tr>
      </thead>
      <tbody>
        <tr v-if="!recentErrors.length">
          <td colspan="6" class="muted">当前范围内没有 ERROR / FATAL 日志</td>
        </tr>
        <tr v-for="log in recentErrors" :key="log.trace_id + log.timestamp" class="clickable" @click="goTrace(log)">
          <td class="mono" :title="fmtBj(log.timestamp)">{{ fmtBj(log.timestamp) }}</td>
          <td><span :class="'level-tag level-' + log.level">{{ log.level }}</span></td>
          <td :title="log.service">{{ log.service }}</td>
          <td class="mono" :title="log.event || ''">{{ log.event || '-' }}</td>
          <td :title="log.message">{{ log.message }}</td>
          <td class="mono" :title="log.trace_id">{{ log.trace_id?.slice(0, 12) }}…</td>
        </tr>
      </tbody>
    </table>
  </div>
</template>
