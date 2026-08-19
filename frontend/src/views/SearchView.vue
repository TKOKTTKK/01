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
    <div v-else class="hint">
      <div class="section-title">全部股票</div>
      <div class="card" style="padding: 2px 14px;">
        <StockRow v-for="s in all" :key="s.id" :stock="s" />
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { listStocks, searchStocks } from '@/api'
import type { StockItem } from '@/api/types'
import StockRow from '@/components/StockRow.vue'

const keyword = ref('')
const results = ref<StockItem[]>([])
const all = ref<StockItem[]>([])
const searched = ref(false)
const inputEl = ref<HTMLInputElement | null>(null)
let debounce: number | undefined

function onInput() {
  window.clearTimeout(debounce)
  debounce = window.setTimeout(doSearch, 300)
}

async function doSearch() {
  const kw = keyword.value.trim()
  if (!kw) {
    results.value = []
    searched.value = false
    return
  }
  try {
    results.value = await searchStocks(kw)
    searched.value = true
  } catch { /* 忽略 */ }
}

onMounted(async () => {
  inputEl.value?.focus()
  try { all.value = await listStocks() } catch { /* 忽略 */ }
})
</script>

<style scoped>
.bar { display: flex; gap: 10px; align-items: center; }
.cancel { background: none; border: none; color: var(--accent); font-size: 15px; cursor: pointer; white-space: nowrap; }
</style>
