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
| STOP | Stop. Pressed again while stopped: every sound fades out (stops long samples and tails). | ✅ |
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
| KEYBOARD | Pads play the focused sound as notes: chromatic or in a scale, from a root note. Press again for pad mode. | ✅ |
| CHORDS | Pads play chords of the focused sound: built from the scale (harmonizer) or from a chord set | ✅ |
| STEP | Pads become the 16 steps of the focused sound | ✅ |
| FIXED VEL | Pads ignore pressure (full velocity), on every page | ✅ |
| SHIFT + FIXED VEL (16 Vel) | The 16 pads play the focused sound at 16 velocities | ✅ |

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
| SOLO + pad | Solo a sound within its group (or a group with group buttons) | ✅ |
| MUTE + pad | Mute a sound (or a group with group buttons) | ✅ |
| SHIFT + MUTE (Choke) | Choke groups setup | ⬜ (fixed hat choke today) |

## Groups

| Control | Function | Status |
|---|---|---|
| GROUP A–H | Focus a group. Pads then play its 16 sounds; every group's pattern plays together. | ✅ |
| MUTE / SOLO + group | Mute / solo a whole group | ✅ |
| SHIFT + group | Group colour (pick on the pads) | 🟡 (colour; copy / clear planned) |
| SHIFT + SELECT | Colour of the focused sound (or AUTO = group colour) | ✅ |

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
| 13 | Semitone down (KEYBOARD / CHORDS) | ✅ |
| 14 | Semitone up (KEYBOARD / CHORDS) | ✅ |
| 15 | Octave down (KEYBOARD / CHORDS) | ✅ |
| 16 | Octave up (KEYBOARD / CHORDS) | ✅ |

## Upper-left block

| Control | Function | Status |
|---|---|---|
| CHANNEL (MIDI) | Channel / sound properties of the focused sound | ⬜ |
| PLUG-IN (Instance) | Plug-in chains of the master, the focused group and the focused sound | ✅ (full app) |
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
| NOTE REPEAT (Arp) | Hold (momentary) or quick press (latch): pads repeat at the chosen rate, velocity follows pressure. SHIFT: arpeggiator. | ✅ |
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

D1–D3 choose the tab. Hold SHIFT for fine knob steps. In EDIT and SLICE: encoder turn zooms
the waveform around the marker you moved last, tilting the encoder left / right scrolls, D4
toggles zero-crossing snap (start, end and slice points), and a playhead follows playback.

| Tab | Controls |
|---|---|
| RECORD | D5 source (controller INPUT / PHONE playback) · D6 start (MANUAL / THRESHOLD) · D8 or encoder push start/stop · K1 threshold · K2 max length (up to 30 s) · pads choose the target pad |
| EDIT | Edits the new take, or the focused pad's sample (pads choose which) · K1 start · K2 end · K3 fade in · K4 fade out · K5 trigger of the pad (one-shot / gate) · D5 normalise · D6 reverse · D7 preview (press again to stop; with gate, only while held) · D8 save as a new WAV in `Download/<app folder>/Recordings` and load it onto the focused pad |
| SLICE | K1 / D5 mode (GRID / DETECT transients) · K2 number of slices or detection sensitivity · pads play the slices · D7 preview · D8 apply: each slice is saved as a WAV and loaded onto pads 1, 2, 3… |

Planned: ZONE tab (multi-sample mapping) together with KEYBOARD mode.

## PLUG-IN (full app)

| Control | Function |
|---|---|
| D1 / D2 / D3 | MASTER / GROUP / SOUND level |
| D4 | Next slot. Sound: slot 1 is the source (SAMPLER or SYNTH), slots 2–4 effects. Group and master: 4 effect slots. |
| D5 | Bypass the selected effect |
| D6 or encoder push | Oscilloscope on the right screen (the selected sound, group or master); encoder changes its time window |
| ◀ ▶ | Parameter pages of the selected slot |
| Knobs | Parameters. SHIFT: fine steps. SHIFT + touching a knob: default. On an effect slot, K1 on the first page chooses the effect. |
| D8 | Zero-crossing snap (sampler SAMPLE page) |
| Encoder / tilt | Zoom / scroll the waveform (sampler VOICE and SAMPLE pages) |
| Pads | Focus and play a sound |

**Sampler pages**

