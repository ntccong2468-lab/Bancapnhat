# License activation failure diagnostics

The owner reported that VNCode 1.4.1 shows the generic Vietnamese request-failure
message and the issued code remains unused in the admin portal. This does not
establish a successful activation or identify the failed step.

## Confirmed defect and correction

The transport emits `LICENSE_NETWORK`, but LicensePane previously looked up
`license.account.error.license_network`; the available translation is
`license.account.error.network`. The lookup therefore hid network failures behind
the generic message. Map network, HTTP, rejected redirects, local processing,
busy requests and cached recheck errors to actionable translations. Preserve
`LICENSE_CHANGED` from the server instead of reducing it to an unknown HTTP error.

Recognized translated errors now include their safe protocol code for support.
Unknown codes and arbitrary upstream text remain generic. No license keys, email
addresses, tokens or response bodies are included in the diagnostic suffix.
Signature verification, device limits, manual approval and activation permissions
are unchanged.

## Verification

- `LicensePaneTest`, `WebLicenseApiTest`, `LicenseServiceTest` and
  `LicenseVerifierTest`: 47 executed, zero failures/errors/skips on Linux with
  Java 25 and DISPLAY=:99.
- All four UI languages exercise actionable transport errors and safe code
  display; unknown and credential-containing messages remain hidden.
- Live read-only `/api/health` returned HTTP 200. `/api/config` with the existing
  application's `okhttp/4.12.0` user agent returned Portal 0.3.2 / gmail_admin.
- A POST to `/api/client/registration/license` with an empty code returned
  `INVALID_LICENSE`; it did not register an account, claim a code or allocate a
  device. No real license was submitted.
- A Python urllib request received Cloudflare 1010 (browser signature banned).
  The application user agent was accepted in the separate check above. This is
  evidence that filtering varies by request; it does not establish Cloudflare
  blocking on the customer's Windows computer.

Actual Windows connectivity, the server's response to the customer's activation
and the production key-pair match remain unverified. The existing installed EXE
does not contain this diagnostic correction. A passing local test does not prove
production activation works or authorize bypassing licensing checks.

## Follow-up 1.4.2 diagnostic candidate

The owner supplied a Windows PowerShell screenshot: curl with the application's
existing user agent returned HTTP 200 and Portal 0.3.2 on `/api/config`. The owner
also reports approved Gmail in the desktop. This establishes connectivity for
curl and the displayed registration state, not a successful activation or a
working HTTPS connection from the packaged Java runtime.

1.4.2 distinguishes `LICENSE_TLS`, `LICENSE_DNS` and `LICENSE_TIMEOUT` from other
network failures without logging exception messages, response bodies or causes.
These are transport failures: only an existing valid signed offline lease may
continue to supply rights. They cannot grant rights on a fresh activation.
Certificate and hostname verification remain enabled. There is no TLS bypass,
automatic resubmission of a real license or change to the production public key.

The installer version is incremented so Windows recognizes the corrected app as
an update. This is a diagnostic test candidate, not a confirmed activation fix or
an official release. Production acceptance remains blocked.
