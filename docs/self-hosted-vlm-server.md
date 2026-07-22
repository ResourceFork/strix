# Self-Hosted VLM Server for Strix

Serve a fast vision-language model from your own PC (Windows 11, RTX 3090) and point
the Strix app at it. The app's remote mode speaks the OpenAI-compatible
`/v1/chat/completions` API with streaming, so any of OpenAI, Gemini (openai-compat),
Ollama, llama.cpp, or vLLM works. This guide uses **Ollama** (simplest) fronted by
your existing **Docker Caddy** reverse proxy.

## Architecture

```
Phone (Strix app) ──HTTPS──▶ vlm.apt.resourcefork.com (Caddy in Docker, TLS + bearer-key check)
                                        │ plain HTTP on trusted LAN
                                        ▼
                             Windows PC (Ollama on :11434, RTX 3090)
```

Golden rule: **never expose Ollama's port 11434 to the internet directly** — it has
no authentication of its own. Caddy is the only public door, and it checks a key.

---

## Part 1 — Windows PC (Ollama)

1. **Install Ollama**: download the Windows installer from
   <https://ollama.com/download> and run it. It runs as a tray/background service
   and uses the 3090 automatically (keep the NVIDIA driver reasonably current).

2. **Pull a vision model** (PowerShell):

   ```powershell
   ollama pull qwen3-vl:8b
   ```

   Why this model: the Qwen VL family is specifically strong at *grounding*
   (bounding boxes), which is what Strix's "Detect Objects" uses. On a 24 GB 3090
   expect ~40–80 tok/s decode and sub-second image prefill — a full detection
   response in roughly 1–3 seconds.

   Upgrade option for max quality: a 27B-class quant sized for 24 GB cards
   (e.g. Gemma 3 27B QAT int4). Roughly half the speed, smarter scene understanding.
   Switching later is just `ollama pull` + changing the Model field in the app.

3. **Sanity check locally**:

   ```powershell
   ollama run qwen3-vl:8b "hello"
   ```

4. **Listen on the LAN** (required because the Caddy proxy runs on a different
   machine). Add a *system* environment variable:

   - Settings → System → About → Advanced system settings → Environment Variables
     → New (System variable)
   - Name: `OLLAMA_HOST`   Value: `0.0.0.0`

   Quit Ollama from the system tray and relaunch it (or reboot) so it picks this up.

5. **Firewall — allow only the Caddy host in** (admin PowerShell; replace
   `192.168.x.z` with your Docker server's IP):

   ```powershell
   New-NetFirewallRule -DisplayName "Ollama (from Caddy host only)" `
     -Direction Inbound -Protocol TCP -LocalPort 11434 `
     -RemoteAddress 192.168.x.z -Action Allow
   ```

6. **DHCP reservation**: give the PC a fixed LAN IP on your router so the upstream
   address in the Caddyfile never drifts.

---

## Part 2 — Caddy (Docker) route

Generate a bearer key first (this is the app's "API Key"; treat it like a password):

```powershell
-join ((1..48) | ForEach-Object { '{0:x}' -f (Get-Random -Max 16) })
```

### If your Caddy uses a mounted Caddyfile

Add a site block (replace the key and the PC's LAN IP):

```caddyfile
vlm.apt.resourcefork.com {
    @unauthorized not header Authorization "Bearer YOUR_LONG_RANDOM_KEY"
    respond @unauthorized 401
    reverse_proxy 192.168.x.y:11434
}
```

Reload without downtime:

```bash
docker exec <caddy-container> caddy reload --config /etc/caddy/Caddyfile
```

### If your Caddy uses caddy-docker-proxy (compose labels)

Labels attach to containers, so an external upstream needs either a placeholder
service or the base Caddyfile. **Prefer the base Caddyfile** (the file referenced by
`CADDY_DOCKER_CADDYFILE_PATH`): paste the same site block as above into it — the
bearer-key matcher is awkward to express in label syntax.

Placeholder-container alternative (no auth matcher — add auth via base file):

```yaml
  vlm-route:
    image: alpine
    command: sleep infinity
    restart: unless-stopped
    labels:
      caddy: vlm.apt.resourcefork.com
      caddy.reverse_proxy: "192.168.x.y:11434"
```

### Streaming note

Caddy's `reverse_proxy` auto-detects server-sent events and streams them, so
detection boxes appear incrementally in the app. If responses ever arrive as one
lump at the end, force unbuffered streaming:

```caddyfile
    reverse_proxy 192.168.x.y:11434 {
        flush_interval -1
    }
```

---

## Part 3 — DNS and router

- **DNS**: `vlm.apt.resourcefork.com` must resolve to your public IP. A wildcard
  `*.apt.resourcefork.com` record covers it; otherwise add the record. Dynamic
  home IP → use a DDNS updater.
- **Router**: forward TCP **443** (and ideally **80** for smoothest certificate
  issuance) to the **Docker host**. Do **not** forward 11434.
- **NAT hairpinning** is enabled on your router, so the same URL works from home
  Wi-Fi and cellular alike.

---

## Part 4 — Verify end to end

From any machine (note both tests):

```bash
# Should return a chat completion:
curl https://vlm.apt.resourcefork.com/v1/chat/completions \
  -H "Authorization: Bearer YOUR_KEY" \
  -H "Content-Type: application/json" \
  -d '{"model":"qwen3-vl:8b","messages":[{"role":"user","content":"hi"}]}'

# Must return 401 (proves the lock works):
curl -i https://vlm.apt.resourcefork.com/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{"model":"qwen3-vl:8b","messages":[{"role":"user","content":"hi"}]}'
```

---

## Part 5 — Configure the Strix app

In the Camera/VLM section, switch **off** "Run on-device (offline)", then:

| Field       | Value                                    |
|-------------|------------------------------------------|
| Server URL  | `https://vlm.apt.resourcefork.com/v1`     |
| Model       | `qwen3-vl:8b`                             |
| API Key     | your generated key                        |

Server URL and Model are remembered across app restarts; the key is deliberately
not persisted. A blank key sends no `Authorization` header (for unauthenticated
local servers); a filled key sends `Bearer <key>`, which is what the Caddy matcher
checks.

---

## Alternative: Tailscale (no public exposure at all)

If you'd rather expose nothing: install Tailscale on the PC and phone (same
account), keep Ollama on `OLLAMA_HOST=0.0.0.0` (tailnet traffic arrives on the
Tailscale interface), and use `http://100.x.y.z:11434/v1` as the Server URL with a
blank key. WireGuard provides the encryption; nothing touches the public internet.
The app permits `http://` URLs for exactly this case.

---

## Troubleshooting

| Symptom | Cause / fix |
|---|---|
| Works on cellular, fails on home Wi-Fi | NAT hairpinning off (yours is on) or DNS override needed |
| 401 from server | Key mismatch between app field and Caddyfile |
| 502/504 from Caddy | Ollama not running, wrong upstream IP, or Windows firewall blocking the Caddy host |
| Whole response arrives at once | Add `flush_interval -1` to the `reverse_proxy` block |
| Full error details | `adb logcat -s RCViewModel` shows the complete failure + stack trace |
