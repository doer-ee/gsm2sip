# Pixel 4a (`sunfish`) with FreePBX/PJSIP

This guide describes the tested Google Pixel 4a setup on stock Android 13 with
Magisk and a FreePBX/PJSIP server. Replace all example numbers, extension
names, and route IDs with your own. Do not commit SIP credentials or PBX
configuration copied from a live server.

## What has been verified

- The Pixel 4a reports `ro.product.device=sunfish`,
  `ro.board.platform=sm6150`, and `ro.hardware=sunfish`. The vendor image
  exposes `incall_music_uplink` → `Telephony Tx` and `Telephony Rx` →
  `voice_rx`, plus Incall Music and `VOC_REC_DL/UL` mixer paths.
- Live GSM-to-SIP and SIP-to-GSM calls have carried digital audio both ways.
  A live incoming call on the second SIM was addressed to that SIM's configured
  DID, and an outgoing call selected that SIM from the SIP Outbound CID.
- SIP registration has succeeded on both Wi-Fi and LTE. The implementation
  uses local UDP port `5062`, an IPv4 socket bound to the active Android
  `Network`, and STUN on that same network.
- A remote GSM hangup closes the SIP leg. SIP cancellation while the GSM leg
  rings stops the cellular call. For a GSM-originated call whose SIP INVITE
  has not been answered, the gateway now sends `CANCEL`, not `BYE`; that last
  correction compiled and re-registered but needs a repeat live hangup test.
- SIP keypad presses are forwarded to the active cellular call through Android
  Telecom. The gateway accepts RTP `telephone-event` and SIP INFO
  `application/dtmf-relay` signaling.

Rapid consecutive calls can still expose a Pixel/Qualcomm audio-HAL race.
The current profile checks whether the previous `TELEPHONY_TX` output is in
standby and not blocked before starting the next call; it releases the wait
after at most 18 seconds if readiness cannot be confirmed. This is not a
fixed delay. Concurrent bridged calls and every rapid-call sequence are not
claimed to be supported.

## Prepare the phone

1. Confirm ordinary cellular calls work before changing the phone. Run
   `tools/check-device.sh` with the phone connected by ADB; its audio-policy
   inspection does not require root. Mixer/HAL checks do require root.
2. Install Magisk and the project's module, then set gsm2sip as the default
   Phone app. The tested Pixel 4a stayed on **stock Android 13**; no custom
   ROM was required. Bootloader unlocking erases device data, so do not do it
   merely to run the read-only device check.
3. Give gsm2sip a SIP account and set the server, port, username, and
   password in the app. Under **Own Number**, configure each active SIM slot
   with its full international number, for example `+15551230001` for slot 0
   and `+15551230002` for slot 1. These values are both inbound DID routing
   keys and the identities used to choose an outbound SIM.
4. Start the foreground gateway service and confirm its status is **Online**.
   For 24/7 service, allow the app unrestricted battery use/Doze exemption.
   The service holds partial wake and high-performance Wi-Fi locks while
   active; these reduce sleep-related interruptions but do not guarantee
   uninterrupted service after a crash, reboot, or network outage.

Only one bridged voice call at a time should be assumed. Slot-based selection
has been exercised for individual calls, not simultaneous calls; physical-SIM
plus eSIM behavior across all carriers has not been exhaustively tested.

## Inbound calls: SIM to PBX

The gateway sends an INVITE with the receiving SIM's configured number in
the Request-URI/To and the cellular caller in From. Define a FreePBX inbound
route for **each** complete DID (including the leading `+` if sent), then
send it to the desired extension or ring group. An inbound call on slot 1
must not be routed through slot 0's legacy Own Number.

If every incoming call appears as the gateway's SIP extension instead of the
external caller, inspect the PJSIP endpoint's caller-ID settings. A fixed
endpoint `callerid` can overwrite the INVITE's From identity before the
FreePBX ring-group dialplan runs. Preserve/trust the authenticated gateway's
inbound identity rather than assigning it a fixed caller ID. Validate with a
test call and inspect both the INVITE From and the eventual extension display.

