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
| REC | Toggle recording (starts playback if stopped), on every page whose pads play. Hold REC while not recording: RECORD PREPARE (D5 1 bar, D6 2 bars, D7 4 bars, D8 AUTO or K4 LENGTH; D2 metronome); releasing arms REC and PLAY starts the take. AUTO: an empty 1-bar pattern grows a bar at a time while recording, and is trimmed to its last bar with notes when recording stops | ✅ |
| SHIFT + REC (Count-in) | Record with a one-bar count-in (clicks) | ✅ |
| STOP | Stop. Pressed again while stopped: every sound fades out (stops long samples and tails). | ✅ |
| RESTART | Restart playback from the beginning of the loop (song mode: from section 1) | ✅ |
| SHIFT + RESTART (Loop) | Loop the playing song section on / off | ✅ |
| ERASE + pad | Erase that sound's events in the pattern | ✅ |
| SHIFT + ERASE (Replace) | Replace on / off (ERASE blinks): while recording, the first hit of a sound in each pass of the loop clears that sound's old notes | ✅ |
| TAP | Tap tempo | ✅ |
| SHIFT + TAP (Metro) | Metronome on / off | ✅ |
| FOLLOW | Step pages follow the playhead | ✅ |
| SHIFT + FOLLOW (Grid) | Grids: D2 perform (when a launched scene takes over: at the scene's end, on the next bar, 1/2, 1/4, 1/8, 1/16, or at once), D3 arrange (pattern length steps), D4 step (what a STEP pad is: 1/4 … 1/64, triplets), D5 nudge (EVENTS position); pads choose; K5 INPUT QUANTIZE (live recorded notes land on the STEP grid); FOLLOW: back | ✅ |

## Pad modes (buttons above the pads)

| Control | Function | Status |
|---|---|---|
| PAD MODE | Pads play the group's 16 sounds. From any other page it goes to the pad screen (SHIFT + PAD MODE / KEYBOARD / CHORDS switch the pads without leaving the page). Press: settings on the screens (D5–D8 base key, K1 choke group and K2 link group of the focused sound; both work within the group only); press again: hide them | ✅ |
| KEYBOARD | Pads play the focused sound as notes. Press: switch and show the settings; press again: hide them (pads stay); SHIFT + KEYBOARD: switch without settings. PAD MODE returns to sounds. | ✅ |
| CHORDS | Pads play chords of the focused sound: built from the scale (harmonizer) or from a chord set | ✅ |
| STEP | Pads become the 16 steps of the focused sound, step 1 on pad 1 (bottom-left) counting up row by row; the playhead chases through them. 4D encoder ▲ ▼ chooses the sound, ◀ ▶ the 16-step page. An unlit pad adds a step (base key, pad velocity, one step long) and selects it; a lit pad removes it (unless you edited it while holding). The knobs edit the held steps (several at once: (MULTI)), else the last step created: K1 POSITION (by the nudge grid; SHIFT fine), K2 PITCH (you hear it), K3 VELOCITY, K4 LENGTH (by the step grid; SHIFT fine); held + encoder also works (VOLUME = velocity, SWING = position, TEMPO = pitch). A step pad lights for any note starting within its step. With the pads in KEYBOARD (KEYBOARD, then STEP) the right screen shows the focused sound's notes on 16 pitch rows instead (K7 scrolls the pitches). Per-step modulation: hold step pads, press PLUG-IN and turn a parameter: it is set for those steps only (automation; the next step returns to the previous value) | ✅ |
| FIXED VEL | Pads ignore pressure (full velocity), on every page | ✅ |
| SHIFT + FIXED VEL (16 Vel) | The 16 pads play the focused sound at 16 velocities (from pad mode) | ✅ |

## Pattern and arrangement buttons (left of the pads)

| Control | Function | Status |
|---|---|---|
| SCENE | Hold: scenes on the pads (select / launch on the perform grid; empty pad = new scene from the patterns playing). K1 RETRIGGER: off (default) a scene switched mid-pattern carries on from the same position, on it starts from the top. Hold + D1 pins the mode. | ✅ |
| SHIFT + SCENE (Section) | Song view (same as ARRANGER) | ✅ |
| PATTERN | Hold: patterns of the focused group on the pads (select, empty pad = new). K4 length (SHIFT: fine), D5 create, D6 delete. Hold + D1 pins the mode. | ✅ |
| EVENTS | Hold (D1 pins). Pads select / deselect all notes of a sound (dim: has notes, lit: all selected), D5 all, D6 none, D7 / D8 (or ▲ ▼) previous / next sound, ERASE + pad erases. D2 EDIT / SELECT. SELECT: K1 START / K2 END select the focused sound's notes in that range (step grid), K4 EVENT picks single notes, ◀ ▶ previous / next note. EDIT: K1 POSITION (nudge grid; SHIFT fine), K2 PITCH, K3 VELOCITY, K4 LENGTH (step grid; SHIFT fine) on the selected notes only ((MULTI) when several); ◀ ▶ page. Selected notes are outlined on the right screen. SHIFT + pads act on the selected notes (none selected: the whole group): 5 / 6 quantise / 50 %, 7 / 8 nudge, 9 clear, 11 copy, 12 paste (same places, also into another pattern), 13–16 transpose; 1 / 2 undo / redo | ✅ |
| VARIATION | Hold (D1 pins): HUMANIZE varies the focused sound's velocity (K1, K2 probability) and timing (K3); RANDOM writes new random notes (density, velocity, note range); D8 applies | ✅ |
| SHIFT + VARIATION (Navigate) | NAVIGATE (until VARIATION again): D3 SONG, D4 MIXER, D5 BROWSER, D8 FOLLOW; pads 1 / 3 scroll and 2 / 6 zoom the pattern grid (16, 32 or 64 steps across), K5 zoom, K6 scroll. SHIFT + D2 PAGE NAV: the focused sound's parameter pages on the pads, a pad opens PLUG-IN on that page | ✅ |
| DUPLICATE + pad | Hold, hit the source pad, then the target: duplicates a sound (pad mode), pattern or scene | ✅ |
| SHIFT + DUPLICATE (Double) | Double the pattern length and its content | ✅ |
| STEP | From any page: the sequencer with the pads as steps (PAD MODE leaves it) | ✅ |
| SELECT + pad | SELECT mode (hold SELECT; SELECT + D1 pins it, SELECT again unpins). A pad focuses its sound without playing it. MULTI off (D8): a pad outside the selection selects only that sound, a pad inside it only moves the focus. MULTI on: pads add sounds to the selection or take them out (the focused one stays). D5 ALL selects the group's 16 sounds, D6 NONE keeps only the focused one. Pads: focused brightest, selected bright (empty ones white), loaded dim; the right screen shows the group with the selection outlined. Every edit of the focused sound then applies to all selected sounds: sound parameters (PLUG-IN, CHANNEL volume / pan, choke and link groups), the same effect in the same slot, an effect chosen in a slot, POLY parameters. Knob-type values move by the same amount, switch-type values (reverse, loop, choke group, …) are copied. Focusing a sound outside the selection moves the selection to it | ✅ |
| SOLO + pad | Solo a sound within its group (or a group with group buttons) | ✅ |
| MUTE + pad | Mute a sound (or a group with group buttons); while held, audible pads are fully lit and muted ones half lit | ✅ |
| SHIFT + MUTE (Choke) | Choke all notes: every sound stops at once (nothing is muted; the next notes play normally). Choke / link groups are on the PAD MODE page | ✅ |

## Groups

| Control | Function | Status |
|---|---|---|
| GROUP A–H | Focus a group. Pads then play its 16 sounds; every group's pattern plays together. | ✅ |
| MUTE / SOLO + group | Mute / solo a whole group | ✅ |
| SHIFT + group | Group colour (pick on the pads) | ✅ |
| DUPLICATE + group + group | Copy a whole group (sounds, samples, synth patches, zones, patterns, mixer) to another | ✅ |
| ERASE + group | Clear a group: built-in sounds, no patterns (UNDO brings it back) | ✅ |
| SHIFT + SELECT | Colour of the focused sound (or AUTO = group colour) | ✅ |

## SHIFT + pad shortcuts (printed on the pads)

They work in every pad mode (PAD, KEYBOARD, CHORDS, STEP) and on every page that doesn't use the pads for something else. In KEYBOARD / CHORDS, pads 13–16 move the keyboard layout instead.

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
| CHANNEL (MIDI) | Channel properties. D1 MASTER / D2 GROUP / D3 SOUND, ◀ ▶ OUTPUT / GROOVE. OUTPUT: K1 volume, K2 pan. GROOVE: K1 swing (master: the project swing; group: extra swing added for that group; sound: extra swing for that sound) | ✅ |
| PLUG-IN (Instance) | Plug-in chains of the master, the focused group and the focused sound (D1 again: RETURN A, RETURN B) | ✅ (full app) |
| ARRANGER | Song view: a list of sections, each playing a scene. D1 song mode on / off, D2 loop, D3 jump to the selected section, D5 duplicate, D6 delete; encoder or ◀ ▶ select, K1 moves a section, K2 sets its length (AUTO = its scene's longest pattern, or 1–64 bars: longer loops the patterns, shorter cuts to the next section); scene pads add a section after the selected one (SHIFT + pad replaces it). A section lasts as long as its scene's longest pattern, and all groups start their patterns together at each section. In song mode, a scene pad in SCENE mode jumps to that scene's section | ✅ |
| MIXER | Mix mode: 8 channel strips with VU meters (green to -12 dB, yellow to 0 dB, red above, peak hold) and a master meter. 4D encoder ▲ groups, ▼ the focused group's sounds (◀ ▶: 1–8 / 9–16). D1–D8 focus the channel above them; MUTE / SOLO + D1–D8 mute / solo it. Knobs set each channel's shown parameter; encoder push cycles VOLUME → PAN → SEND A → SEND B (groups, post-fader); encoder turn sets it for the focused channel (SHIFT: master volume). Pads and group buttons focus too (the strips follow). SHIFT + D7 / D8: the returns' effects. Returns start as a reverb (A) and a delay (B), fully wet | ✅ |
| BROWSER | Browse samples, saved sounds (♪, onto the focused pad) and saved groups (▦, into the focused group, replacing it) | ✅ |
| SHIFT + BROWSER (+Plug-in) | Instrument browser: POLY factory patches by category, FM banks and patches, SYNTH models; loading switches the focused sound's source and plays a note | ✅ (full app) |
| SAMPLING | Record a sample (controller inputs or phone playback), edit, slice, zones (D4; also SHIFT + SAMPLING) | ✅ |
| ◀ ▶ | Page through parameter pages on the screens | 🟡 (pages on the PLUG-IN page; steps through sounds on the home page) |
| FILE (Save) | Projects: save, SAVE AS… (a suggested name such as "Velvet Drift 126", editable on the controller with the 4D encoder: turn = letter, tilt ◀ ▶ = cursor, ▲ ▼ = upper / lower case, push = OK; hold SHIFT for a grid of letters, digits and symbols: tilt / turn moves the highlight, releasing SHIFT enters it at the cursor, push (SHIFT held) types it and moves on; D5 another suggestion, D6 delete, D7 cancel, D8 OK), RENAME (project), RENAME SOUND, RENAME GROUP (names shown on the screens instead of the sample name / letter), SAVE SOUND (the focused sound: sample, settings, synth patch, zones, effects, in `Sounds/`; listed as SOUND → pad, one push loads it onto the focused pad), SAVE GROUP (the focused group as a kit with its patterns, in `Groups/`; listed below the projects as GROUP → X: push twice to load it into the focused group), EXPORT AUDIO (K1 the LOOP playing ×1/2/4/8 on K2, or the SONG from section 1; D8 plays it from the top and records the master as heard into `Exports/<project> loop 001.wav`, up to 3 minutes; D7 cancels), new, load. SHIFT + FILE quick-saves. | ✅ |
| SETTINGS | D1 metronome on, K1 level, K2 count-in length · D5 OUTPUT: the MK3 or the PHONE (its speaker or headphones, Bluetooth included; the MK3 inputs still record) · D6 PHONE AUDIO: while phone audio is captured for sampling, play it through the MK3 and mute the phone's speaker (default on) · D8 LED check | ✅ |
| AUTO | Hold (SHIFT + AUTO pins) and turn knobs while playing: sound parameters (PLUG-IN pages, MIXER volume / pan) are recorded per step into the pattern (16 lanes per pattern). ERASE + turn a knob removes that parameter's automation. Touch auto-write: while AUTO is on and playing, just touching a PLUG-IN knob keeps writing its current value at the playhead (overwriting) until you let go | ✅ |
| MACRO (Set) | 8 macro knobs per group, each driving one sound parameter. SHIFT + MACRO: D1–D8 pick a macro, then turn any parameter on the PLUG-IN page to assign it; ERASE + D-button clears | ✅ |

## Screens, knobs and encoder

| Control | Function | Status |
|---|---|---|
| D1–D8 (buttons above the screens) | Context functions shown on the screen under each button | 🟡 (sampling and sampler pages) |
| Knobs 1–8 | Parameters shown on the screen above each knob. Touching a knob shows its value. | 🟡 (fixed: volume, sensitivity, tempo, swing) |
| 4D encoder turn | Context value: list scrolling, or the selected encoder mode | 🟡 (tempo on home, scroll in browser) |
| 4D encoder push / tilt | Confirm / navigate | ✅ (browser) |
| VOLUME (Velocity) | Encoder adjusts the master volume; hold a pad or group button for just that one. SHIFT + VOLUME: the encoder changes the velocity of the focused sound's notes (SHIFT: fine) | ✅ |
| SWING (Position) | Encoder adjusts swing. SHIFT + SWING: the encoder moves the focused sound's notes by the nudge grid (SHIFT: fine) | ✅ |
| TEMPO (Tune) | Encoder adjusts tempo (SHIFT: fine). SHIFT + TEMPO: encoder tunes the focused sound. | ✅ |
| NOTE REPEAT (Arp) | Hold: pads repeat (arpeggiator in KEYBOARD / CHORDS); repeats and arp notes swing with the master and group swing (the swing is not recorded: the pattern swings them when it plays). Hold + D1 pins it. SHIFT + NOTE REPEAT: KEYBOARD with the arpeggiator. | ✅ |
| LOCK (Ext Lock) | LOCK: quick snapshot of every sound / effect / mixer setting and mute / solo, press again to recall. SHIFT + LOCK: 64 snapshots in 4 banks of 16 on the pads (D7 / D8 bank) (empty pad stores, pad recalls, ERASE + pad deletes, D5 updates), K1 morph on / off, K2 time / grid in bars, K3 MODE: TRAVEL (starts at once, lasts that long) or TARGET (lands on the next downbeat of that grid while playing); D6 DELETE then a pad | ✅ |

## Touch strip

| Control | Function | Status |
|---|---|---|
| PITCH | Strip bends the focused sound ±2 semitones (springs back) | ✅ |
| MOD | Strip closes a low-pass filter on the focused sound (stays where left) | ✅ |
| PERFORM (FX Select) | Strip plays the perform FX while touched (position = amount, off when released): FILTER on the focused group (left low-pass, right high-pass), or on the master STUTTER (repeats the last 1/4 · 1/8 · 1/16 · 1/32), TAPE STOP, TRANCE GATE (1/16 chops), LO-FI, FLANGER (the strip sweeps it), BURST ECHO (dotted 1/8, more repeats to the right), RING (30 Hz – 2 kHz), TREMOLO (1/4 … 1/32 on the beat), SCRATCHER (the strip scratches the last beat) | ✅ |
| SHIFT + PERFORM | Choose the perform FX (pads 1–5) | ✅ |
| NOTES | Strip plays the keyboard layout's notes on the focused sound as you slide | ✅ |

## Sampling sources

| Source | Status |
|---|---|
| Controller audio inputs | ✅ |
| Phone playback, i.e. whatever another app plays | ✅ (Android playback capture: confirm once per session via the notification on the phone; apps that block capture record as silence) |

## Sampling page (SAMPLING)

D1–D4 choose the tab (RECORD, EDIT, SLICE, ZONE). Hold SHIFT for fine knob steps. In EDIT and
SLICE: encoder turn zooms the waveform around the marker you moved last, tilting the encoder
left / right scrolls, D5 on the first page toggles zero-crossing snap (start, end and slice
points), and a playhead follows playback.

| Tab | Controls |
|---|---|
| RECORD | D5 source (controller INPUT / PHONE playback / MASTER: resampling Open MK3's own output, sample-accurate with SYNC) · D6 start: MANUAL, THRESHOLD (starts when the level crosses K1) or SYNC (starts exactly on the next bar the sequencer plays, compensating for the audio latency; press PLAY if stopped) · D8 or encoder push start/stop · K1 threshold · K2 max length (up to 30 s; SYNC: 1, 2, 4, 8 or 16 bars at the song tempo, or FREE until stopped) · pads choose the target pad. When recording stops the take is saved as a WAV in Recordings, loaded onto the target pad and opened in EDIT |
| EDIT, page 1 PLAY RANGE | The new take, or the focused pad's sample (pads choose which) · K1 start · K2 end (the pad plays this range at once; the file is unchanged) · K5 trigger of the pad (one-shot / gate) · D7 preview (press again to stop; with gate, only while held) · D8 save the range as a new WAV in `Download/<app folder>/Recordings` and load it onto the focused pad · ▶ page 2 |
| EDIT, page 2 FUNCTIONS | K1 / K2 selection start / end · K3 or D5 / D6 choose the function: TRUNCATE (keep the selection), NORMALIZE, REVERSE, FADE IN, FADE OUT (equal-power), DC FIX, SILENCE, CUT, COPY, PASTE (inserts at the selection start), DUPLICATE (repeats the selection after itself), STRETCH (K4: 25–400 % length, same pitch) · D7 preview the selection · D8 apply · SHIFT + D8 undo (4 steps). On a pad's sample every change is written to "<name> edit.wav" in Recordings and loaded on the pad; the original file is kept |
| SLICE, page 1 SLICER | K1 mode: SPLIT (K2: 4/8/16/32 equal slices), GRID (K2: 1/4–1/32 note values at the sample's tempo), DETECT (K2 sensitivity; D5 snaps to zero crossings), MANUAL (K2 auto-snap: live slice points move to the nearest hit) · K3 tempo AUTO (from the sample's length) / MANUAL · K4 adjust: detected, half or double / the BPM · pads play the slices (selected: fully lit); more than 16: SHIFT + D7 / D8 previous / next bank · D7 preview · D8 APPLY… |
| SLICE, page 2 EDIT | K1 slice (or press its pad) · K3 start (a joined previous slice's end moves with it) · K4 end (on its own: gaps and overlaps are allowed) · D5 split (the selected slice in half, or at the playhead while the sample plays) · D6 remove (join with the previous slice) · D7 delete all · editing switches the mode to MANUAL |
| SLICE, live slicing | MANUAL with no slices: pad 1 flashes; press it to play the sample, then press each next flashing pad where a new slice should start |
| SLICE, page 3 APPLY | K1 MONO (applied to a group: one voice per pad and all pads in choke group 1) · K2 PATTERN: REPLACE writes notes for the slices into the pattern so it plays the loop back at the song tempo, NONE leaves it |
| SLICE, APPLY… | D5 to the SOUND (focused pad: the slices go on its keys as zones, each on the key its pad plays in KEYBOARD mode so pad 1 = slice 1; pads switch to KEYBOARD) · D6 to the GROUP (slices 1–16 on pads 1–16; group buttons choose the group) · pads choose the sound · D7 cancel · D8 OK. Slices are saved as WAVs in `Recordings/<name> slices` |

**ZONE tab (SAMPLING D4, or SHIFT + SAMPLING)**: up to 16 extra samples per sound, each played
for a key range (transposed from its root key) and a velocity range; other notes play the pad's
own sample. Add zones with D8 / the BROWSER: D7 adds the selected WAV to the target pad, D8 adds
every WAV in the folder and maps them (root keys are read from file names such as `Piano C3` or
`Bass_F#1`, 60 = C3); SLICE → APPLY to a sound also makes zones. Encoder: select a zone ·
◀ ▶ pages: MAP (K1 root, K2 / K3 lowest / highest key, K4 / K5 lowest / highest velocity) ·
PLAY RANGE (K1 start, K2 end of the zone's sample) · LOOP (K1 as the sound / off / on, K2 loop
start, K3 loop end, K4 crossfade) · TUNE / MIX (K1 tune, K2 level, K3 pan) · ENVELOPE (K1 attack,
K2 decay: as the sound, or the zone's own) · D1–D3 other tabs · D5 auto-map (ranges halfway
between roots) · D6 delete · D7 delete all · D8 add. Play them from KEYBOARD mode. Zones and
their settings are saved with the kit and in projects.

## PLUG-IN (full app)

| Control | Function |
|---|---|
| D1 / D2 / D3 | MASTER / GROUP / SOUND level |
| Encoder turn / D4 | Choose the slot (shown side by side, each with an icon; "+" = empty). Sound: slot 1 is the source (SAMPLER, SYNTH, FM or POLY), slots 2–4 effects. Group and master: 4 effect slots. SHIFT + encoder: zoom the waveform / scope |
| D5 | Bypass the selected effect |
| D6 or encoder push | Oscilloscope over the other views (the selected sound, group or master) |
| ◀ ▶ | Parameter pages of the selected slot |
| Knobs | Parameters. SHIFT: fine steps. SHIFT + touching a knob: default. On an effect slot, K1 on the first page chooses the effect. |
| D8 | Sampler SAMPLE page: ZERO-X snap for start, end, loop start and loop end (turning it on also moves the current markers to the nearest zero crossings) |
| Encoder / tilt | Zoom / scroll the waveform (sampler VOICE and SAMPLE pages) |
| Pads | Focus and play a sound |

**Sampler pages**

| Page | K1 | K2 | K3 | K4 | K5 | K6 | K7 | K8 |
|---|---|---|---|---|---|---|---|---|
| VOICE | Source | Volume | Pan | Polyphony (1–16, strict: 1 = one voice, also for chords) | Legato (polyphony 1: a new key glides the playing voice instead of restarting it) | Glide time (legato) | Bend range (touch strip PITCH, ±0–24 semitones) | Engine (standard / vintage A 12-bit 26 kHz / vintage B 12-bit 40 kHz, plain or with a low, low-mid, high-mid or high output filter) |
| SAMPLE | Start | End | Reverse | Trigger (one-shot / gate) | Loop (ONE-SHOT: repeats while the pad or note is held, then plays to the end; AHD / ADSR: until the envelope ends) | Loop start | Loop end | Crossfade |
| PITCH / ENV | Tune | Type (one-shot / AHD / ADSR) | Attack | Hold (AHD) | Decay | Sustain (ADSR) | Release (ADSR) | |
| FX / FILTER | Compressor (4:1, threshold and make-up follow the amount) | Drive | Sample-rate reduction | Bit reduction | Filter (off / LP 12 / BP / HP / LP 24 / EQ) | Cutoff (EQ: frequency) | Resonance (EQ: bandwidth) | EQ gain (±24 dB) |
| MOD ENV | Attack | Hold (AHD) | Decay | Sustain (ADSR) | Release (ADSR) | | | |
| MOD DEST | → Pitch | → Cutoff | → Drive | → Pan | | | | |
| LFO | Wave | Sync (free in Hz / retrigger on each note / locked to the song) | Rate (free) | Speed as a note value (synced) | Start phase | → Pitch | → Cutoff | → Drive |
| LFO DEST | → Pan | → Volume | | | | | | |
| VEL / MOD | Velocity → start (positive: later when harder; negative: later when softer) | Velocity → decay | Velocity → cutoff | Velocity → volume | Mod wheel → start | Mod wheel → cutoff | Mod wheel → LFO depth | Mod wheel → pan |

Parameters that do nothing with the current settings (hold with ADSR, glide without legato, EQ
gain with another filter, rate when the LFO is synced, …) are shown dim.

**Synth pages** (24 models from Mutable Instruments Plaits: analog, waveshaping, FM, granular,
additive, wavetable, chords, speech, swarm, noise, particles, string, modal, drums, …)

| Page | K1 | K2 | K3 | K4 | K5 | K6 | K7 | K8 |
|---|---|---|---|---|---|---|---|---|
| VOICE | Source | Volume | Pan | Tune | Polyphony | Trigger | Base note (the pitch the pad plays in PAD mode, default C3 = 60; in KEYBOARD the C3 key plays it) | |
| SYNTH | Model | Harmonics | Timbre | Morph | Synth gate (the model's own decaying gate) | Decay | Colour | Output (main / aux) |
| SYNTH ENV | Envelope → pitch | → timbre | → morph | | | | | |

AMP ENV, FILTER, MOD ENV, LFO and VELOCITY work as for the sampler. With the synth gate off,
the AMP ENV shapes the note (and the sound stops when the pad is released).

**FM pages** (SOURCE = FM: 6-operator FM from the Dexed engine; DX7-compatible voices)

| Page | K1 | K2 | K3 | K4 | K5 | K6 |
|---|---|---|---|---|---|---|
| VOICE | Source | Volume | Pan | Tune | Polyphony | |
| FM | Bank (ROM 1–3 built-in, USER 1–16) | Patch (by name) | Brightness (modulator levels) | Attack | Release | Feedback (patch / 0–7) |

The 96 built-in voices are the DX7-format banks that ship with Plaits. Put your own 32-voice
DX7 cartridges (.syx) in `Download/<app folder>/DX7`; up to 16 load as USER 1–16 at start.
FM notes keep playing their release after note-off, like the original instrument.

**POLY pages** (SOURCE = POLY: the OB-Xf engine, a polyphonic two-oscillator analog-style synth)

| Page | Knobs |
|---|---|
| VOICE | K1 source, K2 preset category, K3 preset (493 factory patches), K5 volume, K6 pan, K7 tune |
| OSC | Osc 1 pitch, osc 2 pitch, osc 2 detune, pulse width, osc 1 saw / pulse, osc 2 saw / pulse |
| OSC MOD | Sync, cross mod, brightness, osc 2 PW offset, filter env → pitch, filter env → PW, osc 2 keytrack, env → both oscs |
| MIXER | Osc 1, osc 2, ring mod, noise, noise colour, patch volume, transpose, tune |
| FILTER | Cutoff, resonance, env amount, keytrack, mode, 4-pole, Xpander, Xpander mode |
| FILTER ENV / AMP ENV | Attack, decay, sustain, release, attack curve, velocity (and invert / BP blend, glide / push) |
| LFO 1 / LFO 2 | Rate, three waveforms, two amounts, destinations (more on the MORE pages: sync, PW, other targets) |
| VOICING | Polyphony, unison, unison voices, unison detune, legato, note priority, reassign, HQ |
| CONTROL | Bend up / down, bend osc 2 only, vibrato wave / rate, env inverts |
| SLOP / VOICE PAN | Per-voice analog variation and the pan of voices 1–8 |

A POLY sound plays its own polyphony (set in the patch, up to 8 voices and 4 unison voices on the phone; the high-quality oversampling mode stays off; the sound's POLYPHONY does not apply)
with its own envelopes and filter; the sound's volume, pan, effects, the PITCH strip (patch bend
range) and MOD strip (vibrato) apply. Up to four sounds can use POLY at once (the quietest one is
taken when a fifth plays). Stepped switches move one step per knob notch. Turning through presets shows their names; the patch loads when the knob rests. If the audio engine runs late repeatedly, the oldest POLY note is released so the app stays responsive.

**Trigger:** ONE-SHOT plays the whole sample; GATE plays only while the pad is held (then
fades with RELEASE). Sequencer notes last one step.

Oscilloscope (the SCOPE view of synth and effect pages; D6 or encoder push shows it over the other views): it detects the pitch actually sounding and shows its note, cents and Hz (60 = C3). D7 switches LOCKED (default: two cycles of the detected pitch that stand still; SHIFT + encoder shows more cycles) and FREE (the output scrolling as it plays; SHIFT + encoder sets the time window). At sound level D8 chooses SOURCE (the instrument or sampler, before the sound's effects) or OUTPUT (the pad after its effects).
Effect knobs glide (~20 ms) instead of jumping, so turning them while playing does not click.
A sound switched to SYNTH or FM gets 8 voices if it had 1.

**Effects** (sound, group and master slots)

| Effect | Parameters |
|---|---|
| Compressor | Threshold, ratio, attack, release, makeup, mix, knee, SIDECHAIN (OFF or a group A–H: that group drives the compression, e.g. a kick group ducking the bass; gain reduction shown on the scope) |
| Gate | Threshold, range, attack, hold, release, side-chain (a group opens it) |
| Limiter | Input gain, ceiling, release (1.5 ms look-ahead, never above the ceiling) |
| EQ | Low shelf, two peaks (frequency, gain, Q), high shelf |
| Flanger | Mix, rate, depth, feedback, delay, stereo |
| Phaser | Mix, rate, depth, feedback, stages (2–12), centre frequency |
| Distortion | Drive, type (soft / hard / fold / tube), tone, mix, output |
| Lo-fi | Sample rate, bits, mix |
| Transient | Attack, sustain (±12 dB), output |
| Saturator | Drive, warmth, tone, mix (tape-like, level-compensated) |
| Tremolo | Depth, rate (or synced 1/1–1/32), shape (sine / triangle / square), mode (tremolo / auto-pan) |
| Freq shift | Shift (±2000 Hz), mix, stereo (the right side shifts the other way) |
| Ring mod | Frequency, mix |
| Chorus | Mix, rate, depth, delay, feedback, width |
| Delay | Mix, time (ms, or note values when synced), feedback, sync, ping-pong, low cut, high cut; page 2: modulation |
| Reverb | Mix, decay, size, character (DENSE / LUSH / VAST), pre-delay, high cut, low cut; page 2: diffusion, modulation, width, freeze |
| Filter | Cutoff, resonance, mode, drive, mix, morph, envelope follow; page 2: LFO rate, LFO depth. Modes: LADDER LP24 / LP12 / HP24 (Moog-style 4-pole ladder, saturating) and SEM LP / BP / HP / NOTCH / MORPH (Oberheim SEM-style 2-pole state-variable; MORPH sweeps low-pass → notch → high-pass) |

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
pattern (up to 4 notes per step). A sound plays as many notes at once as its POLYPHONY allows
(1: one note, chords included). 60 (C3) plays a sample at its own pitch; for a synth, BASE NOTE
sets what C3 plays.

## NOTE REPEAT and arpeggiator

| Control | Function |
|---|---|
| NOTE REPEAT (hold) | Held pads repeat; in KEYBOARD / CHORDS: the arpeggiator |
| SHIFT + NOTE REPEAT | From pad mode: KEYBOARD with the arpeggiator |
| While NOTE REPEAT is held: D1 | Pin (stays on after release; press NOTE REPEAT again to stop) |
| While held: D5–D8 | Four rate presets; K3 changes the selected one (1/4 … 1/32T) |
| While held (arp): D3 / D4 | Hold (notes keep playing after release) / gate back to 100 % |
| While held: K2 / K6 / K8 | Arp type (up, down, up-down, random, as played, chord) / arp octaves (1–4) / gate (5–200 %) |
| While held: K4 / K5 | Rhythm (which ticks play, e.g. ●○ or ●●○) / dynamics (as played, full, accent, ramp up, ramp down, random) — for repeat and arp |

Repeats follow the transport grid while playing (free-running when stopped) and the pad's
pressure sets their velocity. While recording, repeat and arp notes at 1/16 or slower are
written into the pattern.

## Button lights

Every button that does something on the current page is lit dim, and bright while active. PLAY, STOP and RESTART work on every page; BROWSER, SAMPLING and PLUG-IN open their pages from every page.

Every button that does something on the current page is lit: dim when available, bright when
active or selected (e.g. the open page, a held modifier, a toggle that is on). Buttons that do
nothing yet stay dark.

## Open MK3–specific controls

| Control | Function |
|---|---|
| SHIFT + PAD MODE | Toggle pad flash on hits (diagnostic) |
| Setup wizard (phone app) | Re-learn pads, buttons, encoder or LEDs |
