import { ApiProblem } from '@/api/github'

/** What a view should show for a failed request. Views map their own copy onto these kinds. */
export type ProblemKind = 'NOT_CONNECTED' | 'TOKEN_REJECTED' | 'RATE_LIMITED' | 'OTHER'

export interface ProblemState {
  kind: ProblemKind
  /** The backend's own explanation, or undefined when there is none. */
  detail?: string
  retryAfterSeconds?: number
}

/** Maps a problem's `code` (NOT status text) to a state. Source-specific codes share the suffix. */
export function problemState(error: unknown): ProblemState {
  if (!(error instanceof ApiProblem)) return { kind: 'OTHER', detail: error instanceof Error ? error.message : undefined }
  const base = { detail: error.detail, retryAfterSeconds: error.retryAfterSeconds }
  switch (error.code) {
    case 'GITHUB_NOT_CONNECTED':
      return { kind: 'NOT_CONNECTED', ...base }
    case 'GITHUB_TOKEN_REJECTED':
      return { kind: 'TOKEN_REJECTED', ...base }
    case 'GITHUB_RATE_LIMITED':
      return { kind: 'RATE_LIMITED', ...base }
    default:
      return { kind: 'OTHER', ...base }
  }
}
