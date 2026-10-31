# Codex Web installation

Copied from `pbakaus/impeccable` commit `4f956490f0056ae1ff5ea80372cf56e83c001d3c`,
using its Codex-specific `.agents/skills/impeccable` distribution. The upstream
Apache-2.0 license and notice are included here.

The POSIX launcher has one local adaptation: when `IMPECCABLE_HOME` is not set
and the home directory is not writable, its engine cache uses this skill's
ignored `.runtime` directory. The pinned release, upstream checksum validation,
and the explicit `IMPECCABLE_HOME` override are unchanged. On first use the
engine still needs network access; if downloads are unavailable, set
`IMPECCABLE_BIN` to a compatible preinstalled engine. The Windows launcher is
unchanged.

This manual installation does not install hooks or register native subagents.
Availability depends on the Codex host loading repository skills; reload the
session if the skill catalog does not yet list `impeccable`.
