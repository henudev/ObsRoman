<script setup>
// 页面直接渲染完整 SDK 使用文档（源：docs/SDK.md，构建时同步），左侧为目录导航
import MarkdownDocs from '../components/MarkdownDocs.vue'
import sdkDocRaw from '../docs/sdk.md?raw'
</script>

<template>
  <MarkdownDocs :source="sdkDocRaw" />
</template>
