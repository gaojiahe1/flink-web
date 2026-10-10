<script setup lang="ts">
import { computed, onMounted, ref, watch, nextTick } from 'vue'
import { request, jsonBody } from '../../../shared/api/http'
import { currentUser } from '../../permission/stores/auth'
import { showValidationError } from '../../../shared/utils/fieldValidation'
const image = ref(''),
  revision = ref(0),
  loading = ref(true),
  saving = ref(false),
  error = ref(''),
  message = ref('')
const form = ref<HTMLFormElement>()
const admin = computed(() => currentUser.value?.roles.includes('ROLE_ADMIN'))
watch(image, () => {
  error.value = ''
  message.value = ''
})
onMounted(async () => {
  try {
    const result = await request<{ image: string; revision: number }>('/api/system/downloader')
    image.value = result.image || ''
    revision.value = result.revision || 0
  } catch (e) {
    error.value = (e as Error).message
  } finally {
    loading.value = false
  }
})
async function save() {
  if (saving.value || !form.value?.reportValidity()) return
  saving.value = true
  error.value = ''
  message.value = ''
  try {
    const result = await request<{ image: string; revision: number }>('/api/system/downloader', {
      method: 'PUT',
      ...jsonBody({ image: image.value, revision: revision.value }),
    })
    image.value = result.image
    revision.value = result.revision
    await nextTick()
    message.value = '已更新。后续预览、部署及重建统一使用此镜像；已运行的作业保持当前配置。'
  } catch (e) {
    error.value = showValidationError((e as Error).message, form.value)
  } finally {
    saving.value = false
  }
}
</script>
<template>
  <section class="panel form-panel downloader-settings">
    <h3>文件下载器</h3>
    <p class="muted">
      平台统一绑定一个下载器镜像，与 Flink 运行镜像独立。所有作业的外部文件由此镜像下载。
    </p>
    <form ref="form" @submit.prevent="save">
      <label
        >文件下载器镜像<input
          data-field="image"
          v-model="image"
          required
          maxlength="512"
          pattern="[a-zA-Z0-9][a-zA-Z0-9.:/_@\-]*"
          :disabled="!admin || loading || saving"
          placeholder="仓库地址/flink-web/downloader:版本"
      /></label>
      <div class="form-actions">
        <button v-if="admin" class="button primary" :disabled="loading || saving">
          {{ saving ? '保存中…' : '保存下载器绑定' }}
        </button>
      </div>
    </form>
    <p v-if="loading" class="muted">正在读取配置…</p>
    <p v-if="error" class="notice error" role="alert">{{ error }}</p>
    <p v-if="message" class="notice" role="status">{{ message }}</p>
  </section>
</template>
<style scoped>
.downloader-settings {
  margin-bottom: 16px;
}
form {
  max-width: 760px;
}
.form-actions {
  margin-top: 12px;
}
</style>
