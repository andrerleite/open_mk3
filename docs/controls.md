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
| SHIFT + REC (Count-in) | Record with a one-bar count-in (clicks) | ✅ |
| STOP | Stop. Pressed again while stopped: every sound fades out (stops long samples and tails). | ✅ |
| RESTART | Restart playback from the beginning of the loop (song mode: from section 1) | ✅ |
| SHIFT + RESTART (Loop) | Loop the playing song section on / off | ✅ |
| ERASE + pad | Erase that sound's events in the pattern | ✅ |
| SHIFT + ERASE (Replace) | Recording replaces existing events instead of adding | ⬜ |
| TAP | Tap tempo | ✅ |
| SHIFT + TAP (Metro) | Metronome on / off | ✅ |
| FOLLOW | Step pages follow the playhead | ✅ |
| SHIFT + FOLLOW (Grid) | Grids: D3 arrange (pattern length steps), D4 step (what a STEP pad is: 1/4 … 1/64, triplets), D5 nudge (EVENTS position); pads choose; FOLLOW: back | ✅ |

## Pad modes (buttons above the pads)

| Control | Function | Status |
|---|---|---|
| PAD MODE | Pads play the group's 16 sounds. Press: settings on the screens (D5–D8 base key, K1 choke group and K2 link group of the focused sound); press again: hide them | ✅ |
| KEYBOARD | Pads play the focused sound as notes. Press: switch and show the settings; press again: hide them (pads stay); SHIFT + KEYBOARD: switch without settings. PAD MODE returns to sounds. | ✅ |
| CHORDS | Pads play chords of the focused sound: built from the scale (harmonizer) or from a chord set | ✅ |
| STEP | Pads become the 16 steps of the focused sound | ✅ |
| FIXED VEL | Pads ignore pressure (full velocity), on every page | ✅ |
| SHIFT + FIXED VEL (16 Vel) | The 16 pads play the focused sound at 16 velocities (from pad mode) | ✅ |

## Pattern and arrangement buttons (left of the pads)

| Control | Function | Status |
|---|---|---|
| SCENE | Hold: scenes on the pads (select / launch; empty pad = new scene from the patterns playing). Hold + D1 pins the mode. | ✅ |
| SHIFT + SCENE (Section) | Song view (same as ARRANGER) | ✅ |
| PATTERN | Hold: patterns of the focused group on the pads (select, empty pad = new). K4 length (SHIFT: fine), D5 create, D6 delete. Hold + D1 pins the mode. | ✅ |
| EVENTS | Hold (D1 pins): pads add / remove a sound's notes from the selection (dim: has notes, lit: selected), D5 all, D6 none, ERASE + pad erases; K1 position, K2 note, K3 velocity, K4 length (SHIFT: fine); ◀ ▶ pages | ✅ |
| VARIATION | Hold (D1 pins): HUMANIZE varies the focused sound's velocity (K1, K2 probability) and timing (K3); RANDOM writes new random notes (density, velocity, note range); D8 applies | ✅ |
| SHIFT + VARIATION (Navigate) | Zoom and scroll the pattern view | ⬜ |
| DUPLICATE + pad | Hold, hit the source pad, then the target: duplicates a sound (pad mode), pattern or scene | ✅ |
| SHIFT + DUPLICATE (Double) | Double the pattern length and its content | ✅ |
| SELECT + pad | Focus a sound without playing it | ✅ |
| SOLO + pad | Solo a sound within its group (or a group with group buttons) | ✅ |
| MUTE + pad | Mute a sound (or a group with group buttons); while held, audible pads are fully lit and muted ones half lit | ✅ |
| SHIFT + MUTE (Choke) | Choke groups setup | 🟡 (choke and link groups on the PAD MODE page) |

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
| 1 | Undo | ✅ |
| 2 | Redo | ✅ |
| 3 | Step undo (same as undo) | ✅ |
| 4 | Step redo (same as redo) | ✅ |
| 5 | Quantise the focused sound (with SELECT held: the whole group) | ✅ |
| 6 | Quantise 50 % | ✅ |
| 7 | Nudge the focused sound's notes one step left | ✅ |
| 8 | Nudge right | ✅ |
| 9 | Clear the focused group's pattern | ✅ |
| 10 | Clear the focused group's automation | ✅ |
| 11 | Copy the pattern | ✅ |
| 12 | Paste the pattern | ✅ |
| 13 | Semitone down: KEYBOARD / CHORDS layout, or the focused sound's notes (pad mode) | ✅ |
| 14 | Semitone up (KEYBOARD / CHORDS) | ✅ |
| 15 | Octave down (KEYBOARD / CHORDS) | ✅ |
| 16 | Octave up (KEYBOARD / CHORDS) | ✅ |

