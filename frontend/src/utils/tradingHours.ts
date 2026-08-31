/**
 * A股交易时段判断（Asia/Shanghai 时区，不受设备本地时区影响），
 * 周一到周五 9:30-11:30、13:00-15:00。
 *
 * 不含法定节假日判断——客户端没有交易日历数据源，节假日当天会被误判成
 * "在交易时段"。这不是正确性问题：节假日行情本来就不变，多判断成"在
 * 交易"顶多是让轮询多跑几轮、每轮请求回来的数据和上一轮一样，不会展示
 * 错误内容。如果以后要精确排除节假日，需要后端提供交易日历接口，
 * 属于单独的功能，不在这次修改范围内。
 *
 * 被 stores/market.ts 的常规轮询、utils/visiblePricePolling.ts 的可视区
 * 高频轮询共用，避免两处各自实现一份容易长出细微差异（比如一个用了
 * 11:30 闭区间、另一个用开区间这种不容易发现的不一致）。
 */
export function isTradingHours(): boolean {
  const parts = new Intl.DateTimeFormat('en-US', {
    timeZone: 'Asia/Shanghai',
    hour12: false,
    weekday: 'short',
    hour: '2-digit',
    minute: '2-digit'
  }).formatToParts(new Date())
  const get = (type: string) => parts.find(p => p.type === type)?.value ?? ''
  const weekday = get('weekday')
  if (weekday === 'Sat' || weekday === 'Sun') return false
  const hour = Number(get('hour'))
  const minute = Number(get('minute'))
  const mins = hour * 60 + minute
  const morning = mins >= 9 * 60 + 30 && mins <= 11 * 60 + 30
  const afternoon = mins >= 13 * 60 && mins <= 15 * 60
  return morning || afternoon
}
