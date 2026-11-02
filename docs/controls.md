# Controls spec

Open MK3 aims to behave the way existing users expect the controller to behave, so their
muscle memory carries over. This file is the reference and the checklist. Each control is
described in our own words, from the labels printed on the hardware and the established
groovebox workflow.

Status: ✅ done · 🟡 partial / temporary behaviour · ⬜ planned

**Concepts used below:**
- A **project** has 8 **groups** (A–H).
- Each group holds 16 **sounds**, one per pad, and a set of **patterns**.
- **Scenes** combine one pattern per group. The **arranger** sequences scenes into a song.

## Transport

| Control | Function | Status |
|---|---|---|
| PLAY | Start / stop playback | ✅ |
| REC | Toggle recording while playing (starts playback if stopped) | ✅ |
| SHIFT + REC (Count-in) | Record with a one-bar count-in | ⬜ |
| STOP | Stop. Press again to return to the start. | 🟡 (no return-to-start yet) |
| RESTART | Restart playback from the beginning of the loop | ✅ |
| SHIFT + RESTART (Loop) | Loop on / off | ⬜ |
| ERASE + pad | Erase that sound's events in the pattern | ✅ |
| SHIFT + ERASE (Replace) | Recording replaces existing events instead of adding | ⬜ |
| TAP | Tap tempo | ⬜ |
| SHIFT + TAP (Metro) | Metronome on / off | ⬜ |
| FOLLOW | Display follows the playhead | ⬜ |
| SHIFT + FOLLOW (Grid) | Grid / quantise settings | ⬜ |

## Pad modes (buttons above the pads)

| Control | Function | Status |
|---|---|---|
| PAD MODE | Pads play the 16 sounds of the focused group | ✅ |
| KEYBOARD | Pads play the focused sound chromatically | ⬜ |
| CHORDS | Pads play chords of the focused sound | ⬜ |
| STEP | Pads become the 16 steps of the focused sound | ✅ |
| FIXED VEL | Pads ignore pressure (full velocity) | ✅ |
| SHIFT + FIXED VEL (16 Vel) | The 16 pads play the focused sound at 16 velocities | ⬜ |

## Pattern and arrangement buttons (left of the pads)

| Control | Function | Status |
|---|---|---|
| SCENE | Select or create scenes on the pads | ⬜ |
| SHIFT + SCENE (Section) | Arranger sections | ⬜ |
| PATTERN | Select or create patterns of the focused group on the pads | ⬜ |
| EVENTS | Edit or select events: pads choose the sound, encoder and knobs edit | ⬜ |
| VARIATION | Humanise / randomise the selected events | ⬜ |
| SHIFT + VARIATION (Navigate) | Zoom and scroll the pattern view | ⬜ |
| DUPLICATE + pad | Duplicate a sound, pattern or scene onto another pad | ⬜ |
| SHIFT + DUPLICATE (Double) | Double the pattern length and its content | ⬜ |
| SELECT + pad | Focus a sound without playing it | ✅ |
| SOLO + pad | Solo a sound (or a group with group buttons) | ✅ (sounds) |
| MUTE + pad | Mute a sound (or a group with group buttons) | ✅ (sounds) |
| SHIFT + MUTE (Choke) | Choke groups setup | ⬜ (fixed hat choke today) |

## Groups

| Control | Function | Status |
|---|---|---|
| GROUP A–H | Focus a group. Pads then play its sounds. | ⬜ (single group today, A lit) |
| SHIFT + group | Group options (copy, clear, colour) | ⬜ |

## SHIFT + pad shortcuts (printed on the pads)

| Pad | Function | Status |
|---|---|---|
| 1 | Undo | ⬜ |
| 2 | Redo | ⬜ |
| 3 | Step undo | ⬜ |
| 4 | Step redo | ⬜ |
| 5 | Quantise | ⬜ |
| 6 | Quantise 50 % | ⬜ |
| 7 | Nudge left | ⬜ |
| 8 | Nudge right | ⬜ |
| 9 | Clear (pattern / selection) | 🟡 (SHIFT + ERASE clears all today) |
| 10 | Clear automation | ⬜ |
| 11 | Copy | ⬜ |
| 12 | Paste | ⬜ |
| 13 | Semitone down | ⬜ |
| 14 | Semitone up | ⬜ |
| 15 | Octave down | ⬜ |
| 16 | Octave up | ⬜ |

## Upper-left block

| Control | Function | Status |
|---|---|---|
| CHANNEL (MIDI) | Channel / sound properties of the focused sound | ⬜ |
| PLUG-IN (Instance) | Instrument and effect slots of the focused sound/group | 🟡 (sampler parameters of the focused sound; full app) |
| ARRANGER | Arranger view (scenes / sections / song) | ⬜ |
| MIXER | Mixer: levels, pan, sends per sound or group | ⬜ |
| BROWSER | Browse samples and presets, load onto the focused sound | ✅ (samples) |
| SHIFT + BROWSER (+Plug-in) | Browse instruments and effects | ⬜ |
| SAMPLING | Record a sample (controller inputs or phone playback), edit, slice | 🟡 (record, edit, slice; zones planned) |
| ◀ ▶ | Page through parameter pages on the screens | 🟡 (pages on the PLUG-IN page; steps through sounds on the home page) |
| FILE (Save) | Project menu. SHIFT + FILE saves. | ⬜ (pattern and kit autosave today) |
| SETTINGS | Settings | 🟡 (LED palette page today) |
| AUTO | Record knob automation while held / latched | ⬜ |
| MACRO (Set) | Macro controls | ⬜ |

