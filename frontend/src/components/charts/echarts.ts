/**
 * ECharts 按需引入。
 *
 * 之前两个图表组件都用 `import * as echarts from 'echarts'` 引入全量包，
 * 导致整个 ECharts（含所有图表类型、地图、3D 等）被打进 StockDetailView 的
 * chunk，构建时出现 >1MB（gzip 347kB）的体积警告。
 *
 * 这里只注册项目实际用到的：
 *   - 图表：折线（分时/均线/指标）、K 线、柱状（成交量/MACD）
 *   - 组件：Grid、Tooltip、AxisPointer、DataZoom、Legend、MarkLine
 *   - 渲染器：Canvas（不引 SVG 渲染器）
 *
 * 新增图表类型时，记得在这里补上对应的 use() 注册，否则运行时图表不显示。
 */
import * as echarts from 'echarts/core'
import { BarChart, CandlestickChart, LineChart } from 'echarts/charts'
import {
  AxisPointerComponent,
  DataZoomComponent,
  GridComponent,
  LegendComponent,
  MarkLineComponent,
  TooltipComponent
} from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import type { ComposeOption } from 'echarts/core'
import type { BarSeriesOption, CandlestickSeriesOption, LineSeriesOption } from 'echarts/charts'
import type {
  DataZoomComponentOption,
  GridComponentOption,
  LegendComponentOption,
  TooltipComponentOption
} from 'echarts/components'

echarts.use([
  LineChart,
  BarChart,
  CandlestickChart,
  GridComponent,
  TooltipComponent,
  AxisPointerComponent,
  DataZoomComponent,
  LegendComponent,
  MarkLineComponent,
  CanvasRenderer
])

/** 本项目用到的 option 组合类型 */
export type AppEChartsOption = ComposeOption<
  | LineSeriesOption
  | BarSeriesOption
  | CandlestickSeriesOption
  | GridComponentOption
  | TooltipComponentOption
  | DataZoomComponentOption
  | LegendComponentOption
>

export type AppECharts = echarts.ECharts

export { echarts }
export default echarts
