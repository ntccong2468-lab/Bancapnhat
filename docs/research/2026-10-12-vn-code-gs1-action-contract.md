# GS1 actions for VNCode 1.4.0

The public National Catalog frontend was read again during this change:
`https://xn--j1ab.xn----7sbabas4ajkhfocclk9d3cvfsa.xn--p1ai/assets/index-BKa3ZwwN.js`.
Its SHA-256 is `418008d632e150b62eac9e8e24eebb951c00fb54d2cc947665ddbb386e8c8a60`,
the same asset recorded in the existing GS1 research.

The frontend obtains `/gs1/gcp-gln` and `/gs1/form`. A nonempty application
`errorText` resets `isSent` to false in frontend state. Form saves merge the loaded
application and send it to `/gs1/form`; signing uses GET and POST `/gs1/sign-form`.
No application bootstrap endpoint or `feePaid` field was found. The empty-form
initialization fallback is a VNCode decision bounded by the authenticated adapter
assertions; it still needs validation with an enterprise that has never opened its
application.

`Gs1ActionPolicy` uses explicit sourced membership and application status:

- An excluded enterprise can request membership restoration independently of its
  old joining application. Uncertain restoration delivery remains blocked.
- A non-member with a submitted application sees the submitted state and can
  inquire about a joining invoice. This does not assert an unpaid fee or approval.
- Sending additional prefix requests requires active membership and a complete
  zero allowance. The dialog can open read-only for fresh verified membership
  with positive or unknown allowance, or missing validity dates, showing the
  reason sending is blocked. Renewal appears only when membership is due.
- Missing, stale or malformed assertions require a fresh status check. Local
  `SENDING`, `SUBMITTED` and `RECONCILE_REQUIRED` joining checkpoints prevent a new
  submission, including for an empty remote form.

The application service reads current status before saving a reviewed draft. An
already submitted remote application cannot be changed through a stale editor.
Rejected `isSent=true` applications use the official `errorText` precedence when
preparing and submitting a reviewed correction.

Joining-invoice and restoration letters use the existing user-owned TLS mailbox,
reviewed SMTP send and durable reconciliation. A successful request blocks repeat
sends until a fresh official production assertion observes active membership.
That observation records completion of only `SENT` membership requests in an
additive resolution table. VNCode 1.4.0 overlays `RESOLVED` while reading; the
legacy journal keeps its `SENT` token for older readers. Uncertain delivery,
other enterprises and unsupported sources stay unchanged. A mailbox
reply alone never constitutes membership approval or payment verification.

The manual reminder implementation prepares one correlated prefix-request
reminder after explicit opt-in and seven days from confirmed outgoing mail.
Automatic reminders require separate persistent consent after displaying the
exact recipient and template. The consent binds the original request and its
attachments, the reminder content and the customer's SMTP/IMAP account identity.
Encrypted-password rotation does not change that identity; other account changes
stop the job until consent is reviewed again.

The bounded automatic scan rotates persistently so that answered or blocked
requests cannot starve later due requests. Before preparing a follow-up it reads
IMAP and rechecks consent, account binding, current rights and original confirmed
delivery. Any inbound reply or mailbox-read failure stops sending. One atomic
checkpoint consumes the automatic attempt and creates the correlated draft.
Consent and reply checks participate in the durable send claim, then a guard
checks them again immediately before SMTP `sendMessage`, after connecting.
Revocation or a known pre-send failure leaves a draft for manual review; an
uncertain delivery requires reconciliation. Neither outcome is retried by the
automatic scheduler. Adding the consent and fair-scan tables leaves the earlier
GS1 schema and journals intact; compatibility tests verify the old database,
snapshot, restored copy and SQLite integrity.

The user's screenshots supplied on 13 October show the Wbcode membership card,
six checks, GCP/GLN table and independent inbox; they are UI evidence, not live
enterprise state. The user clarified that profile fields must come automatically
from National Catalog. No screenshot API key, personal sample contacts, INN or
DOCX is bundled or used for authentication. Catalog's missing/unlimited date is
not proof of GS1 fee coverage; no speculative GS1 registry endpoint is added.

The supplied DOCX has twelve plain paragraphs and an underscore signature line.
The importer binds company/INN/all GCPs/date/signer in that structure, or explicit
`${companyName}`, `${inn}`, `${gs1Codes}`, `${date}`, `${signerName}` placeholders.
It preserves supported literal text/runs/alignment/fonts, generates filled DOCX,
unsigned PDF and transient-ink PDF, and rejects unsupported complex layouts.
PDF uses explicit Word page geometry, otherwise A4 with 20 mm margins. The file
is seller-supplied, not independently authenticated as an official downloaded
GS1 template. Imports are scoped to enterprise/environment in private appdata.

The request capacities are exactly 999 or 99,999 and the reviewed recipient is
`mail@gs1ru.org`, as shown in the screenshot. `gs1_prefix_request_reviews` is an
additive checkpoint binding exact mail/PDF digests, capacity and every active
GCP. The selected enterprise/certificate/environment is checked around Catalog
reads; complete fresh zero quota is checked before claim and immediately before
SMTP. Historical drafts without this checkpoint cannot bypass the guard. A
confirmed outgoing copy proves delivery, not allocation of a new GS1 prefix.
