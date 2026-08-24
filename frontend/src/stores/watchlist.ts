import { defineStore } from 'pinia'
import { ref } from 'vue'
import { addWatch, getWatchlist, removeWatch } from '@/api'
import type { StockItem } from '@/api/types'

export const useWatchlistStore = defineStore('watchlist', () => {
  const list = ref<StockItem[]>([])
  const loaded = ref(false)

  async function refresh() {
    list.value = await getWatchlist()
    loaded.value = true
  }

  async function add(stockId: number) {
    await addWatch(stockId)
    await refresh()
  }

  async function remove(stockId: number) {
    await removeWatch(stockId)
    list.value = list.value.filter(s => s.id !== stockId)
  }

  function reset() { list.value = []; loaded.value = false }

  return { list, loaded, refresh, add, remove, reset }
})
