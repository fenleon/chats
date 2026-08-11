# Chats — homeserver & bridge setup

Chats is a Matrix client. "Your account" is a Matrix account on any homeserver,
and every conversation is a Matrix room. WhatsApp (and later Signal, Telegram,
…) arrives through **bridges running on your homeserver** — the same model
Beeper uses, but open and self-hosted.

## 1. Signing in

Open the tool's Settings (Phase 3) and enter:

- **Homeserver** — a bare domain (`matrix.org`) or a full URL
  (`https://matrix.org`). A LAN/self-hosted server over plain HTTP needs the
  scheme (`http://10.0.2.2:8008`).
- **User** — full Matrix ID, e.g. `@alice:matrix.org`.
- **Password** — the account password. An **access token** also works (toggle
  "use access token"); tokens are found in other Matrix clients under
  "Session" / "Access tokens".

The companion resolves the server (including `.well-known` discovery), logs in
with Trixnity, and keeps syncing in the background (`ChatSyncService`). The
session survives restarts; logout deletes it.

## 2. No server yet? Options

### Existing account (easiest)
Register on any Matrix homeserver with open registration (e.g. matrix.org) or
ask an operator you trust. Nothing else to set up — sign in with the password.

### Self-hosted Synapse (dev / private)
A Synapse homeserver runs anywhere Python runs. For a test server on one
machine (like the one used to verify this app on the emulator):

```bash
python3 -m venv /tmp/synapse-venv && /tmp/synapse-venv/bin/pip install matrix-synapse
# (or: pip3 install --target /tmp/synapse-site matrix-synapse, then PYTHONPATH=...)
cd /tmp/synapse
/tmp/synapse-venv/bin/python -m synapse.app.homeserver \
  --server-name localhost --config-path homeserver.yaml --generate-config --report-stats=no
# then edit homeserver.yaml: enable_registration: true,
# enable_registration_captcha: false, enable_registration_without_verification: true
/tmp/synapse-venv/bin/python -m synapse.app.homeserver --config-path homeserver.yaml
```

Register test users:

```bash
curl -X POST http://127.0.0.1:8008/_matrix/client/v3/register \
  -H 'Content-Type: application/json' \
  -d '{"username":"alice","password":"alicepass","auth":{"type":"m.login.dummy"}}'
```

On the `lightos` emulator, the host's homeserver is reachable at
`http://10.0.2.2:8008` (the emulator's alias for host loopback). The companion
allows cleartext HTTP for exactly this case.

## 3. WhatsApp (and other bridges)

WhatsApp support is **not built into Chats** — it comes from a
[mautrix-whatsapp](https://github.com/mautrix/whatsapp) bridge on your own
homeserver, exactly as it does for Beeper. The bridge links your WhatsApp
account as a WhatsApp-Web device and exposes each chat as a Matrix room; the
Chats companion just reads and writes those rooms.

To set it up (on the machine hosting your homeserver):

1. Install the bridge. For Synapse the packaged route is
   [matrix-docker-ansible-deploy](https://github.com/spantaleev/matrix-docker-ansible-deploy)
   (playbook tag `setup-mautrix-whatsapp`) or the bridge's own
   [binary install guide](https://docs.mau.fi/bridges/python/setup.html).
2. Configure `homeserver` / `appservice` in the bridge config and restart.
3. In a Matrix client (Element, or Chats once it's wired), message the bridge
   bot (`@whatsappbot:your.server`) — it replies with a link to scan the
   WhatsApp QR code. Scanning pairs the bridge.
4. Existing chats appear as rooms; new WhatsApp messages arrive as Matrix
   events, and replies sent from Chats reach WhatsApp.

**Honest caveats:** mautrix-whatsapp uses the WhatsApp-Web (multidevice)
protocol, so it shares the same ToS/ban risk as Beeper's own WhatsApp support —
this is a property of the protocol, not of Chats. The bridge is user-hosted;
Chats ships docs, not server infrastructure. Signal/Telegram/etc. work the same
way via their respective mautrix bridges if you ever want them.

## 4. Development verification (emulator)

The companion exposes a dev status screen with a login form, connection state,
room list, and a thread view. It can also be scripted via launch extras:

```bash
adb shell am start -n com.lightphone.chats.server/.MainActivity \
  --es homeserver http://10.0.2.2:8008 \
  --es user @alice:localhost --es password alicepass \
  --es sendTo '!room:localhost' --es sendBody 'hi there'   # optional test send
```

Note: values with spaces need device-shell quoting, and `!` must be passed
unescaped (adb shell re-parses the command line).
