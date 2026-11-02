# Roadmap

Rough order of work. Items marked (V) also go into Open MK3 Vanilla; everything else is the
full app.

1. **Foundation**
   - Shared core with the two apps.
   - Default control map.
   - Release pipeline.
2. **Sampling** (V) — done in 0.2.0 (slicing later)
   - Record from the controller's inputs.
   - Record from phone playback.
   - Trim, normalise, assign to a pad.
3. **Sound parameters** — done in 0.3.0
   - Per-sound tune, volume, pan, start/end, decay/envelope, filter and reverse.
   - Shown on the screens and set with the knobs.
   - Pages via ◀ ▶.
4. **Groups** — done in 0.5.0 (patterns per group come with step 5)
   - 8 groups × 16 sounds.
   - Group buttons, group mute/solo, choke groups.
5. **Patterns, scenes, arranger**
   - Multiple patterns per group, adjustable length.
   - Scenes, then the arranger / song.
   - DUPLICATE and Double.
6. **Projects**
   - Save and load projects (FILE).
   - Undo / redo (SHIFT + pads 1–4).
7. **Event editing**
   - EVENTS mode.
   - Quantise, nudge, copy/paste (SHIFT + pads).
   - Velocity and position with SHIFT + VOLUME / SWING.
8. **Pad modes**
   - KEYBOARD and CHORDS, 16 velocities.
   - NOTE REPEAT / arpeggiator.
   - Touch-strip modes.
9. **Instruments** — synth source (Plaits) done in 0.6.0
   - High-quality open-source synthesis engines. Candidates:
     - Mutable Instruments' open-source DSP (MIT)
     - DaisySP (MIT)
     - Surge XT components (GPL-3)
   - Credited in the licence notices and labelled with our own names.
10. **Effects** — compressor, chorus, delay, reverb done in 0.6.0 (EQ, filter, saturation next)
    - Insert and send effects: filter, EQ, compressor, saturation, delay, reverb.
    - Candidates: Airwindows (MIT), DaisySP, Surge XT.
11. **Mixer and automation**
    - MIXER view, AUTO recording, MACRO.

Third-party DSP code keeps its original licence headers. It is listed in `THIRD_PARTY_NOTICES.md`
when it's added.
