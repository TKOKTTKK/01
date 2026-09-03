/**
 * A 股交易日分时点「分钟偏移（0~239）」与「HH:mm」互转。
 *
 * 跟后端 com.stockapp.common.util.TradingMinuteOffset 是同一份规则的镜像实现：
 * 9:30-11:30、13:00-15:00 各 120 分钟，合计 240 点。
 *   偏移 0   = 09:30
 *   偏移 119 = 11:29
 *   偏移 120 = 13:00
 *   偏移 239 = 14:59
 *
 * Protobuf 分时点用 minute_offset 整数代替 "HH:mm" 字符串传输（省掉每个点
 * 5 字节的字符串开销），解码时用这里的 toHHmm() 还原成界面展示需要的格式，
 * 保持跟现有 IntradayPoint.time 字段同样的 "HH:mm" 语义，下游图表组件
 * （IntradayChart.vue）不用感知协议差异。
 */
const MORNING_START_MIN = 9 * 60 + 30 // 09:30
const MORNING_END_MIN = 11 * 60 + 30 // 11:30（开区间，最后一个点是 11:29）
const AFTERNOON_START_MIN = 13 * 60 // 13:00
const MORNING_SESSION_LEN = MORNING_END_MIN - MORNING_START_MIN // 120

/** "HH:mm" -> 0~239 的分钟偏移 */
export function toMinuteOffset(hhmm: string): number {
  const [hourStr, minuteStr] = hhmm.split(':')
  const mins = Number(hourStr) * 60 + Number(minuteStr)
  if (mins >= MORNING_START_MIN && mins < MORNING_END_MIN) {
    return mins - MORNING_START_MIN
  }
  if (mins >= AFTERNOON_START_MIN && mins < AFTERNOON_START_MIN + MORNING_SESSION_LEN) {
    return MORNING_SESSION_LEN + (mins - AFTERNOON_START_MIN)
  }
  throw new Error(`不在 A 股交易时段内的时间: ${hhmm}`)
}

/** 0~239 的分钟偏移 -> "HH:mm" */
export function toHHmm(offset: number): string {
  if (offset < 0 || offset >= 240) {
    throw new Error(`分钟偏移超出 [0,239]: ${offset}`)
  }
  const mins =
    offset < MORNING_SESSION_LEN
      ? MORNING_START_MIN + offset
      : AFTERNOON_START_MIN + (offset - MORNING_SESSION_LEN)
  const hh = String(Math.floor(mins / 60)).padStart(2, '0')
  const mm = String(mins % 60).padStart(2, '0')
  return `${hh}:${mm}`
}
