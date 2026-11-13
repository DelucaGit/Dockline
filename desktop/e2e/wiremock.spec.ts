import { expect, test } from './support/fixtures'

// Proves the fake-API wiring that later stories use for GitHub and Hostinger.
test('WireMock starts, answers stubs, can be handed to the app and stops again', async ({
  wiremock,
  launchApp
}) => {
  await wiremock.stubFor({
    request: { method: 'GET', url: '/user' },
    response: { status: 200, jsonBody: { login: 'octo-test' } }
  })
  const res = await fetch(`${wiremock.url}/user`)
  expect(await res.json()).toEqual({ login: 'octo-test' })
  expect(await wiremock.requests()).toHaveLength(1)

  // The app and its backend start fine with the external APIs pointing at WireMock.
  const { window } = await launchApp({
    DOCKLINE_GITHUB_API_URL: wiremock.url,
    DOCKLINE_GITHUB_OAUTH_URL: wiremock.url,
    DOCKLINE_HOSTINGER_API_URL: wiremock.url
  })
  await expect(window.getByText('Backend connected')).toBeVisible()

  await wiremock.stop()
  await expect(fetch(`${wiremock.url}/__admin/health`)).rejects.toThrow()
})
