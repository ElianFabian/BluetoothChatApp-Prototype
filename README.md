
<img height="450px" src="https://github.com/user-attachments/assets/930c546f-1746-4a74-be52-b2bb62cd9f41" />

# BluetoothChatApp demo
This project is a demo to show the capabilities and challenges of working with Bluetooth Classic.

# Characteristics of Bluetooth Classic
For the official documentation, check this out: https://developer.android.com/develop/connectivity/bluetooth

If you want a more beginner-friendly introduction to Bluetooth, check out this video: 🎥 https://youtu.be/A41hkHoYu4M

To communicate over Bluetooth, two Android devices need to establish a connection. One device must act as a server (waiting for connections), and the other as a client (initiating the connection). Once they're connected, there’s no real distinction — both can send and receive messages.

To establish the connection, both devices need to use the same UUID, and the client must also know the server’s MAC address. If the UUIDs don’t match, nothing happens. No errors, no logs — just nothing.

There are also 2 ways of connecting given by these 2 pairs of methods:

```kotlin
// Require pairing
listenUsingRfcommWithServiceRecord(...)
createRfcommSocketToServiceRecord(...)

// Doesn’t require pairing
listenUsingInsecureRfcommWithServiceRecord(...)
createInsecureRfcommSocketToServiceRecord(...)
```

- listenUsingRfcommWithServiceRecord is used by the server to wait for a connection.

- createRfcommSocketToServiceRecord is used by the client to connect.

The insecure versions allow communication without pairing, but both devices must use the insecure variants for that to work.

Once connected, both devices can send and receive data via streams.
Suggestion: send the length of each message first — that way, you know how many bytes to read for a full message.

# Bluetooth requirements
Since this is Android, as always we need to care about permissions, we’re not going to discuss all the concrete permissions in detail since you can check them out in the AndroidManifest.xml, but we’ll talk about the things I’ve seen many apps do wrong (intentionally or not, who knows).

At API level 30 and lower you need to request the ACCESS_FINE_LOCATION in order to allow scanning nearby devices, but that’s it, just to request, not to enable the location. Well, actually this is not completely true, there are some devices that actually force you to enable the location for scanning to work. I’ve seen this behavior in Xiaomi Mi MIX 2S API level 29, so I guess this is the case for Xiaomi devices with API level 30 or less. You should not worry about this since we can check if we actually need it or not programmatically, refer to HomeViewModel.kt.

For API level 31 and above we need just need to request BLUETOOTH_SCAN and BLUETOOTH_CONNECT both at the same time, we never need to request any kind of location permissions or even to enable location for Bluetooth to work (but still many apps do these things when is not required).





Since this is Android, of course we have to talk permissions :).
We won’t go into every single one — check AndroidManifest.xml for that — but I’ll highlight the common mistakes I’ve seen apps make (sometimes on purpose, who knows).

API 30 and below:
- You must request ACCESS_FINE_LOCATION to scan for nearby devices.
- Just request it — you don’t need to actually enable location.
- However, some devices (e.g. Xiaomi Mi MIX 2S on API 29) do force you to enable it anyway. So yeah, device quirks exist.
- You can handle this programmatically — see HomeViewModel.kt.

API 31 and above:
- You only need BLUETOOTH_SCAN and BLUETOOTH_CONNECT, and that’s it.
- No location permissions. No need to enable location.
- But some apps still ask for it — unnecessarily.

# About the app

The app has the following features:

- Show paired devices
- Scan devices
- Allow to connect with devices without pairing (just check the checkbox at the bottom)
- Connect to devices (just one tap)
- Cancel connection attempt (just one tap when the color is yellow)
- Disconnect from a device (long tap)
- Start/stop server
- Make the device discoverable (Modern Android doesn’t make you visible to others by default — you have to explicitly allow it.)
- Receive messages even if the app is closed.
- Receive notifications when the app is in the background or closed.
- Send a message from the notification.
- Send messages to just one device or all at the same time.
- Change your bluetooth device name (1)


(1): Not all devices allow this, on some devices even though it seems the name was changed when you go to Bluetooth settings and come back to the app you will see the previous name you had.
Not all devices support changing the Bluetooth name, and there doesn't seem to be a way to check it.
Here's a list of devices that I tested that support it:
- Google Pixel
- Motorola
- Sony Xperia 10 (34)
- SHARP AQUOS
- ZTE
- FUJITSU

And here's a list of devices that I tested that don't support it:
- Huawei
- Xiaomi
- Realme
- Samsung

# Extra
These are some things I've tested:
- Sending really long messages
(Works fine, but the UI isn’t made for this.)
- Sending messages over ~60 meters with no obstacles
- Connecting 4 devices all at once
(Didn’t have more devices to test their actual limit. Theoretical max is 7, but real-world use is usually less.)


If you find any issues or bugs, feel free to open an issue.
I appreciate it. :D
