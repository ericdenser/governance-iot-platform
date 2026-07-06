import { ref, watch, onScopeDispose, type Ref } from 'vue'

export function useDebouncedRef<T>(source: Ref<T>, delayMs = 300): Ref<T> {
  const debounced = ref(source.value) as Ref<T>
  let timer: ReturnType<typeof setTimeout> | null = null

  const stopWatching = watch(source, (newValue) => {
    if (timer) clearTimeout(timer)
    timer = setTimeout(() => {
      debounced.value = newValue
      timer = null
    }, delayMs)
  })

  onScopeDispose(() => {
    if (timer) clearTimeout(timer)
    stopWatching()
  })

  return debounced
}
