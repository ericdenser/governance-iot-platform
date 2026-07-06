import { ref } from 'vue'

export type ToastVariant = 'success' | 'warning' | 'error' | 'info'

export interface ToastItem {
  id: number
  message: string
  variant: ToastVariant
}

const items = ref<ToastItem[]>([])
let nextId = 0

const DEFAULT_DURATION = 4000
const ERROR_DURATION = 6000

const push = (message: string, variant: ToastVariant, duration: number) => {
  const id = nextId++
  items.value.push({ id, message, variant })
  setTimeout(() => {
    items.value = items.value.filter((t) => t.id !== id)
  }, duration)
}

export const toast = {
  success: (msg: string) => push(msg, 'success', DEFAULT_DURATION),
  warning: (msg: string) => push(msg, 'warning', DEFAULT_DURATION),
  error: (msg: string) => push(msg, 'error', ERROR_DURATION),
  info: (msg: string) => push(msg, 'info', DEFAULT_DURATION),
}

export function useToast() {
  const dismiss = (id: number) => {
    items.value = items.value.filter((t) => t.id !== id)
  }
  return { items, dismiss }
}
