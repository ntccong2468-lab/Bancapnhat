# License connectivity on Wi-Fi, hotspots and VN/RU networks

Owner reports `LICENSE_TIMEOUT`: Wi-Fi works but mobile tethering or switching Vietnamese/Russian networks fails. This is a transport timeout, not evidence of an invalid key. No live packet trace or Cloudflare event from the affected carrier is available.

Device identity is an Ed25519 key pair saved locally under the existing protected license store. Neither desktop nor Portal binds rights to an IP, ASN, SIM, SSID, country or local time zone. The Portal uses IP only for registration/code-abuse rate limits. Moving networks on the same computer must not allocate a second device, redeem a second subscription period or require a new key. New computers remain subject to the administrator's device limit.

Transport change: permit OkHttp route recovery, including trying another system DNS address when a connection fails. Give a single call a 45-second total budget, a 30-second response wait and an 8-second per-route connect timeout. POST bodies are explicitly one-shot: a proof/activation already sent is never automatically replayed after a lost response or an HTTP retry instruction. A user can retry activation with the same key and device; the server's existing conditional redemption retains the original subscription/device. TLS validation and the fixed HTTPS origin remain enforced; credentials never follow redirects.

Cloudflare's explicit `cf-mitigated: challenge` response now produces `LICENSE_EDGE_CHALLENGE`, rather than persisting a malformed-license denial. It cannot activate an unlicensed machine. An already verified lease can retain only its existing bounded offline rights. Real Portal revocation/expiry still denies access. This change is defensive and is not a claim that the owner's reported timeout was a browser challenge.

The Portal network-switch tests use synthetic IPv4/IPv6, VN/RU country/ASN metadata for Wi-Fi/hotspot scenarios. They exercise real Worker routing, D1-compatible SQL, Ed25519 proofs/signatures, enrollment, activation, renewal, repeat activation, device capacity and revocation. They prove the application policy is network-independent, not the reachability of real Vietnamese/Russian carriers or Cloudflare's account-level WAF.

## When the affected network still times out

On the same Windows computer and failing hotspot, run these read-only probes:

```powershell
Test-NetConnection vncode.id.vn -Port 443
curl.exe -4 -i --connect-timeout 10 --max-time 45 https://vncode.id.vn/api/config
curl.exe -6 -i --connect-timeout 10 --max-time 45 https://vncode.id.vn/api/config
```

`/api/config` contains public configuration, not credentials. IPv6 failure alone is normal on an IPv4-only network; compare it with the IPv4 result. Do not change or disclose license keys. A successful browser visit is not proof that Java or all API routes are reachable. The updater and licensing have independent HTTP clients.

If IPv4 and IPv6/TCP both time out, app-side retries cannot overcome a carrier outage, DNS failure or firewall block. Inspect the failing network's DNS/proxy/firewall and Cloudflare Security Events at the actual attempt time. No Cloudflare account credential has been used to inspect or edit the production zone's custom country/IP/bot rules.

For an identified country/IP/browser-challenge rule, remove the inappropriate license API match from that specific rule, covering the exact host `vncode.id.vn` and `/api/client/registration/`, `/api/client/license/` (and public `/api/config` for diagnostics). Desktop APIs cannot complete an interactive browser challenge. Keep administrator authentication, signed device proof, application rate limits and other protections. Do not disable the whole zone's WAF or allow all requests based solely on a spoofable User-Agent. No Portal redeployment or key rotation is required for the desktop transport change; zone-level rule changes, if needed, are separate.

Actual VN/RU Wi-Fi and mobile carrier acceptance must be recorded after the owner tests the new installer on those networks. Until then it remains unverified.

## Owner connectivity results before the new installer

On the reported network, forced IPv4 `GET /api/config` returned HTTP 200 with Portal 0.3.5 at 2026-10-15 05:25:30 UTC. The owner also reports a successful IPv6 response. `GET /api/client/registration/status` with the app's `okhttp/4.12.0` User-Agent and no credentials returned the expected HTTP 401 `AUTH_REQUIRED` at 05:33:01 UTC. These results establish reachability for those curl requests; they do not establish successful Java connectivity, an authenticated activation POST or acceptance on every Vietnamese/Russian carrier.

The actual Windows-built 1.4.9 Java transport was also exercised read-only from the cloud using the session's HTTPS proxy and trusted CA. A synthetic registration credential correctly returned 401 `REGISTRATION_EXPIRED`; no account, license or device was created. TLS verification stayed enabled. See the release download record for the final Windows/release bindings.