| Page | K1 | K2 | K3 | K4 | K5 | K6 | K7 | K8 |
|---|---|---|---|---|---|---|---|---|
| VOICE | Tune | Volume | Pan | Polyphony (1–16) | Engine (standard / vintage A 12-bit 26 kHz / vintage B 12-bit 40 kHz) | Reverse | Source | Trigger (one-shot / gate) |
| SAMPLE | Start | End | | | Loop | Loop start | Loop end | Crossfade |
| AMP ENV | Type (one-shot / AHD / ADSR) | Attack | Hold (AHD) | Decay | Sustain (ADSR) | Release | | |
| FILTER | Type (off / LP 12 / BP / HP / LP 24) | Cutoff | Resonance | Drive | | | | |
| MOD ENV | Attack | Decay | → Pitch | → Cutoff | | | | |
| LFO | Wave | Rate | → Pitch | → Cutoff | → Pan | → Volume | | |
| VELOCITY | → Volume | → Cutoff | | | | | | |

**Synth pages** (24 models from Mutable Instruments Plaits: analog, waveshaping, FM, granular,
additive, wavetable, chords, speech, swarm, noise, particles, string, modal, drums, …)

| Page | K1 | K2 | K3 | K4 | K5 | K6 | K7 | K8 |
|---|---|---|---|---|---|---|---|---|
| VOICE | Tune | Volume | Pan | Polyphony | Note | | Source | Trigger |
| SYNTH | Model | Harmonics | Timbre | Morph | Synth gate (the model's own decaying gate) | Decay | Colour | Output (main / aux) |
| SYNTH ENV | Envelope → pitch | → timbre | → morph | | | | | |

AMP ENV, FILTER, MOD ENV, LFO and VELOCITY work as for the sampler. With the synth gate off,
the AMP ENV shapes the note (and the sound stops when the pad is released).

**Trigger:** ONE-SHOT plays the whole sample; GATE plays only while the pad is held (then
fades with RELEASE). Sequencer notes last one step.

**Effects** (sound, group and master slots)

| Effect | Parameters |
|---|---|
| Compressor | Threshold, ratio, attack, release, knee, makeup, mix (gain reduction shown on the scope) |
| Chorus | Rate, depth, mix, delay, feedback, width |
| Delay | Time (ms, or note values when synced), sync, feedback, mix, ping-pong, low cut, high cut, modulation |
| Reverb | Character (DENSE / LUSH / VAST), pre-delay, size, decay, high cut, low cut, diffusion, modulation, mix, freeze, width |

## KEYBOARD, CHORDS and 16 VEL

Pad 1 (bottom-left) is the lowest note. Pad modes work on every page; the home screen shows
the pads' notes or chords and these knobs:

| Mode | K1 | K2 | K3 | K5 | K6 |
|---|---|---|---|---|---|
| KEYBOARD | Root | Scale (chromatic, major, minor, harmonic / melodic minor, modes, pentatonics, blues, whole tone) | Octave | | |
| CHORDS | Root | Scale | Octave | Chord mode (harmonizer / chord set) | Chord type (harmonizer) or chord set (pop, minor, jazz, neo soul, house, cinematic) |

Root pads light brighter. Notes and chords played while recording are written into the
pattern (up to 4 notes per step). A chord always gets the voices it needs, even when the
sound's polyphony is 1. 60 (C3) plays a sample at its own pitch; for a synth, NOTE sets what C3
plays.

## NOTE REPEAT and arpeggiator

| Control | Function |
|---|---|
| NOTE REPEAT (hold) | Momentary: held pads repeat |
| NOTE REPEAT (quick press) | Latch on / off |
| SHIFT + NOTE REPEAT | Arpeggiator instead of repeat (hold or latch the same way) |
| While NOTE REPEAT is held: D1–D8 | Rate: 1/4, 1/4T, 1/8, 1/8T, 1/16, 1/16T, 1/32, 1/32T |
| While held: K1 / K2 / K3 | Gate (note length) / arp type (up, down, up-down, random, as played, chord) / arp octaves (1–4) |

Repeats follow the transport grid while playing (free-running when stopped) and the pad's
pressure sets their velocity. While recording, repeat and arp notes at 1/16 or slower are
written into the pattern.

## Button lights

Every button that does something on the current page is lit: dim when available, bright when
active or selected (e.g. the open page, a held modifier, a toggle that is on). Buttons that do
nothing yet stay dark.

## Open MK3–specific controls

| Control | Function |
|---|---|
| SHIFT + PAD MODE | Toggle pad flash on hits (diagnostic) |
| Setup wizard (phone app) | Re-learn pads, buttons, encoder or LEDs |
