// Open MK3 FM voice built on Dexed's msfa engine (third_party/dexed).
#include "fm.h"

#include <algorithm>
#include <cstring>
#include <memory>

#include "msfa/controllers.h"
#include "msfa/dx7note.h"
#include "msfa/env.h"
#include "msfa/exp2.h"
#include "msfa/fm_core.h"
#include "msfa/freqlut.h"
#include "msfa/lfo.h"
#include "msfa/pitchenv.h"
#include "msfa/sin.h"
#include "msfa/tuning.h"

#include "plaits/resources.h"  // the DX7-format voices that ship with Plaits (MIT)

namespace openmk3 {

namespace {

uint8_t gUser[kFmUserBanks][32 * 128];
bool gUserLoaded[kFmUserBanks] = {};
std::shared_ptr<TuningState> gTuning;
MTSClient gMts;
FmCore gCore;

const uint8_t* packedVoice(int bank, int program) {
    program = std::clamp(program, 0, 31);
    if (bank >= 0 && bank < kFmBuiltInBanks) return plaits::fm_patches_table[bank] + program * 128;
    int u = bank - kFmBuiltInBanks;
    if (u >= 0 && u < kFmUserBanks && gUserLoaded[u]) return gUser[u] + program * 128;
    return plaits::fm_patches_table[0] + program * 128;
}

/** DX7 32-voice bulk format (128 bytes per voice) -> the 156-byte layout msfa uses. */
void unpack(const uint8_t* p, uint8_t* u) {
    for (int op = 0; op < 6; op++) {
        const uint8_t* s = p + op * 17;
        uint8_t* d = u + op * 21;
        for (int i = 0; i < 11; i++) d[i] = s[i] & 0x7f;          // rates, levels, break point, depths
        d[11] = s[11] & 3;                                         // left curve
        d[12] = (s[11] >> 2) & 3;                                  // right curve
        d[13] = s[12] & 7;                                         // rate scaling
        d[14] = s[13] & 3;                                         // amp mod sensitivity
        d[15] = (s[13] >> 2) & 7;                                  // key velocity sensitivity
        d[16] = s[14] & 0x7f;                                      // output level
        d[17] = s[15] & 1;                                         // oscillator mode
        d[18] = (s[15] >> 1) & 31;                                 // frequency coarse
        d[19] = s[16] & 0x7f;                                      // frequency fine
        d[20] = (s[12] >> 3) & 15;                                 // detune
    }
    for (int i = 0; i < 8; i++) u[126 + i] = p[102 + i] & 0x7f;    // pitch EG
    u[134] = p[110] & 31;                                          // algorithm
    u[135] = p[111] & 7;                                           // feedback
    u[136] = (p[111] >> 3) & 1;                                    // oscillator key sync
    u[137] = p[112] & 0x7f;                                        // LFO speed
    u[138] = p[113] & 0x7f;                                        // LFO delay
    u[139] = p[114] & 0x7f;                                        // pitch mod depth
    u[140] = p[115] & 0x7f;                                        // amp mod depth
    u[141] = p[116] & 1;                                           // LFO sync
    u[142] = (p[116] >> 1) & 7;                                    // LFO wave
    u[143] = (p[116] >> 4) & 7;                                    // pitch mod sensitivity
    u[144] = p[117] & 0x7f;                                        // transpose
    for (int i = 0; i < 10; i++) u[145 + i] = p[118 + i];          // name
    u[155] = 0x3f;                                                 // all operators on
}

class DexedVoice : public FmVoice {
public:
    DexedVoice() : note_(gTuning, &gMts) {
        std::memset(patch_, 0, sizeof(patch_));
        ctrls_.pitch_cc = 0x2000;
        ctrls_.pitch_range_up = 12;
        ctrls_.pitch_range_dn = 12;
        ctrls_.pitch_step = 0;
        ctrls_.masterTune = 0;
        ctrls_.modwheel_cc = 0;
        ctrls_.foot_cc = 0;
        ctrls_.breath_cc = 0;
        ctrls_.aftertouch_cc = 0;
        ctrls_.portamento_enable_cc = false;
        ctrls_.portamento_cc = 0;
        ctrls_.portamento_gliss_cc = false;
        ctrls_.core = &gCore;
        ctrls_.refresh();
    }

