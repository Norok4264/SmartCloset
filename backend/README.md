# AI try-on backend

See the root README and `docs/AI_PROVIDER.md`. The server is intentionally disabled until configured. Development runs on loopback only; never expose the development HTTP server to the public internet. Production must terminate HTTPS at a trusted ingress, apply per-user/session controls, and store provider credentials in the host secret manager.

From this directory, run the cost-free API tests with `py -m pytest tests`. For local development, copy `.env.example` to `.env`; leave `TRYON_PROVIDER=disabled` to keep generation unavailable, or set `TRYON_PROVIDER=mock` to return a clearly labeled test card. The mock never contacts an image provider. Real Google requests require production server ADC/workload identity and incur provider charges.
