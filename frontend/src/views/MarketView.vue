<template>
  <div class="page">
    <div class="section-title">
      市场指数
      <span class="mock-badge">模拟行情</span>
    </div>
    <div class="idx-list" v-if="market.indexes.length">
      <IndexCard v-for="i in market.indexes" :key="i.code" :index="i" />
    </div>
    <div v-else class="idx-list">
      <div class="skeleton" style="flex:1;height:78px" v-for="n in 3" :key="n"></div>
    </div>

    <div class="section-title">全部股票</div>
    <div class="card" style="padding: 2px 14px;">
      <StockRow v-for="s in market.stocks" :key="s.id" :stock="s" />
      <div v-if="!market.stocks.length && market.error" class="error-block">
        <p>行情加载失败</p>
        <button @click="market.refresh()">重新加载</button>
      </div>
      <div v-else-if="!market.stocks.length" class="skeleton" style="height:220px;margin:10px 0"></div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { onActivated, onMounted } from 'vue'
import { useMarketStore } from '@/stores/market'
import IndexCard from '@/components/IndexCard.vue'
import StockRow from '@/components/StockRow.vue'

defineOptions({ name: 'MarketView' })

const market = useMarketStore()
onMounted(() => market.ensure())
onActivated(() => market.ensure())
</script>

<style scoped>
.idx-list { display: flex; gap: 8px; }
</style>
