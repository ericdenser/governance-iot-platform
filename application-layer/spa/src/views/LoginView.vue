<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { useRoute } from 'vue-router'
import BrandMark from '@/components/BrandMark.vue'
import StarfieldBackdrop from '@/components/StarfieldBackdrop.vue'

const route = useRoute()

const sessionExpired = computed(() => route.query.expired === '1')

const fazerLogin = () => {
  window.location.href = '/api/oauth2/authorization/keycloak'
}

type FeatureKind = 'provision' | 'ota' | 'telemetry' | 'governance'

const features: { kind: FeatureKind; title: string; text: string }[] = [
  { kind: 'provision',  title: 'Provisionamento',       text: 'Cadastre um dispositivo e ele se registra sozinho na primeira conexão.' },
  { kind: 'ota',        title: 'Atualizações remotas',  text: 'Publique novas versões de firmware e acompanhe a instalação em cada dispositivo.' },
  { kind: 'telemetry',  title: 'Monitoramento ao vivo', text: 'Estado, conectividade e saúde da frota atualizados em tempo real.' },
  { kind: 'governance', title: 'Governança',            text: 'Grupos, permissões e histórico de todas as ações na plataforma.' },
]

const featuresEl = ref<HTMLElement | null>(null)
const visible = ref(false)
let observer: IntersectionObserver | null = null

const scrollToFeatures = () => featuresEl.value?.scrollIntoView({ behavior: 'smooth' })

onMounted(() => {
  // Cards fade in (and start their animation) once scrolled into view
  observer = new IntersectionObserver((entries) => {
    if (entries.some(e => e.isIntersecting)) {
      visible.value = true
      observer?.disconnect()
    }
  }, { threshold: 0.2 })
  if (featuresEl.value) observer.observe(featuresEl.value)
})

onUnmounted(() => observer?.disconnect())
</script>

<template>
  <div class="login-page">
    <section class="hero">
      <StarfieldBackdrop />

      <div class="hero-content">
        <h1 class="title"><BrandMark /></h1>
        <p class="tagline">Provisionamento, atualizações remotas e monitoramento para dispositivos conectados.</p>

        <p v-if="sessionExpired" class="expired" role="alert">
          Sua sessão expirou. Entre novamente para continuar.
        </p>

        <button type="button" class="btn-login" @click="fazerLogin">Entrar</button>
        <!-- Placeholder until the access request flow is decided -->
        <p class="access-note">Ainda não tem acesso? Peça ao administrador da plataforma.</p>
      </div>

      <button type="button" class="scroll-hint" @click="scrollToFeatures">
        Conheça a plataforma
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="6 9 12 15 18 9" /></svg>
      </button>
    </section>

    <section ref="featuresEl" class="features" :class="{ visible }">
      <h2 class="features-title">Do primeiro boot à frota em operação</h2>

      <div class="cards">
        <article v-for="(f, i) in features" :key="f.kind" class="card" :style="{ transitionDelay: `${i * 90}ms` }">
          <div class="illustration">
            <!-- Provisioning: a packet travels from the device to the platform -->
            <svg v-if="f.kind === 'provision'" viewBox="0 0 200 80">
              <rect x="18" y="26" width="36" height="28" rx="4" class="stroke" />
              <line x1="58" y1="40" x2="152" y2="40" class="dash" />
              <circle cx="168" cy="40" r="14" class="stroke accent" />
              <circle cx="62" cy="40" r="3.5" class="packet" />
            </svg>

            <!-- OTA: version bar filling up -->
            <svg v-else-if="f.kind === 'ota'" viewBox="0 0 200 80">
              <text x="20" y="28" class="label">v1.0</text>
              <text x="180" y="28" class="label" text-anchor="end">v1.1</text>
              <rect x="20" y="36" width="160" height="10" rx="5" class="track" />
              <rect x="20" y="36" width="160" height="10" rx="5" class="fill" />
            </svg>

            <!-- Telemetry: sparkline being drawn -->
            <svg v-else-if="f.kind === 'telemetry'" viewBox="0 0 200 80">
              <line x1="16" y1="62" x2="184" y2="62" class="axis" />
              <polyline points="16,50 40,44 62,52 86,30 108,38 130,22 152,34 184,18" class="spark" pathLength="100" />
            </svg>

            <!-- Governance: audit rows appear one by one -->
            <svg v-else viewBox="0 0 200 80">
              <g v-for="row in 3" :key="row" class="row" :style="{ animationDelay: `${row * 0.5}s` }">
                <polyline :points="`28,${row * 20} 32,${row * 20 + 4} 39,${row * 20 - 4}`" class="check" />
                <rect x="50" :y="row * 20 - 3" :width="120 - row * 18" height="6" rx="3" class="line" />
              </g>
            </svg>
          </div>
          <h3>{{ f.title }}</h3>
          <p>{{ f.text }}</p>
        </article>
      </div>
    </section>
  </div>
</template>

<style scoped>
.login-page {
  background: var(--bg);
}

/* ── Hero ─────────────────────────────────────────── */
.hero {
  position: relative;
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  overflow: hidden;
  padding: var(--space-10) var(--space-4);
}

.hero-content {
  position: relative;
  max-width: 820px;
  text-align: center;
  animation: rise 600ms ease-out both;
}

.title {
  font-size: clamp(40px, 7vw, 72px);
  line-height: 1.05;
  letter-spacing: -0.02em;
  margin: 0 0 var(--space-4);
}