    void noteOn(int bank, int program, const FmEdit& e, int note, int velocity) override {
        unpack(packedVoice(bank, program), patch_);
        const int alg = patch_[134];
        for (int op = 0; op < 6; op++) {
            uint8_t* d = patch_ + op * 21;
            // operator 1 is stored last; isCarrier counts operators from 0 = OP1
            if (!FmCore::isCarrier(alg, 5 - op) && e.brightness != 0.f)
                d[16] = uint8_t(std::clamp(int(d[16] + e.brightness * 20.f), 0, 99));
            if (e.attack != 0.f) d[0] = uint8_t(std::clamp(int(d[0] - e.attack * 40.f), 0, 99));
            if (e.release != 0.f) d[3] = uint8_t(std::clamp(int(d[3] - e.release * 40.f), 0, 99));
        }
        if (e.feedback >= 0) patch_[135] = uint8_t(std::min(e.feedback, 7));
        const bool steal = note_.isPlaying();
        note_.init(patch_, std::clamp(note, 0, 127), std::clamp(velocity, 1, 127), 1, &ctrls_);
        if (patch_[136] && !steal) note_.oscSync();
        lfo_.reset(patch_ + 137);
        lfo_.keydown();
        pos_ = N;
        down_ = true;
    }

    void noteOff() override {
        if (down_) note_.keyup();
        down_ = false;
    }

    bool playing() override { return note_.isPlaying(); }

    void setBend(float semitones) override {
        ctrls_.pitch_cc = std::clamp(0x2000 + int(semitones / 12.f * 8191.f), 0, 0x3fff);
    }

    void render(float* out, int n) override {
        for (int i = 0; i < n; i++) {
            if (pos_ >= N) {
                std::memset(buf_, 0, sizeof(buf_));
                const int32_t lv = lfo_.getsample();
                const int32_t ld = lfo_.getdelay();
                note_.compute(buf_, lv, ld, &ctrls_);
                for (int j = 0; j < N; j++) {
                    int32_t v = buf_[j] >> 4;
                    int c = v < -(1 << 24) ? -0x8000 : v >= (1 << 24) ? 0x7fff : v >> 9;
                    fbuf_[j] = float(c) / 32768.f;
                }
                pos_ = 0;
            }
            out[i] = fbuf_[pos_++];
        }
    }

private:
    Dx7Note note_;
    Controllers ctrls_;
    Lfo lfo_;
    uint8_t patch_[156];
    int32_t buf_[N];
    float fbuf_[N] = {};
    int pos_ = N;
    bool down_ = false;
};

}  // namespace

void FmVoice::initTables(double sr) {
    static bool done = false;
    if (done) return;
    done = true;
    Exp2::init();
    Tanh::init();
    Sin::init();
    Freqlut::init(sr);
    Lfo::init(sr);
    PitchEnv::init(sr);
    Env::init_sr(sr);
    gTuning = createStandardTuning();
}

bool FmVoice::setUserBank(int index, const uint8_t* data, int size) {
    if (index < 0 || index >= kFmUserBanks) return false;
    const uint8_t* voices = nullptr;
    if (size == 4104 && data[0] == 0xF0) voices = data + 6;  // sysex header F0 43 0n 09 20 00
    else if (size >= 4096) voices = data;
    if (!voices) return false;
    std::memcpy(gUser[index], voices, 4096);
    gUserLoaded[index] = true;
    return true;
}

bool FmVoice::bankLoaded(int bank) {
    if (bank >= 0 && bank < kFmBuiltInBanks) return true;
    int u = bank - kFmBuiltInBanks;
    return u >= 0 && u < kFmUserBanks && gUserLoaded[u];
}

void FmVoice::patchName(int bank, int program, char out[11]) {
    const uint8_t* p = packedVoice(bank, program);
    for (int i = 0; i < 10; i++) {
        char c = char(p[118 + i] & 0x7f);
        out[i] = (c >= 32 && c < 127) ? c : ' ';
    }
    out[10] = 0;
}

FmVoice* FmVoice::create() { return new DexedVoice(); }

}  // namespace openmk3
