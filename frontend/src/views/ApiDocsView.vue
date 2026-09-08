<script setup>
// 页面直接渲染完整 API 文档（源：docs/API.md，构建时同步），左侧为目录导航
import MarkdownDocs from '../components/MarkdownDocs.vue'
import apiDocRaw from '../docs/api.md?raw'
</script>

<template>
  <MarkdownDocs :source="apiDocRaw" />
</template>
