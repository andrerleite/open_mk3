// Open MK3 FM voice: 6-operator FM from Dexed's engine (third_party/dexed: msfa, Apache-2.0,
// with Dexed's changes, GPL-3.0). Keeps the Dexed headers out of the engine.
#pragma once
#include <cstdint>

namespace openmk3 {

constexpr int kFmBuiltInBanks = 3;   // the 96 DX7-format voices that ship with Plaits
constexpr int kFmUserBanks = 16;     // .syx cartridges loaded from the phone
constexpr int kFmBanks = kFmBuiltInBanks + kFmUserBanks;

/** Live edits on top of the patch (0 = as stored). */
struct FmEdit {
    float brightness = 0.f;  // -1..1: scales the modulators' output levels
    float attack = 0.f;      // -1..1: faster / slower envelope attacks (rate 1)
    float release = 0.f;     // -1..1: faster / slower releases (rate 4)
    int feedback = -1;       // 0..7, -1 = the patch's
};

class FmVoice {
public:
    /** Builds the lookup tables; call once (not on the audio thread). */
    static void initTables(double sampleRate);
    /** Installs a user cartridge (4104-byte .syx or 4096 bytes of packed voices). */
    static bool setUserBank(int index, const uint8_t* data, int size);
    static bool bankLoaded(int bank);
    /** Patch name (10 characters) of a voice. */
    static void patchName(int bank, int program, char out[11]);

    static FmVoice* create();  // allocates: never call from the audio thread
    virtual ~FmVoice() {}
    virtual void noteOn(int bank, int program, const FmEdit& edit, int note, int velocity) = 0;
    virtual void noteOff() = 0;
    virtual bool playing() = 0;
    /** Pitch bend in semitones (live). */
    virtual void setBend(float semitones) = 0;
    /** Renders [n] (<= 16) mono frames, about +-1. */
    virtual void render(float* out, int n) = 0;
};

}  // namespace openmk3
