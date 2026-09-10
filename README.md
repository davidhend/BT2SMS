# BT2SMS

An Android app that acts as a two-way **Bluetooth ↔ SMS gateway**. It holds a
Bluetooth Serial Port Profile (SPP/RFCOMM) connection to a microcontroller
(a Teensy in the original build) and relays text between that serial link and
a single configured phone number:

- **Bluetooth → SMS**: each newline-terminated line the microcontroller sends
  over the serial link is sent as a text message to the configured number.
- **SMS → Bluetooth**: each incoming text message from the configured number
  is written to the microcontroller over the serial link, terminated with `\n`.

This lets a device with no cellular radio of its own talk to a remote phone by
borrowing the Android handset's SMS capability.

## How it works

| Component | Role |
|---|---|
| `MainActivity` | Settings and status UI. Lets you enter the phone number and the target device's Bluetooth MAC address, shows connection status and a log of messages, and has a **Manually Connect** button. |
| `MyForegroundService` | Owns the Bluetooth socket and does all the forwarding in both directions. Runs as a `connectedDevice` foreground service with a persistent notification so it keeps working when the app is backgrounded. |
| `MessageReceiver` | Manifest-registered receiver for the system `SMS_RECEIVED` broadcast. Reassembles multi-part messages and hands them to the service, which means forwarding works even if the process was killed. |

Additional behaviour:

- Incoming SMS is only forwarded if the sender matches the configured number
  (compared with `PhoneNumberUtils.compare`, so formatting differences are
  tolerated). Messages from anyone else are dropped.
- Outgoing SMS numbers without a leading `+` are assumed to be US numbers and
  prefixed with `+1`.
- If a Bluetooth line arrives before a phone number has been configured, the
  service writes `_nonum` back to the microcontroller instead of sending SMS.
- Carriage returns from the microcontroller's `println()` are stripped so they
  don't end up in the SMS body.
- Settings (MAC address and phone number) persist in `SharedPreferences`.

## Requirements

- Android 9 (API 28) or later. Built against `compileSdk 34`, `targetSdk 33`.
- A device with both Bluetooth and telephony hardware (both are declared as
  required features).
- A Bluetooth SPP peripheral, paired with the phone, that speaks
  newline-delimited ASCII.

Permissions requested at runtime: `SEND_SMS`, `RECEIVE_SMS`,
`BLUETOOTH_CONNECT`, `BLUETOOTH_SCAN`, and `POST_NOTIFICATIONS`.

## Building

Open the project in Android Studio, or from the command line:

```sh
./gradlew assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/`.

## Usage

1. Pair the phone with your Bluetooth serial device in Android's Bluetooth
   settings.
2. Launch BT2SMS and grant the requested permissions.
3. Enter the phone number the gateway should talk to, and the MAC address of
   the Bluetooth device.
4. Tap **Manually Connect** (the service also tries to connect on launch and
   whenever an SMS arrives).
5. The notification shows the connection state. The **Communication Output**
   box logs traffic while the app is open.

## Project layout

```
app/src/main/java/com/sloppingcomputing/bt2sms/
├── MainActivity.java          # UI, permissions, service binding
├── MyForegroundService.java   # Bluetooth socket + SMS forwarding
└── MessageReceiver.java       # SMS_RECEIVED broadcast receiver
app/src/main/res/layout/activity_main.xml
```

## Notes

- There is a fallback MAC address hardcoded in `MyForegroundService` that is
  used only when none has been saved; replace it with your own device's
  address or simply enter one in the app.
- The Bluetooth reader polls `InputStream.available()` every 25 ms rather than
  blocking on `read()`.
