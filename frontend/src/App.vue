<script setup>
// 顶栏布局：品牌 / 导航 / API Key / 版本徽标 / 更新日志弹窗
import { computed, ref, watch } from 'vue'
import { getApiKey, setApiKey } from './api'
import changelogRaw from './docs/changelog.md?raw'
import { marked } from 'marked'

const keyInput = ref(getApiKey())
watch(keyInput, (value) => setApiKey(value ?? ''))

const showChangelog = ref(false)

// 从 CHANGELOG 解析最新版本号：## [vX.Y.Z] - 日期
const currentVersion = computed(() => {
  const match = changelogRaw.match(/^## \[(v[\d.]+)\]/m)
  return match ? match[1] : ''
})

const changelogHtml = computed(() => marked.parse(changelogRaw))
</script>

<template>
  <header class="topbar">
    <span class="brand">Trace Log Service</span>
    <nav>
      <router-link to="/">Dashboard</router-link>
      <router-link to="/search">日志搜索</router-link>
      <router-link to="/docs">API 文档</router-link>
      <router-link to="/sdk">SDK 文档</router-link>
    </nav>
    <span class="spacer"></span>
    <input class="key-input" v-model="keyInput" placeholder="API Key（Bearer）" title="Authorization: Bearer <api-key>" />
    <span class="version-badge">{{ currentVersion }}</span>
    <button class="changelog-btn" @click="showChangelog = true">更新日志</button>
  </header>

  <main class="main">
    <router-view />
  </main>

  <div v-if="showChangelog" class="modal-mask" @click.self="showChangelog = false">
    <div class="modal">
      <div class="modal-head">
        <h2>版本更新日志</h2>
        <button class="close" @click="showChangelog = false">✕</button>
      </div>
      <div class="markdown" v-html="changelogHtml"></div>
    </div>
  </div>
</template>
