# strix

Strix Cunicularia (burrowing owl), an LLM powered RC car controller

## Hardware

- [Hardware wiring guide](docs/hardware-wiring.md) — overview, parts glossary, and map of the wiring guides (start here)
- [ESC & servo wiring](docs/esc-wiring.md) — the direct path: wire a conventional ESC and steering servo to the Arduino Nano (beginner friendly)
- [Controller takeover](docs/controller-takeover.md) — workaround for sealed receiver+ESC combos (e.g. Hosim X15): automate a second controller's pots instead of wiring the ESC
- [Sensor wiring](docs/sensor-wiring.md) — the forward-perception array: center time-of-flight + corner ultrasonics, for either build

## VLM setup guides

- [On-device (offline) VLM](docs/on-device-vlm.md) — run Gemma 3n on the phone itself
- [Self-hosted VLM server](docs/self-hosted-vlm-server.md) — serve a faster model from your own PC (Ollama + Caddy/Tailscale)
