# VN code branding

**Superseded:** the user clarified separate installation and empty data on 2026-10-08. See [independent application spec](2026-10-08-vn-code-independent-app.md). The reuse requirements below record the earlier assumption, not the current behavior.

Display the product as **VN code** in all four languages, window titles, installer metadata and current documentation. Technical package/artifact names are `com.vncode.app` and `VNcode`; version 1.1.34, Windows download `VN-code-1.1.34-Windows-x64.exe`.

Existing data, license, recovery markers and mutation identities must remain usable. Reuse an existing legacy data directory rather than copying a live database. Fresh installs use VNcodeData on Windows. Preserve the Windows upgrade UUID, shared database lock filename, signed-envelope protocol, original upstream URLs and attribution. Accept legacy JVM property aliases; prefer vncode properties when both are supplied. Test profiles never scan production data.

Verify Java/FXML, release contracts and a native Windows launcher before publishing a new prerelease. Existing releases remain immutable. GTIN production write gates remain unchanged.

The selected default data directory is fixed for the process; a legacy directory appearing after startup must never redirect database/license access away from the held ownership lock.
