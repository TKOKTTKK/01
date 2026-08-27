<template>
  <div ref="rootEl" class="vstock-list" :style="{ height: totalHeight + 'px' }">
    <div ref="spacerEl" class="vstock-spacer" :style="{ transform: `translateY(${offsetY}px)` }">
      <StockRow v-for="item in visibleItems" :key="item.id" :stock="item" />
    </div>
  </div>
  <div v-if="loadingMore" class="vstock-loading">加载中…</div>
</template>

<script setup lang="ts">
/**
 * 虚拟滚动股票列表：只渲染可视区域（+ 上下缓冲）内的行，而不是把整个数组
 * 一次性 v-for 出来。
 *
 * 【为什么需要】股票池只有几只、几十只的时候，裸 v-for 完全没问题；扩到
 * 几千只之后，一次性挂载几千个 StockRow 组件 + DOM 节点，首屏渲染会明显
 * 卡顿，长列表滚动也会掉帧。这里用固定思路的窗口化渲染：整个列表用一个
 * 撑开总高度的容器占位（保证滚动条长度正确），实际渲染的行用 translateY
 * 平移到正确位置，数量始终只有"可视区 + 缓冲区"那么多。
 *
 * 行高不是硬编码估算的，而是首次渲染后测量真实 DOM 得到——CSS 的
 * padding/line-height 手工换算很容易算错，长列表下这点误差累积起来会导致
 * 滚动到后面出现空白或重叠。
 *
 * 已知的小取舍：StockRow 自身靠 `:last-child` 去掉最后一行的下边框，
 * 在虚拟滚动下这条规则会作用在"当前渲染窗口"的最后一行而不是整个列表的
 * 最后一行，滚动中间位置时可能有一行看起来少一条底边线，纯视觉小瑕疵，
 * 不影响功能。
 */
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import StockRow from './StockRow.vue'
import type { StockItem } from '@/api/types'

const props = defineProps<{
  items: StockItem[]
  hasMore?: boolean
  loadingMore?: boolean
}>()
const emit = defineEmits<{ (e: 'load-more'): void }>()

const OVERSCAN = 8            // 视口上下各多渲染几行，减少快速滚动时的白屏闪烁
const LOAD_MORE_THRESHOLD = 6 // 剩余可视行数少于这个值时触发加载下一页

const rootEl = ref<HTMLDivElement | null>(null)
const spacerEl = ref<HTMLDivElement | null>(null)
const scrollY = ref(0)
const viewportH = ref(0)
const rootTop = ref(0)
// 初始估算值，首次渲染出真实行后会被 measureRowHeight() 校正
const rowHeight = ref(64)

const totalHeight = computed(() => props.items.length * rowHeight.value)

const range = computed(() => {
  const relativeTop = Math.max(0, scrollY.value - rootTop.value)
  const start = Math.max(0, Math.floor(relativeTop / rowHeight.value) - OVERSCAN)
  const visibleCount = Math.ceil(viewportH.value / rowHeight.value) + OVERSCAN * 2
  const end = Math.min(props.items.length, start + visibleCount)
  return { start, end }
})

const visibleItems = computed(() => props.items.slice(range.value.start, range.value.end))
const offsetY = computed(() => range.value.start * rowHeight.value)

function measureRowHeight() {
  const firstRow = spacerEl.value?.querySelector<HTMLElement>('.row')
  if (firstRow) {
    const h = firstRow.getBoundingClientRect().height
    if (h > 0) rowHeight.value = h
  }
}

function maybeLoadMore() {
  if (!props.hasMore || props.loadingMore) return
  if (props.items.length - range.value.end <= LOAD_MORE_THRESHOLD) emit('load-more')
}

function sync() {
  if (rootEl.value) rootTop.value = rootEl.value.getBoundingClientRect().top + window.scrollY
  viewportH.value = window.innerHeight
  scrollY.value = window.scrollY
  maybeLoadMore()
}

let ticking = false
function onScroll() {
  if (ticking) return
  ticking = true
  requestAnimationFrame(() => { sync(); ticking = false })
}

onMounted(() => {
  sync()
  nextTick(measureRowHeight)
  window.addEventListener('scroll', onScroll, { passive: true })
  window.addEventListener('resize', onScroll)
})
onBeforeUnmount(() => {
  window.removeEventListener('scroll', onScroll)
  window.removeEventListener('resize', onScroll)
})

// 列表从空到有数据、或滚动加载追加后，容器高度会变，顶部偏移量需要重新量一次
watch(() => props.items.length, (len, prevLen) => {
  sync()
  if (!prevLen && len) nextTick(measureRowHeight)
})
</script>

<style scoped>
.vstock-list { position: relative; }
.vstock-spacer { position: absolute; left: 0; right: 0; top: 0; }
.vstock-loading { text-align: center; font-size: 12px; color: var(--text-3); padding: 10px 0; }
</style>
