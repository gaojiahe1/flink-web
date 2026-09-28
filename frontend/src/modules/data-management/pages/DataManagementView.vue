<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import PageHeader from '../../../shared/components/layout/PageHeader.vue'
import HelpTip from '../../../shared/components/form/HelpTip.vue'
import DataSourcesView from '../../datasource/pages/DataSourcesView.vue'
import FlinkCatalogsView from '../../flink-catalog/pages/FlinkCatalogsView.vue'
import NetworkProbe from '../components/NetworkProbe.vue'
const route = useRoute()
const catalog = computed(() => route.query.tab === 'catalogs')
</script>
<template>
  <PageHeader
    ><template #title
      >数据管理
      <HelpTip
        text="数据源定义连接与表结构；Catalog 管理目录上下文，在 SQL 开发中复用。所有连接和元数据测试均由用户手动触发。" /></template
  ></PageHeader>
  <nav class="data-tabs" aria-label="数据管理功能">
    <RouterLink to="/datasources" :class="{ active: !catalog }">数据源</RouterLink
    ><RouterLink to="/datasources?tab=catalogs" :class="{ active: catalog }"
      >Flink Catalog</RouterLink
    >
  </nav>
  <NetworkProbe />
  <div class="data-pane"><FlinkCatalogsView v-if="catalog" /><DataSourcesView v-else /></div>
</template>
