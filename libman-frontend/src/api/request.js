import axios from 'axios'
import { ElMessage } from 'element-plus'

// 创建 axios 实例（前端统一出口）
// 注意：baseURL='/api' 通常配合 Vite 代理或后端网关，把前端请求转发到 SpringBoot。
const request = axios.create({
  baseURL: '/api',
  timeout: 10000,
  withCredentials: true // 携带 Cookie（Session）
})

// 请求拦截器
request.interceptors.request.use(
  config => {
    // 可以在这里添加 token 等
    return config
  },
  error => {
    console.error('请求错误:', error)
    return Promise.reject(error)
  }
)

// 响应拦截器
request.interceptors.response.use(
  response => {
    const res = response.data
    
    // 根据后端的 Result 结构判断。
    // 约定：{ status: 'SUCCESS'|'FAIL'|'UNLOGIN', data?, errorMessage? }
    if (res.status === 'SUCCESS') {
      // 如果有data字段，返回data；否则返回整个res（用于AI接口等）
      return res.data !== undefined ? res.data : res
    } else if (res.status === 'UNLOGIN') {
      // 静默处理未登录状态，不显示任何提示
      // 只在非登录页且非首次加载时跳转
      const isAuthPage = window.location.pathname.includes('/login') ||
                         window.location.pathname.includes('/register')
      if (!isAuthPage && window.performance.navigation.type !== 1) {
        // 不是刷新操作才跳转
        window.location.href = '/login'
      }
      return Promise.reject(new Error('UNLOGIN'))
    } else {
      // 在登录/注册页面不显示 ElMessage，让组件自己处理错误提示
      const isAuthPage = window.location.pathname.includes('/login') ||
                         window.location.pathname.includes('/register')
      if (!isAuthPage) {
        ElMessage.error(res.errorMessage || '请求失败')
      }
      return Promise.reject(new Error(res.errorMessage || '请求失败'))
    }
  },
  error => {
    console.error('响应错误:', error)
    // 在登录/注册页面不显示 ElMessage
    const isAuthPage = window.location.pathname.includes('/login') ||
                       window.location.pathname.includes('/register')
    const isLogoutRequest = error.config?.url?.includes('/user/logout')
    
    // 忽略logout请求的404错误
    if (isLogoutRequest && error.response?.status === 404) {
      return Promise.resolve(null)
    }
    
    // 如果是401错误：交由上层（路由守卫/组件）统一处理为未登录。
    if (error.response?.status === 401) {
      return Promise.reject(new Error('UNLOGIN'))
    }
    
    if (!isAuthPage && !isLogoutRequest) {
      ElMessage.error(error.message || '网络错误')
    }
    return Promise.reject(error)
  }
)

export default request