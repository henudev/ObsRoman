<script setup>
// 页面直接渲染完整 SDK 使用文档（源：docs/SDK.md，构建时同步）
import { computed } from 'vue'
import sdkDocRaw from '../docs/sdk.md?raw'
import { marked } from 'marked'

const sdkDocHtml = computed(() => marked.parse(sdkDocRaw))
</script>

<template>
  <h1 class="page-title">SDK 使用文档</h1>
  <div class="card">
    <div class="markdown" v-html="sdkDocHtml"></div>
  </div>
</template>
