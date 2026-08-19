<template>
  <div ref="el" class="chart"></div>
</template>

<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import * as echarts from 'echarts'
import type { Intraday } from '@/api/types'

const props = defineProps<{ data: Intraday | null }>()
const el = ref<HTMLDivElement | null>(null)
let chart: echarts.ECharts | null = null

const UP = '#f0493e'
const DOWN = '#0fbf7f'

function render() {
  if (!chart || !props.data) return
  const d = props.data
  const times = d.points.map(p => p.time)
  const prices = d.points.map(p => p.price)
  const avgs = d.points.map(p => p.avgPrice)
  const vols = d.points.map((p, i) => ({
    value: p.volume,
    itemStyle: { color: i > 0 && p.price < d.points[i - 1].price ? DOWN : UP }
  }))
  const pre = d.preClose
  const maxDiff = Math.max(
    Math.abs(d.high - pre), Math.abs(d.low - pre), pre * 0.002
  )
  const last = prices[prices.length - 1]
  const lineColor = last >= pre ? UP : DOWN

  const option = {
    animation: false,
    axisPointer: { link: [{ xAxisIndex: 'all' }] },
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: 'cross', label: { backgroundColor: '#2a3648' } },
      backgroundColor: 'rgba(19,25,34,.95)',
      borderColor: '#232e3f',
      textStyle: { color: '#e8edf4', fontSize: 11 },
      confine: true,
      formatter: (params: unknown) => {
        const arr = params as { dataIndex: number }[]
        const i = arr[0].dataIndex
        const p = d.points[i]
        const pct = ((p.price - pre) / pre * 100).toFixed(2)
        return `${p.time}<br/>价格 ${p.price.toFixed(2)}（${pct}%）` +
          `<br/>均价 ${p.avgPrice.toFixed(2)}<br/>成交 ${p.volume} 手`
      }
    },
    grid: [
      { left: 8, right: 8, top: 12, height: '62%', containLabel: true },
      { left: 8, right: 8, top: '78%', height: '18%', containLabel: true }
    ],
    xAxis: [
      {
        type: 'category', data: times, boundaryGap: false,
        axisLine: { lineStyle: { color: '#232e3f' } },
        axisLabel: { color: '#5f6b7e', fontSize: 10, interval: 59 },
        axisTick: { show: false }
      },
      {
        type: 'category', gridIndex: 1, data: times, boundaryGap: false,
        axisLabel: { show: false }, axisLine: { show: false }, axisTick: { show: false }
      }
    ],
    yAxis: [
      {
        type: 'value', scale: true,
        min: pre - maxDiff, max: pre + maxDiff,
        splitLine: { lineStyle: { color: '#1c2533' } },
        axisLabel: {
          color: '#5f6b7e', fontSize: 10,
          formatter: (v: number) => v.toFixed(2)
        }
      },
      {
        type: 'value', gridIndex: 1,
        axisLabel: { show: false }, splitLine: { show: false }
      }
    ],
    series: [
      {
        name: '价格', type: 'line', data: prices,
        symbol: 'none', lineStyle: { width: 1.4, color: lineColor },
        areaStyle: {
          color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
            { offset: 0, color: lineColor + '38' },
            { offset: 1, color: lineColor + '00' }
          ])
        },
        markLine: {
          symbol: 'none', silent: true,
          data: [{ yAxis: pre }],
          lineStyle: { color: '#5f6b7e', type: 'dashed', width: 1 },
          label: { show: false }
        }
      },
      {
        name: '均价', type: 'line', data: avgs,
        symbol: 'none', lineStyle: { width: 1, color: '#d9a441' }
      },
      { name: '成交量', type: 'bar', xAxisIndex: 1, yAxisIndex: 1, data: vols, barWidth: '60%' }
    ]
  }
  chart.setOption(option as unknown as echarts.EChartsOption, true)
}

onMounted(() => {
  if (el.value) {
    chart = echarts.init(el.value)
    render()
    window.addEventListener('resize', resize)
  }
})

function resize() { chart?.resize() }

watch(() => props.data, render)

onBeforeUnmount(() => {
  window.removeEventListener('resize', resize)
  chart?.dispose()
  chart = null
})
</script>

<style scoped>
.chart { width: 100%; height: 300px; }
</style>
