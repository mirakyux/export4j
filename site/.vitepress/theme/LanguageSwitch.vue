<!-- SPDX-License-Identifier: Apache-2.0 -->
<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, useId, watch } from 'vue'
import { useData, useRoute, withBase } from 'vitepress'

const { lang, frontmatter } = useData()
const route = useRoute()
const id = useId()
const root = ref<HTMLElement>()
const trigger = ref<HTMLButtonElement>()
const open = ref(false)
const chinese = computed(() => lang.value.startsWith('zh'))
const options = computed(() => {
  const translations = frontmatter.value.translations ?? {}
  return [
    { code: 'en', label: 'English', href: !chinese.value ? route.path : translations.en ?? '/docs/README', active: !chinese.value,
      note: !translations.en && chinese.value ? 'Guides' : '' },
    { code: 'zh', label: '简体中文', href: chinese.value ? route.path : translations.zh ?? '/zh/docs/README', active: chinese.value, note: '' }
  ]
})

function close(restoreFocus = false) {
  open.value = false
  if (restoreFocus) trigger.value?.focus()
}

function selectLanguage(event: MouseEvent, active: boolean) {
  if (active) event.preventDefault()
  close(active)
}

async function focusOption(index: number) {
  open.value = true
  await nextTick()
  root.value?.querySelectorAll<HTMLAnchorElement>('.language-option')[index]?.focus()
}

function navigateOptions(event: KeyboardEvent) {
  const links = [...(root.value?.querySelectorAll<HTMLAnchorElement>('.language-option') ?? [])]
  const index = links.indexOf(document.activeElement as HTMLAnchorElement)
  if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
    event.preventDefault()
    const delta = event.key === 'ArrowDown' ? 1 : -1
    links[(index + delta + links.length) % links.length]?.focus()
  } else if (event.key === 'Home' || event.key === 'End') {
    event.preventDefault()
    links[event.key === 'Home' ? 0 : links.length - 1]?.focus()
  }
}

function outside(event: PointerEvent) {
  if (event.target instanceof Node && !root.value?.contains(event.target)) close()
}

function leave(event: FocusEvent) {
  if (!root.value?.contains(event.relatedTarget as Node | null)) close()
}

watch(() => route.path, () => close())
onMounted(() => document.addEventListener('pointerdown', outside))
onBeforeUnmount(() => document.removeEventListener('pointerdown', outside))
</script>

<template>
  <div ref="root" class="language-switch" @focusout="leave" @keydown.esc.stop.prevent="close(true)">
    <button ref="trigger" class="language-trigger" type="button"
      :aria-label="chinese ? '切换语言' : 'Change language'"
      :aria-expanded="open" :aria-controls="`languages-${id}`"
      @click="open = !open" @keydown.down.prevent="focusOption(0)" @keydown.up.prevent="focusOption(1)">
      <svg aria-hidden="true" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round">
        <path d="M3 5h12M9 2v3m4 0c-1 6-4 10-9 12m1-9c2 4 5 7 9 9m1 4 4-10 4 10m-6.8-3h5.6" />
      </svg>
      <span>{{ chinese ? '简体中文' : 'English' }}</span>
      <svg class="language-chevron" :class="{ expanded: open }" aria-hidden="true" width="12" height="12" viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.5"><path d="m4 6 4 4 4-4" /></svg>
    </button>
    <div v-if="open" :id="`languages-${id}`" class="language-popover"
      :aria-label="chinese ? '语言' : 'Languages'" @keydown="navigateOptions">
      <a v-for="option in options" :key="option.code" class="language-option"
        :class="{ selected: option.active }" :href="withBase(option.href)" :lang="option.code === 'zh' ? 'zh-CN' : 'en'"
        :hreflang="option.code === 'zh' ? 'zh-CN' : 'en'" :aria-current="option.active ? 'true' : undefined"
        @click="selectLanguage($event, option.active)">
        <span>{{ option.label }}<span v-if="option.note" class="language-note">{{ ` (${option.note})` }}</span></span>
        <svg v-if="option.active" aria-hidden="true" width="16" height="16" viewBox="0 0 20 20" fill="none" stroke="currentColor" stroke-width="1.5"><path d="m4 10 4 4 8-8" /></svg>
      </a>
    </div>
  </div>
</template>

<style scoped>
.language-switch { position: relative; color: var(--vp-c-text-1); }
.language-trigger { display: flex; align-items: center; justify-content: center; gap: 8px; min-height: 44px; padding: 0 10px; border-radius: 6px; color: var(--vp-c-text-2); font-size: 13px; font-weight: 500; white-space: nowrap; transition: background-color .15s, color .15s; }
.language-trigger:hover, .language-trigger[aria-expanded="true"] { color: var(--vp-c-text-1); background: var(--vp-c-default-soft); }
.language-chevron { color: var(--vp-c-text-3); transition: transform .15s; }
.language-chevron.expanded { transform: rotate(180deg); }
.language-popover { position: absolute; z-index: 70; top: calc(100% + 8px); right: 0; width: 176px; padding: 6px; border: 1px solid var(--vp-c-divider); border-radius: 8px; background: var(--vp-c-bg-elv); box-shadow: 0 4px 20px rgb(0 0 0 / .08); }
.language-option { display: flex; align-items: center; justify-content: space-between; gap: 16px; min-height: 44px; padding: 0 12px; border-radius: 4px; color: var(--vp-c-text-2); font-size: 14px; line-height: 20px; text-decoration: none; }
.language-option:hover, .language-option:focus-visible { background: var(--vp-c-default-soft); color: var(--vp-c-text-1); }
.language-option.selected { color: var(--vp-c-text-1); font-weight: 500; }
.language-note { margin-left: 8px; font-size: 12px; color: var(--vp-c-text-3); }
.language-trigger:focus-visible, .language-option:focus-visible { outline: 2px solid var(--vp-c-brand-1); outline-offset: 2px; }
@media (max-width: 767px) { .language-trigger { justify-content: flex-start; width: 100%; } .language-popover { position: static; width: 100%; margin-top: 8px; } }
@media (prefers-reduced-motion: reduce) { .language-trigger, .language-chevron { transition: none; } }
</style>
