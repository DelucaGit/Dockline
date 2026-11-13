import { useEffect, useRef, useState } from 'react'

/** "Rate limited, retrying in X s": counts down locally and asks again when it reaches 0. `message` holds {seconds}. */
export function RateLimited({ message, seconds, onElapsed }: { message: string; seconds: number; onElapsed: () => void }) {
  const [left, setLeft] = useState(seconds)
  const elapsed = useRef(onElapsed)
  elapsed.current = onElapsed
  useEffect(() => {
    setLeft(seconds)
    const started = Date.now()
    const timer = setInterval(() => {
      const remaining = Math.max(0, seconds - Math.floor((Date.now() - started) / 1000))
      setLeft(remaining)
      if (remaining === 0) {
        clearInterval(timer)
        elapsed.current()
      }
    }, 1000)
    return () => clearInterval(timer)
  }, [seconds])
  return (
    <p role="status" className="mt-8 text-center text-lg font-medium">
      {message.replace('{seconds}', String(left))}
    </p>
  )
}
