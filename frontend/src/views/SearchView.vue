<template>
  <div class="page no-tab">
    <div class="bar">
      <input ref="inputEl" v-model="keyword" class="input" placeholder="输入股票代码或名称"
        @input="onInput" @keyup.enter="doSearch" />
      <button class="cancel" @click="$router.back()">取消</button>
    </div>

    <div class="card" style="padding: 2px 14px; margin-top: 12px;" v-if="results.length">
      <StockRow v-for="s in results" :key="s.id" :stock="s" />
    </div>
    <div v-else-if="searched" class="empty">未找到相关股票</div>
    <template v-else>
      <!-- 最近搜索 -->
      <div v-if="recent.length" class="section-title">
        最近搜索
        <span class="more" @click="clearRecent">清除</span>
      </div>
      <div v-if="recent.length" class="chips">
        <button v-for="k in recent" :key="k" class="chip" @click="useKeyword(k)">{{ k }}</button>
      </div>

      <div class="section-title">全部股票</div>
      <div class="card" style="padding: 2px 14px;">
        <VirtualStockList
          v-if="market.stocks.length"
          :items="market.stocks"
          :has-more="market.stocksHasMore"
          :loading-more="market.stocksLoadingMore"
          @load-more="market.loadMoreStocks()"
        />
        <div v-else class="skeleton" style="height:160px;margin:10px 0"></div>
      </div>
    </template>
  </div>
</template>

<script setup lang="ts">
import { onActivated, onMounted, ref } from 'vue'
import { searchStocks } from '@/api'
import type { StockItem } from '@/api/types'
import { useMarketStore } from '@/stores/market'
import StockRow from '@/components/StockRow.vue'
import VirtualStockList from '@/components/VirtualStockList.vue'

defineOptions({ name: 'SearchView' })

const RECENT_KEY = 'recent_searches_v1'
const market = useMarketStore()
const keyword = ref('')
const results = ref<StockItem[]>([])
const searched = ref(false)
const recent = ref<string[]>([])
const inputEl = ref<HTMLInputElement | null>(null)
let debounce: number | undefined

function loadRecent() {
  try { recent.value = JSON.parse(localStorage.getItem(RECENT_KEY) || '[]') } catch { recent.value = [] }
}
function saveRecent(kw: string) {
  const list = [kw, ...recent.value.filter(k => k !== kw)].slice(0, 8)
  recent.value = list
  localStorage.setItem(RECENT_KEY, JSON.stringify(list))
}
function clearRecent() {
  recent.value = []
  localStorage.removeItem(RECENT_KEY)
}
function useKeyword(k: string) {
  keyword.value = k
  doSearch()
}

function onInput() {
  window.clearTimeout(debounce)
  debounce = window.setTimeout(doSearch, 300)
}

/**
 * 请求序号防竞态：每次发起搜索都领一个递增序号，响应回来时先比对
 * "自己是不是最新一次发出的请求"，不是就直接丢弃——避免网络乱序时，
 * 一次更早发出、但更晚返回的旧关键词结果，覆盖掉新关键词已经显示的结果。
 * 单纯防抖（300ms）覆盖不了这种情况：防抖只保证"停顿时只发一个请求"，
 * 挡不住"两次不同停顿分别发出的请求，在网络层不按发出顺序回来"。
 */
let searchSeq = 0

async function doSearch() {
  const kw = keyword.value.trim()
  const seq = ++searchSeq
  if (!kw) {
    results.value = []
    searched.value = false
    return
  }
  try {
    const res = await searchStocks(kw)
    if (seq !== searchSeq) return // 期间又发起了更新的搜索，本次结果已过期，丢弃
    results.value = res
    searched.value = true
    if (results.value.length) saveRecent(kw)
  } catch { /* 忽略 */ }
}

onMounted(() => {
  loadRecent()
  market.ensure()
  inputEl.value?.focus()
})
onActivated(() => { loadRecent(); market.ensure() })
</script>

<style scoped>
.bar { display: flex; gap: 10px; align-items: center; }
.cancel { background: none; border: none; color: var(--accent); font-size: 15px; cursor: pointer; white-space: nowrap; }
.chips { display: flex; flex-wrap: wrap; gap: 8px; }
.chip {
  border: 1px solid var(--border); background: var(--bg-soft); color: var(--text-2);
  border-radius: 16px; padding: 6px 14px; font-size: 13px; cursor: pointer;
}
</style>
