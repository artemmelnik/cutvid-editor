# Jamal Video Compositor

Web application for isolating the main person in a reference video, adding a white outline, and compositing it over a background video.

## Current milestone

The local web UI provides two multi-video inputs:

- Reference video — person to cut out.
- Background video — destination footage.

Files are paired in selection order. Select the same number on each side for
one-to-one pairing, or select one file on either side to reuse it for all files
on the other side. A submission may create up to five outputs; all five begin
rendering in parallel. The page shows upload progress, render stage, elapsed
time, estimated remaining time, and a separate progress bar for every output.

`Render video` creates a local render job and invokes the C++ engine. The engine isolates the dominant person, applies a configurable outline, composites it over the background, loops a shorter background when needed, and preserves the reference video's audio.

## Projects

`+ Add project` (above the quick-render form) creates a reusable project:

- **Reference videos folder** and **Background videos folder** — choose whole
  folders. Every video file inside (MOV, MP4, M4V, AVI, MKV, WEBM, including
  subfolders) is uploaded once and kept on the server; hidden, empty and
  non-video files are skipped. Choosing a folder again while editing replaces
  that side's videos.
- **Videos to generate** — how many outputs one `Generate` run creates (1–100).
  The count can also be changed on the project card just before generating.
- **Composition settings** — every setting is a *from–to* range instead of a
  single value. Each generated video gets its own random outline width, person
  scale, left and bottom margin inside those ranges, and an outline colour
  between the two chosen colours. Set both ends equal for a fixed value.
- **Google Drive folder link** — optional; see below.

Each generated video takes a random reference video and a random background
video. Every video in a folder is used once before any of them repeats, and a
reference/background pair is not repeated while unused pairs remain. The
chosen files and values are shown on every render in *My renders*. Up to five
renders run at a time; the rest wait in the queue.

## Saving to Google Drive

A project with a Google Drive folder link uploads each finished video into that
folder (links such as `https://drive.google.com/drive/folders/…`, *My Drive*
and shared drives work). Finished videos also stay downloadable from *My renders*.

1. Google sign-in must be configured (see below). In the same Google Cloud
   project, enable the **Google Drive API** and add the
   `https://www.googleapis.com/auth/drive` scope to the OAuth consent screen.
   No additional redirect URI is needed.
2. After signing in, choose **Connect Google Drive** and allow Drive access.
   The folder must be one that account can add files to.

The Drive scope is a restricted Google scope. While the OAuth app is in
*Testing* mode only its listed test users can connect, and Google expires their
Drive access after 7 days — the app then asks to connect again. An internal
Google Workspace app, or a published app (Google reviews apps that use
restricted scopes), avoids the weekly reconnect.

Uploads that fail (for example after access was revoked) show the reason and a
**Retry upload** button. An upload interrupted by a network error or a server
restart continues from where it stopped.

## Run the web app

Build the native render engine first, then start the local web entry point:

```sh
cmake -S render-engine -B render-engine/build
cmake --build render-engine/build
./gradlew :web-app:run
```

Open http://127.0.0.1:8787. Videos are uploaded only to this machine. Completed exports are saved to `OneDrive-Personal/Jamal Video Compositor/Exports` (and offered as browser downloads); temporary uploads remain local in `~/.jamal`.

## Docker deployment

The production image builds the web app and native renderer together, includes
the ONNX-simplified MODNet model with its fixed `1×3×1024×576` input, and writes
all user uploads, render history, sessions, and exports to a persistent Docker
volume. The image verifies the model checksum during its build.

1. Copy `.env.example` to `.env` and set `GOOGLE_OAUTH_CLIENT_ID`,
   `GOOGLE_OAUTH_CLIENT_SECRET`, and `JAMAL_PUBLIC_URL`.
2. In Google Cloud Console, add
   `https://your-domain.example/auth/google/callback` as an authorised redirect
   URI. It must exactly match `JAMAL_PUBLIC_URL` plus that path.
3. Put the app behind HTTPS (for example Caddy or Nginx) and proxy requests to
   `http://127.0.0.1:8787`.
4. Start it:

```sh
docker compose up -d --build
docker compose logs -f jamal
```

The named `jamal-data` volume is intentionally retained by `docker compose
down`; it contains users' render history and exported videos. To expose the
container directly for a test server, browse to `http://SERVER_IP:8787` and set
`JAMAL_PUBLIC_URL` to that exact public address. For Google sign-in on a real
server, use HTTPS and the matching HTTPS URL.

Uploads require a Google sign-in. Each render is permanently associated with
the signed-in Google account, so its render history and downloads are shown
only to that account, including after restarting the app. Google access tokens
from sign-in are used only during sign-in and are never stored. Only when a
user connects Google Drive does the app keep that user's Drive refresh token,
AES-GCM encrypted with a key derived from the session secret, so uploads can
run in the background; **Disconnect Drive** deletes it and revokes it at Google.

If Google OAuth credentials are not configured, the upload page offers a local
browser-session mode instead, so the app remains usable without `.env` setup.
That mode keeps render history only in that browser session; configure Google
OAuth when users need account-based history across devices.

Create a Google OAuth **Web application** client, add
`http://127.0.0.1:8787/auth/google/callback` as an authorised redirect URI,
then put its credentials in the project-root `.env` file:

```dotenv
GOOGLE_OAUTH_CLIENT_ID=your-client-id.apps.googleusercontent.com
GOOGLE_OAUTH_CLIENT_SECRET=your-google-client-secret
```

The repository includes `.env.example`, while the real `.env` is ignored by
Git. Restart the application after changing its configuration. Shell environment
variables and JVM system properties override values loaded from `.env`.

### Admin activity dashboard

Open [http://127.0.0.1:8787/admin](http://127.0.0.1:8787/admin). The dashboard
is unlocked and shows every persisted render job, its Google-account owner,
state, progress, and status message. It refreshes every two seconds.
It also provides a download of the current structured log.

Admin tokens may contain Unicode characters. The dashboard encodes the token
as UTF-8/Base64 before placing it in the authenticated request header; the
token itself is never persisted or logged by the server.

Activity is stored as newline-delimited JSON under `~/.jamal/logs`, rotated
daily and retained for 30 days. Logs include uploads, render pipeline changes,
render-engine output, progress, failures with stack traces, downloads,
admin authentication failures, and relevant HTTP requests. Tokens and raw MAC
addresses are never logged; sensitive detail keys are automatically redacted.

To use a different shared folder, such as Dropbox, set `jamal.exports.dir` when starting the app:

```sh
./gradlew -Djamal.exports.dir="$HOME/Dropbox/Jamal Video Compositor/Exports" :web-app:run
```

For a direct end-to-end rendering verification, use:

```sh
./tests/render-smoke.sh
```
