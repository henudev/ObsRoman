<script setup>
// 页面直接渲染完整 API 文档（源：docs/API.md，构建时同步）
import { computed } from 'vue'
import apiDocRaw from '../docs/api.md?raw'
import { marked } from 'marked'

const apiDocHtml = computed(() => marked.parse(apiDocRaw))
</script>

<template>
  <h1 class="page-title">API 文档</h1>
  <div class="card">
    <div class="markdown" v-html="apiDocHtml"></div>
  </div>
</template>
