<script setup>
import { onMounted, reactive, ref } from 'vue'
import { apiKeyCreate, apiKeyDelete, apiKeysList, apiKeyUpdate } from '../api'

const list = ref([])
const loading = ref(false)
const errorText = ref('')
const toast = ref('')
let toastTimer = null

const createForm = reactive({ name: '', service: '', environment: '' })
const creating = ref(false)
// 创建成功后一次性展示的 ak / sk
const created = ref(null)

// 目标行正在执行的操作（启停/删除）
const busyAk = ref('')

function showToast(text, warn) {
  toast.value = text
  clearTimeout(toastTimer)
  toastTimer = setTimeout(() => (toast.value = ''), 4200)
}

function fmtTime(ms) {
  if (!ms) return '-'
  const d = new Date(ms)
  return d.toLocaleString('zh-CN', { hour12: false })
}

async function load() {
  loading.value = true
  errorText.value = ''
  try {
    list.value = await apiKeysList()
  } catch (e) {
    errorText.value = `${e.message}（code=${e.code}）`
  } finally {
    loading.value = false
  }
}

async function createKey() {
  if (!createForm.name.trim()) {
    errorText.value = '请填写应用名称'
    return
  }
  errorText.value = ''
  creating.value = true
  try {
    const data = await apiKeyCreate({
      name: createForm.name.trim(),
      service: createForm.service.trim() || null,
      environment: createForm.environment.trim() || null
    })
    created.value = data
    createForm.name = ''
    createForm.service = ''
    createForm.environment = ''
    await load()
  } catch (e) {
    errorText.value = `创建失败：${e.message}（code=${e.code}）`
  } finally {
    creating.value = false
  }
}

async function toggleEnabled(key) {
  busyAk.value = key.ak
  errorText.value = ''
  try {
    await apiKeyUpdate(key.ak, { enabled: !key.enabled })
    await load()
  } catch (e) {
    errorText.value = `更新失败：${e.message}（code=${e.code}）`
  } finally {
    busyAk.value = ''
  }
}

async function removeKey(key) {
  if (!confirm(`确认删除应用「${key.name}」（${key.ak}）？删除后不可恢复。`)) return
  busyAk.value = key.ak
  errorText.value = ''
  try {
    await apiKeyDelete(key.ak)
    showToast('已删除')
    await load()
  } catch (e) {
    errorText.value = `删除失败：${e.message}（code=${e.code}）`
  } finally {
    busyAk.value = ''
  }
}

function copySecret(text) {
  navigator.clipboard?.writeText(text).then(() => showToast('已复制'))
}

onMounted(load)
</script>

<template>
  <div class="keys-page">
    <h1 class="page-title">API Key 管理</h1>

    <div v-if="errorText" class="error-banner">{{ errorText }}</div>
    <div v-if="toast" class="toast">{{ toast }}</div>

    <!-- 创建 -->
    <div class="card">
      <h3>创建接入 Key（AK/SK）</h3>
      <div class="filter-grid">
        <div class="filter-cell">
          <label>应用名称（name）*</label>
          <input v-model="createForm.name" placeholder="如：order-service" />
        </div>
        <div class="filter-cell">
          <label>绑定 Service（可选）</label>
          <input v-model="createForm.service" placeholder="order-service" />
        </div>
        <div class="filter-cell">
          <label>绑定 Environment（可选）</label>
          <input v-model="createForm.environment" placeholder="prod" />
        </div>
      </div>
      <div class="filter-actions">
        <button class="primary" :disabled="creating" @click="createKey">
          {{ creating ? '创建中…' : '创建 Key' }}
        </button>
      </div>
    </div>

    <!-- 一次性展示 AK/SK -->
    <div v-if="created" class="card" style="border-color: var(--primary); margin-top: 12px">
      <h3>创建成功（Secret 仅此一次展示，请妥善保存）</h3>
      <div class="filter-cell" style="margin-top: 8px">
        <label>Access Key (AK)</label>
        <div class="mono secret-row">{{ created.ak }}
          <button class="ghost" @click="copySecret(created.ak)">复制</button>
        </div>
      </div>
      <div class="filter-cell" style="margin-top: 8px">
        <label>Secret Key (SK)</label>
        <div class="mono secret-row">{{ created.sk }}
          <button class="ghost" @click="copySecret(created.sk)">复制</button>
        </div>
      </div>
      <p class="muted" style="margin-top: 8px">用法：<code>Authorization: Bearer &lt;ak&gt;:&lt;sk&gt;</code>（SDK 把 apiKey 设为 <code>ak:sk</code> 即可）。</p>
      <div class="filter-actions">
        <button class="ghost" @click="created = null">我已保存</button>
      </div>
    </div>

    <!-- 列表 -->
    <div class="card" style="margin-top: 12px">
      <h3>已接入的应用（{{ list.length }}）</h3>
      <table class="data-table">
        <thead>
          <tr>
            <th>名称</th><th>AK</th><th>绑定</th><th>状态</th><th>创建时间</th><th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-if="loading && !list.length"><td colspan="6" class="muted">加载中…</td></tr>
          <tr v-else-if="!list.length"><td colspan="6" class="muted">暂无 Key，请先创建。</td></tr>
          <tr v-for="key in list" :key="key.ak">
            <td :title="key.name">{{ key.name }}<span v-if="key.legacy" class="badge">种子</span></td>
            <td class="mono" :title="key.ak">{{ key.ak }}</td>
            <td>
              <span v-if="key.boundService">{{ key.boundService }}</span>
              <span v-if="key.boundEnvironment"> · {{ key.boundEnvironment }}</span>
              <span v-if="!key.boundService && !key.boundEnvironment" class="muted">不限</span>
            </td>
            <td>
              <span :class="'level-tag ' + (key.enabled ? 'level-INFO' : 'level-WARN')">
                {{ key.enabled ? '启用' : '停用' }}
              </span>
            </td>
            <td class="mono">{{ fmtTime(key.createdAt) }}</td>
            <td>
              <div style="display:flex; gap:6px; align-items:center">
                <button class="ghost" :disabled="busyAk === key.ak" @click="toggleEnabled(key)">
                  {{ key.enabled ? '停用' : '启用' }}
                </button>
                <button class="ghost danger" :disabled="busyAk === key.ak" @click="removeKey(key)">删除</button>
              </div>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>
</template>

<style scoped>
.secret-row {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 10px;
  background: var(--panel-2);
  border: 1px solid var(--border-soft);
  border-radius: 8px;
  word-break: break-all;
}
.danger { color: #b4544f; }
</style>
