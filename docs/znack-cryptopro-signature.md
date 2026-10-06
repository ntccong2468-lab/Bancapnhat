# CryptoPro signatures for Znack

WCode signs Znack requests directly with the locally installed CryptoPro signing components. Signature configuration, the selected certificate, normalized certificate metadata, and successful test time are stored independently for each shop. WCode never stores or supplies a PIN.

## Required tools

- `cryptcp` signs authentication challenges, SUZ order bodies, True API documents, and local test payloads when it is available.
- On Windows, when `cryptcp` is absent, WCode automatically signs through the free CryptoPro CAdES Browser Plug-in's `CAdESCOM` component, which is the same signing family used by browser portals such as True Mark.
- `certmgr` discovers certificates from the CryptoPro user certificate store.
- A legacy `csptest` path may remain in shop persistence, but WCode does not use a container list as proof that a specific certificate has an accessible private key.

WCode searches `PATH` and common CryptoPro installation directories on Windows, Linux, and macOS. On Windows it supports `cryptcp.exe`, `cryptcp.x64.exe`, and `cryptcp.x86.exe`, including CryptoPro installed under the system `ProgramFiles` directories. WCode does not redistribute CryptoPro binaries; if `cryptcp` is unavailable on Windows, it uses the installed CAdESCOM component instead. CryptoPro CSP and access to the certificate's private key remain required in both cases. Znack Settings does not expose executable paths, arbitrary signer arguments, API host overrides, or other technical fields.

Signing uses the documented CryptoPro form:

`cryptcp -sign -uMy -thumbprint <thumbprint> -der -attached|-detached <input> <output>`

`-uMy` restricts certificate lookup to the current user's personal store, `-thumbprint` selects exactly one certificate, and `-der` produces binary CMS/CAdES for validation and Base64 transport. WCode deliberately does not pass `-pin` or `-askpin`; CryptoPro and the token keep control of native confirmation and PIN handling.

CryptoPro's official command-line reference is the source of truth for these options:

- https://cryptopro.ru/sites/default/files/docs/csp/50r2/%D0%96%D0%A2%D0%AF%D0%98.00101-02%2093%2001.%20%D0%9F%D1%80%D0%B8%D0%BB%D0%BE%D0%B6%D0%B5%D0%BD%D0%B8%D0%B5%20%D0%BA%D0%BE%D0%BC%D0%B0%D0%BD%D0%B4%D0%BD%D0%BE%D0%B9%20%D1%81%D1%82%D1%80%D0%BE%D0%BA%D0%B8%20cryptcp.pdf
- https://cryptopro.ru/sites/default/files/docs/csp/50r2/%D0%96%D0%A2%D0%AF%D0%98.00101-02%2093%2002.%20%D0%9F%D1%80%D0%B8%D0%BB%D0%BE%D0%B6%D0%B5%D0%BD%D0%B8%D0%B5%20%D0%BA%D0%BE%D0%BC%D0%B0%D0%BD%D0%B4%D0%BD%D0%BE%D0%B9%20%D1%81%D1%82%D1%80%D0%BE%D0%BA%D0%B8%20%D0%B4%D0%BB%D1%8F%20%D1%80%D0%B0%D0%B1%D0%BE%D1%82%D1%8B%20%D1%81%20%D1%81%D0%B5%D1%80%D1%82%D0%B8%D1%84%D0%B8%D0%BA%D0%B0%D1%82%D0%B0%D0%BC%D0%B8.pdf
- https://cryptopro.ru/products/other/cryptcp

## Simplified settings and test

1. Open the Znack Automation module for the intended shop.
2. Enter `omsId` and `omsConnection`.
3. Open the certificate dropdown to reload certificates, then select a non-expired certificate from CryptoPro's current-user `My` store.
4. Use **Test signature**. CryptoPro or the USB token may show its native confirmation/PIN prompt.
5. Optionally enter the default goods-document number, issue date, and expiry date in `dd.MM.yyyy` format and enable automatic introduction.
6. Save the settings.

`certmgr` output is not assumed to prove private-key availability. Only a successful real signing test through `cryptcp` or CAdESCOM marks that shop `VERIFIED` and proves that the selected certificate's private key is currently usable. Changing the certificate clears verification. Switching shops reloads the selected certificate and verification state of the new shop.

Goods-document defaults are shop-scoped and optional for buying and downloading KIZ. If automatic introduction is enabled but required document data is missing, WCode keeps the downloaded codes available, skips introduction, and records `INTRODUCTION_SKIPPED_MISSING_DOCUMENTS`. There is no Advanced Settings section or KIZ PDF generation in the Znack UI.

When no usable certificate is found, WCode displays:

`Không tìm thấy chữ ký điện tử. Vui lòng cắm USB token, kiểm tra CryptoPro rồi thử lại.`

## Request mapping

- Authentication signs the documented challenge as attached CMS/CAdES Base64.
- SUZ order creation signs the exact raw JSON body as detached CMS/CAdES and sends it in `X-Signature`. The unchanged JSON is the HTTP body and the dynamic token is sent in `clientToken`.
- SUZ order status and code downloads use `clientToken` without an extra signature. Code download sends the documented `omsId`, `orderId`, `quantity`, and `gtin` query parameters.
- True API introduction signs the exact unencoded document JSON bytes as detached CMS/CAdES. The document and signature are then Base64-encoded into the API request.

## Errors and security

WCode distinguishes missing CryptoPro, absent token/certificate, unavailable private key, expired certificate, cancellation, timeout, signing failure, discovery failure, and invalid signature output. Signer output must be non-empty CMS/CAdES data; invalid or arbitrary command output is rejected.

Payloads, tokens, PINs, private-key material, and raw signatures are not written to operation logs. Diagnostics are sanitized and truncated. Legacy generic signer commands are not executed; only recognizable `cryptcp` and `certmgr` paths are migrated, and ambiguous legacy configurations lose their verified state.

The request mapping is verified against the local documentation fixtures in `../znack_api/ZnackAPIDocument_md/OMS_API_3.0.md` and `../znack_api/ZnackAPIDocument_md/Guides-v16.0-05.06.2026-at-13-02-49.md`. The desktop flow corresponds to browser signing as follows: browser certificate selection maps to the shop's selected CryptoPro certificate, browser signing confirmation maps to the native CryptoPro/token prompt, and browser-side Base64 CMS output maps to WCode's validated CryptoPro output.
