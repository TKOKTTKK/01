<template>
  <div ref="el" class="chart"></div>
</template>

<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { echarts } from './echarts'
import type { AppEChartsOption, AppECharts } from './echarts'
import { useSettingsStore } from '@/stores/settings'
import type { Indicators, KlineItem } from '@/api/types'

/**
 * K 线图：蜡烛 + MA 叠加 + 成交量 + 副图指标（MACD / KDJ / RSI）。
 * 指标数据全部来自后端，前端只负责绘制，保证前后端一致。
 * 支持缩放（dataZoom inside）与横向拖动、十字光标、OHLC 提示。
 */
const props = defineProps<{
  kline: KlineItem[]
  indicators: Indicators | null
  sub: 'MACD' | 'KDJ' | 'RSI'
}>()

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

const MA_COLORS: Record<string, string> = {
  MA5: '#d9a441', MA10: '#3f8cff', MA20: '#c678dd', MA60: '#56b6c2'
}

function nn(arr: (number | null)[] | undefined): (number | string)[] {
  return (arr ?? []).map(v => (v === null || v === undefined ? '-' : v))
}

function render() {
  const { UP, DOWN, SPLIT, AXIS, LABEL, TIP_BG, TIP_TEXT } = themeColors()
  if (!chart || props.kline.length === 0) return
  const k = props.kline
  const ind = props.indicators
  const dates = k.map(i => i.date)
  const candle = k.map(i => [i.open, i.close, i.low, i.high])
  const vols = k.map(i => ({
    value: i.volume,
    itemStyle: { color: i.close >= i.open ? UP : DOWN }
  }))
  const startPct = Math.max(0, 100 - (80 / k.length) * 100)

  const maSeries = ind
    ? (['MA5', 'MA10', 'MA20', 'MA60'] as const).map(name => ({
        name, type: 'line' as const,
        data: nn(ind.ma[name.toLowerCase() as 'ma5' | 'ma10' | 'ma20' | 'ma60']),
        symbol: 'none' as const, smooth: true,
        lineStyle: { width: 1, color: MA_COLORS[name] },
        emphasis: { disabled: true }
      }))
    : []

  // 副图指标序列
  let subSeries: object[] = []
  if (ind) {
    if (props.sub === 'MACD') {
      subSeries = [
        {
          name: 'MACD', type: 'bar', xAxisIndex: 2, yAxisIndex: 2,
          data: (ind.macd.macd ?? []).map(v => ({
            value: v ?? 0,
            itemStyle: { color: (v ?? 0) >= 0 ? UP : DOWN }
          })),
          barWidth: '50%'
        },
        { name: 'DIF', type: 'line', xAxisIndex: 2, yAxisIndex: 2, data: nn(ind.macd.dif), symbol: 'none', lineStyle: { width: 1, color: '#d9a441' } },
        { name: 'DEA', type: 'line', xAxisIndex: 2, yAxisIndex: 2, data: nn(ind.macd.dea), symbol: 'none', lineStyle: { width: 1, color: '#3f8cff' } }
      ]
    } else if (props.sub === 'KDJ') {
      subSeries = [
        { name: 'K', type: 'line', xAxisIndex: 2, yAxisIndex: 2, data: nn(ind.kdj.k), symbol: 'none', lineStyle: { width: 1, color: '#d9a441' } },
        { name: 'D', type: 'line', xAxisIndex: 2, yAxisIndex: 2, data: nn(ind.kdj.d), symbol: 'none', lineStyle: { width: 1, color: '#3f8cff' } },
        { name: 'J', type: 'line', xAxisIndex: 2, yAxisIndex: 2, data: nn(ind.kdj.j), symbol: 'none', lineStyle: { width: 1, color: '#c678dd' } }
      ]
    } else {
      subSeries = [
        { name: 'RSI6', type: 'line', xAxisIndex: 2, yAxisIndex: 2, data: nn(ind.rsi.rsi6), symbol: 'none', lineStyle: { width: 1, color: '#d9a441' } },
        { name: 'RSI12', type: 'line', xAxisIndex: 2, yAxisIndex: 2, data: nn(ind.rsi.rsi12), symbol: 'none', lineStyle: { width: 1, color: '#3f8cff' } },
        { name: 'RSI24', type: 'line', xAxisIndex: 2, yAxisIndex: 2, data: nn(ind.rsi.rsi24), symbol: 'none', lineStyle: { width: 1, color: '#c678dd' } }
      ]
    }
  }

  const smallAxis = {
    axisLabel: { show: false }, axisLine: { show: false },
    axisTick: { show: false }, splitLine: { show: false }
  }

  const option = {
    animation: false,
    axisPointer: { link: [{ xAxisIndex: 'all' }] },
    legend: {
      top: 0, left: 4,
      data: ['MA5', 'MA10', 'MA20', 'MA60'],
      textStyle: { color: '#97a3b6', fontSize: 10 },
      itemWidth: 10, itemHeight: 2
    },
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: 'cross', label: { backgroundColor: '#2a3648' } },
      backgroundColor: TIP_BG,
      borderColor: AXIS,
      textStyle: { color: TIP_TEXT, fontSize: 11 },
      confine: true,
      formatter: (params: unknown) => {
        const arr = params as { dataIndex: number }[]
        if (!arr.length) return ''
        const i = arr[0].dataIndex
        const it = k[i]
        if (!it) return ''
        const chg = i > 0 ? ((it.close - k[i - 1].close) / k[i - 1].close * 100).toFixed(2) : '--'
        return `${it.date}<br/>开 ${it.open.toFixed(2)}  高 ${it.high.toFixed(2)}` +
          `<br/>低 ${it.low.toFixed(2)}  收 ${it.close.toFixed(2)}` +
          `<br/>涨跌 ${chg}%  量 ${(it.volume / 1e4).toFixed(2)}万手`
      }
    },
    grid: [
      { left: 8, right: 8, top: 22, height: '48%', containLabel: true },
      { left: 8, right: 8, top: '58%', height: '14%', containLabel: true },
      { left: 8, right: 8, top: '76%', height: '18%', containLabel: true }
    ],
    xAxis: [
      {
        type: 'category', data: dates, boundaryGap: true,
        axisLine: { lineStyle: { color: AXIS } },
        axisLabel: { color: LABEL, fontSize: 10 },
        axisTick: { show: false }
      },
      { type: 'category', gridIndex: 1, data: dates, ...smallAxis },
      { type: 'category', gridIndex: 2, data: dates, ...smallAxis, axisLabel: { show: false } }
    ],
    yAxis: [
      {
        type: 'value', scale: true,
        splitLine: { lineStyle: { color: SPLIT } },
        axisLabel: { color: LABEL, fontSize: 10 }
      },
      { type: 'value', gridIndex: 1, ...smallAxis },
      {
        type: 'value', gridIndex: 2, scale: true,
        splitLine: { show: false },
        axisLabel: { color: LABEL, fontSize: 9 }
      }
    ],
    dataZoom: [
      { type: 'inside', xAxisIndex: [0, 1, 2], start: startPct, end: 100, minValueSpan: 15 },
      {
        type: 'slider', xAxisIndex: [0, 1, 2], start: startPct, end: 100,
        height: 14, bottom: 0,
        borderColor: AXIS, backgroundColor: '#131922',
        fillerColor: 'rgba(63,140,255,.15)',
        handleStyle: { color: '#3f8cff' },
        textStyle: { color: LABEL, fontSize: 9 }
      }
    ],
    series: [
      {
        name: 'K线', type: 'candlestick', data: candle,
        itemStyle: {
          color: UP, color0: DOWN,
          borderColor: UP, borderColor0: DOWN
        }
      },
      ...maSeries,
      { name: '成交量', type: 'bar', xAxisIndex: 1, yAxisIndex: 1, data: vols, barWidth: '55%' },
      ...subSeries
    ]
  }
  chart.setOption(option as unknown as AppEChartsOption, true)
}

const settings = useSettingsStore()
watch(() => [settings.priceColorMode, settings.themeMode, settings.themeColor], () => render())

onMounted(() => {
  if (el.value) {
    chart = echarts.init(el.value)
    render()
    window.addEventListener('resize', resize)
  }
})

function resize() { chart?.resize() }

watch(() => [props.kline, props.indicators, props.sub], render, { deep: false })

onBeforeUnmount(() => {
  window.removeEventListener('resize', resize)
  chart?.dispose()
  chart = null
})
</script>

<style scoped>
.chart { width: 100%; height: 420px; }
</style>
