<template>
  <div class="page no-tab">
    <div class="nav">
      <button class="back" @click="$router.back()">‹</button>
      <div style="font-size:15px;font-weight:600">新闻详情</div>
    </div>
    <template v-if="news">
      <h1 class="h">{{ news.title }}</h1>
      <div class="meta">{{ news.source }} · {{ fmtTime(news.publishTime) }}
        <span class="mock-badge" style="margin-left:6px">模拟内容</span>
      </div>
      <p class="body">{{ news.content }}</p>
    </template>
    <div v-else class="skeleton" style="height:200px"></div>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { getNewsDetail } from '@/api'
import type { NewsItem } from '@/api/types'
import { fmtTime } from '@/utils/format'

const route = useRoute()
const news = ref<NewsItem | null>(null)

onMounted(async () => {
  try { news.value = await getNewsDetail(String(route.params.id)) } catch { /* 忽略 */ }
})
</script>

<style scoped>
.nav { display: flex; align-items: center; gap: 6px; margin-bottom: 14px; }
.back { background: none; border: none; color: var(--text); font-size: 30px; line-height: 1; cursor: pointer; padding: 0 6px 4px 0; }
.h { font-size: 19px; line-height: 1.45; font-weight: 700; }
.meta { font-size: 12px; color: var(--text-3); margin: 10px 0 18px; }
.body { font-size: 15px; line-height: 1.8; color: var(--text-2); }
</style>
