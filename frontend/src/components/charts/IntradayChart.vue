<template>
  <div ref="el" class="chart"></div>
</template>

<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { echarts } from './echarts'
import type { AppEChartsOption, AppECharts } from './echarts'
import { useSettingsStore } from '@/stores/settings'
import type { Intraday } from '@/api/types'

const props = defineProps<{ data: Intraday | null }>()
const el = ref<HTMLDivElement | null>(null)
let chart: AppECharts | null = null


function themeColors() {
  const css = getComputedStyle(document.documentElement)
  const v = (name: string, fb: string) => (css.getPropertyValue(name).trim() || fb)
  return {
    UP: v('--up', '#f0493e'),
    DOWN: v('--down', '#0fbf7f'),
    SPLIT: v('--chart-split', '#1c2533'),
    AXIS: v('--border', '#232e3f'),
    LABEL: v('--text-3', '#5f6b7e'),
    TIP_BG: v('--tooltip-bg', 'rgba(19,25,34,.95)'),
    TIP_TEXT: v('--text', '#e8edf4')
  }
}

/** 与后端 tradingMinutes() 相同的 241 个交易分钟：09:30-11:30, 13:01-15:00 */
function placeholderTimes(): string[] {
  const out: string[] = []
  const push = (h: number, m: number) =>
    out.push(`${String(h).padStart(2, '0')}:${String(m).padStart(2, '0')}`)
  for (let t = 9 * 60 + 30; t <= 11 * 60 + 30; t++) push(Math.floor(t / 60), t % 60)
  for (let t = 13 * 60 + 1; t <= 15 * 60; t++) push(Math.floor(t / 60), t % 60)
  return out
}

/**
 * 数据未到达时先画"空图框"：坐标轴 + 网格 + 时间刻度，无折线。
 * 让图表容器在组件 mounted 那一刻就有结构，数据到达后原地填充折线，
 * 感知上是"图渐渐点亮"而不是"从空白突然弹出一张图"。
 */
function renderEmpty() {
  if (!chart) return
  const { SPLIT, AXIS, LABEL } = themeColors()
  const times = placeholderTimes()
  const option = {
    animation: false,
    grid: [
      { left: 8, right: 8, top: 12, height: '62%', containLabel: true },
      { left: 8, right: 8, top: '78%', height: '18%', containLabel: true }
    ],
    xAxis: [
      {
        type: 'category', data: times, boundaryGap: false,
        axisLine: { lineStyle: { color: AXIS } },
        axisLabel: { color: LABEL, fontSize: 10, interval: 59 },
        axisTick: { show: false }
      },
      {
        type: 'category', gridIndex: 1, data: times, boundaryGap: false,
        axisLabel: { show: false }, axisLine: { show: false }, axisTick: { show: false }
      }
    ],
    yAxis: [
      {
        type: 'value', min: 0, max: 1,
        splitLine: { lineStyle: { color: SPLIT } },
        axisLabel: { show: false }
      },
      {
        type: 'value', gridIndex: 1,
        axisLabel: { show: false }, splitLine: { show: false }
      }
    ],
    series: []
  }
  chart.setOption(option as unknown as AppEChartsOption, true)
}

function render() {
  const { UP, DOWN, SPLIT, AXIS, LABEL, TIP_BG, TIP_TEXT } = themeColors()
  if (!chart) return
  if (!props.data) {
    renderEmpty()
    return
  }
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
      backgroundColor: TIP_BG,
      borderColor: AXIS,
      textStyle: { color: TIP_TEXT, fontSize: 11 },
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
        axisLine: { lineStyle: { color: AXIS } },
        axisLabel: { color: LABEL, fontSize: 10, interval: 59 },
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
        splitLine: { lineStyle: { color: SPLIT } },
        axisLabel: {
          color: LABEL, fontSize: 10,
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
          // 用声明式渐变对象，避免依赖 echarts.graphic（按需引入后更稳妥）
          color: {
            type: 'linear', x: 0, y: 0, x2: 0, y2: 1,
            colorStops: [
              { offset: 0, color: lineColor + '38' },
              { offset: 1, color: lineColor + '00' }
            ]
          }
        },
        markLine: {
          symbol: 'none', silent: true,
          data: [{ yAxis: pre }],
          lineStyle: { color: LABEL, type: 'dashed', width: 1 },
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
  chart.setOption(option as unknown as AppEChartsOption, true)
}

const settings = useSettingsStore()
watch(() => [settings.priceColorMode, settings.themeMode, settings.themeColor], () => render())

onMounted(() => {
  if (el.value) {
    chart = echarts.init(el.value)
    render() // 无数据时内部走 renderEmpty()：坐标轴骨架立即可见
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
