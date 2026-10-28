<script setup lang="ts">
import ThemePicker from '../../../shared/components/layout/ThemePicker.vue'
import { showValidationError } from '../../../shared/utils/fieldValidation'
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { login } from '../stores/auth'
const router = useRouter()
const username = ref('admin')
const password = ref('')
const visible = ref(false)
const busy = ref(false)
const error = ref('')
async function submit() {
  if (busy.value) return
  busy.value = true
  error.value = ''
  try {
    await login(username.value, password.value)
    password.value = ''
    await router.replace('/jobs')
  } catch (e) {
    error.value = showValidationError((e as Error).message)
  } finally {
    busy.value = false
  }
}
</script>
<template>
  <div class="login-page">
    <div class="login-theme"><ThemePicker /></div>
    <div class="login-story">
      <div class="brand"><span class="brand-mark">F</span>Flink Web</div>
      <div>
        <p class="eyebrow">YOUR STREAMS. YOUR WORKSPACE.</p>
        <h1>让每一次作业变更<br />都有迹可循。</h1>
        <p>集中管理 Flink 作业、配置与运行环境。<br />从一份清晰的部署配置开始。</p>
      </div>
      <small>Flink Kubernetes Operator · 作业管理平台</small>
    </div>
    <div class="login-panel">
      <form class="login-form" :aria-busy="busy" @submit.prevent="submit">
        <p class="eyebrow">欢迎回来</p>
        <h2>登录工作空间</h2>
        <p class="muted">使用平台账号继续</p>
        <div v-if="error" class="notice error" role="alert">{{ error }}</div>
        <label for="login-username">用户名</label>
        <input
          data-field="username"
          id="login-username"
          v-model="username"
          name="username"
          autocomplete="username"
          maxlength="64"
          :disabled="busy"
          required
        />
        <label for="login-password">密码</label>
        <div class="password-field">
          <input
            data-field="password"
            id="login-password"
            v-model="password"
            name="password"
            :type="visible ? 'text' : 'password'"
            autocomplete="current-password"
            :disabled="busy"
            required
          />
          <button
            type="button"
            class="password-toggle"
            :disabled="busy"
            :aria-pressed="visible"
            :aria-label="visible ? '隐藏密码' : '显示密码'"
            aria-controls="login-password"
            @click="visible = !visible"
          >
            {{ visible ? '隐藏' : '显示' }}
          </button>
        </div>
        <button class="button primary full-width" :disabled="busy">
          {{ busy ? '正在登录…' : '登录' }}
        </button>
        <p class="form-help">使用平台账号登录；首次管理员由部署环境配置。</p>
      </form>
    </div>
  </div>
</template>