## Upper-left block

| Control | Function | Status |
|---|---|---|
| CHANNEL (MIDI) | Channel properties. D1 MASTER / D2 GROUP / D3 SOUND, ◀ ▶ OUTPUT / GROOVE. OUTPUT: K1 volume, K2 pan. GROOVE: K1 swing (master: the project swing; group: extra swing added for that group) | ✅ |
| PLUG-IN (Instance) | Plug-in chains of the master, the focused group and the focused sound | ✅ (full app) |
| ARRANGER | Song view: a list of sections, each playing a scene. D1 song mode on / off, D2 loop, D3 jump to the selected section, D5 duplicate, D6 delete; encoder or ◀ ▶ select, K1 moves a section; scene pads add a section after the selected one (SHIFT + pad replaces it). A section lasts as long as its scene's longest pattern, and all groups start their patterns together at each section. In song mode, a scene pad in SCENE mode jumps to that scene's section | ✅ |
| MIXER | Mixer: volume / pan of 8 channels on the knobs (groups or sounds), master on the encoder | ✅ (sends ⬜) |
| BROWSER | Browse samples and presets, load onto the focused sound | ✅ (samples) |
| SHIFT + BROWSER (+Plug-in) | Browse instruments and effects | ⬜ |
| SAMPLING | Record a sample (controller inputs or phone playback), edit, slice | 🟡 (record, edit, slice; zones planned) |
| ◀ ▶ | Page through parameter pages on the screens | 🟡 (pages on the PLUG-IN page; steps through sounds on the home page) |
| FILE (Save) | Projects: save, save as new, new, load. SHIFT + FILE quick-saves. | ✅ |
| SETTINGS | Metronome on / level, count-in length (D8: LED check) | ✅ |
| AUTO | Hold (SHIFT + AUTO pins) and turn knobs while playing: sound parameters (PLUG-IN pages, MIXER volume / pan) are recorded per step into the pattern (16 lanes per pattern). ERASE + turn a knob removes that parameter's automation | ✅ |
| MACRO (Set) | 8 macro knobs per group, each driving one sound parameter. SHIFT + MACRO: D1–D8 pick a macro, then turn any parameter on the PLUG-IN page to assign it; ERASE + D-button clears | ✅ |

## Screens, knobs and encoder

| Control | Function | Status |
|---|---|---|
| D1–D8 (buttons above the screens) | Context functions shown on the screen under each button | 🟡 (sampling and sampler pages) |
| Knobs 1–8 | Parameters shown on the screen above each knob. Touching a knob shows its value. | 🟡 (fixed: volume, sensitivity, tempo, swing) |
| 4D encoder turn | Context value: list scrolling, or the selected encoder mode | 🟡 (tempo on home, scroll in browser) |
| 4D encoder push / tilt | Confirm / navigate | ✅ (browser) |
| VOLUME (Velocity) | Encoder adjusts the master volume; hold a pad or group button for just that one | 🟡 (event velocity ⬜) |
| SWING (Position) | Encoder adjusts swing | 🟡 (position ⬜) |
| TEMPO (Tune) | Encoder adjusts tempo (SHIFT: fine). SHIFT + TEMPO: encoder tunes the focused sound. | ✅ |
| NOTE REPEAT (Arp) | Hold: pads repeat (arpeggiator in KEYBOARD / CHORDS). Hold + D1 pins it. SHIFT + NOTE REPEAT: KEYBOARD with the arpeggiator. | ✅ |
| LOCK (Ext Lock) | LOCK: quick snapshot of every sound / effect / mixer setting, press again to recall. SHIFT + LOCK: 16 snapshots on the pads (empty pad stores, pad recalls, ERASE + pad deletes, D5 updates), K1 morph on / off, K2 morph time in bars | ✅ |

