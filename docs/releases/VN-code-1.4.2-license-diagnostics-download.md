# VNCode 1.4.2 license diagnostic candidate

[Download Windows artifact](https://github.com/ntccong2468-lab/Bancapnhat/actions/runs/38552502757/artifacts/11887267816)
from successful [build 38552502757](https://github.com/ntccong2468-lab/Bancapnhat/actions/runs/38552502757),
source `6e71fe8e5170dad2bd318aa0cdd9a67960fd46ee`.

Sign into GitHub, download the artifact ZIP and extract it. Close VNCode, open
the `out` folder and run `VN-code-1.4.2-License-Diagnostics-Windows-x64.exe`.
Open License, retry activation and send the displayed safe error code. Do not
send the issued key, registration/device tokens or private signing key.

The artifact includes 63 Windows license transport/service/verifier/storage/key/
clock/cadence/UI tests: zero failures, errors or skips. Native Windows launcher,
isolated schema-3 to schema-5 migration with preserved history and rollback
snapshot, and the Windows secret-protection probe passed. Downloaded EXE bytes
were independently checked against the artifact checksum:

`5862fa13a8fd1b21732c398c797c7fe7ec16a13ad548d92d44b25ce4849d705e`

This is an unsigned diagnostic candidate, not an official release or a claim of
successful production activation. The full application/workspace/installer
upgrade matrix continues separately in [build 38551260158](https://github.com/ntccong2468-lab/Bancapnhat/actions/runs/38551260158).
The server key-pair match and actual customer activation remain unverified.
No real key was activated by these tests.

Portal 0.3.3 sources (admin desktop licensing, canceled-code deletion, Gmail
deletion/re-registration and stale-filter protection) passed 50 tests and a
Wrangler dry run in [build 38551887523](https://github.com/ntccong2468-lab/Bancapnhat/actions/runs/38551887523).
[Portal artifact](https://github.com/ntccong2468-lab/Bancapnhat/actions/runs/38551887523/artifacts/11887685101)
was downloaded and its ZIP checksum verified. It has not been deployed to the
production Worker by this session. Windows installation alone does not enable
admin desktop licensing on the currently deployed Portal 0.3.2.
