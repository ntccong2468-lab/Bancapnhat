# VN code: Vietnamese news translation

Verified official sources:

- [TranslationService protobuf](https://github.com/yandex-cloud/cloudapi/blob/master/yandex/cloud/ai/translate/v2/translation_service.proto): POST `/translate/v2/translate`, source/target language, plain text, total 10,000 characters/request. SHA-256 of the retrieved source: `6dbeccc3a2e0b93a45dd3108e5940082fceedcde8a84caf659391570dbb2e089`.
- [Official endpoint registry](https://github.com/yandex-cloud/docs/blob/5b36f976bb25c0200ab937a30ea6b88328c39ae9/md-docs/api-design-guide/concepts/endpoints.md): Translate endpoint `translate.api.cloud.yandex.net:443`.
- [Official API key authentication](https://github.com/yandex-cloud/docs/blob/5b36f976bb25c0200ab937a30ea6b88328c39ae9/en/_includes/authentication-api-key.md): `Authorization: Api-Key <key>` for service accounts. The current desktop setting uses a service-account API key and omits folderId, as the Translate proto specifies for service-account authentication.

The implementation allows only the official HTTPS endpoint, disables redirects/retries/credential-bearing interceptors, and bounds response/time/chunk sizes. UTF-16 surrogate pairs remain intact, so each request is conservatively at or below the 10,000-character limit. News and keys are never combined into request text.

Keys are protected with Windows DPAPI CurrentUser and passed to PowerShell through stdin. There is no plaintext or non-Windows fallback. Each settings save creates a new configuration revision, so failure cache from an old key cannot suppress validation of a new one. Removing a key closes the previous translator and cancels its in-flight HTTP request before more chunks can be sent.

Translations are cached by original revision/content hash, language, provider/configuration version and source rights scope. Numeric literals, dates and GTINs must survive exactly; invalid translations retain the original. The UI labels automatic translations and offers the original and official source. No Yandex account, key or paid quota was used in development; synthetic transport and native DPAPI fixtures are separate evidence.
