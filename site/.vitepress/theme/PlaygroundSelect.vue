<!-- SPDX-License-Identifier: Apache-2.0 -->
<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, useId, watch } from 'vue'
import type { CSSProperties } from 'vue'

defineOptions({ inheritAttrs: false })
const props = withDefaults(defineProps<{
  modelValue: string
  options: { value: string; label: string }[]
  label: string
  disabled?: boolean
}>(), { disabled: false })
const emit = defineEmits<{ 'update:modelValue': [value: string] }>()
const id = useId()
const root = ref<HTMLElement>()
const trigger = ref<HTMLButtonElement>()
const menu = ref<HTMLElement>()
const open = ref(false)
const active = ref(0)
const menuStyle = ref<CSSProperties>({ visibility: 'hidden' })
const selected = computed(() => props.options.findIndex(option => option.value === props.modelValue))
const selectedLabel = computed(() => props.options[selected.value]?.label ?? props.modelValue)
const activeId = computed(() => open.value && props.options[active.value] ? `${id}-option-${active.value}` : undefined)
let search = ''
let searchTimer: ReturnType<typeof setTimeout> | undefined
let triggerObserver: ResizeObserver | undefined

function positionMenu() {
  if (!open.value || !trigger.value || !menu.value) return
  const bounds = trigger.value.getBoundingClientRect()
  const edge = 8
  const gap = 6
  const width = Math.min(Math.max(bounds.width, 156), innerWidth - edge * 2)
  const below = Math.max(0, innerHeight - bounds.bottom - gap - edge)
  const above = Math.max(0, bounds.top - gap - edge)
  const preferBelow = below >= Math.min(menu.value.scrollHeight, 280) || below >= above
  const maxHeight = Math.min(320, Math.max(edge, preferBelow ? below : above))
  const height = Math.min(menu.value.scrollHeight, maxHeight)
  const top = preferBelow ? bounds.bottom + gap : bounds.top - gap - height
  const left = Math.min(Math.max(edge, bounds.left), Math.max(edge, innerWidth - width - edge))
  menuStyle.value = {
    top: `${Math.max(edge, top)}px`, left: `${left}px`, width: `${width}px`,
    maxHeight: `${maxHeight}px`, visibility: 'visible'
  }
}

function revealActive() {
  menu.value?.querySelector<HTMLElement>(`[data-option-index="${active.value}"]`)?.scrollIntoView({ block: 'nearest' })
}

async function show(index = selected.value < 0 ? 0 : selected.value) {
  if (props.disabled || !props.options.length) return
  active.value = Math.max(0, Math.min(index, props.options.length - 1))
  open.value = true
  menuStyle.value = { visibility: 'hidden' }
  await nextTick()
  positionMenu()
  revealActive()
}

function close(restoreFocus = false) {
  open.value = false
  search = ''
  clearTimeout(searchTimer)
  if (restoreFocus) trigger.value?.focus({ preventScroll: true })
}

function choose(index: number) {
  const option = props.options[index]
  if (props.disabled || !option) return
  emit('update:modelValue', option.value)
  close(true)
}

async function keydown(event: KeyboardEvent) {
  if (props.disabled) return
  if (event.key === 'Tab') { close(); return }
  if (event.key === 'Escape') {
    if (open.value) { event.preventDefault(); event.stopPropagation(); close(true) }
    return
  }
  if (['ArrowDown', 'ArrowUp', 'Home', 'End'].includes(event.key)) {
    event.preventDefault()
    const last = props.options.length - 1
    if (event.key === 'Home' || event.key === 'End') {
      await show(event.key === 'Home' ? 0 : last)
    } else if (!open.value) {
      await show(selected.value < 0 ? (event.key === 'ArrowDown' ? 0 : last) : selected.value)
    } else {
      active.value = (active.value + (event.key === 'ArrowDown' ? 1 : -1) + props.options.length) % props.options.length
      revealActive()
    }
    return
  }
  if (event.key === 'Enter' || event.key === ' ') {
    event.preventDefault()
    if (open.value) choose(active.value)
    else await show()
    return
  }
  if (event.key.length === 1 && !event.altKey && !event.ctrlKey && !event.metaKey) {
    event.preventDefault()
    const character = event.key.toLocaleLowerCase()
    search = search.length === 1 && search === character ? character : search + character
    clearTimeout(searchTimer)
    searchTimer = setTimeout(() => { search = '' }, 700)
    const start = search.length === 1 ? active.value + 1 : active.value
    for (let step = 0; step < props.options.length; step++) {
      const index = (start + step) % props.options.length
      if (props.options[index].label.toLocaleLowerCase().startsWith(search)) {
        if (!open.value) await show(index)
        else { active.value = index; revealActive() }
        break
      }
    }
  }
}

function outside(event: Event) {
  if (!(event.target instanceof Node)) return
  if (!root.value?.contains(event.target) && !menu.value?.contains(event.target)) close()
}

function scroll(event: Event) {
  if (event.target instanceof Node && menu.value?.contains(event.target)) return
  close()
}

