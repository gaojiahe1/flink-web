<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import PageHeader from '../../../shared/components/layout/PageHeader.vue'
import { aiApi, type AiModel } from '../../ai/api/ai'
import { currentUser } from '../../permission/stores/auth'
import { confirmDialog } from '../../../shared/utils/confirmDialog'
const admin = computed(() => currentUser.value?.roles.includes('ROLE_ADMIN'))
const models = ref<AiModel[]>([]),
  selected = ref(''),
  busy = ref(false),
  error = ref(''),
  message = ref('')
const defaults = () => ({
  id: '',
  name: '',
  model: '',
  baseUrl: '',
  revision: 0,
  apiKey: '',
  clearKey: false,
  enabled: true,
  external: true,
  privateNetwork: false,
  purposes: ['sql', 'definition', 'diagnosis'],
  timeoutSeconds: 90,
  maxTokens: 4096,
})
const form = reactive(defaults())
async function load() {
  if (!admin.value) return
  try {
    models.value = await aiApi.models(true)
  } catch (cause) {
    error.value = (cause as Error).message
  }
}
function edit(model?: AiModel) {
  Object.assign(form, defaults(), model || {}, { apiKey: '', clearKey: false })
  selected.value = model?.id || ''
  error.value = ''
  message.value = ''
}
async function save() {
  busy.value = true
  error.value = ''
  message.value = ''
  try {
    const saved = await aiApi.saveModel(form.id, { ...form })
    await load()
    edit(saved)
    message.value = '模型配置已保存，密钥不会返回页面。'
  } catch (cause) {
    error.value = (cause as Error).message
  } finally {
    busy.value = false
    form.apiKey = ''
  }
}
async function test(model: AiModel) {
  busy.value = true
  error.value = ''
  message.value = ''
  try {
    message.value = (await aiApi.testModel(model.id)).message
  } catch (cause) {
    error.value = (cause as Error).message
  } finally {
    busy.value = false
  }
}
async function remove(model: AiModel) {
  if (!(await confirmDialog('删除模型“' + model.name + '”？历史生成记录仍保留。'))) return
  busy.value = true
  try {
    await aiApi.deleteModel(model)
    await load()
    edit()
  } catch (cause) {
    error.value = (cause as Error).message
  } finally {
    busy.value = false
  }
}
onMounted(load)
</script>
<template>
  <PageHeader eyebrow="AI SERVICES"
    ><template #title>模型服务</template
    ><template #description
      >配置 SQL 助手与作业诊断使用的模型，支持兼容 Chat Completions 的服务。</template
    ><template #actions
      ><RouterLink class="button" to="/settings">返回系统设置</RouterLink></template
    ></PageHeader
  >
  <p v-if="!admin" class="notice error">仅管理员可维护模型服务。</p>
  <template v-else>
    <div v-if="error" class="notice error" role="alert">{{ error }}</div>
    <div v-if="message" class="notice" role="status">{{ message }}</div>
    <div class="models-layout">
      <section class="panel model-list">
        <div class="panel-toolbar">
          <h3>已添加模型</h3>
          <button class="button primary" :disabled="busy" @click="edit()">添加模型</button>
        </div>
        <p v-if="!models.length" class="empty-state">
          尚未添加模型。先填写服务地址和实际模型标识。
        </p>
        <article v-for="model in models" :key="model.id" :class="{ active: selected === model.id }">
          <button class="model-name text-button" :disabled="busy" @click="edit(model)">
            {{ model.name }}
          </button>
          <span class="badge">{{ model.enabled ? '启用' : '停用' }}</span>
          <p>{{ model.model }} · {{ model.external ? '外部服务' : '私有服务' }}</p>
          <small>{{
            model.purposes
              .map(
                (p) => ({ sql: 'SQL 助手', definition: '数据定义', diagnosis: '作业诊断' })[p] || p,
              )
              .join(' / ')
          }}</small>
          <div class="toolbar-actions">
            <button class="text-button" :disabled="busy" @click="test(model)">测试连接</button
            ><button class="text-button danger" :disabled="busy" @click="remove(model)">
              删除
            </button>
          </div>
        </article>
      </section>
      <form class="panel model-form" @submit.prevent="save">
        <h3>{{ selected ? '编辑模型' : '添加模型' }}</h3>
        <div class="model-grid">
          <label
            >配置标识<input
              v-model="form.id"
              :disabled="!!selected || busy"
              required
              pattern="[a-z0-9][a-z0-9_.-]{0,63}"
              placeholder="team-sql-model" /></label
          ><label
            >显示名称<input
              v-model="form.name"
              :disabled="busy"
              required
              maxlength="80"
              placeholder="团队 SQL 助手"
          /></label>
        </div>
        <label
          >Base URL<input
            v-model="form.baseUrl"
            :disabled="busy"
            required
            placeholder="https://model.example.com/v1"
          /><small>填写协议服务前缀；平台追加 /chat/completions，不跟随重定向。</small></label
        >
        <label
          >实际模型标识<input
            v-model="form.model"
            :disabled="busy"
            required
            placeholder="服务商提供的 model 标识"
        /></label>
        <label
          >API Key<input
            v-model="form.apiKey"
            :disabled="busy"
            type="password"
            autocomplete="new-password"
            :placeholder="selected ? '已保存的密钥留空保留' : '无认证服务可留空'"
          /><small>使用一次性加密传输，并以平台主密钥加密保存。</small></label
        >
        <label v-if="selected" class="check-field"
          ><input v-model="form.clearKey" :disabled="busy" type="checkbox" />清除已保存密钥</label
        >
        <div class="model-grid">
          <label
            >调用超时（秒）<input
              v-model.number="form.timeoutSeconds"
              :disabled="busy"
              type="number"
              min="5"
              max="180" /></label
          ><label
            >最大输出 tokens<input
              v-model.number="form.maxTokens"
              :disabled="busy"
              type="number"
              min="256"
              max="8192"
          /></label>
        </div>
        <fieldset :disabled="busy">
          <legend>可用用途</legend>
          <label
            v-for="purpose in [
              { id: 'sql', label: 'SQL 生成 / 解释 / 修复' },
              { id: 'definition', label: '数据定义建议' },
              { id: 'diagnosis', label: '作业报错分析' },
            ]"
            :key="purpose.id"
            class="check-field"
            ><input v-model="form.purposes" type="checkbox" :value="purpose.id" />{{
              purpose.label
            }}</label
          >
        </fieldset>
        <label class="check-field"
          ><input v-model="form.enabled" :disabled="busy" type="checkbox" />启用此模型</label
        >
        <label class="check-field"
          ><input
            v-model="form.external"
            :disabled="busy"
            type="checkbox"
          />外部模型：每次调用需明确允许发送脱敏上下文</label
        >
        <label class="check-field"
          ><input
            v-model="form.privateNetwork"
            :disabled="busy"
            type="checkbox"
          />允许内网／本机地址与 HTTP（仅受信任的私有模型）</label
        >
        <p class="muted">
          链路本地、组播和云元数据地址始终拒绝。HTTP 不提供传输加密，公网服务请使用 HTTPS。
        </p>
        <button class="button primary" :disabled="busy || !form.purposes.length">
          {{ busy ? '正在处理…' : '保存模型' }}
        </button>
      </form>
    </div>
  </template>
</template>
<style scoped>
.models-layout {
  display: grid;
  grid-template-columns: minmax(260px, 0.8fr) minmax(0, 1.2fr);
  gap: 18px;
  align-items: start;
}
.model-list,
.model-form {
  padding: 20px;
  min-width: 0;
}
.model-list article {
  padding: 16px 10px;
  border-bottom: 1px solid var(--line);
  overflow-wrap: anywhere;
}
.model-list article.active {
  background: #edf3ff;
  border-radius: 6px;
}
.model-name {
  font-weight: 600;
  margin-right: 8px;
}
.model-list p {
  margin: 5px 0;
}
.model-list small {
  color: var(--color-muted);
}
.model-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
}
label {
  min-width: 0;
}
fieldset {
  border: 1px solid var(--line);
  border-radius: 6px;
  padding: 12px;
  margin-bottom: 16px;
}
.check-field {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
  font-weight: 400;
}
.check-field input {
  width: auto;
  flex: none;
}
.muted,
small {
  font-size: 12px;
}
@media (max-width: 850px) {
  .models-layout {
    grid-template-columns: 1fr;
  }
}
@media (max-width: 480px) {
  .model-grid {
    grid-template-columns: 1fr;
  }
}
</style>
