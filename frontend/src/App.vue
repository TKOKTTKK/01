<template>
  <router-view v-slot="{ Component, route }">
    <keep-alive :include="cachedViews" :max="12">
      <component :is="Component" :key="pageKey(route)" />
    </keep-alive>
  </router-view>
  <TabBar v-if="showTab" />
  <ToastHost />
  <ConfirmDialog />
</template>

<script setup lang="ts">
import { computed, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import type { RouteLocationNormalizedLoaded } from 'vue-router'
import TabBar from './components/TabBar.vue'
import ToastHost from './components/ui/ToastHost.vue'
import ConfirmDialog from './components/ui/ConfirmDialog.vue'
import { useSettingsStore } from './stores/settings'

const route = useRoute()
const showTab = computed(() => route.meta.tab === true)

// Tab 页 + 详情/搜索 缓存，切换不销毁（滚动、K线缩放、输入等状态保留）
const cachedViews = [
  'HomeView', 'MarketView', 'WatchlistView', 'TradeView', 'MineView',
  'StockDetailView', 'SearchView'
]

// 股票详情按代码分别缓存（不同股票是不同实例）
function pageKey(r: RouteLocationNormalizedLoaded) {
  return r.name === 'stock' ? r.fullPath : String(r.name ?? r.path)
}

onMounted(() => useSettingsStore().apply())
</script>
