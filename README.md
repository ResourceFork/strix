# strix

Strix Cunicularia (burrowing owl), an LLM powered RC car controller

## Hardware

**[→ Start with the hardware guide](docs/hardware-wiring.md)** — it explains what
you're building, helps you pick a build path, and links to everything below.

_Shared references_

- [Glossary](docs/glossary.md) — every acronym and concept in plain language
- [Parts & shopping](docs/parts-and-shopping.md) — what to buy, what to salvage, what to skip
- [Serial protocol](docs/serial-protocol.md) — the phone ↔ Nano command language

_Drive path A — conventional ESC with a signal wire_

- [ESC & servo wiring](docs/esc-wiring.md) — wire the ESC and steering servo straight to the Nano

_Drive path B — sealed receiver+ESC combo (e.g. Hosim X15)_

- [Controller takeover](docs/controller-takeover.md) — automate a spare controller's pots instead of wiring the ESC
- [Identify the pots](docs/pot-identification.md) — multimeter bench procedure
- [Calibration worksheet](docs/controller-takeover-calibration.md) — fill-in checklist
- [Bring-up log](docs/controller-takeover-bringup.md) — this car's measured values and the findings that cost time

_Optional add-on, either path_

- [Sensor wiring](docs/sensor-wiring.md) — forward-perception array: center time-of-flight + corner ultrasonics

## VLM setup guides

- [On-device (offline) VLM](docs/on-device-vlm.md) — run Gemma 3n on the phone itself
- [Self-hosted VLM server](docs/self-hosted-vlm-server.md) — serve a faster model from your own PC (Ollama + Caddy/Tailscale)