watch(() => props.disabled, disabled => { if (disabled) close() })
watch(() => props.options, async () => {
  if (!props.options.length) { close(); return }
  active.value = Math.min(active.value, props.options.length - 1)
  if (open.value) { await nextTick(); positionMenu() }
})
onMounted(() => {
  document.addEventListener('pointerdown', outside)
  document.addEventListener('focusin', outside)
  document.addEventListener('scroll', scroll, true)
  window.addEventListener('resize', positionMenu)
  triggerObserver = new ResizeObserver(positionMenu)
  if (trigger.value) triggerObserver.observe(trigger.value)
})
onBeforeUnmount(() => {
  clearTimeout(searchTimer)
  document.removeEventListener('pointerdown', outside)
  document.removeEventListener('focusin', outside)
  document.removeEventListener('scroll', scroll, true)
  window.removeEventListener('resize', positionMenu)
  triggerObserver?.disconnect()
})
</script>

<template>
  <div ref="root" class="pg-select" v-bind="$attrs">
    <button :id="`${id}-trigger`" ref="trigger" type="button" class="pg-select-trigger" role="combobox"
      :disabled="disabled || !options.length" :aria-label="label" aria-haspopup="listbox" :aria-expanded="open"
      :aria-controls="open ? `${id}-listbox` : undefined" :aria-activedescendant="activeId"
      @click="open ? close() : show()" @keydown="keydown">
      <span class="pg-select-value">{{ selectedLabel }}</span>
      <span aria-hidden="true" class="pg-select-chevron" :class="{ expanded: open }" />
    </button>
    <Teleport to="body">
      <div v-if="open" :id="`${id}-listbox`" ref="menu" class="pg-select-menu" role="listbox"
        :aria-label="label" :style="menuStyle">
        <div v-for="(option, index) in options" :id="`${id}-option-${index}`" :key="option.value"
          class="pg-select-option" :class="{ active: index === active, selected: option.value === modelValue }"
          role="option" :aria-selected="option.value === modelValue" :data-option-index="index"
          @pointerdown.prevent @pointermove="active = index" @click="choose(index)">
          <span>{{ option.label }}</span><span v-if="option.value === modelValue" aria-hidden="true" class="pg-select-check" />
        </div>
      </div>
    </Teleport>
  </div>
</template>

<style scoped>
.pg-select { display: inline-block; min-width: 0; color: var(--vp-c-text-1); }
.pg-select-trigger { display: inline-flex; align-items: center; justify-content: space-between; gap: 16px; width: 100%; min-height: 44px; padding: 9px 12px; border: 1px solid var(--vp-c-border); border-radius: 6px; color: var(--vp-c-text-1); background: var(--pg-panel); font: inherit; font-size: 13px; line-height: 24px; text-align: left; cursor: pointer; transition: border-color 150ms ease, background-color 150ms ease, box-shadow 150ms ease; }
.pg-select-trigger:hover { background: var(--pg-control-hover); border-color: var(--vp-c-text-3); }
.pg-select-trigger[aria-expanded='true'] { border-color: var(--vp-c-brand-1); box-shadow: 0 0 0 3px var(--pg-focus-ring); }
.pg-select-trigger:focus-visible { outline: 2px solid var(--vp-c-brand-1); outline-offset: 3px; }
.pg-select-trigger:disabled { cursor: default; opacity: .5; }
.pg-select-value { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.pg-select-chevron { flex: 0 0 6px; width: 6px; height: 6px; margin: -3px 2px 0 0; transform: rotate(45deg); border-right: 1.5px solid currentColor; border-bottom: 1.5px solid currentColor; color: var(--vp-c-text-2); transition: transform 150ms ease; }
.pg-select-chevron.expanded { margin-top: 3px; transform: rotate(225deg); }
.pg-select-menu { position: fixed; z-index: calc(var(--vp-z-index-nav, 30) + 20); box-sizing: border-box; padding: 5px; overflow-x: hidden; overflow-y: auto; overscroll-behavior: contain; border: 1px solid var(--vp-c-border); border-radius: 8px; color: var(--vp-c-text-1); background: var(--vp-c-bg-elv); box-shadow: var(--pg-popover-shadow); font-family: var(--vp-font-family-base); font-size: 13px; line-height: 20px; scrollbar-width: thin; scrollbar-color: var(--vp-c-border) transparent; }
.pg-select-option { display: flex; align-items: center; justify-content: space-between; gap: 16px; min-height: 44px; padding: 10px 11px; border-radius: 4px; color: var(--vp-c-text-1); cursor: pointer; overflow-wrap: anywhere; }
.pg-select-option.active { background: var(--pg-control-hover); }
.pg-select-option.selected { color: var(--pg-selected-text); font-weight: 500; }
.pg-select-option.selected.active { background: var(--pg-selected-bg); }
.pg-select-check { flex: 0 0 5px; width: 5px; height: 9px; margin: -3px 3px 0 0; transform: rotate(45deg); border-right: 1.5px solid currentColor; border-bottom: 1.5px solid currentColor; }
@media (prefers-reduced-motion: reduce) { .pg-select-trigger, .pg-select-chevron { transition: none; } }
</style>
