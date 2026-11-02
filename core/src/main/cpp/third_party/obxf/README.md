# OB-Xf synth engine

The synthesizer engine of OB-Xf (https://github.com/surge-synthesizer/OB-Xf, revision
5c4a1f558338852b0a8fa4cab4e799635d5aa1d2), by the Surge Synth Team, continuing OB-Xd by
Vadim Filatov (2DaT) and the OB-Xd contributors. GPL-3.0 (see `LICENSE`). Open MK3 uses it for
the POLY sound source (`../../obxf.cpp`). Every file keeps its original header.

- `engine/`, `Constants.h`, `configuration.h`, `ParameterInfo.h`, `ParameterList.h`: from
  OB-Xf's `src/`. `SynthParam.h` is OB-Xf's without the JUCE plug-in parameter class.
  `Handlers.h` is the parameter-to-engine table from OB-Xf's `ParameterCoordinator.h`.
- `libs/sst-basic-blocks`: the headers used from sst-basic-blocks
  (https://github.com/surge-synthesizer/sst-basic-blocks, 38cf04ae6a16994cc6c82635ce7c206f2a0ca31d, GPL-3.0).
- `libs/simde`: the x86 headers of SIMDe (https://github.com/simd-everywhere/simde,
  65c15e6b545361f6dd89b1e3bcc76273e1344653, MIT), so the engine's SSE code runs on ARM NEON.
- `libs/fmt`: {fmt} (https://github.com/fmtlib/fmt, 4ed3535092a96ac9f56fab80df83416d51bcc78e, MIT), used header-only by
  the parameter formatting.

Changes made for Open MK3:

- `shim/`: a minimal stand-in for the few JUCE helpers the engine calls (math constants,
  clamps, a small random generator, a lock-free FIFO), written for Open MK3 (no JUCE code), and
  for the MTS-ESP client (no tuning master: standard tuning).
- `engine/AudioUtils.h` defines `getPitch` (from OB-Xf's `Utils.h`) instead of including the
  plug-in utilities; `engine/SynthEngine.h` no longer includes the patch (program) class;
  `engine/VoiceMatrix.h` drops its XML save / load (Open MK3 stores parameters itself).
- Only the engine is used: no plug-in, editor, MIDI-learn or patch-file code.

The factory patches (`core/src/main/assets/obxf/patches.json`) are OB-Xf's, released by their
authors as CC0 / public domain; converted to JSON with each patch's name, category and author.
