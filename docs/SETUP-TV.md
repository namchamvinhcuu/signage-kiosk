# Setting up one TV

What it takes to turn a TV into a kiosk that starts by itself. Installing the APK alone does NOT make the TV start the app
after power-on: the steps below are done once per TV, over adb, by the operator. Every item is marked **measured**
(seen on a real TV, with the model and Android version) or **not measured**.

## 1. Install and configure (every TV)
1. Enable Developer options and network debugging on the TV, then `adb connect <tv-ip>:5555` and accept the dialog on the TV.
2. Install the release-signed APK: `adb -s <tv-ip>:5555 install signage-kiosk.apk`. A debug-signed and a release-signed
   build cannot replace each other: uninstall first (this wipes the pairing).
3. Start it once with the base URL (accepted only while the TV is unconfigured):
   `adb -s <tv-ip>:5555 shell am start -n com.namchamvinhcuu.signagekiosk/.MainActivity --es base_url https://<odoo-host>`
4. The TV shows a 6-digit code: enter it in Odoo (Pair TV). The code expires quickly; a fresh one appears after
   `am force-stop` + `am start -S` while the TV is unpaired. The pairing survives a reboot (measured, TCL Android 11).
5. Allow reopening from the background: `adb -s <tv-ip>:5555 shell appops set com.namchamvinhcuu.signagekiosk SYSTEM_ALERT_WINDOW allow`.

## 2. Start after power-on
| TV | What is needed | Status |
|---|---|---|
| Samsung WA65D, Android 13 | Step 1 only (overlay allowed): the boot receiver opens the app about 1 min after boot; the overlay appop stays `allow` across reboots | measured |
| TCL Smart TV, Android 11 | The TV's Safety Guard must allow auto-start (see below) AND the accessibility service below | measured |
| Any other TV | Run step 1, reboot, and check the result (section 4); add the pieces below only if the app does not come up | not measured |

**TCL only:** open Safety Guard (`com.tcl.guard`) > Permission Shield > Auto Launch Permission and set Signage Kiosk to
"Opened"; without it the TV drops the boot broadcast. The TCL also resets the overlay appop to `default` at every boot, which blocks
the receiver from opening an activity, so a start-up service is needed:

```
# read the current list first: the setting REPLACES the whole list, append with ':'
adb -s <tv-ip>:5555 shell settings get secure enabled_accessibility_services
adb -s <tv-ip>:5555 shell settings put secure enabled_accessibility_services "<existing>:com.namchamvinhcuu.signagekiosk/.KioskStartService"
adb -s <tv-ip>:5555 shell settings put secure accessibility_enabled 1
```
The service reads no screen content and only opens the app within 5 minutes of device boot. It is not needed on the
Samsung (a control boot without it started the app, measured). `am force-stop` of the app (adb, the TV's "Force stop", a
cleaner app) REMOVES the service from the list: run the three commands again. A TV in that state looks healthy until its next boot.

## 3. Updates over GitHub (optional, default off)
Channel `off` never checks. `stable` and `canary` follow the GitHub Releases of this repository (see `docs/RELEASE.md`). Extra
requirements per TV:
- Android 12 or newer for a silent install. Android 11 (TCL, measured): the system asks for a confirmation nobody can give, the
  update is abandoned and nothing changes. Keep the channel `off` there and install by adb.
- `appops set <pkg> REQUEST_INSTALL_PACKAGES allow` and the overlay appop from step 1.
- Google Play Protect: with its defaults (Samsung Android 13, measured) the update stalls behind a full-screen "Harmful app
  blocked" dialog. With `package_verifier_enable 0`, `verifier_verify_adb_installs 0`, `package_verifier_user_consent -1`
  the update installed silently in 3 seconds and the app reopened. This is a per-TV security setting change: decide per customer,
  and write down the old values to restore them.
- Set the channel when the TV is unconfigured (`--es update_channel canary` next to `base_url`) or later from the settings dialog.

## 4. Verify by rebooting
`adb -s <tv-ip>:5555 reboot`, wait for adb, then check that the app is in front about 1-2 minutes after boot:
`adb -s <tv-ip>:5555 shell dumpsys activity activities | grep -m1 topResumedActivity`. The log is
`adb -s <tv-ip>:5555 logcat -b all -d -s SignageKiosk:V`; look for `app_start {start=boot`. Some TVs flood logcat (TCL: about 30
lines per second) and the app lines roll out within minutes, so start a live capture to a file BEFORE the event:
`adb logcat -b all -v time SignageKiosk:V '*:S' > boot.log`. Turning the TV on from standby is NOT a boot (the process keeps running,
measured on the TCL). A power cut (cable pulled, TV switched on with the remote) was measured once on the TCL: the app came
up by itself in under a minute; a power cut on the Samsung is not measured.

## 5. Things that go wrong
- `pm clear` wipes the pairing, the channel and the update backoff (pair again). Never `pm clear` or force-stop while an install
  session is pending: the abandoned session fails and its stored result sets a 6 h backoff.
- A system dialog over the signage (Play Protect) stays on screen until someone answers it: the app's 10 minute fail-safe only
  lifts the app's own restart block, it cannot dismiss the dialog.
- A debug-signed APK must never be published as a release asset.
