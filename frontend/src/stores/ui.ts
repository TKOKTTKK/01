import { defineStore } from 'pinia'
import { ref } from 'vue'

export interface ToastItem { id: number; text: string; type: 'success' | 'error' | 'info' }
export interface ConfirmOptions {
  title: string
  lines?: string[]        // 正文多行（如交易确认的明细）
  confirmText?: string
  danger?: boolean
}

let seq = 0

export const useUiStore = defineStore('ui', () => {
  const toasts = ref<ToastItem[]>([])

  function toast(text: string, type: ToastItem['type'] = 'info') {
    const id = ++seq
    toasts.value.push({ id, text, type })
    setTimeout(() => { toasts.value = toasts.value.filter(t => t.id !== id) }, 2200)
  }

  // ---- Confirm 对话框（Promise 化） ----
  const confirmVisible = ref(false)
  const confirmOpts = ref<ConfirmOptions>({ title: '' })
  let resolver: ((v: boolean) => void) | null = null

  function confirm(opts: ConfirmOptions): Promise<boolean> {
    confirmOpts.value = opts
    confirmVisible.value = true
    return new Promise(resolve => { resolver = resolve })
  }
  function answer(v: boolean) {
    confirmVisible.value = false
    resolver?.(v)
    resolver = null
  }

  return { toasts, toast, confirmVisible, confirmOpts, confirm, answer }
})
