import { ref } from 'vue'

export interface ConfirmOptions {
  title: string
  message: string
  confirmText?: string
  cancelText?: string
  danger?: boolean
}

interface ActiveConfirm extends ConfirmOptions {
  resolve: (value: boolean) => void
}

const active = ref<ActiveConfirm | null>(null)

export function confirm(options: ConfirmOptions): Promise<boolean> {
  return new Promise((resolve) => {
    if (active.value) active.value.resolve(false)
    active.value = { ...options, resolve }
  })
}

export function useConfirm() {
  const respond = (value: boolean) => {
    if (!active.value) return
    active.value.resolve(value)
    active.value = null
  }
  return { active, respond }
}
