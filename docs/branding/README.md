# VNCode Liquid Glass logo

The blue Liquid Glass VN design with white lettering was selected by the user on 19 October 2026 for VNCode 1.5.1.

- `vncode-liquid-glass-master.png` retains the approved design as the high-resolution transparent production master.
- `src/main/resources/com/vncode/app/assets/images/logo.png` is the 512 px transparent application/sidebar/window icon, with padding for the glass edge.
- The resource `logo.ico` and root `app.ico` are identical Windows icon sets: 16, 24, 32, 48, 64, 96, 128 and 256 px. Both Windows packaging paths use the resource icon.
- Root `logo.icns` contains 16–1024 px transparent icons. The macOS build now explicitly selects it.

The application resource paths remain the same. Existing historical screenshots and release evidence retain their original logos.

Verification: `bash -n build.sh`, decoded PNG/ICO frames, ICNS header/chunk checks, matching root/resource ICO bytes and successful Maven package. The packaged 1.5.1 JAR contains the exact updated PNG/ICO resource bytes. See [verification results](2026-10-19-logo-verification.json).

This change has not been published to Latest; native Windows/macOS installers were not produced in this Linux session.
