## 💙 BluKeyborg v1.0.2 — Secure Provisioning & App Integration Release

BluKeyborg is an Android companion app for the **Blue Keyboard Dongle**, allowing you to securely send text, passwords, and special keyboard commands to your computer or device through BLE-to-USB HID.

This **v1.0.2 release** builds on the original v1.0 foundation and introduces **simplified provisioning, consistent mTLS security, and deep integration with password managers and other Android apps**.

> ⚠️ **Firmware requirement:**  
> BluKeyborg v1.0.2 **requires Blue Keyboard firmware v2.0.0 or newer**.

---

## 📱 App Overview

BluKeyborg provides:

- A clean main screen for sending text  
- Automatic reconnection to the selected dongle  
- A full-screen raw keyboard  
- A special-keys panel (arrows, delete, function keys, etc.)  
- A send-history panel  
- A way to send two key codes using the phone volume up/down (configurable in settings)  
- Secure provisioning and mTLS session handling  
- Integration points for password managers and external apps  

---

## 🖼️ Screenshots

### Main Screen
![Main Screen](doc/BluKeyborg_main_screen_anno.jpg)

### Send String
![Send String](doc/BluKeyborg_send_string.jpg)

### Send History
![Send History](doc/BluKeyborg_string_history.jpg)

### Full Keyboard
![Full Keyboard](doc/BluKeyborg_full_keyboard.jpg)
![Full Keyboard Typing](doc/BluKeyborg_full_keyboard_type.jpg)

### Special Keys Panel
![Special Keys Panel](doc/BluKeyborg_special_keys.jpg)

### Control via phone buttons
![Vol Ctrl](doc/BluKeyborg_vol_buttons_ctrl.jpg)
![Vol Ctrl Lock](doc/BluKeyborg_vol_buttons_ctrl_lockscrn.jpg)

### Settings
![Settings](doc/BluKeyborg_settings.jpg)

---

## 🚀 What’s New in v1.0.2

### 🔐 Simplified Provisioning & mTLS Consistency
- New streamlined provisioning flow  
- More consistent and robust mTLS session handling  
- Removes legacy edge-cases from earlier handshake logic  
- **Requires Blue Keyboard firmware v2.0.0**

---

### 📤 Type Text from Other Apps (Android Share)
BluKeyborg can now receive text from **any Android app** via the **Share** menu.

**How it works:**
- Select text in another app  
- Tap **Share**  
- Choose **BluKeyborg**  
- The text is sent securely to the dongle and typed on the host  

⚙️ **Note:**  
This feature must be explicitly enabled in **Settings → Share / External Input**.

---

### 🔑 KeePass2Android (KP2A) Integration
BluKeyborg can now act as an **external output device** for **KeePass2Android (KP2A)**.

**How to use:**
1. Enable KP2A integration in BluKeyborg settings  
2. Enable the BluKeyborg plugin inside KeePass2Android  
3. When KP2A shows the plugin confirmation dialog:  
   - **Rotate the phone to landscape**  
   - Due to a KP2A immersive-mode bug, the **Accept** button is not visible or usable in portrait mode  

Once enabled, credentials can be securely typed via the Blue Keyboard dongle.

---

### 🔐 KeePassDX Integration (New AIDL Service)
BluKeyborg now exposes a dedicated **AIDL service** for **KeePassDX**, enabling a cleaner and more secure integration path.

- No direct BLE handling inside KeePassDX  
- BluKeyborg remains the single BLE + mTLS authority  
- Credentials are sent over IPC and securely typed via the dongle  

👉 See the new KeePassDX fork here:  
https://github.com/larrylart/KeePassDX

---

### 🎮 Basic Media Controls (Remote Panel)
The remote panel now includes **basic media control buttons**:

- Play / Pause  
- Stop  
- Volume Up  
- Volume Down  

✅ Works reliably with PCs  
⚠️ Partial compatibility with TVs and media boxes  

> Different vendors use different HID / consumer-control codes — this area will be refined in future releases.

---

## ✅ Core Features (Carried Over from v1.0)

### Manual Text Sending
Write any text in the input field and send it directly to the dongle.

BluKeyborg will:
- Send the text via BLE  
- Verify the dongle’s response  
- Append the sent text to the history  
- Clear the input box after a successful send  

---

### Send History (Session-Based)
- Alternating white / light-gray rows  
- Auto-scrolls to the newest entry  
- History resets when the activity or process is recreated  

---

### Full Keyboard Activity
A dedicated full-screen keyboard for raw HID typing:
- Automatically enables fast-keys mode  
- Sends raw HID codes via fast-key commands  
- Exits via the top-right **X** button  

---

### Special Keys Panel
Send HID keys such as:
- Arrow keys  
- Backspace / Delete  
- Enter / Tab  
- Escape  
- Home / End  
- Page Up / Page Down  
- Function keys (F1–F12)  

---

### Auto-Connect to Preferred Dongle
On app start or resume:
- Automatically reconnects to the selected dongle  
- Non-blocking BLE connection logic  
- Graceful failure handling if the device is unavailable  

---

### Minimal Permissions
BluKeyborg requires:
- **Bluetooth / BLE permissions only**  
- No Internet  
- No storage  
- No location  
- No personal data access  

---

## 🔧 Internal Architecture (Updated)

BluKeyborg acts as a **secure hub** between apps and the dongle:

- BLE GATT writes & notifications  
- Binary command protocol  
- mTLS-secured sessions  
- Centralized provisioning & key storage  
- `BleHub` as the single authority for BLE + security  
- IPC interfaces (AIDL) for password managers  

This design avoids duplicating BLE logic in third-party apps.

---

## 📦 Installation

### 1. Download the APK
From GitHub Releases:

**Releases → BluKeyborg v1.0.2**

---

### 2. Pair the Dongle
In BluKeyborg:

**Settings → Output Device → Scan → Select your dongle**

---

### 3. Provision & Connect
Follow the provisioning flow (requires firmware v2.0.0).

---

### 4. Start Sending
- Manual text  
- Share from other apps  
- Password manager integrations  
- Full keyboard / remote panel  

---

## 🗂️ Source Code Structure

```
app/src/main/java/com/blu/blukeyborg/
│
├── MainActivity.kt # Main UI + send history
├── FullKeyboardActivity.kt # Raw HID full-screen keyboard
├── SpecialKeysActivity.kt # Special keys + media controls
│
├── BleHub.kt # BLE + provisioning + mTLS
├── BluetoothDeviceManager.kt # Device scanning & selection
│
├── keepassdx/ # AIDL service (KeePassDX)
├── kp2a/ # KP2A plugin integration
│
└── BluKeyborgApp.kt # Application init
```


---

## 📄 License

Released under the **MIT License**.  
See the `LICENSE` file for details.

---

## 📢 Notes About v1.0.2

This release marks a **major architectural step**:

- BluKeyborg becomes the **central secure bridge** for all integrations  
- Password managers no longer need direct BLE access  
- Provisioning and mTLS are now consistent across platforms  

Planned next steps:
- Improved TV / media device compatibility  
- Multi-dongle profiles  
- Further UI refinements  
- Expanded iOS and Linux feature parity  

---

💙 **Thank you for using BluKeyborg!**  
Security, correctness, and openness remain the core design goals.


