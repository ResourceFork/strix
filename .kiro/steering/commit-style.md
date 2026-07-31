# Commit message style

This repo does NOT use Conventional Commits. Do not write `feat:`, `fix:`,
`chore(scope):`, `refactor:` etc. — the owner finds that format ugly and will
rewrite it.

## Subject line

```
[Area] Summary in plain English
```

- `[Area]` is a short capitalized tag for the part of the project touched.
  Tags in use: `[UI]`, `[VLM]`, `[Housekeeping]`. Coin a similar one when
  needed (e.g. `[Firmware]`, `[Sensors]`, `[Docs]`).
- Summary is a plain, readable sentence fragment. Capitalized, no trailing
  period, ~70 chars max.

## Body

- Optional; plain bullets or short prose explaining what and why.
- A closing "Verified: …" line describing how the change was checked is
  welcome.

## Examples from history

- `[Housekeeping] Align tasks-core to 0.10.35, document litert pin`
- `[VLM] Disable chain-of-thought on non-OpenAI servers`
- `[UI] Rebuild main screen around a pinned camera hero`
