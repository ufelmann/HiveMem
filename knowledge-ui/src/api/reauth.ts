import type { AuthMode } from './authMode'

const GUARD_KEY = 'hivemem_reauth_at'
const GUARD_MS = 5 * 60 * 1000

interface Nav {
  assign: (url: string) => void
}

interface ReauthOptions {
  /**
   * Skip the guard. Only for deliberate user actions (logout, the upload page's re-login
   * button): those must never return silently, or the user is left on a splash with no
   * error and nothing navigating.
   */
  force?: boolean
}

const defaultNav: Nav = {
  assign: (url) => { window.location.href = url },
}

/**
 * Single re-auth path for the whole app, in both modes: a full navigation to /login.
 *
 * It has to be /login and it has to be a navigation. In Access mode Cloudflare answers an
 * expired request with a cross-origin redirect that no fetch handler can follow, and a
 * reload of '/' does not help either — the service worker's navigation fallback serves '/'
 * from the precache, so the browser never issues a request and never sees the challenge.
 * '/login' is on the worker's denylist (vite.config.ts) and therefore always reaches the
 * network; in Access mode the origin answers it with a redirect back into the app
 * (GoneController), in legacy mode with the login page.
 *
 * The guard stops any repeatedly-failing caller from navigating on every attempt. Today the
 * only caller that can reach it is the startup wake_up in stores/auth.ts, so one navigation
 * per failure is all that could happen right now; HttpApiClient.subscribe()'s 10s status
 * poll would be the real loop, but nothing in src/ calls subscribe() yet. The guard stays so
 * that wiring it up — or adding any other retry — cannot turn into a navigation loop.
 *
 * `_mode` no longer selects a destination — both modes go to /login — but it stays in the
 * signature: the caller has already resolved it, and the two modes' recovery paths have
 * diverged before (and may again).
 */
export function triggerReauth(_mode: AuthMode, nav: Nav = defaultNav, options: ReauthOptions = {}): void {
  if (!options.force) {
    const last = Number(sessionStorage.getItem(GUARD_KEY) ?? 0)
    if (Date.now() - last < GUARD_MS) return
  }
  sessionStorage.setItem(GUARD_KEY, String(Date.now()))
  nav.assign('/login')
}

/**
 * Drop the guard stamp. A session that came back healthy must not leave a stamp behind
 * that suppresses the next genuine re-auth.
 */
export function clearReauthGuard(): void {
  sessionStorage.removeItem(GUARD_KEY)
}