## Screens, knobs and encoder

| Control | Function | Status |
|---|---|---|
| D1–D8 (buttons above the screens) | Context functions shown on the screen under each button | 🟡 (sampling and sampler pages) |
| Knobs 1–8 | Parameters shown on the screen above each knob. Touching a knob shows its value. | 🟡 (fixed: volume, sensitivity, tempo, swing) |
| 4D encoder turn | Context value: list scrolling, or the selected encoder mode | 🟡 (tempo on home, scroll in browser) |
| 4D encoder push / tilt | Confirm / navigate | ✅ (browser) |
| VOLUME (Velocity) | Encoder adjusts master or focused volume. SHIFT: note velocity. | ⬜ |
| SWING (Position) | Encoder adjusts swing. SHIFT: event position. | ⬜ (knob 4 today) |
| TEMPO (Tune) | Encoder adjusts tempo. SHIFT: tune. | ⬜ (encoder always tempo today) |
| NOTE REPEAT (Arp) | Hold or latch: pads repeat at the chosen rate. SHIFT: arpeggiator. | ⬜ |
| LOCK (Ext Lock) | Snapshot / lock parameter states | ⬜ |

## Touch strip

| Control | Function | Status |
|---|---|---|
| PITCH | Strip bends pitch of the focused sound | ⬜ |
| MOD | Strip sends modulation | ⬜ |
| PERFORM (FX Select) | Strip controls performance effects (e.g. filter sweep, stutter) | ⬜ |
| NOTES | Strip plays notes or strums | ⬜ |

## Sampling sources

| Source | Status |
|---|---|
| Controller audio inputs | ✅ |
| Phone playback, i.e. whatever another app plays | ✅ (Android playback capture: confirm once per session via the notification on the phone; apps that block capture record as silence) |

## Sampling page (SAMPLING)

D1–D3 choose the tab. Hold SHIFT for fine knob steps.

| Tab | Controls |
|---|---|
| RECORD | D5 source (controller INPUT / PHONE playback) · D6 start (MANUAL / THRESHOLD) · D8 or encoder push start/stop · K1 threshold · K2 max length (up to 30 s) · pads choose the target pad |
| EDIT | Edits the new take, or the focused pad's sample (pads choose which) · K1 start · K2 end · K3 fade in · K4 fade out · D5 normalise · D6 reverse · D7 preview · D8 save as a new WAV in `Download/<app folder>/Recordings` and load it onto the focused pad |
| SLICE | K1 / D5 mode (GRID / DETECT transients) · K2 number of slices or detection sensitivity · pads play the slices · D7 preview · D8 apply: each slice is saved as a WAV and loaded onto pads 1, 2, 3… |

Planned: ZONE tab (multi-sample mapping) together with KEYBOARD mode.

## Sampler parameters (PLUG-IN, full app)

◀ ▶ or D1–D7 choose the page. Hold SHIFT for fine steps; SHIFT + touching a knob resets it.
Pads focus (and play) a sound. Settings are saved per pad. Loading a new sample resets
start, end and loop points. Values that do nothing with the current settings are shown dimmed.

| Page | K1 | K2 | K3 | K4 | K5 | K6 |
|---|---|---|---|---|---|---|
| VOICE | Tune | Volume | Pan | Polyphony (1–16) | Engine (standard / vintage A 12-bit 26 kHz / vintage B 12-bit 40 kHz) | Reverse |
| SAMPLE | Start | End | | | Loop | Loop start · K7 loop end · K8 crossfade |
| AMP ENV | Type (one-shot / AHD / ADSR) | Attack | Hold (AHD) | Decay | Sustain (ADSR) | Release (ADSR) |
| FILTER | Type (off / LP 12 / BP / HP / LP 24) | Cutoff | Resonance | Drive | | |
| MOD ENV | Attack | Decay | → Pitch | → Cutoff | | |
| LFO | Wave (sine / triangle / saw / square / random) | Rate | → Pitch | → Cutoff | → Pan | → Volume |
| VELOCITY | → Volume | → Cutoff | | | | |

ADSR sustains while a pad is held; sequencer notes last one step. Loops need an AHD or ADSR
envelope so that a looped sound always ends.

## Button lights

Every button that does something on the current page is lit: dim when available, bright when
active or selected (e.g. the open page, a held modifier, a toggle that is on). Buttons that do
nothing yet stay dark.

## Open MK3–specific controls

| Control | Function |
|---|---|
| SHIFT + PAD MODE | Toggle pad flash on hits (diagnostic) |
| Setup wizard (phone app) | Re-learn pads, buttons, encoder or LEDs |
