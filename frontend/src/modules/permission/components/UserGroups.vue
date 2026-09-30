<script setup lang="ts">
import { showValidationError } from '../../../shared/utils/fieldValidation'
import { ref, reactive, computed, onMounted } from 'vue'
import { request, jsonBody } from '../../../shared/api/http'
import { currentUser } from '../stores/auth'
import type { Group } from '../../../shared/types/workbench'
const groups = ref<Group[]>([]),
  busy = ref(false),
  error = ref(''),
  message = ref('')
const admin = computed(() => currentUser.value?.roles.includes('ROLE_ADMIN'))
const group = reactive({ id: '', name: '', members: '', revision: 0 })
async function run(task: () => Promise<void>) {
  if (busy.value) return
  busy.value = true
  error.value = ''
  message.value = ''
  try {
    await task()
  } catch (e) {
    error.value = showValidationError((e as Error).message)
  } finally {
    busy.value = false
  }
}
onMounted(() =>
  run(async () => {
    groups.value = await request('/api/platform/groups')
  }),
)
function editGroup(row?: Group) {
  Object.assign(group, {
    id: row?.id || '',
    name: row?.name || '',
    members: (row?.members || []).join(','),
    revision: row?.revision || 0,
  })
}
async function saveGroup() {
  await run(async () => {
    await request('/api/platform/groups/' + encodeURIComponent(group.id), {
      method: 'PUT',
      ...jsonBody({
        ...group,
        members: group.members
          .split(',')
          .map((x) => x.trim())
          .filter(Boolean),
      }),
    })
    groups.value = await request('/api/platform/groups')
    editGroup()
    message.value = '用户组已保存，权限变更立即生效。'
  })
}
</script>
<template>
  <section class="panel form-panel detail-panel">
    <h3>用户组与作业授权</h3>
    <div v-if="error" class="notice error" role="alert">{{ error }}</div>
    <div v-if="message" class="notice" role="status">{{ message }}</div>
    <p class="muted">平台角色决定功能权限，用户组决定作业范围；在作业详情“归属与链接”中授权。</p>
    <table>
      <thead>
        <tr>
          <th>组标识</th>
          <th>名称</th>
          <th>成员</th>
          <th>操作</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="row in groups" :key="row.id">
          <td>{{ row.id }}</td>
          <td>{{ row.name }}</td>
          <td>{{ row.members.join('、') }}</td>
          <td><button v-if="admin" class="text-button" @click="editGroup(row)">编辑</button></td>
        </tr>
      </tbody>
    </table>
    <form v-if="admin" class="group-form" @submit.prevent="saveGroup">
      <h3>{{ group.revision ? '编辑用户组' : '新增用户组' }}</h3>
      <div class="field-grid">
        <label
          >组标识<input
            data-field="id"
            v-model="group.id"
            required
            :disabled="!!group.revision"
            pattern="[a-z0-9][a-z0-9_.\-]{0,100}" /></label
        ><label>名称<input data-field="name" v-model="group.name" required /></label
        ><label class="wide-field"
          >成员账号<input
            data-field="members"
            v-model="group.members"
            placeholder="现有账号，以逗号分隔；清空可移除全部成员"
        /></label>
      </div>
      <div class="form-actions">
        <button type="button" class="button" @click="editGroup()">清空</button
        ><button class="button primary" :disabled="busy">保存用户组</button>
      </div>
    </form>
  </section>
</template>

<style scoped>
.group-form {
  border-top: 1px solid var(--line);
  margin-top: 16px;
  padding-top: 14px;
}
h3 {
  margin-bottom: 12px;
}
</style>
