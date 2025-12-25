import { createRouter, createWebHistory } from 'vue-router'
import { useUserStore } from '@/stores/user'

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    {
      path: '/',
      name: 'home',
      component: () => import('@/views/HomeView.vue'),
      meta: { requiresAuth: false, transition: 'fade' }
    },
    {
      path: '/books',
      name: 'books',
      component: () => import('@/views/BooksView.vue'),
      meta: { requiresAuth: false, transition: 'fade' }
    },
    {
      path: '/categories',
      name: 'categories',
      component: () => import('@/views/CategoriesView.vue'),
      meta: { requiresAuth: false, transition: 'fade' }
    },
    {
      path: '/profile',
      name: 'profile',
      component: () => import('@/views/ProfileView.vue'),
      meta: { requiresAuth: true, transition: 'fade' }
    },
    {
      path: '/donate',
      name: 'donate',
      component: () => import('@/views/DonateView.vue'),
      meta: { requiresAuth: true, transition: 'fade' }
    },
    {
      path: '/ai',
      name: 'ai',
      component: () => import('@/views/AiAssistantView.vue'),
      meta: { requiresAuth: false, transition: 'fade' }
    },
    {
      path: '/my-donations',
      name: 'my-donations',
      component: () => import('@/views/MyDonationsView.vue'),
      meta: { requiresAuth: true, transition: 'fade' }
    },
    {
      path: '/login',
      name: 'login',
      component: () => import('@/views/AuthView.vue'),
      meta: { requiresAuth: false, transition: 'fade', hideNavBar: true }
    },
    {
      path: '/register',
      name: 'register',
      component: () => import('@/views/AuthView.vue'),
      meta: { requiresAuth: false, transition: 'fade', hideNavBar: true }
    }
  ],
})

// 路由守卫：
// - 通过 meta.requiresAuth 控制需要登录的页面
// - 首次进入页面时（from.name 为空），为了避免“先渲染再跳转”的闪烁，这里会先静默拉取用户信息
router.beforeEach(async (to, from, next) => {
  const userStore = useUserStore()
  
  // 如果是首次访问（from.name 为 undefined）
  if (!from.name) {
    // 如果访问的不是登录/注册页，先检查登录状态
    if (to.name !== 'login' && to.name !== 'register') {
      try {
        // 静默检查登录状态
        await userStore.fetchUserInfo()
        // 已登录，继续访问
        next()
      } catch (error) {
        // 未登录，直接跳转到登录页，不显示任何内容
        next({ name: 'login', replace: true })
        return
      }
    } else {
      // 访问登录/注册页，直接放行
      next()
    }
    return
  }
  
  // 如果需要认证但未登录
  if (to.meta.requiresAuth && !userStore.isLoggedIn) {
    // 尝试获取用户信息
    try {
      await userStore.fetchUserInfo()
      next()
    } catch (error) {
      // 未登录，跳转到登录页
      next({ name: 'login', query: { redirect: to.fullPath } })
    }
  } else {
    next()
  }
})

export default router