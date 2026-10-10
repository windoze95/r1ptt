# Messages

Messages is an optional personal SMS companion inside the existing robotOS APK. It uses the
Android SMS service and your SIM. It does not replace the GSI, change the default SMS role, enable
the stock Messaging app, or add a messaging backend.

## Use

Open the message icon on Home. **New text** opens a single-recipient draft. **Review text**, the
keyboard action, and a side-button tap all stop at recipient/message review. Only **Send SMS**
in that review dispatches the text. Long messages are split by Android; the review shows the part
count. The limit is ten SMS parts and 1,600 draft characters. Pictures, attachments, group MMS,
RCS, and importing old SMS are outside this version.

Messages → **Options** has separate opt-ins:

- **Enable SMS sending** requests Android `SEND_SMS` access. A valid default SMS SIM and the
  existing **Use cellular data (SIM)** setting are required. The SIM is checked again at send.
- **Enable incoming texts** requests `RECEIVE_SMS` and saves new text SMS after opt-in. It does
  not read the system inbox (`READ_SMS` is not requested), take the default messaging role, or
  process MMS. Turning this option off stops app reception; existing local conversations remain.
- **Enable dictation for this visit** discloses the configured transcription destinations.
  Audio leaves the device only after this opt-in and a hold in the compose screen. Release
  inserts words into the draft. The draft, recipient, and prior texts are not supplied as context.
  Changing endpoints invalidates consent. Messages dictation clips are excluded from `saveClips`.
- **Enable assistant SMS drafts** recognizes completed requests like “Text Yana that I’m on my
  way”, “Please text Yana saying I’m on my way”, and “Text +15551234567: I’m on my way”. This
  prepares a draft; it cannot send. Your spoken request goes to the configured voice or STT
  provider, as disclosed when enabling the action. Contextual requests such as “send that to her”
  are not supported.
- **Saved recipients** stores names and exact numbers you enter on this device. The assistant
  receives no contact list. Exact names match without case sensitivity; different numbers under
  the same name require a local choice. Missing names leave the phone field empty. No phone
  number is inferred from an AI response or invented.

In the transcription/chat path, matching commands are intercepted before the chat request. The
current GPT-Live speech-to-speech protocol has no client device-tool bridge, so a matching command
opens its draft only after the voice turn completes. Interrupted or failed turns do not execute a
draft action. Completed recognized commands are not copied into AI chat history. Provider speech
is not evidence of SMS dispatch: the local review and status are authoritative.

## Delivery, privacy, and sleep

Drafts, recipients, and conversations live in the private `messages.db`, separate from AI history.
No received texts are forwarded to AI, spoken aloud automatically, or included in notifications.
Notifications only say that Messages has a text. The recent view shows the newest 100 conversations
and 100 texts per conversation; older records remain stored. There is no export or backup UI yet.

An outgoing attempt and its per-part identities are committed before calling `SmsManager`.
Sent and delivery callbacks use explicit, non-exported receivers and unique per-attempt/part
PendingIntents. Incoming broadcasts require Android's `BROADCAST_SMS` sender permission. There
is no exported send action. Replayed incoming PDUs and duplicate status callbacks are idempotent.

**Sent** requires success for every part; **Delivered** requires a positive carrier report for every
part. A callback without a valid successful status PDU is not delivery proof. Missing send results
become **Send status unknown** after two minutes. Partial send and carrier-reported delivery failure
have separate states. There are no automatic retries, including after process death or reboot.
“Use as a new draft” warns that an uncertain previous text may have arrived and still requires review.
The in-app updater waits for an active SMS handoff to settle or reach its bounded uncertainty window.

SMS does not wait for internet connectivity. A confirmed send asks the existing radio policy to
wake radios it put to sleep; it does not modify that policy, APNs, roaming, or background limits.
There is no receiving foreground service, polling job, SMS wake lock, or continuous modem lease.
The existing idle cut can therefore delay or prevent reception. Carriers may retry queued SMS
after the modem reconnects, subject to their retention and retry policy; this device/carrier path
has not been validated. SMS entitlement is separate from having working mobile data.

Android marks SMS permissions as hard restricted. Depending on the installer/GSI, the user dialog
may not grant them. Messages keeps drafts and reports unavailable access instead of changing roles,
allowlists, root permissions, or app-ops. This personal sideloaded companion is not represented as
meeting Google Play's default-handler distribution requirements.

## Validation and live acceptance boundary

JVM and Robolectric tests use synthetic data and a fake transport: address validation, explicit
command parsing, local/ambiguous recipient resolution, draft ownership, durable SQLite transactions,
process restart, replay protection, part aggregation, uncertain failures, permission/SIM changes,
manifest protection, and compose/button behavior. The existing voice, configuration, updater,
release tooling, builds, and lint checks remain in the suite. These are not carrier acceptance tests.

Before a live-device SMS test, obtain explicit approval for installation, the precise SMS runtime
permission changes, the consenting test recipient and number, the message content, and any return
text. Do not enable a default role, stock app, RCS, or hidden permission workaround. Then verify:

1. Opening Messages asks for no permissions. Decline setup; drafts remain usable and no text sends.
2. Enable only the agreed access. Prepare a synthetic text to the agreed recipient; cancel review
   once and verify no send. Confirm once and check both local sent status and the recipient's report.
3. Repeat multipart/Unicode only if included in the test approval. Do not infer delivery from
   “Sent”; missing carrier receipts remain unconfirmed.
4. Test optional incoming reception while awake and after the normal radio idle cut. Verify the
   modem still sleeps; document actual retry delay and any missed/expired text instead of promising it.
5. Check assistant missing/duplicate-name drafts, explicit final confirmation, cloud-dictation
   consent, Home PTT/audio, app update fences, and draft recovery after app restart.

No live SMS send, incoming-message inspection, default-role change, or SMS permission grant is
performed by the build or automated tests.

## Platform references

- [Android Telephony: default-role and SMS_RECEIVED behavior](https://developer.android.com/reference/android/provider/Telephony)
- [SmsManager: send results and delivery reports](https://developer.android.com/reference/android/telephony/SmsManager)
- [Android SMS permission restrictions](https://developer.android.com/reference/android/Manifest.permission#SEND_SMS)
- [Android IMS single registration: RCS privilege/provisioning requirements](https://source.android.com/docs/core/connect/ims-single-registration)
