import { createRouter, createWebHashHistory } from 'vue-router'
import { clearSession, ensureAuth } from '../../modules/permission/stores/auth'
import MainLayout from '../layouts/MainLayout.vue'

const router = createRouter({
  history: createWebHashHistory(),
  routes: [
    { path: '/login', component: () => import('../../modules/permission/pages/LoginView.vue') },
    {
      path: '/',
      component: MainLayout,
      children: [
        { path: '', redirect: '/jobs' },
        {
          path: 'jobs',
          component: () => import('../../modules/job-management/pages/JobsView.vue'),
        },
        {
          path: 'datasources',
          component: () => import('../../modules/data-management/pages/DataManagementView.vue'),
        },
        {
          path: 'catalogs',
          redirect: '/datasources?tab=catalogs',
        },
        { path: 'sql', component: () => import('../../modules/sql/pages/SqlStudioView.vue') },
        {
          path: 'jar',
          component: () => import('../../modules/job-creation/pages/JarDevelopmentView.vue'),
        },
        {
          path: 'jar/new',
          component: () => import('../../modules/job-creation/pages/JobEditorView.vue'),
        },
        {
          path: 'jar/:id/edit',
          component: () => import('../../modules/job-creation/pages/JobEditorView.vue'),
        },
        {
          path: 'sessions',
          component: () => import('../../modules/session/pages/SessionsView.vue'),
        },
        {
          path: 'jobs/jar/new',
          component: () => import('../../modules/job-creation/pages/JobEditorView.vue'),
        },
        {
          path: 'jobs/new',
          component: () => import('../../modules/job-creation/pages/JobEditorView.vue'),
        },
        {
          path: 'jobs/:id/edit',
          component: () => import('../../modules/job-creation/pages/JobEditorView.vue'),
        },
        {
          path: 'templates',
          component: () => import('../../modules/template/pages/TemplatesView.vue'),
        },
        { path: 'images', component: () => import('../../modules/image/pages/ImagesView.vue') },
        {
          path: 'configs',
          component: () => import('../../modules/configuration/pages/ConfigsView.vue'),
        },
        {
          path: 'artifacts',
          component: () => import('../../modules/artifact/pages/ArtifactsView.vue'),
        },
        { path: 'users', component: () => import('../../modules/permission/pages/UsersView.vue') },
        { path: 'audit', component: () => import('../../modules/audit/pages/AuditView.vue') },
        {
          path: 'settings',
          component: () => import('../../modules/system/pages/SettingsView.vue'),
        },
      ],
    },
    { path: '/:pathMatch(.*)*', redirect: '/jobs' },
  ],
})

router.beforeEach(async (to) => (to.path !== '/login' && !(await ensureAuth()) ? '/login' : true))
window.addEventListener('session-expired', () => {
  clearSession()
  void router.replace('/login')
})
export default router
