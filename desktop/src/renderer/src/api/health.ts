// Spring Boot Actuator's health endpoint is not part of backend/openapi.json,
// so there is no generated type for it. This hand-written one is the only place it is described.
export interface ActuatorHealth {
  status: 'UP' | 'DOWN' | 'OUT_OF_SERVICE' | 'UNKNOWN'
}

export async function fetchHealth(baseUrl: string): Promise<ActuatorHealth> {
  const res = await fetch(`${baseUrl}/actuator/health`)
  // Actuator answers 503 with {"status":"DOWN"} when unhealthy; either way the body says it.
  const body = (await res.json()) as ActuatorHealth
  return body
}
