# Setting up the GitHub OAuth App

Dockline's "Connect to GitHub" button uses GitHub's **device flow**: the app shows a short code, you confirm it in your browser, and GitHub hands the app a token. For this to work, GitHub must know about Dockline, which means registering a small **OAuth App** on your GitHub account. You do this once, it takes about five minutes, and it costs nothing.

You only need the **Client ID** from this registration. Dockline does **not** use a client secret, so do not generate one.

## Before you start

- You need to be signed in to github.com with the account that will own the app (the repository owner).
- The labels below follow GitHub's documentation. GitHub changes its pages from time to time, so a button may look slightly different. The doc links are at the bottom.

## Part 1: Register the app on GitHub

1. Go to <https://github.com> and sign in.
2. Click your **profile picture** in the top-right corner, then click **Settings**.
3. In the left sidebar, click **Developer settings**.
4. Click **OAuth Apps**.
5. Click **New OAuth App**. (If you have never made one, the button says **Register a new application** instead.)
6. Fill in the form:

   | Field | What to enter |
   | --- | --- |
   | **Application name** | `Dockline`. This is shown on GitHub's approval page and is public, so do not put anything private in it. |
   | **Homepage URL** | `https://github.com/DelucaGit/Dockline` (the repository). Any valid web address works; the app does not use it. |
   | **Application description** | Optional. For example: `Desktop app that shows my GitHub repositories and deployments.` |
   | **Authorization callback URL** | `http://localhost` |

   **About the callback URL:** the device flow does not use a callback URL at all, because nothing is redirected back to the app. GitHub's form still asks for one, so enter the placeholder `http://localhost`. It is never called.

7. Tick the checkbox **Enable Device Flow**. This is essential. If it is not ticked, GitHub rejects Dockline's request with a `device_flow_disabled` error and "Connect to GitHub" cannot work.
8. If you see **Expire user access tokens** ticked, you can leave it as it is. GitHub turns it on by default. Dockline treats an expired or revoked token as "not connected" and offers "Reconnect".
9. Click **Register application**.

## Part 2: Copy the Client ID

1. After registering, GitHub shows the app's page. Near the top you will see **Client ID**, a short text such as `Ov23li...`.
2. Copy it. The Client ID is **not a secret**. It only tells GitHub which app is asking.
3. **Do not click "Generate a new client secret".** Dockline does not need one and never stores one.

## Part 3: Give the Client ID to Dockline

The backend reads the Client ID from this setting:

| Property | Environment variable |
| --- | --- |
| `dockline.github.client-id` | `DOCKLINE_GITHUB_CLIENT_ID` |

The simplest way on Windows is the environment variable. In PowerShell, set it for your user account:

```powershell
[Environment]::SetEnvironmentVariable("DOCKLINE_GITHUB_CLIENT_ID", "PASTE_YOUR_CLIENT_ID_HERE", "User")
```

Then **close and reopen** Dockline (and any terminal you started it from) so it picks up the new value.

If you run the backend by hand, you can also pass it as an argument: `--dockline.github.client-id=PASTE_YOUR_CLIENT_ID_HERE`.

Things worth knowing:

- If the Client ID is **not set**, Dockline does not crash. "Connect to GitHub" shows a clear message saying the Client ID is missing, and points you back to this document.
- The automated tests use a **fake Client ID** together with a fake GitHub server, so they never talk to the real GitHub and never need your real Client ID.
- The access token you receive after approving is stored in **Windows Credential Manager**, not in files or logs.

(The setting itself is added to the backend by a later task, so until that is done the app will not read it yet.)

## Part 4: Check that it works

1. Start Dockline and open **Settings**.
2. Click **Connect to GitHub**. A short code appears and your browser opens `https://github.com/login/device`.
3. Type the code, review the permissions, and approve. Dockline notices on its own and shows **Connected**.

If you see a `device_flow_disabled` message, go back to the app's page on GitHub (Settings, Developer settings, OAuth Apps, Dockline), tick **Enable Device Flow** and save.

## Note for the owner: which permissions Dockline asks for

GitHub calls permissions "scopes". Dockline plans to request:

- `read:user`: read your basic profile (name and avatar) so Dockline can show who is connected.
- `repo`: needed to list your **private** repositories and their deployments.

**An honest caveat:** Dockline only reads data (v1 is read-only), but `repo` is a broad scope. GitHub's scope list has no read-only scope for private repositories. `repo` grants read **and write** access to code, commit statuses, deployment statuses and webhooks of your repositories, public and private. GitHub does offer narrower scopes (`repo:status`, `repo_deployment`), but they only cover statuses and deployments, not listing private repositories or reading their details. The read-only behaviour therefore comes from Dockline itself, which only makes read requests, not from GitHub enforcing it.

What this means for you:

- Only approve the app on your own account. The token lives in Windows Credential Manager.
- You can revoke access at any time at <https://github.com/settings/applications> (Authorized OAuth Apps), or click **Disconnect** in Dockline.
- If you only use public repositories, a future version could ask for much less, but v1 asks for `repo`.
- Repositories owned by an organisation may need an organisation owner to approve the app. GitHub shows this on the approval page.

## Sources

- Creating an OAuth app: <https://docs.github.com/en/apps/oauth-apps/building-oauth-apps/creating-an-oauth-app>
- Authorizing OAuth apps (device flow): <https://docs.github.com/en/apps/oauth-apps/building-oauth-apps/authorizing-oauth-apps#device-flow>
- Scopes for OAuth apps: <https://docs.github.com/en/apps/oauth-apps/building-oauth-apps/scopes-for-oauth-apps>
