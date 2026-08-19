<template>
  <div class="page">
    <div class="section-title">我的自选 <span class="mock-badge">模拟行情</span></div>

    <template v-if="userStore.isLoggedIn()">
      <div class="card" style="padding: 2px 14px;">
        <div class="wrow" v-for="s in list" :key="s.id">
          <StockRow :stock="s" style="flex:1" />
          <button class="del" @click.stop="onRemove(s)">删除</button>
        </div>
        <div v-if="list.length === 0 && loaded" class="empty">
          暂无自选，<span style="color:var(--accent)" @click="$router.push('/search')">去添加</span>
        </div>
      </div>
    </template>
    <div v-else class="card empty">
      <span style="color: var(--accent)" @click="$router.push('/login')">登录</span> 后管理自选股
    </div>
  </div>
</template>

<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'
import { getWatchlist, removeWatch } from '@/api'
import type { StockItem } from '@/api/types'
import { useUserStore } from '@/stores/user'
import StockRow from '@/components/StockRow.vue'

const userStore = useUserStore()
const list = ref<StockItem[]>([])
const loaded = ref(false)
let timer: number | undefined

async function load() {
  if (!userStore.isLoggedIn()) return
  try {
    list.value = await getWatchlist()
    loaded.value = true
  } catch { /* 忽略 */ }
}

async function onRemove(s: StockItem) {
  try {
    await removeWatch(s.id)
    list.value = list.value.filter(i => i.id !== s.id)
  } catch (e) {
    alert((e as Error).message)
  }
}

onMounted(() => {
  load()
  timer = window.setInterval(load, 10000)
})
onUnmounted(() => window.clearInterval(timer))
</script>

<style scoped>
.wrow { display: flex; align-items: center; gap: 8px; }
.del {
  border: 1px solid var(--border);
  background: transparent;
  color: var(--text-3);
  border-radius: 8px;
  font-size: 12px;
  padding: 6px 10px;
  cursor: pointer;
  flex-shrink: 0;
}
</style>
