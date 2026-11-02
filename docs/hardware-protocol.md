# MK3 controller: USB protocol notes

What Open MK3 knows about the controller, worked out by observing the device on USB. All
values are for the MK3 (USB `17CC:1600`).

## USB interfaces

| # | Class | Purpose | Endpoints |
|---|---|---|---|
| 0 | Audio (UAC2) control | clock source, terminals | — |
| 1 | Audio streaming | output, 4 channels | isochronous OUT `0x01`, asynchronous, max 208 bytes |
| 2 | Audio streaming | input, 2 channels | isochronous IN `0x81`, max 104 bytes, **implicit feedback** |
| 3 | Audio / MIDI streaming | MIDI | bulk `0x82` / `0x02` |
| 4 | HID | controls, LEDs | interrupt IN `0x83`, interrupt OUT `0x03`, 64 bytes |
| 5 | Vendor-specific | screens | bulk OUT `0x04` |
| 6 | DFU | firmware update | — |

The controller is USB 2.0 high-speed and bus powered, and asks for 480 mA.

## HID input

### Report `0x01`: buttons, knobs, encoder, touch strip (42 bytes)

| Bytes | Content |
|---|---|
| 1–9 | 72 button bits. Bit number = (byte − 1) × 8 + bit (see the table below). |
| 10 | Knob touch sensors. Bit (7 − k) is knob k+1, so bit 7 = knob 1 and bit 0 = knob 8. |
| 11 | Low nibble: 4D encoder position, 0–15, wrapping, ±1 per detent. |
| 12–27 | Knobs 1–8, 16-bit little-endian, absolute, wrapping over 0–999. |
| 28–29 | Touch strip: counter / timestamp. |
| 30–31 | Touch strip: position, 16-bit little-endian. |
| 32–41 | Analogue values (not yet used). |

The 4D encoder also has a **touch sensor** that reports as one of the button bits as soon as
a finger rests on it. Treat it as a touch, not a button press. The setup wizard detects it.

**Button bits:**

| Bits | Controls |
|---|---|
| 2, 3, 4, 5 | Encoder up, right, down, left |
| 6 | SHIFT |
| 7 | D8 |
| 8–15 | Groups A–H |
| 16–21 | NOTES, VOLUME, SWING, TEMPO, NOTE REPEAT, LOCK |
| 24–31 | PAD MODE, KEYBOARD, CHORDS, STEP, FIXED VEL, SCENE, PATTERN, EVENTS |
| 33–37 | VARIATION, DUPLICATE, SELECT, SOLO, MUTE |
| 38–40 | PITCH, MOD, PERFORM |
| 41–47 | RESTART, ERASE, TAP, FOLLOW, PLAY, REC, STOP |
| 48–61 | MACRO 48, SETTINGS 49, PAGE RIGHT 50, SAMPLING 51, MIXER 52, PLUG-IN 53, CHANNEL 56, ARRANGER 57, BROWSER 58, PAGE LEFT 59, FILE 60, AUTO 61 |
| 64–70 | D1–D7 |
| 71 | Encoder push |

The default control map (`core/.../mk3/Mk3Map.kt`) holds all of the above. The on-device
setup wizard can re-learn any of it.

### Report `0x02`: pads (64 bytes)

- A list of 3-byte entries after the report ID: `[pad][state << 4 | pressure 11..8][pressure 7..0]`.
- The list ends at an all-zero entry.
- Pressure is 12-bit.
- States: `1` = hit, `4` = pressure update (about every 25 ms while held), `3` = release.
- Pad index 0 is the **top-left** pad, then left → right, top → bottom.

## HID output: LEDs

| Report | Size | Content |
|---|---|---|
| `0x80` | 62 bytes | Button LEDs |
| `0x81` | 41 bytes | Indices 0–24 = touch-strip LEDs, 25 + pad index = pad LEDs |

**Button LED indices (report `0x80`):**

| Index | LEDs |
|---|---|
| 0–5 | CHANNEL, PLUG-IN, ARRANGER, MIXER, BROWSER, SAMPLING |
| 6–7 | PAGE LEFT, PAGE RIGHT |
| 8–11 | FILE, SETTINGS, AUTO, MACRO |
| 12–19 | D1–D8 |
| 20–28 | VOLUME, SWING, NOTE REPEAT, TEMPO, LOCK, PITCH, MOD, PERFORM, NOTES |
| 29–36 | Groups A–H |
| 37–44 | RESTART, ERASE, TAP, FOLLOW, PLAY, REC, STOP, SHIFT |
| 45–57 | FIXED VEL, PAD MODE, KEYBOARD, CHORDS, STEP, SCENE, PATTERN, EVENTS, VARIATION, DUPLICATE, SELECT, SOLO, MUTE |
| 58–61 | The four LEDs around the 4D encoder |

**Colours:**
- Single-colour LEDs take 0–127 as brightness.
- RGB LEDs use **value = colour × 4 + brightness**. That covers the pads, touch strip, groups and SAMPLING.
  - Colour 0 is off.
  - Colours 1–16, in order: red, orange, light orange, warm yellow, yellow, lime, green, mint, cyan, turquoise, blue, plum, violet, purple, magenta, fuchsia.
  - Colour 17 and above are white.
  - Brightness runs 0–3. Brightness 2 is the most saturated.
- With every LED at full white the controller draws a lot of current from the phone, so keep normal states dim.

## Screens (bulk OUT `0x04`)

There are two 480 × 272 screens, `0` and `1`. Each transfer is a header followed by commands:

```
header  : 84 00 <screen> 60 00 00 00 00  xH xL  yH yL  wH wL  hH hL
00 nn nn nn : transmit nn × 2 pixels (RGB565, big-endian), followed by the pixel data
01 nn nn nn : repeat the following 2 pixels (4 bytes) nn times
03 00 00 00 : blit
40 00 00 00 : end of transmission
```

- Rectangles can be partial. Keep x and width even.
- A full frame is about 261 KB.
- Sending only changed rectangles keeps the USB traffic small.

## Audio

- **Format:** UAC2, 48 kHz (clock source ID 2, set with `SET_CUR` on the sampling-frequency control).
  24-bit samples, left-justified in 4-byte subslots.
- **Channels:** 4 out (1/2 and 3/4) and 2 in.
- **Packets** every 125 µs microframe, 6 frames nominal.
- **Implicit feedback:** the OUT endpoint is asynchronous, and its clock is carried by the IN
  stream. Each IN packet's frame count is exactly how many frames the matching OUT packet
  must carry. A host that sends a fixed 48 kHz instead drifts against the controller's
  crystal. That shows up as a slip, an audible pop, every 30–70 s.

**How Open MK3 streams** (`UsbAudio` in `core/src/main/cpp/engine.cpp`):
- It claims interfaces 0–2 from the system driver and selects alt setting 1 on 1 and 2.
- It streams isochronous URBs through usbfs on the connection's file descriptor (no root needed).
- Each OUT packet is sized from the IN packet counts.
- **Scheduling matters.** An ordinary app thread can be descheduled for more than 8 ms, which
  empties the queue. So the queue is serviced from the callback of a silent AAudio stream on
  the phone's own output: Android runs that callback at real-time priority. A fallback thread
  covers the case where the callback isn't running.
- **Timeouts matter too.** Timed-out bulk/interrupt transfers on the same file descriptor get
  cancelled in the kernel. So while streaming, the HID input is read with queued interrupt
  URBs on the same thread instead.
- **Queue length:** it starts at 8 ms and grows by 1 ms after each gap, up to 16 ms.
