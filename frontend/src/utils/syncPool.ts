/**
 * 通用小并发池：从队列里最多同时跑 poolSize 个 worker，跑完一个再取下一个，
 * 不是"批内还要再分批"。从 fullSync.ts 里抽出来，供它自己（日K全量同步）和
 * quoteIntradaySync.ts（分时图+行情全量同步）共用——两边的"整个股票池分批 +
 * 批内小并发"调度模型完全一样，只是每只股票内部实际请求什么不同，没必要
 * 各写一份容易长出细微差异的并发控制逻辑。
 */
export async function runPool<T>(
  items: T[], poolSize: number, worker: (item: T) => Promise<void>
): Promise<void> {
  let idx = 0
  async function lane(): Promise<void> {
    while (idx < items.length) {
      const item = items[idx++]
      await worker(item)
    }
  }
  await Promise.all(Array.from({ length: Math.min(poolSize, items.length) }, lane))
}
