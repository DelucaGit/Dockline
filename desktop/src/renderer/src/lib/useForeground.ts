import { useEffect, useState } from 'react'

/**
 * True while somebody can be looking at the app: the document is visible (not minimised or hidden) and the
 * window has focus. Used to stop automatic refreshing when nobody is watching.
 */
export function useForeground(): boolean {
  const [visible, setVisible] = useState(() => document.visibilityState === 'visible')
  const [focused, setFocused] = useState(() => document.hasFocus())
  useEffect(() => {
    const onVisibility = () => setVisible(document.visibilityState === 'visible')
    const onFocus = () => setFocused(true)
    const onBlur = () => setFocused(false)
    // Anything that changed between the first render and this effect.
    onVisibility()
    setFocused(document.hasFocus())
    document.addEventListener('visibilitychange', onVisibility)
    window.addEventListener('focus', onFocus)
    window.addEventListener('blur', onBlur)
    return () => {
      document.removeEventListener('visibilitychange', onVisibility)
      window.removeEventListener('focus', onFocus)
      window.removeEventListener('blur', onBlur)
    }
  }, [])
  return visible && focused
}
