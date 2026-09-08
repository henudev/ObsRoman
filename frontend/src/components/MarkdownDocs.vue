<script setup>
// 文档渲染组件：左侧目录导航 + 右侧内容，支持点击跳转与滚动高亮当前章节。
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { marked } from 'marked'

const props = defineProps({ source: { type: String, required: true } })

function slugify(text) {
  return String(text).trim().toLowerCase()
    .replace(/[^\p{L}\p{N}\s-]/gu, '')
    .replace(/\s+/g, '-')
}

// 给章节标题注入 id（与左侧目录的 slug 保持一致，用于锚点定位）
marked.use({
  renderer: {
    heading({ tokens, depth }) {
      const html = this.parser.parseInline(tokens)
      const plain = tokens.map((t) => t.text ?? t.raw ?? '').join('').trim().replace(/`/g, '')
      return `<h${depth} id="${slugify(plain)}">${html}</h${depth}>`
    }
  }
})

const html = computed(() => marked.parse(props.source))

// 从 markdown 原文提取标题（跳过代码块与一级标题）生成目录
const headings = computed(() => {
  const out = []
  let fence = false
  for (const line of props.source.split('\n')) {
    const t = line.trim()
    if (/^(```|~~~)/.test(t)) {
      fence = !fence
      continue
    }
    if (fence) continue
    const m = t.match(/^(#{2,4})\s+(.+?)\s*$/)
    if (m) {
      const text = m[2].replace(/`/g, '').trim()
      out.push({ level: m[1].length, text, id: slugify(text) })
    }
  }
  return out
})

const activeId = ref('')
let scrollListener = null

function scrollTo(id) {
  activeId.value = id
  document.getElementById(id)?.scrollIntoView({ behavior: 'smooth', block: 'start' })
}

function initScrollSpy() {
  if (scrollListener) window.removeEventListener('scroll', scrollListener)
  scrollListener = () => {
    const els = document.querySelectorAll('.docs-content h2, .docs-content h3, .docs-content h4')
    let current = ''
    els.forEach((el) => {
      if (el.getBoundingClientRect().top <= 90) current = el.id
    })
    activeId.value = current || (headings.value[0]?.id ?? '')
  }
  window.addEventListener('scroll', scrollListener, { passive: true })
  scrollListener()
}

watch(html, () => requestAnimationFrame(initScrollSpy), { flush: 'post' })

onBeforeUnmount(() => {
  if (scrollListener) window.removeEventListener('scroll', scrollListener)
})
</script>

<template>
  <div class="docs-layout">
    <aside class="docs-nav">
      <div class="docs-nav-title">目录</div>
      <a
        v-for="h in headings" :key="h.id" href="#"
        class="docs-toc" :class="['lvl-' + h.level, { active: activeId === h.id }]"
        @click.prevent="scrollTo(h.id)"
      >{{ h.text }}</a>
      <div v-if="!headings.length" class="muted" style="font-size:12px;padding:6px 8px">无章节</div>
    </aside>
    <section class="docs-content">
      <div class="markdown" v-html="html"></div>
    </section>
  </div>
</template>
