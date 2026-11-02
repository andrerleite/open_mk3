// Open MK3 synth voice: a thin wrapper that keeps the Mutable Instruments Plaits headers
// out of the engine (third_party/mutable, MIT licence).
#pragma once

namespace openmk3 {

struct SynthPatch {
    int model = 8;           // 0..23
    float note = 48.f;       // MIDI note (fractional)
    float harmonics = 0.5f;  // 0..1
    float timbre = 0.5f;     // 0..1
    float morph = 0.5f;      // 0..1
    float decay = 0.5f;      // 0..1, internal low-pass gate decay
    float colour = 0.5f;     // 0..1, low-pass gate brightness
    float fmAmount = 0.f;    // -1..1, internal envelope -> pitch
    float timbreAmount = 0.f;  // -1..1, internal envelope -> timbre
    float morphAmount = 0.f;   // -1..1, internal envelope -> morph
    bool lpg = true;         // true: the model's own decaying gate shapes each note
};

constexpr int kSynthModels = 24;
constexpr int kSynthMaxBlock = 24;

class SynthVoice {
public:
    static SynthVoice* create();  // allocates: never call from the audio thread
    virtual ~SynthVoice() {}
    /** Renders [n] (<= kSynthMaxBlock) mono frames to [out] (main) and [aux], about +-1. */
    virtual void render(const SynthPatch& p, bool gate, float* out, float* aux, int n) = 0;
};

}  // namespace openmk3
