<script setup>
import { onMounted, ref } from 'vue'
import { apiRequest } from '../api'
import { fmtNumber } from '../charts'
import { fmtBj } from '../bjtime'

const props = defineProps({
  traceId: { type: String, required: true }
})

const trace = ref(null)
const loading = ref(false)
const errorText = ref('')

async function load() {
  loading.value = true
  errorText.value = ''
  try {
    trace.value = await apiRequest('GET', `/api/v1/traces/${props.traceId}`)
  } catch (e) {
    errorText.value = `${e.message}（code=${e.code}）`
  } finally {
    loading.value = false
  }
}

function offsetMs(log) {
  if (!trace.value || !log.timestamp) return null
  const start = Date.parse(trace.value.start_time)
  const current = Date.parse(log.timestamp)
  return current - start
}

function offsetText(log) {
  const offset = offsetMs(log)
  if (offset === null) return ''
  return (offset >= 0 ? '+' : '-') + (Math.abs(offset) / 1000).toFixed(3) + 's'
}

onMounted(load)
</script>

<template>
  <h1 class="page-title">Trace 链路</h1>

  <div v-if="errorText" class="error-banner">{{ errorText }}</div>

  <div class="card" v-if="trace">
    <div class="kpi-grid">
      <div class="kpi">
        <div class="label">Trace ID</div>
        <div class="mono" style="margin-top: 8px">{{ trace.trace_id }}</div>
      </div>
      <div class="kpi">
        <div class="label">状态</div>
        <div :class="'trace-status status-' + trace.status">{{ trace.status }}</div>
      </div>
      <div class="kpi">
        <div class="label">总耗时</div>
        <div class="value">{{ fmtNumber(trace.duration_ms) }} ms</div>
      </div>
      <div class="kpi">
        <div class="label">涉及服务（{{ trace.services?.length || 0 }}）</div>
        <div class="wrap" style="margin-top: 8px">
          <span v-for="s in trace.services" :key="s" class="mono" style="margin-right: 8px">{{ s }}</span>
        </div>
      </div>
    </div>

    <div class="muted" style="margin-bottom: 8px">
      {{ fmtBj(trace.start_time) }} →
      {{ fmtBj(trace.end_time) }}
      <span v-if="trace.truncated"> · 日志超过上限已截断</span>
    </div>
  </div>

  <div class="card">
    <h3>日志时间线（timestamp ASC，{{ trace?.logs?.length || 0 }} 条）</h3>
    <div class="waterfall">
      <div v-for="(log, index) in trace?.logs || []" :key="index" class="waterfall-row">
        <span class="t">{{ offsetText(log) }}</span>
        <span class="mono t">{{ log.timestamp?.replace('T', ' ').slice(11, 23) }}</span>
        <span class="svc">{{ log.service }}</span>
        <span :class="'level-tag level-' + log.level">{{ log.level }}</span>
        <span class="msg">
          <span class="mono muted">{{ log.event || '' }}</span>
          {{ log.message }}
          <span v-if="log.attributes" class="mono muted">
            {{ JSON.stringify(log.attributes) }}
          </span>
        </span>
        <span v-if="log.duration_ms != null" class="t">{{ log.duration_ms }}ms</span>
      </div>
      <div v-if="!trace?.logs?.length && !loading" class="muted" style="padding: 12px">
        该 Trace 没有日志（可能尚未写入或已超出查询范围）
      </div>
    </div>
  </div>
</template>
