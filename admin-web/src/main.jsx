import React, { Suspense, lazy } from 'react'
import ReactDOM from 'react-dom/client'
import './index.css'

const AdminApp = lazy(() => import('./App.jsx'))
const AuthActionPage = lazy(() => import('./AuthActionPage.jsx'))

const pathname = window.location.pathname.replace(/\/+$/, '') || '/'
const searchParams = new URLSearchParams(window.location.search)
const hasAuthActionParams = Boolean(
  searchParams.get('mode') && (searchParams.get('token') || searchParams.get('oobCode'))
)
const isAuthActionRoute = pathname === '/auth/action' || hasAuthActionParams

ReactDOM.createRoot(document.getElementById('root')).render(
  <React.StrictMode>
    <Suspense
      fallback={
        <div className="min-h-screen bg-[#F5F3EA] flex items-center justify-center text-sm font-medium text-[#07683B]">
          Loading Hold High The Shop…
        </div>
      }
    >
      {isAuthActionRoute ? <AuthActionPage /> : <AdminApp />}
    </Suspense>
  </React.StrictMode>
)
