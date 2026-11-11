import type { Directive } from 'vue'

// v-modal-dialog="closeFn" on a modal overlay: dialog semantics, focus trap, ESC and focus restore

interface State {
  close: () => void
  previous: HTMLElement | null
  onKeydown: (e: KeyboardEvent) => void
}

const FOCUSABLE =
  'a[href], button:not([disabled]), input:not([disabled]):not([type="hidden"]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])'

const stack: HTMLElement[] = []
const states = new WeakMap<HTMLElement, State>()
let titleSeq = 0

const focusable = (root: HTMLElement) =>
  Array.from(root.querySelectorAll<HTMLElement>(FOCUSABLE)).filter(el => el.offsetParent !== null)

export const vModalDialog: Directive<HTMLElement, () => void> = {
  mounted(overlay, binding) {
    const panel = (overlay.firstElementChild as HTMLElement | null) ?? overlay
    panel.setAttribute('role', 'dialog')
    panel.setAttribute('aria-modal', 'true')
    panel.tabIndex = -1

    const title = panel.querySelector<HTMLElement>('h1, h2, h3')
    if (title) {
      if (!title.id) title.id = `modal-title-${++titleSeq}`
      panel.setAttribute('aria-labelledby', title.id)
    }

    const state: State = {
      close: binding.value,
      previous: document.activeElement as HTMLElement | null,
      onKeydown: (e) => {
        // only the dialog on top reacts
        if (stack[stack.length - 1] !== overlay) return
        if (e.key === 'Escape') {
          e.stopPropagation()
          state.close()
          return
        }
        if (e.key !== 'Tab') return
        const items = focusable(panel)
        if (items.length === 0) { e.preventDefault(); panel.focus(); return }
        const first = items[0]
        const last = items[items.length - 1]
        const current = document.activeElement
        if (e.shiftKey && (current === first || !panel.contains(current))) { e.preventDefault(); last.focus() }
        else if (!e.shiftKey && (current === last || !panel.contains(current))) { e.preventDefault(); first.focus() }
      },
    }
    states.set(overlay, state)
    stack.push(overlay)
    document.addEventListener('keydown', state.onKeydown)

    const first = focusable(panel).find(el => el.matches('input, select, textarea')) ?? focusable(panel)[0]
    ;(first ?? panel).focus()
  },

  updated(overlay, binding) {
    const state = states.get(overlay)
    if (state) state.close = binding.value
  },

  unmounted(overlay) {
    const state = states.get(overlay)
    if (!state) return
    document.removeEventListener('keydown', state.onKeydown)
    const i = stack.indexOf(overlay)
    if (i >= 0) stack.splice(i, 1)
    if (state.previous && document.contains(state.previous)) state.previous.focus()
  },
}
