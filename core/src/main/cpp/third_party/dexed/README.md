# Dexed FM engine (msfa)

The 6-operator FM synthesis engine of Dexed (https://github.com/asb2m10/dexed, revision
fea2f0f0f2c397799f59b27eb11c038cd34b22c5), used for the FM sound source (`../../fm.cpp`).

- `msfa/`: "Music Synthesizer for Android" by Google (Apache License 2.0), with changes by
  Pascal Gauthier and the Dexed contributors (Dexed is GPL-3.0; see `LICENSE-GPL3`). Every
  file keeps its original header.

Changes made for Open MK3:

- `msfa/tuning_standard.cc` replaces Dexed's `tuning.cc` (which loads scale files through
  JUCE): standard 12-tone tuning only, using Dexed's own table.
- `shim/`: stand-ins for the MTS-ESP client (no tuning master) and the Surge tuning
  library types; `debugger/Logger.h`: logging compiled out.
- Only the voice engine is used: no plug-in, editor or cartridge manager code.

Built-in voices come from the DX7-format banks that ship with Mutable Instruments Plaits
(`third_party/mutable`, MIT). User cartridges (.syx) are read from the phone.
