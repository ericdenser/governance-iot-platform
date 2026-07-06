<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'

const route = useRoute()

const sessionExpired = computed(() => route.query.expired === '1')


const fazerLogin = () => {
  window.location.href = '/api/oauth2/authorization/keycloak'
}
</script>

<template>
  <div class="login-container">
    <div class="login-card">
      <h1>Bem-vindo</h1>
      <p class="subtitle">Governance IoT — plataforma de gerenciamento de dispositivos</p>

      <div v-if="sessionExpired" class="expired-banner" role="alert">
        <strong>Sua sessão expirou.</strong>
        <span>Faça login novamente para continuar.</span>
      </div>

      <button @click="fazerLogin" class="btn-login">
        Entrar com Keycloak
      </button>
    </div>
  </div>
</template>

<style scoped>
.login-container {
  display: flex;
  justify-content: center;
  align-items: center;
  min-height: 100vh;
  background: var(--bg);
  padding: var(--space-4);
}

.login-card {
  background: var(--surface);
  border: 1px solid var(--border);
  padding: var(--space-10);
  border-radius: var(--radius-lg);
  box-shadow: 0 10px 40px rgba(0, 0, 0, 0.35);
  text-align: center;
  max-width: 400px;
  width: 100%;
}

.login-card h1 {
  color: var(--text);
  margin: 0 0 var(--space-2);
  font-size: var(--text-2xl);
}

.subtitle {
  color: var(--text-secondary);
  margin: 0 0 var(--space-6);
  font-size: var(--text-sm);
}

.expired-banner {
  display: flex;
  flex-direction: column;
  gap: var(--space-1);
  background: var(--warning-dim);
  border: 1px solid var(--warning);
  color: var(--warning);
  padding: var(--space-3) var(--space-4);
  border-radius: var(--radius-md);
  margin-bottom: var(--space-5);
  text-align: left;
  font-size: var(--text-sm);
}

.expired-banner span {
  color: var(--text-secondary);
}

.btn-login {
  background: var(--primary);
  color: var(--text-inverse);
  border: none;
  padding: var(--space-3) var(--space-6);
  font-size: var(--text-base);
  font-weight: 600;
  border-radius: var(--radius-md);
  cursor: pointer;
  width: 100%;
  transition: background-color 0.15s;
}

.btn-login:hover {
  background: var(--primary-hover);
}
</style>
