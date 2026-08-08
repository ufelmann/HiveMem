import { describe, it, expect, vi, beforeEach } from 'vitest'
import { triggerReauth, clearReauthGuard } from '../../src/api/reauth'

describe('reauth', () => {
  beforeEach(() => {
    clearReauthGuard()
    sessionStorage.clear()
  })

  // Rewritten for the Access fallback (see documentation/auth.md): reload() navigated to
  // '/', which the service worker answers from the precache, so the Access login page was
  // never reached. '/login' is denylisted in the SW and always hits the network.
  // Was: "reloads once in access mode".
  it('navigates to /login once in access mode', () => {
    const assign = vi.fn()
    triggerReauth('access', { assign })
    expect(assign).toHaveBeenCalledTimes(1)
    expect(assign).toHaveBeenCalledWith('/login')
  })

  // Same rewrite: the subject is still the guard, only the navigation it guards changed
  // from reload() to assign('/login'). Was: "does not reload twice within the guard window".
  it('does not navigate twice within the guard window', () => {
    const assign = vi.fn()
    triggerReauth('access', { assign })
    triggerReauth('access', { assign })
    // Any caller that fails repeatedly must not navigate on every attempt. Today only the
    // startup wake_up can reach triggerReauth (HttpApiClient.subscribe()'s 10s poll is
    // unwired), so this guards the next such caller as much as the current one.
    expect(assign).toHaveBeenCalledTimes(1)
  })

  it('redirects to /login in legacy mode', () => {
    const assign = vi.fn()
    triggerReauth('legacy', { assign })
    expect(assign).toHaveBeenCalledWith('/login')
  })

  it('guards legacy mode too, so a repeating caller cannot navigate on every attempt', () => {
    const assign = vi.fn()
    triggerReauth('legacy', { assign })
    triggerReauth('legacy', { assign })
    expect(assign).toHaveBeenCalledTimes(1)
  })

  // logout() and the upload page's re-login button are deliberate user actions. Without
  // force they would be swallowed by a stamp left behind by an earlier automatic re-auth,
  // stranding the user on a permanent splash with no error to show.
  it('bypasses the guard when force is set', () => {
    const assign = vi.fn()
    triggerReauth('access', { assign })
    triggerReauth('access', { assign }, { force: true })
    expect(assign).toHaveBeenCalledTimes(2)
    expect(assign).toHaveBeenLastCalledWith('/login')
  })

  // A healthy session must not leave a stale stamp behind that suppresses the next
  // genuine re-auth.
  it('clearReauthGuard() lets the next call navigate again', () => {
    const assign = vi.fn()
    triggerReauth('access', { assign })
    clearReauthGuard()
    triggerReauth('access', { assign })
    expect(assign).toHaveBeenCalledTimes(2)
  })
})
