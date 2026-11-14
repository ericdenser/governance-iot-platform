<script setup lang="ts">
import { ref, onMounted, onUnmounted } from 'vue'
import { cssVar } from '@/utils/cssVar'

// Discreet starfield: stars drift slowly out from the center
const canvas = ref<HTMLCanvasElement | null>(null)

interface Star { x: number; y: number; z: number; tint: string }

const STAR_COUNT = 420
const DEPTH = 1000
const SPEED = 0.35 // depth units per 16 ms frame, low on purpose

let stars: Star[] = []
let frame = 0
let last = 0
let width = 0
let height = 0
let resizeObserver: ResizeObserver | null = null
const reducedMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches

const spawn = (anyDepth: boolean, tints: string[]): Star => ({
  x: (Math.random() - 0.5) * width * 2,
  y: (Math.random() - 0.5) * height * 2,
  z: anyDepth ? Math.random() * DEPTH : DEPTH,
  // Mostly neutral, a few in the accent color
  tint: Math.random() < 0.12 ? tints[1] : tints[0],
})

const draw = (dt: number) => {
  const c = canvas.value
  const ctx = c?.getContext('2d')
  if (!c || !ctx) return
  const dpr = window.devicePixelRatio || 1
  ctx.setTransform(dpr, 0, 0, dpr, 0, 0)
  ctx.clearRect(0, 0, width, height)

  const tints = [cssVar('--text-secondary'), cssVar('--accent')]
  const cx = width / 2
  const cy = height / 2
  const focal = Math.max(width, height) * 0.5

  for (let i = 0; i < stars.length; i++) {
    const s = stars[i]
    s.z -= SPEED * dt
    if (s.z <= 1) { stars[i] = spawn(false, tints); continue }

    const k = focal / s.z
    const px = cx + s.x * k
    const py = cy + s.y * k
    if (px < 0 || px > width || py < 0 || py > height) { stars[i] = spawn(false, tints); continue }

    const near = 1 - s.z / DEPTH
    ctx.globalAlpha = 0.3 + near * 0.6
    ctx.fillStyle = s.tint
    const r = 0.6 + near * 1.4
    ctx.beginPath(); ctx.arc(px, py, r, 0, Math.PI * 2); ctx.fill()
  }
  ctx.globalAlpha = 1
}

const loop = (t: number) => {
  const dt = last ? Math.min(t - last, 50) : 16
  last = t
  draw(dt / 16)
  frame = requestAnimationFrame(loop)
}

const resize = () => {
  const c = canvas.value
  if (!c) return
  const dpr = window.devicePixelRatio || 1
  const rect = c.getBoundingClientRect()
  width = rect.width
  height = rect.height
  c.width = Math.round(width * dpr)
  c.height = Math.round(height * dpr)
  const tints = [cssVar('--text-secondary'), cssVar('--accent')]
  stars = Array.from({ length: STAR_COUNT }, () => spawn(true, tints))
  draw(0)
}

onMounted(() => {
  resize()
  resizeObserver = new ResizeObserver(resize)
  if (canvas.value) resizeObserver.observe(canvas.value)
  if (!reducedMotion) frame = requestAnimationFrame(loop)
})

onUnmounted(() => {
  cancelAnimationFrame(frame)
  resizeObserver?.disconnect()
})
</script>

<template>
  <canvas ref="canvas" class="backdrop" aria-hidden="true" />
</template>

<style scoped>
.backdrop {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  pointer-events: none;
  /* Softer behind the title */
  mask-image: radial-gradient(ellipse 45% 35% at 50% 45%, rgba(0, 0, 0, 0.35) 0%, #000 80%);
  -webkit-mask-image: radial-gradient(ellipse 45% 35% at 50% 45%, rgba(0, 0, 0, 0.35) 0%, #000 80%);
}
</style>