## Outbound calls: PBX to cellular

An outbound INVITE needs two independent values:

| Value | Purpose | Example |
| --- | --- | --- |
| `X-GSM-Forward` | Cellular destination | `+12025550147` |
| Outbound caller ID (`P-Asserted-Identity`, `Remote-Party-ID`, or `From`) | Selects exactly one configured Pixel SIM | `+15551230002` |

The gateway checks the asserted identity, then Remote-Party-ID, then From.
It normalizes exact digits and US `+1`/10-digit formatting for comparison.
If the CID is absent, matches no configured SIM, or matches more than one,
the gateway refuses to dial (`503 No Matching SIM`) instead of opening
Android's SIM picker or choosing the wrong account.
Outbound CID is a routing selector, not an authorization mechanism: restrict
who may call the PBX route and protect the gateway SIP account with normal
authentication and network controls.

One tested FreePBX approach uses a custom PJSIP trunk targeting the gateway's
registered endpoint (`AMP:PJSIP/<gateway-extension>`). In **Trunk Dial
Options**, the following Gosubs set the destination on the called channel
and preserve the number displayed on the SIP phone:

```text
IB(gsm2sip-set-connected^s^1)b(gsm2sip-set-forward^s^1(${OUTNUM}))
```

Place the Gosubs in FreePBX's `extensions_custom.conf` (or the appropriate
custom include for your installation):

```asterisk
[gsm2sip-set-forward]
exten => s,1,Set(GSMNUM=${ARG1})
 same => n,ExecIf($["${GSM_FORWARD}"!=""]?Set(GSMNUM=${GSM_FORWARD}))
 same => n,ExecIf($["${GSMNUM:0:1}"="+"]?Set(GSMNUM=${GSMNUM:1}))
 same => n,ExecIf($[${LEN(${GSMNUM})}=10]?Set(GSMNUM=1${GSMNUM}))
 same => n,Set(PJSIP_HEADER(add,X-GSM-Forward)=+${GSMNUM})
 same => n,Return()

[gsm2sip-set-connected]
exten => s,1,Set(GSM_DISPLAY=${DIAL_NUMBER})
 ; Example route-ID mapping only; replace with your actual FreePBX route IDs.
 same => n,ExecIf($["${ROUTEID}"="5"]?Set(GSM_DISPLAY=1*${DIAL_NUMBER}))
 same => n,ExecIf($["${ROUTEID}"="6"]?Set(GSM_DISPLAY=2*${DIAL_NUMBER}))
 same => n,Set(CONNECTEDLINE(num,i)=${GSM_DISPLAY})
 same => n,Set(CONNECTEDLINE(name,i)=${GSM_DISPLAY})
 same => n,Return()
```

The `b(...)` subroutine runs on the outbound PJSIP channel, where
`PJSIP_HEADER(add,...)` can add the header. The `I`/connected-line part
prevents the intermediate gateway extension from replacing the original
number on the calling SIP phone. The `ROUTEID` mapping is **site-specific**;
do not copy route IDs `5` and `6` blindly. An earlier attempt to use
`${CDR(dst)}` inside the Gosub displayed only `S`, because it evaluated to
`s` there. Reconstruct the display value from a known route selector or
another variable you have verified on your PBX.

Create one outbound route per SIM selector, for example `1*` and `2*`:

- Strip the selector prefix before sending the destination to the trunk.
- Set each route's **Outbound CID** to the corresponding SIM's configured Own
  Number, and enable **Override Extension** so the SIP phone's CID does not
  replace it.
- Include the gsm2sip trunk in each route's trunk sequence. A matching route
  with no trunk produces a busy announcement without reaching the phone.
- Check FreePBX's dial-pattern columns and generated dialplan before testing:
  the route selector is for PBX selection, not part of the GSM destination.

For example, dialing `2*2025550147` can select the second-SIM route, send
`+12025550147` as `X-GSM-Forward`, and present the second SIM's number as
Outbound CID. The connected-line display may retain `2*2025550147` while
hiding the intermediate gateway extension. Verify the actual display with a
live SIP phone; it depends on FreePBX's generated dialplan and phone behavior.

