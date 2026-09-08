<script setup>
// 顶栏布局：品牌 / 导航 / 版本徽标 / 更新日志弹窗
import { computed, ref } from 'vue'
import changelogRaw from './docs/changelog.md?raw'
import { marked } from 'marked'

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
      <router-link to="/keys">API Key 管理</router-link>
    </nav>
    <span class="spacer"></span>
    <span class="version-badge">{{ currentVersion }}</span>
    <button class="changelog-btn" @click="showChangelog = true">更新日志</button>
  </header>

  <main class="main">
    <!-- 搜索页状态保留：从 Trace 页返回时筛选条件与结果不丢失 -->
    <router-view v-slot="{ Component }">
      <keep-alive include="SearchView">
        <component :is="Component" />
      </keep-alive>
    </router-view>
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