## Touch strip

| Control | Function | Status |
|---|---|---|
| PITCH | Strip bends the focused sound ±2 semitones (springs back) | ✅ |
| MOD | Strip closes a low-pass filter on the focused sound (stays where left) | ✅ |
| PERFORM (FX Select) | Strip sweeps a filter on the focused group: left low-pass, right high-pass (off when released) | ✅ (more effects ⬜) |
| NOTES | Strip plays the keyboard layout's notes on the focused sound as you slide | ✅ |

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

**FM pages** (SOURCE = FM: 6-operator FM from the Dexed engine; DX7-compatible voices)

| Page | K1 | K2 | K3 | K4 | K5 | K6 |
|---|---|---|---|---|---|---|
| VOICE | Tune | Volume | Pan | Polyphony | | |
| FM | Bank (ROM 1–3 built-in, USER 1–16) | Patch (by name) | Brightness (modulator levels) | Attack | Release | Feedback (patch / 0–7) |

The 96 built-in voices are the DX7-format banks that ship with Plaits. Put your own 32-voice
DX7 cartridges (.syx) in `Download/<app folder>/DX7`; up to 16 load as USER 1–16 at start.
FM notes keep playing their release after note-off, like the original instrument.

**Trigger:** ONE-SHOT plays the whole sample; GATE plays only while the pad is held (then
fades with RELEASE). Sequencer notes last one step.

**Effects** (sound, group and master slots)

| Effect | Parameters |
|---|---|
| Compressor | Threshold, ratio, attack, release, knee, makeup, mix (gain reduction shown on the scope) |
| Chorus | Rate, depth, mix, delay, feedback, width |
| Delay | Time (ms, or note values when synced), sync, feedback, mix, ping-pong, low cut, high cut, modulation |
| Reverb | Character (DENSE / LUSH / VAST), pre-delay, size, decay, high cut, low cut, diffusion, modulation, mix, freeze, width |

## Patterns and scenes

- Every group has 64 pattern slots in 4 banks of 16 (D7 / D8 change bank in PATTERN and SCENE mode); a pattern is 1 step to 4 bars of 16ths (K1 in PATTERN mode,
  16 steps at a time; hold SHIFT for single steps).
- Choosing a pattern while playing starts it when the playing one ends (its pad blinks until
  then); stopped, it switches at once. All groups keep playing together.
- 64 scenes (4 banks): each remembers one pattern (or none) per group. Launching a scene switches every
  group at the end of its pattern.
- PATTERN mode: D5 create (next free slot), D6 delete, K4 length. SCENE mode: D2 update the
  scene with the patterns playing now, D5 create, D6 delete. Mode buttons are momentary; hold +
  D1 pins them. Double: SHIFT + DUPLICATE; clear: SHIFT + pad 9.
- STEP mode: ◀ ▶ move through the bars of longer patterns; FOLLOW makes the page follow the
  playhead. Recording into a group without a pattern creates one. Hold a step and turn the
  encoder: VOLUME active = its velocity, SWING = its position, TEMPO = its pitch (an existing
  step is removed on release unless it was edited).
- Live recording keeps the timing you played (not rounded to the step) and the length you held
  the pad; SHIFT + pad 5 / 6 quantise.
- Songs (patterns, scenes, mixer settings) are kept automatically and saved in projects (FILE).

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
| NOTE REPEAT (hold) | Held pads repeat; in KEYBOARD / CHORDS: the arpeggiator |
| SHIFT + NOTE REPEAT | From pad mode: KEYBOARD with the arpeggiator |
| While NOTE REPEAT is held: D1 | Pin (stays on after release; press NOTE REPEAT again to stop) |
| While held: D5–D8 | Four rate presets; K3 changes the selected one (1/4 … 1/32T) |
| While held (arp): D3 / D4 | Hold (notes keep playing after release) / gate back to 100 % |
| While held: K2 / K6 / K8 | Arp type (up, down, up-down, random, as played, chord) / arp octaves (1–4) / gate (5–200 %) |

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
