# Catalog country dictionary hotfix — VNCode 1.5.6

The 1.5.5 registration preflight rejected a valid dictionary response before allocating GTIN or submitting a card. `countryDictionary` used the generic array parser, but the actual country endpoint returns `result` as an object containing `_etag` and `_list`. Empty parsed rows caused `CATALOG_COUNTRY_DICTIONARY_UNAVAILABLE`.

A focused read-only production request returned HTTP 200 and 254 countries, including `country_iso=RU`, `country_name=Россия`. The current attribute 2630 points to this dictionary and has no inline presets. No GTIN allocation, card write or signature was performed by that read.

The fix unwraps `_list` only for country dictionaries, retaining flat-array compatibility and all empty/invalid/ambiguous-country checks. Generic category and attribute parsing is unchanged. WB/Ozon preflight and submission fixtures now use the actual response envelope; the Ozon HTTP registration regression verifies the outgoing country value `RU`.

This is a focused hotfix. The owner requested immediate publication without another full regression or legacy-upgrade run. Existing 1.5.5 acceptance remains historical; it is not reused as fresh 1.5.6 full-suite/native/production acceptance.

Linux focused regression: **129 tests passed**, no failures/errors/skips (36.534 seconds). This is a focused test result, not a new full-suite acceptance.
