/** 数字/涨跌格式化工具 */

export function fmtPrice(v: number | null | undefined, digits = 2): string {
  if (v === null || v === undefined || Number.isNaN(v)) return '--'
  return v.toFixed(digits)
}

export function fmtChange(v: number | null | undefined): string {
  if (v === null || v === undefined) return '--'
  return (v > 0 ? '+' : '') + v.toFixed(2)
}

export function fmtPercent(v: number | null | undefined): string {
  if (v === null || v === undefined) return '--'
  return (v > 0 ? '+' : '') + v.toFixed(2) + '%'
}

/** 红涨绿跌样式类 */
export function changeClass(v: number | null | undefined): string {
  if (v === null || v === undefined || v === 0) return 'flat'
  return v > 0 ? 'up' : 'down'
}

/** 成交量（手）→ 万手/亿手 */
export function fmtVolume(v: number | null | undefined): string {
  if (v === null || v === undefined) return '--'
  if (v >= 1e8) return (v / 1e8).toFixed(2) + '亿手'
  if (v >= 1e4) return (v / 1e4).toFixed(2) + '万手'
  return String(v) + '手'
}

/** 成交额（元）→ 万/亿 */
export function fmtAmount(v: number | null | undefined): string {
  if (v === null || v === undefined) return '--'
  if (v >= 1e8) return (v / 1e8).toFixed(2) + '亿'
  if (v >= 1e4) return (v / 1e4).toFixed(2) + '万'
  return v.toFixed(0)
}

export function fmtTime(s: string | null | undefined): string {
  if (!s) return ''
  return s.replace('T', ' ').slice(5, 16)
}

/** 金额：千分位 + 固定两位小数，用于资产/市值/成交额 */
export function fmtMoney(v: number | null | undefined): string {
  if (v === null || v === undefined) return '--'
  return v.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}

/** 带正负号的金额，用于盈亏 */
export function fmtSigned(v: number | null | undefined): string {
  if (v === null || v === undefined) return '--'
  return (v > 0 ? '+' : '') + fmtMoney(v)
}

/** 带正负号的百分比（入参已是百分数，如 1.23 表示 1.23%） */
export function fmtSignedPercent(v: number | null | undefined): string {
  if (v === null || v === undefined) return '--'
  return (v > 0 ? '+' : '') + v.toFixed(2) + '%'
}
