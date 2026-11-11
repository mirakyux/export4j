<!-- SPDX-License-Identifier: Apache-2.0 -->
<script setup lang="ts">
import { computed } from 'vue'
import { useData, useRoute, useRouter } from 'vitepress'

const { lang } = useData()
const route = useRoute()
const router = useRouter()
const language = computed(() => lang.value.startsWith('zh') ? 'zh' : 'en')
const counterparts: Record<string, string> = {
  '/': '/zh/', '/zh/': '/',
  '/getting-started': '/zh/getting-started', '/zh/getting-started': '/getting-started',
  '/docs/faq': '/zh/docs/faq', '/zh/docs/faq': '/docs/faq'
}

function changeLanguage(event: Event) {
  const selected = (event.target as HTMLSelectElement).value
  if (selected === language.value) return
  const current = route.path.replace(/\.html$/, '')
  // Detailed guides currently have Chinese content only.
  void router.go(counterparts[current] || (selected === 'zh' ? '/zh/' : '/'))
}
</script>

<template>
  <div class="language-switch">
    <svg aria-hidden="true" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
      <path d="M3 5h12M9 2v3m4 0c-1 6-4 10-9 12m1-9c2 4 5 7 9 9m1 4 4-10 4 10m-6.8-3h5.6" />
    </svg>
    <select :value="language" :aria-label="language === 'zh' ? '切换语言' : 'Change language'" @change="changeLanguage">
      <option value="en">English</option>
      <option value="zh">简体中文</option>
    </select>
  </div>
</template>
