# Serving the web client remotely (commercials.anifantakis.eu)

The Ktor server can serve the BUILT WasmJS client itself: one process, one
origin for page + API, so the existing Cloudflare tunnel makes the whole app
reachable at `https://commercials.anifantakis.eu` — a complete remote demo
with no second server and no CORS.

## One-time setup (already done on this machine)

- `server.yaml` points at the built distribution:
  ```yaml
  webApp: "webApp/build/dist/wasmJs/productionExecutable"
  ```
- `~/.cloudflared/config.yml` has a second ingress hostname
  (`commercials.anifantakis.eu → http://localhost:8080`), and the DNS CNAME
  was created with:
  ```bash
  cloudflared tunnel route dns commercials-mcp commercials.anifantakis.eu
  ```

## Every demo session

```bash
# 1. Build the PRODUCTION web client (only after client code changes)
./gradlew :webApp:wasmJsBrowserDistribution

# 2. Run the server (serves the app at / and the API under /api)
./gradlew :server:run

# 3. Bring the tunnel up (Ctrl-C to stop when the demo is over)
cloudflared tunnel run commercials-mcp
```

Then share `https://commercials.anifantakis.eu` with the client. Stop the
tunnel when done — nothing is exposed while it is down (530 from Cloudflare).

## How it works

- The server mounts `staticFiles("/")` over the distribution when
  `server.yaml webApp:` points at an existing directory (unset or missing =
  API-only, and `/` stays the liveness text). Explicit routes (`/api`,
  `/mcp`, `/oauth`, `/swagger`) always win over the static files.
- `/config.properties` is answered DYNAMICALLY with the origin the request
  came through (`behindReverseProxy: true` + XForwardedHeaders make that the
  public https origin behind the tunnel). The same build therefore works on
  `http://localhost:8080` AND on the tunnel hostname with zero edits.
- HTTPS at the Cloudflare edge gives the browser a SECURE CONTEXT, so
  KSafe's WebCrypto (remembered sessions) and WebAuthn (biometric unlock)
  both work — the plain-HTTP limitation only applies to LAN-IP serving.

## Notes

- The wasm dev server (`:webApp:wasmJsBrowserDevelopmentRun`, port 3001) is
  for development only: debug-sized bundle, must keep Gradle running, and
  webpack's host check rejects foreign hostnames. Don't tunnel it — build
  the distribution instead (step 1 takes ~1-2 minutes).
- Optional: put a Cloudflare Access policy in front of
  `commercials.anifantakis.eu` (Zero Trust → Access) if the demo should ask
  for an email code before the login screen — same as the mcp hostname.
- The demo hits the REAL local databases. Log in as a demo user with grants
  on the demo stations if the client should not see production data.