## Registration on Wi-Fi or LTE

The server's SIP port and the phone's **local** UDP port are different
settings. The gateway's local port is `5062`; the server port remains whatever
your PBX listens on. The Pixel 4a/Android 13 test observed replies to a
user-space socket on local `5060` being dropped on Wi-Fi. Moving only the
server address or selecting IPv4 DNS did not solve it. The working code uses
an Android-native IPv4 socket bound to the active network and keeps STUN on
that same network.

A normal digest-registration trace is an initial REGISTER, a `401`/`407`
challenge, an authenticated REGISTER, then `200 OK`. If it fails:

1. Check that Android has an active data network and that DNS resolves the
   PBX from that network.
2. Check the SIP username/password and server port without posting them in
   issue reports.
3. Confirm the PBX receives the REGISTER and sends its response; then check
   whether the app receives it. A missing fail2ban entry does not prove a
   network packet reached the app.
4. Compare Wi-Fi and LTE using the same server settings. Check router
   SIP-ALG, firewall/NAT, and IPv4/IPv6 reachability if only one works.

The device log can be inspected with `adb logcat -s SipClient GatewayService
CallOrchestrator RtpSession`. Avoid sharing unredacted logs containing phone
numbers or SIP credentials.

## Call and audio troubleshooting

- **Caller hears the Pixel microphone instead of the SIP party:** the digital
  uplink injection or microphone mute did not take effect. Confirm the
  `sunfish` profile, privileged module, root grants, and Incall Music mixer
  route. This symptom is not a PBX caller-ID problem.
- **Only one side hears audio after a quick redial:** inspect
  `dumpsys media.audio_flinger` for `AUDIO_DEVICE_OUT_TELEPHONY_TX`,
  `Blocked in write`, and `Standby`. The old Qualcomm output can remain busy
  after the Java RTP session has stopped. The Pixel profile polls for
  readiness and uses an 18-second maximum fallback.
- **Silence just after answer:** audio routing and the voice HAL may still be
  settling. The Pixel profile uses a 500 ms route-settle delay and a
  deep-buffer playback track; reducing delays may make one-way audio recur.
- **SIP hangs up late:** check whether the GSM disconnect reached the
  orchestrator and whether the SIP dialog was established. Established calls
  use BYE; unanswered outbound INVITEs use CANCEL.
- **PBX keeps ringing after GSM caller hangs up before answer:** capture the
  SIP transaction. The gateway must CANCEL the pending INVITE using the same
  branch and CSeq; a BYE in the early dialog is not enough.
- **Busy/no dial after selecting a SIM:** inspect the FreePBX trunk sequence,
  route CID override, and Pixel Own Number slots. An unmatched CID intentionally
  refuses the call.

## Battery charge protection

The Pixel 4a-specific option in app Settings is off by default. With root,
it reads battery capacity every 30 seconds and writes the Pixel 4a's
authoritative
`/sys/class/power_supply/sm7150_bms/charge_disable` node (with the older
`smb5/charge_disable` node as a fallback): at or above the upper threshold it
pauses charging; at or below the lower threshold it resumes; inside the band
it keeps the existing state. Defaults are 35%/65%; the UI
enforces a gap of at least five percentage points. Turning the feature off
releases a charge gate it owns.

This reduces time spent fully charged. It **does not bypass the battery** or
guarantee the handset runs only from USB. The observed high-end state was
charging paused; a complete lower-to-upper threshold cycle was not verified.
The controller runs in the gateway service, not in a Magisk `service.sh`
daemon, because the latter could not read the app's private preferences under
the tested SELinux policy.

## SMS and the limits of this guide

SIP MESSAGE carries inbound SMS, outbound requests, and delivery reports;
see the [README's SMS section](../README.md#sms-over-sip). Explicit
`X-SMS-Sim-Slot` or `X-SMS-Sim-Sub` is important when replying from a
dual-SIM gateway. A Telegram bridge used in one deployment lives outside
this repository and is **not** installed or configured by gsm2sip.
