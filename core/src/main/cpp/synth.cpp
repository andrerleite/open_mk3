// Open MK3 synth voice built on Mutable Instruments Plaits (third_party/mutable, MIT).
#include "synth.h"

#include <algorithm>
#include <cstring>

#include "plaits/dsp/voice.h"

namespace openmk3 {

namespace {

class PlaitsVoice : public SynthVoice {
public:
    PlaitsVoice() {
        std::memset(buffer_, 0, sizeof(buffer_));
        stmlib::BufferAllocator allocator(buffer_, sizeof(buffer_));
        voice_.Init(&allocator);
    }

    void render(const SynthPatch& sp, bool gate, float* out, float* aux, int n) override {
        plaits::Patch p{};
        p.note = sp.note;
        p.harmonics = sp.harmonics;
        p.timbre = sp.timbre;
        p.morph = sp.morph;
        p.frequency_modulation_amount = sp.fmAmount;
        p.timbre_modulation_amount = sp.timbreAmount;
        p.morph_modulation_amount = sp.morphAmount;
        p.engine = std::clamp(sp.model, 0, kSynthModels - 1);
        p.decay = sp.decay;
        p.lpg_colour = sp.colour;

        plaits::Modulations m{};
        m.trigger = gate ? 1.f : 0.f;
        m.trigger_patched = true;  // drums, strings and the gate need note starts
        // Without the model's own gate, a constant level keeps the VCA open and the
        // sampler-style AMP ENV shapes the note instead.
        m.level_patched = !sp.lpg;
        m.level = 1.f;

        plaits::Voice::Frame frames[kSynthMaxBlock];
        n = std::min(n, kSynthMaxBlock);
        voice_.Render(p, m, frames, size_t(n));
        for (int i = 0; i < n; i++) {
            out[i] = frames[i].out * (1.f / 32768.f);
            aux[i] = frames[i].aux * (1.f / 32768.f);
        }
    }

private:
    char buffer_[16384];
    plaits::Voice voice_;
};

}  // namespace

SynthVoice* SynthVoice::create() { return new PlaitsVoice(); }

}  // namespace openmk3
