# Third-party notices

Open MK3 is GPL-3.0. It includes the following third-party code under GPL-compatible
licences. The original licence headers are kept in every file.

## Mutable Instruments Plaits and stmlib (MIT)

- Location: `core/src/main/cpp/third_party/mutable/`
- Upstream: https://github.com/pichenettes/eurorack and https://github.com/pichenettes/stmlib
- Copyright Emilie Gillet. Used under the MIT licence.
- Used for the SYNTH sound source (24 synthesis models).
- Changes are listed in `core/src/main/cpp/third_party/mutable/README.md`.

```
Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in
all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
THE SOFTWARE.
```

## Dexed FM engine (Apache-2.0 / GPL-3.0)

- Location: `core/src/main/cpp/third_party/dexed/`
- Upstream: https://github.com/asb2m10/dexed
- The msfa engine is Copyright 2012 Google Inc. (Apache License 2.0, "Music Synthesizer for
  Android"), with changes Copyright Pascal Gauthier and the Dexed contributors (GPL-3.0).
- Used for the FM sound source (6-operator FM, DX7-compatible voices and cartridges).
- Changes are listed in `core/src/main/cpp/third_party/dexed/README.md`.

The effects (compressor, chorus, delay, reverb) are original Open MK3 code (GPL-3.0).

## OB-Xf synth engine (GPL-3.0)

The POLY sound source uses the synthesizer engine of OB-Xf
(https://github.com/surge-synthesizer/OB-Xf) by the Surge Synth Team, continuing OB-Xd by
Vadim Filatov (2DaT) and the OB-Xd contributors, licensed under the GNU General Public License
v3. Source and changes: `core/src/main/cpp/third_party/obxf/` (see its README.md). It brings:

- sst-basic-blocks (https://github.com/surge-synthesizer/sst-basic-blocks), GPL-3.0.
- SIMDe (https://github.com/simd-everywhere/simde), MIT License, Copyright (c) 2017 Evan
  Nemerson and the SIMDe contributors.
- {fmt} (https://github.com/fmtlib/fmt), MIT License, Copyright (c) 2012 - present, Victor
  Zverovich and {fmt} contributors.

The OB-Xf factory patches are CC0 / public domain, by their authors (named in each patch).
