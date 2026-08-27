<template>
  <router-view v-slot="{ Component, route }">
    <!-- Tab 页永久常驻、不设 max：只有 5 个固定实例，不会无限增长，
         不该被"逛了很多只股票详情"挤出缓存——之前跟详情页共用一个
         max:12 的池子，连续看 7-8 只不同股票就会把 Tab 页挤掉，
         "返回列表"会变成真实重新 mount（虚拟列表要重新测量/重新撑开
         总高度），看起来跟刷新了一样。 -->
    <keep-alive :include="TAB_VIEWS">
      <component v-if="route.meta.tab" :is="Component" :key="pageKey(route)" />
    </keep-alive>
    <!-- 详情/搜索页单独一个 LRU 池：股票详情按代码分实例，逛得越多
         缓存实例越多，用 max 兜底防止无限增长；淘汰只影响这个池子
         内部（最早看的那只股票详情），不会连带影响上面的 Tab 页。 -->
    <keep-alive :include="DETAIL_VIEWS" :max="12">
      <component v-if="!route.meta.tab" :is="Component" :key="pageKey(route)" />
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

const TAB_VIEWS = ['HomeView', 'MarketView', 'WatchlistView', 'TradeView', 'MineView']
const DETAIL_VIEWS = ['StockDetailView', 'SearchView']

// 股票详情按代码分别缓存（不同股票是不同实例）
function pageKey(r: RouteLocationNormalizedLoaded) {
  return r.name === 'stock' ? r.fullPath : String(r.name ?? r.path)
}

onMounted(() => useSettingsStore().apply())
</script>
