<script setup lang="ts">
import { useAuthStore } from '@/stores/auth'

const authStore = useAuthStore()
</script>

<template>
  <div class="perfil-container">
    <header class="cabecalho">
      <h2>Meu Perfil</h2>
      <router-link to="/tarefas" class="btn-voltar">Voltar</router-link>
    </header>

    <main class="perfil-card">
      <div class="avatar-area">
        <div class="avatar-circulo">
          {{ authStore.user?.nome?.charAt(0).toUpperCase() }}
        </div>
        <h3 class="nome-usuario">{{ authStore.user?.nome }}</h3>
        <p class="username-texto">@{{ authStore.user?.username }}</p>
      </div>
    </main>
  </div>
</template>

<style scoped>
.perfil-container {
  max-width: 500px;
  margin: 40px auto;
  font-family: 'Segoe UI', Tahoma, Geneva, Verdana, sans-serif;
  padding: 20px;
}

.cabecalho {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 30px;
  padding-bottom: 10px;
  border-bottom: 2px solid #eee;
}

h2 { color: #2c3e50; margin: 0; }

.btn-voltar {
  background-color: #95a5a6;
  color: white;
  text-decoration: none;
  padding: 8px 16px;
  border-radius: 4px;
  font-weight: bold;
  font-size: 14px;
  transition: 0.2s;
}

.btn-voltar:hover { background-color: #7f8c8d; }

.perfil-card {
  background: white;
  border-radius: 12px;
  box-shadow: 0 4px 15px rgba(0,0,0,0.08);
  padding: 30px;
  text-align: center;
}

.avatar-circulo {
  width: 80px;
  height: 80px;
  background-color: #42b983;
  color: white;
  font-size: 36px;
  font-weight: bold;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  margin: 0 auto 15px auto;
  box-shadow: 0 4px 10px rgba(66, 185, 131, 0.3);
}

.nome-usuario {
  margin: 0;
  font-size: 24px;
  color: #2c3e50;
}

.username-texto {
  margin: 5px 0 0 0;
  color: #7f8c8d;
  font-size: 14px;
}
</style>
