import { ref, computed, type Ref } from 'vue'

export interface FeedPage<T> { items: T[]; last: boolean }

// Newest-first list shown `step` items at a time; older pages are fetched on demand
export function useFeed<T>(fetchPage: (page: number) => Promise<FeedPage<T>>, step = 10) {
  const items = ref([]) as Ref<T[]>
  const shown = ref(step)
  const last = ref(false)
  const loading = ref(false)
  let nextPage = 0

  const loadPage = async () => {
    const r = await fetchPage(nextPage++)
    items.value = [...items.value, ...r.items]
    last.value = r.last
  }

  const reset = async () => {
    items.value = []
    nextPage = 0
    last.value = false
    shown.value = step
    await loadPage()
  }

  const more = async () => {
    shown.value += step
    if (items.value.length >= shown.value || last.value) return
    loading.value = true
    try {
      while (items.value.length < shown.value && !last.value) await loadPage()
    } finally { loading.value = false }
  }

  const less = () => { shown.value = step }

  const visible = computed(() => items.value.slice(0, shown.value))
  const canMore = computed(() => items.value.length > shown.value || !last.value)
  const canLess = computed(() => shown.value > step)

  return { items, visible, loading, canMore, canLess, reset, more, less }
}
