<script setup>
// 点击式日期时间选择器：全程以北京时区（UTC+8）展示与取值，输出 +08:00 的 ISO 字符串。
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { bjIso, isoToBjParts } from '../bjtime'

const props = defineProps({
  modelValue: { type: String, default: '' },
  placeholder: { type: String, default: '选择时间' }
})

const emit = defineEmits(['update:modelValue'])

const open = ref(false)
const wrap = ref(null)
const view = reactive({ y: 2026, m: 1, d: 1, hh: 0, mm: 0 })

watch(open, (isOpen) => {
  if (isOpen) {
    const parts = isoToBjParts(props.modelValue)
    view.y = parts.y
    view.m = parts.m
    view.d = parts.d
    view.hh = parts.hh
    view.mm = parts.mm
  }
})

const display = computed(() => {
  if (!props.modelValue) return ''
  return props.modelValue.replace('T', ' ').slice(0, 16)
})

const WEEKDAYS = ['一', '二', '三', '四', '五', '六', '日']

const monthTitle = computed(() => `${view.y} 年 ${view.m} 月`)

const dayCells = computed(() => {
  const first = new Date(Date.UTC(view.y, view.m - 1, 1))
  // 该月 1 号是星期几（周一为 0）
  const lead = (first.getUTCDay() + 6) % 7
  const days = new Date(Date.UTC(view.y, view.m, 0)).getUTCDate()
  const cells = []
  for (let i = 0; i < lead; i++) cells.push(null)
  for (let day = 1; day <= days; day++) cells.push(day)
  return cells
})

function prevMonth() {
  if (view.m === 1) { view.y--; view.m = 12 } else view.m--
}

function nextMonth() {
  if (view.m === 12) { view.y++; view.m = 1 } else view.m++
}

function pickDay(day) {
  view.d = day
}

function isToday(day) {
  const now = isoToBjParts('')
  return now.y === view.y && now.m === view.m && now.d === day
}

function isSelected(day) {
  if (!props.modelValue) return false
  const parts = isoToBjParts(props.modelValue)
  return parts.y === view.y && parts.m === view.m && parts.d === day
}

function pickNow() {
  const now = isoToBjParts('')
  view.y = now.y
  view.m = now.m
  view.d = now.d
  view.hh = now.hh
  view.mm = now.mm
  emit('update:modelValue', bjIso({ ...now }))
  open.value = false
}

function clear() {
  emit('update:modelValue', '')
  open.value = false
}

function confirm() {
  emit('update:modelValue', bjIso({ ...view }))
  open.value = false
}

function onDocClick(event) {
  if (open.value && wrap.value && !wrap.value.contains(event.target)) {
    open.value = false
  }
}

onMounted(() => document.addEventListener('mousedown', onDocClick))
onBeforeUnmount(() => document.removeEventListener('mousedown', onDocClick))
</script>

<template>
  <div class="dtp" ref="wrap">
    <div class="dtp-input" :class="{ open }" @click="open = !open">
      <span v-if="display" class="dtp-text">{{ display }}</span>
      <span v-else class="dtp-text placeholder">{{ placeholder }}</span>
      <span class="dtp-icon">📅</span>
    </div>

    <div v-if="open" class="dtp-pop" @mousedown.stop>
      <div class="dtp-head">
        <button type="button" class="dtp-nav" @click="prevMonth">‹</button>
        <span class="dtp-title">{{ monthTitle }}</span>
        <button type="button" class="dtp-nav" @click="nextMonth">›</button>
      </div>

      <div class="dtp-week">
        <span v-for="w in WEEKDAYS" :key="w">{{ w }}</span>
      </div>
      <div class="dtp-grid">
        <button
          v-for="(cell, index) in dayCells" :key="index" type="button"
          class="dtp-day" :disabled="!cell"
          :class="{ today: cell && isToday(cell), selected: cell && isSelected(cell) }"
          @click="cell && pickDay(cell)"
        >{{ cell || '' }}</button>
      </div>

      <div class="dtp-time">
        <span class="dtp-label">时间</span>
        <select v-model.number="view.hh">
          <option v-for="h in 24" :key="h" :value="h - 1">{{ String(h - 1).padStart(2, '0') }} 时</option>
        </select>
        <select v-model.number="view.mm">
          <option v-for="minute in [0, 5, 10, 15, 20, 25, 30, 35, 40, 45, 50, 55]" :key="minute" :value="minute">
            {{ String(minute).padStart(2, '0') }} 分
          </option>
        </select>
      </div>

      <div class="dtp-foot">
        <button type="button" class="ghost" @click="pickNow">此刻</button>
        <button type="button" class="ghost" @click="clear">清空</button>
        <button type="button" class="primary" @click="confirm">确定</button>
      </div>
    </div>
  </div>
</template>
