<script setup lang="ts">
import { useRouter } from 'vue-router'
const router = useRouter()
</script>
<template>
  <div class="cinema-root">
    <v-btn class="back-btn" icon="mdi-arrow-left" @click="router.push('/')" />
    <div class="placeholder">Cinema Mode (TresJS HiveSphere wired in Task 22)</div>
  </div>
</template>
<style scoped>
.cinema-root { position:fixed; inset:0; background:#000; }
.back-btn { position:fixed; top:16px; left:16px; }
.placeholder { color:#4dc4ff; display:grid; place-items:center; height:100%; font-size:14px; letter-spacing:0.2em; }
</style>
