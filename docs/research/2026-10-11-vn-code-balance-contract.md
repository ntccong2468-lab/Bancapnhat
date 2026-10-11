# VN code: read-only ELK balance contract

Verified from the official public [Честный ЗНАК portal](https://markirovka.crpt.ru/) on 11 October 2026, without private-account access. [Public script](https://markirovka.crpt.ru/assets/index-f1en0Ki5.js), SHA-256 `c4dfb18519b64d4c5b0da1f660e10212754ef6b389471fb04f2c67b49be5fdd6`.

| Operation | Public frontend evidence |
|---|---|
| GET `/bff-elk/api/v1/auth/key` | `QTe.getAuthKey`, response `uuid,data` |
| Sign challenge | `dS` base64-encodes challenge text; `Gne` defaults detached=false; CryptoPro CAdES-BES includes the challenge |
| POST `/bff-elk/v1/united-auth/login` | `kep:{data:CMS,mrdToken:false,uuid}`; delegated login signs a new challenge with `mrdToken:true,inn` chosen from `organisations` |
| ELK session | `access_token`, bearer authorization. The legacy `id_token` and True API token are different sessions |
| GET `/bff-elk/v1/profile/organisation` | `V1e`, response `.data.inn`; `I2` selects the verified enterprise INN |
| GET `/bff-elk/v1/organisation/balance` | `xTe.fetchAccounts`, `Ew` consumes `.data` as account array |
| Account scope | `uZe`: organisationId, productGroupId, contractId, balance; choose exact group and contract, never add different wallets |
| Units / currency | `c0` divides integer balance by 100; official account and invoice UI displays Russian roubles (RUB). VN code uses exact decimal arithmetic and rejects missing/fractional minor units instead of applying the frontend's `||0` fallback |

Production origin is `https://markirovka.crpt.ru`. Redirects, foreign origins and True API sessions are rejected before transmitting tokens. Production certificate authentication is initiated explicitly from GS1, using the validated Znack certificate and certificate signing queue. Tokens remain in memory; snapshots persist only verified INN/environment/group/account, amount, currency, source and time. Sandbox ELK has not been verified and remains unavailable.

The public web contract can change independently of the True API. HTTP/parse/rights errors retain the last scoped snapshot and are not a zero balance. Native CryptoPro signing and a real seller balance require the user's Windows certificate/account; offline tests exercise the public contract with synthetic HTTP fixtures, not live account acceptance.
