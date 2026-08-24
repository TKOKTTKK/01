<template>
  <div class="idx" :class="cls">
    <div class="iname">{{ index.name }}</div>
    <div class="ival">{{ fmtPrice(index.value) }}</div>
    <div class="ichg">{{ fmtChange(index.changeAmount) }} {{ fmtPercent(index.changePercent) }}</div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import type { MarketIndex } from '@/api/types'
import { changeClass, fmtChange, fmtPercent, fmtPrice } from '@/utils/format'

const props = defineProps<{ index: MarketIndex }>()
const cls = computed(() => changeClass(props.index.changePercent))
</script>

<style scoped>
.idx {
  flex: 1;
  background: var(--card);
  border: 1px solid var(--border);
  border-radius: 12px;
  padding: 11px 10px;
  text-align: center;
}
.iname { font-size: 12px; color: var(--text-2); }
.ival { font-size: 17px; font-weight: 700; margin: 5px 0 3px; font-variant-numeric: tabular-nums; }
.ichg { font-size: 10.5px; font-variant-numeric: tabular-nums; }
.idx.up .ival, .idx.up .ichg { color: var(--up); }
.idx.down .ival, .idx.down .ichg { color: var(--down); }
</style>