.tagline {
  font-size: var(--text-lg);
  color: var(--text-secondary);
  margin: 0 auto var(--space-8);
  max-width: 720px;
}

.expired {
  display: inline-block;
  font-size: var(--text-sm);
  color: var(--warning);
  border-left: 2px solid var(--warning);
  padding-left: var(--space-3);
  margin: 0 0 var(--space-5);
}

.btn-login {
  display: block;
  margin: 0 auto;
  min-width: 220px;
  padding: 12px var(--space-6);
  background: var(--accent);
  color: var(--accent-contrast);
  border: none;
  border-radius: var(--radius-md);
  font-family: var(--font-sans);
  font-size: var(--text-md);
  font-weight: 500;
  cursor: pointer;
  transition: background var(--transition), transform var(--transition);
}
.btn-login:hover { background: var(--accent-hover); transform: translateY(-1px); }

.access-note {
  margin: var(--space-4) 0 0;
  font-size: var(--text-sm);
  color: var(--text-muted);
}

.scroll-hint {
  position: absolute;
  bottom: var(--space-6);
  left: 50%;
  transform: translateX(-50%);
  display: inline-flex;
  flex-direction: column;
  align-items: center;
  gap: 2px;
  background: none;
  border: none;
  color: var(--text-muted);
  font-family: var(--font-sans);
  font-size: var(--text-xs);
  cursor: pointer;
}
.scroll-hint:hover { color: var(--text); }
.scroll-hint svg { animation: nudge 2s ease-in-out infinite; }

/* ── Features ─────────────────────────────────────── */
.features {
  max-width: 1120px;
  margin: 0 auto;
  padding: var(--space-10) var(--space-6) calc(var(--space-10) * 2);
}

.features-title {
  font-size: var(--text-2xl);
  font-weight: 600;
  text-align: center;
  margin: 0 0 var(--space-8);
}

.cards {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: var(--space-4);
}

.card {
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--radius-lg);
  padding: var(--space-5);
  opacity: 0;
  transform: translateY(16px);
  transition: opacity 500ms ease, transform 500ms ease, border-color var(--transition);
}
.card:hover { border-color: var(--border-strong); }
.features.visible .card { opacity: 1; transform: none; }

.card h3 { font-size: var(--text-md); font-weight: 600; margin: var(--space-4) 0 var(--space-2); }
.card p { font-size: var(--text-sm); color: var(--text-muted); margin: 0; line-height: 1.55; }

.illustration {
  height: 88px;
  border-radius: var(--radius-md);
  background: var(--panel);
  display: flex;
  align-items: center;
  justify-content: center;
}
.illustration svg { width: 100%; height: 100%; }

.stroke { fill: none; stroke: var(--border-strong); stroke-width: 2; }
.stroke.accent { stroke: var(--accent); }
.dash { stroke: var(--border-strong); stroke-width: 1.5; stroke-dasharray: 4 5; }
.packet { fill: var(--accent); }
.label { font-family: var(--font-mono); font-size: 11px; fill: var(--text-muted); }
.track { fill: var(--muted); }
.fill { fill: var(--accent); transform-origin: 20px 41px; transform: scaleX(1); }
.axis { stroke: var(--border); stroke-width: 1; }
.spark { fill: none; stroke: var(--success); stroke-width: 2; stroke-linejoin: round; stroke-linecap: round; stroke-dasharray: 100; stroke-dashoffset: 0; }
.check { fill: none; stroke: var(--success); stroke-width: 2; stroke-linecap: round; stroke-linejoin: round; }
.line { fill: var(--border-strong); }

/* Illustrations only animate once the cards are visible */
.features.visible .packet { animation: travel 2.4s ease-in-out infinite; }
.features.visible .fill { animation: fill 3.2s ease-in-out infinite; }
.features.visible .spark { animation: draw 3.2s ease-in-out infinite; }
.features.visible .row { animation: appear 3s ease-out infinite both; }

@keyframes travel {
  0%   { transform: translateX(0); opacity: 0; }
  10%  { opacity: 1; }
  85%  { opacity: 1; }
  100% { transform: translateX(92px); opacity: 0; }
}
@keyframes fill {
  0%   { transform: scaleX(0); }
  70%  { transform: scaleX(1); }
  100% { transform: scaleX(1); }
}
@keyframes draw {
  0%   { stroke-dashoffset: 100; }
  70%  { stroke-dashoffset: 0; }
  100% { stroke-dashoffset: 0; }
}
@keyframes appear {
  0%   { opacity: 0; transform: translateX(-6px); }
  20%  { opacity: 1; transform: none; }
  85%  { opacity: 1; }
  100% { opacity: 0; }
}
@keyframes rise {
  from { opacity: 0; transform: translateY(8px); }
  to   { opacity: 1; transform: none; }
}
@keyframes nudge {
  0%, 100% { transform: translateY(0); }
  50%      { transform: translateY(3px); }
}

@media (prefers-reduced-motion: reduce) {
  .hero-content, .scroll-hint svg { animation: none; }
  .card { opacity: 1; transform: none; transition: none; }
  .features.visible .packet, .features.visible .fill,
  .features.visible .spark, .features.visible .row { animation: none; }
}

@media (max-width: 960px) {
  .cards { grid-template-columns: repeat(2, 1fr); }
}

@media (max-width: 540px) {
  .cards { grid-template-columns: 1fr; }
  .features { padding: var(--space-8) var(--space-4); }
}
</style>
