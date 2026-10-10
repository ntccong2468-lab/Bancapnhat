# VN code — independent application

The user clarified that VN code must be a different application installed beside ứng dụng tham chiếu, and explicitly chose an empty database and separate shop setup. This supersedes the legacy-data reuse and installer-identity requirements in the 2026-10-07 branding spec.

- Display name VN code, version 1.1.34; Java namespace com.vncode.app; JAR VNcode; release EXE VN-code-1.1.34-Windows-x64.exe.
- Production Windows upgrade UUID 8CBBA0E2-6E73-4F56-9101-6BC0948D3C72; test-profile UUID 5570F943-39B6-43B8-A643-BBCB53910BF8. Neither reuses a ứng dụng tham chiếu identity.
- Program files VNcodeApp; default data VNcodeData (Windows) or VNcode (other OS). Test profile data/cache are VNcodeZnackRegistrationTestData. Cache/backups use the VNcode system directory.
- Never discover, import or modify ứng dụng tham chiếu directories, databases, licenses, backups or JVM configuration properties. Only vncode.appdata.dir and vncode.data.profile configure the new app.
- Both update channels belong to ntccong2468-lab/Vncode. Keep the signed-update envelope wire format, upstream provenance and marketplace behavior; production GTIN write gates remain unchanged.
- Test real Windows installation beside the pinned ứng dụng kiểm thử tham chiếu EXE/MSI, independent registrations and empty VN code data. Uninstall only VN code and verify ứng dụng tham chiếu executable/data/registration hashes are intact. This probe must refuse non-hosted or pre-existing application directories.
- Native window readiness must inspect visible windows owned by the launcher/JVM child, not assume Process.MainWindowTitle identifies the JavaFX stage.

The user subsequently selected a free personal VN code application without a ứng dụng tham chiếu license. Remove the original vendor subscription client, signed-license cache/fingerprint and activation UI from this fork, rather than claiming a vendor license is valid. The sidebar shows the free personal edition in all four languages. KIZ/GTIN UI no longer requires ứng dụng tham chiếu subscription; real GS1/CryptoPro and marketplace credentials and verified write contracts remain required. Error diagnostics are copied locally for the owner to review/share instead of being posted to the original license server. Original ứng dụng tham chiếu binaries and licenses are never modified.
