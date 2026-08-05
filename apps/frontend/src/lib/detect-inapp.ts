export function isInAppBrowser(): boolean {
  if (typeof window === 'undefined') return false
  return /KAKAOTALK/i.test(navigator.userAgent)
}

export function getOS(): 'ios' | 'android' | 'other' {
  if (typeof window === 'undefined') return 'other'
  const ua = navigator.userAgent
  if (/iPhone|iPad|iPod/i.test(ua)) return 'ios'
  if (/Android/i.test(ua)) return 'android'
  return 'other'
}

export function getExternalBrowserUrl(): string {
  const os = getOS()
  const url = window.location.href
  if (os === 'android') {
    const { host, pathname, search } = window.location
    return `intent://${host}${pathname}${search}#Intent;scheme=https;package=com.android.chrome;end`
  }
  return url
}
