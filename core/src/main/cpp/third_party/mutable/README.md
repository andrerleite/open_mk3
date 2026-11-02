# Mutable Instruments code (MIT)

Synthesis code from Mutable Instruments' open-source Eurorack firmware, by Emilie Gillet,
used under the MIT licence (see the header of every file and `stmlib/LICENSE`).

| Directory | Upstream | Revision |
|---|---|---|
| `plaits/` | https://github.com/pichenettes/eurorack (`plaits/dsp`, `plaits/resources.*`) | f4cfa7697095a28d292bff758beb51deb49b5a86 |
| `stmlib/` | https://github.com/pichenettes/stmlib (`dsp`, `utils` headers, `units.cc`, `random.cc`) | d18def816c51d1da0c108236928b2bbd25c17481 |

Changes made for Open MK3:

- `plaits/user_data.h`: the STM32 flash storage was replaced by a stub (no user data; every
  engine uses its built-in tables).
- Only the files needed by the Plaits voice are included. They are compiled with `TEST`
  defined, which selects stmlib's portable C code paths instead of ARM Cortex-M assembly.

Open MK3 uses the Plaits voice as the SYNTH sound source (`../../synth.cpp`). Engine names
on the controller are our own descriptions of what each model does.
