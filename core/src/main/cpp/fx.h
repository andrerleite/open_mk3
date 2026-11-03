// Open MK3 effects: compressor, chorus, delay, reverb. Original code, GPL-3.0.
//
// Every effect processes interleaved stereo in place. Instances allocate all their memory
// when created (on a Java thread) and never in process(), which runs on the audio thread.
// Parameters are read live from the shared parameter block (see FxParams.kt): p[0] = type,
// p[1] = bypass, p[2..15] = the effect's own parameters.
#pragma once

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <vector>

namespace openmk3 {

constexpr int kFxParams = 16;
enum FxType { FX_NONE = 0, FX_COMPRESSOR = 1, FX_CHORUS = 2, FX_DELAY = 3, FX_REVERB = 4, FX_TYPES = 5 };

class Effect {
public:
    virtual ~Effect() {}
    /** [p] points at this slot's 16 parameters; [bpm] for tempo-synced effects. */
    virtual void process(float* io, int frames, const float* p, double bpm, float sr) = 0;
    /** Silences tails (delay lines, reverb) on the audio thread: "all sounds off". */
    virtual void reset() {}
    /** A level for the screen: gain reduction (dB) for the compressor, else 0. */
    float meter = 0.f;
};

// ---------------------------------------------------------------------------------------
// small helpers

static inline float dbToGain(float db) { return std::pow(10.f, db / 20.f); }
static inline float gainToDb(float g) { return 20.f * std::log10(std::max(g, 1e-9f)); }

/** One-pole smoother / filter coefficient for a time constant in seconds. */
static inline float onePoleCoef(float seconds, float sr) {
    return seconds <= 0.f ? 0.f : std::exp(-1.f / (seconds * sr));
}

/** One-pole low-pass coefficient for a cutoff frequency. */
static inline float lpCoef(float hz, float sr) {
    return std::exp(-2.f * 3.14159265f * std::clamp(hz, 10.f, sr * 0.49f) / sr);
}

/**
 * Parameter smoothing: knob moves glide over ~20 ms instead of jumping (a jump in a delay
 * length or a gain is heard as a click). next() is called per sample.
 */
struct Glide {
    float v = 0.f;
    bool primed = false;
    inline float next(float target, float coef) {
        if (!primed) { v = target; primed = true; }
        v = target + coef * (v - target);
        return v;
    }
};
static inline float glideCoef(float sr) { return std::exp(-1.f / (0.02f * sr)); }

/** Circular delay line with fractional (Hermite) reads. Size is a power of two. */
class DelayLine {
public:
    void init(int minSize) {
        int s = 1;
        while (s < minSize + 4) s <<= 1;
        buf_.assign(size_t(s), 0.f);
        mask_ = s - 1;
        w_ = 0;
    }
    inline void write(float x) { buf_[size_t(w_)] = x; w_ = (w_ + 1) & mask_; }
    /** Sample written [d] samples ago (d >= 1). */
    inline float tap(int d) const { return buf_[size_t((w_ - d) & mask_)]; }
    inline float read(float d) const {
        d = std::clamp(d, 1.f, float(mask_ - 3));
        int i = int(d);
        float t = d - float(i);
        float y0 = tap(i - 1 < 1 ? 1 : i - 1), y1 = tap(i), y2 = tap(i + 1), y3 = tap(i + 2);
        float c1 = 0.5f * (y2 - y0);
        float c2 = y0 - 2.5f * y1 + 2.f * y2 - 0.5f * y3;
        float c3 = 0.5f * (y3 - y0) + 1.5f * (y1 - y2);
        return ((c3 * t + c2) * t + c1) * t + y1;
    }
    int size() const { return mask_ + 1; }
    void clear() { std::fill(buf_.begin(), buf_.end(), 0.f); }

private:
    std::vector<float> buf_;
    int mask_ = 0;
    int w_ = 0;
};

/** Schroeder all-pass with a fixed-length (modulatable) delay. */
class Allpass {
public:
    void init(int maxLen) { d_.init(maxLen + 64); }
    void clear() { d_.clear(); }
    inline float process(float x, float len, float g) {
        float delayed = d_.read(len);
        float v = x + g * delayed;
        d_.write(v);
        return delayed - g * v;
    }

private:
    DelayLine d_;
};

// ---------------------------------------------------------------------------------------
// Compressor: feed-forward, stereo-linked peak detection, soft knee, parallel mix.
//  p2 threshold dB (-60..0), p3 ratio (1..20), p4 attack ms, p5 release ms,
//  p6 knee dB (0..24), p7 makeup dB (0..24), p8 mix (0..1)

class Compressor : public Effect {
public:
    void process(float* io, int frames, const float* p, double, float sr) override {
        const float thr = std::clamp(p[2], -60.f, 0.f);
        const float ratio = std::clamp(p[3], 1.f, 20.f);
        const float att = onePoleCoef(std::clamp(p[4], 0.05f, 200.f) / 1000.f, sr);
        const float rel = onePoleCoef(std::clamp(p[5], 5.f, 3000.f) / 1000.f, sr);
        const float knee = std::clamp(p[6], 0.f, 24.f);
        const float makeupT = dbToGain(std::clamp(p[7], 0.f, 24.f));
        const float mixT = std::clamp(p[8], 0.f, 1.f);
        const float gc = glideCoef(sr);
        float maxGr = 0.f;
        for (int i = 0; i < frames; i++) {
            const float makeup = makeupG_.next(makeupT, gc), mix = mixG_.next(mixT, gc);
            float l = io[i * 2], r = io[i * 2 + 1];
            float level = gainToDb(std::max(std::fabs(l), std::fabs(r)));
            // static curve: gain reduction in dB (>= 0)
            float over = level - thr;
            float gr;
            if (2.f * over < -knee) gr = 0.f;
            else if (knee > 0.f && 2.f * std::fabs(over) <= knee) {
                float k = over + knee / 2.f;
                gr = (1.f - 1.f / ratio) * k * k / (2.f * knee);
            } else gr = (1.f - 1.f / ratio) * over;
            // ballistics on the gain reduction
            float c = gr > env_ ? att : rel;
            env_ = gr + c * (env_ - gr);
            maxGr = std::max(maxGr, env_);
            float g = dbToGain(-env_) * makeup;
            io[i * 2] = l * (1.f - mix) + l * g * mix;
            io[i * 2 + 1] = r * (1.f - mix) + r * g * mix;
        }
        meter = maxGr;
    }

private:
    float env_ = 0.f;
    Glide makeupG_, mixG_;
};

// ---------------------------------------------------------------------------------------
// Chorus: three modulated voices per side, spread across the stereo field.
//  p2 rate Hz, p3 depth (0..1), p4 mix, p5 delay ms (2..25), p6 feedback (-0.9..0.9), p7 width

class Chorus : public Effect {
public:
    Chorus() { dl_.init(48000 / 10); dr_.init(48000 / 10); }
    void process(float* io, int frames, const float* p, double, float sr) override {
        const float rate = std::clamp(p[2], 0.02f, 10.f);
        const float depthT = std::clamp(p[3], 0.f, 1.f) * 0.004f * sr;  // up to 4 ms
        const float mixT = std::clamp(p[4], 0.f, 1.f);
        const float baseT = std::clamp(p[5], 2.f, 25.f) * 0.001f * sr;
        const float fbT = std::clamp(p[6], -0.9f, 0.9f);
        const float widthT = std::clamp(p[7], 0.f, 1.f);
        const float inc = rate / sr;
        const float gc = glideCoef(sr);
        for (int i = 0; i < frames; i++) {
            const float depth = depthG_.next(depthT, gc), mix = mixG_.next(mixT, gc), base = baseG_.next(baseT, gc);
            const float fb = fbG_.next(fbT, gc), width = widthG_.next(widthT, gc);
            float l = io[i * 2], r = io[i * 2 + 1];
            dl_.write(l + fbL_ * fb);
            dr_.write(r + fbR_ * fb);
            float wl = 0.f, wr = 0.f;
            for (int v = 0; v < 3; v++) {
                float ph = phase_ + v / 3.f;
                ph -= std::floor(ph);
                float tri = 1.f - 4.f * std::fabs(ph - 0.5f);   // -1..1
                float ph2 = ph + 0.25f; ph2 -= std::floor(ph2);
                float tri2 = 1.f - 4.f * std::fabs(ph2 - 0.5f);
                wl += dl_.read(base + depth * (0.5f + 0.5f * tri));
                wr += dr_.read(base + depth * (0.5f + 0.5f * tri2));
            }
            wl *= 0.4f; wr *= 0.4f;
            fbL_ = wl; fbR_ = wr;
            // width: mono wet at 0, full stereo at 1
            float m = 0.5f * (wl + wr), s = 0.5f * (wl - wr) * width;
            io[i * 2] = l * (1.f - mix * 0.5f) + (m + s) * mix;
            io[i * 2 + 1] = r * (1.f - mix * 0.5f) + (m - s) * mix;
            phase_ += inc;
            if (phase_ >= 1.f) phase_ -= 1.f;
        }
    }

    void reset() override { dl_.clear(); dr_.clear(); fbL_ = fbR_ = 0.f; }

private:
    Glide depthG_, mixG_, baseG_, fbG_, widthG_;
    DelayLine dl_, dr_;
    float phase_ = 0.f, fbL_ = 0.f, fbR_ = 0.f;
};

// ---------------------------------------------------------------------------------------
// Delay: stereo or ping-pong, free (ms) or tempo-synced, filtered and modulated feedback.
//  p2 time (ms, or division index when synced), p3 sync, p4 feedback, p5 mix,
//  p6 ping-pong, p7 low cut Hz, p8 high cut Hz, p9 modulation (0..1)

static const float kDivisions[] = {  // in quarter notes
    0.125f, 1.f / 6, 0.25f, 0.375f, 1.f / 3, 0.5f, 0.75f, 2.f / 3, 1.f, 1.5f, 2.f, 4.f};
constexpr int kDivisionCount = 12;

class Delay : public Effect {
public:
    Delay() { dl_.init(48000 * 4); dr_.init(48000 * 4); }
    void process(float* io, int frames, const float* p, double bpm, float sr) override {
        float target;
        if (p[3] > 0.5f) {
            int d = std::clamp(int(p[2] + 0.5f), 0, kDivisionCount - 1);
            double b = (bpm >= 20.0 && bpm <= 400.0) ? bpm : 120.0;
            target = float(kDivisions[d] * 60.0 / b * sr);
        } else {
            target = std::clamp(p[2], 1.f, 2000.f) * 0.001f * sr;
        }
        target = std::clamp(target, 1.f, float(dl_.size() - 8));
        const float fbT = std::clamp(p[4], 0.f, 1.1f);
        const float mixT = std::clamp(p[5], 0.f, 1.f);
        const float gc = glideCoef(sr);
        const bool ping = p[6] > 0.5f;
        const float hpC = lpCoef(std::clamp(p[7], 10.f, 2000.f), sr);
        const float lpC = lpCoef(std::clamp(p[8], 500.f, 20000.f), sr);
        const float mod = std::clamp(p[9], 0.f, 1.f) * 0.002f * sr;
        const float smooth = onePoleCoef(0.15f, sr);  // tape-like glide when the time changes
        if (time_ <= 0.f) time_ = target;
        for (int i = 0; i < frames; i++) {
            const float fb = fbG_.next(fbT, gc), mix = mixG_.next(mixT, gc);
            time_ = target + smooth * (time_ - target);
            float wob = mod * std::sin(6.2831853f * modPh_);
            modPh_ += 0.6f / sr;
            if (modPh_ >= 1.f) modPh_ -= 1.f;
            float yl = dl_.read(time_ + wob), yr = dr_.read(time_ - wob);
            // feedback filtering: high-pass (low cut) and low-pass (high cut)
            lpL_ = yl + lpC * (lpL_ - yl); lpR_ = yr + lpC * (lpR_ - yr);
            hpL_ = lpL_ + hpC * (hpL_ - lpL_); hpR_ = lpR_ + hpC * (hpR_ - lpR_);
            float fl = std::tanh((lpL_ - hpL_) * fb), fr = std::tanh((lpR_ - hpR_) * fb);
            float l = io[i * 2], r = io[i * 2 + 1];
            if (ping) {
                dl_.write(0.5f * (l + r) + fr);
                dr_.write(fl);
            } else {
                dl_.write(l + fl);
                dr_.write(r + fr);
            }
            io[i * 2] = l + yl * mix;
            io[i * 2 + 1] = r + yr * mix;
        }
    }

    void reset() override { dl_.clear(); dr_.clear(); lpL_ = lpR_ = hpL_ = hpR_ = 0.f; }

private:
    DelayLine dl_, dr_;
    Glide fbG_, mixG_;
    float time_ = -1.f, modPh_ = 0.f, lpL_ = 0.f, lpR_ = 0.f, hpL_ = 0.f, hpR_ = 0.f;
};

// ---------------------------------------------------------------------------------------
// Reverb: input diffusion -> 8-line feedback delay network (Hadamard mixing, modulated
// lines, frequency-dependent decay) -> stereo output, with pre-delay, freeze and width.
// Three characters:
//  DENSE  small, dense, early reflections, little modulation
//  LUSH   medium to large, smooth, in-loop diffusion, gentle chorus-like modulation
//  VAST   very large, blooming diffusion and deep modulation for huge washes
//  p2 mode, p3 pre-delay ms (0..500), p4 size (0..1), p5 decay s (0.2..30),
//  p6 high cut Hz (damping), p7 low cut Hz, p8 diffusion (0..1), p9 modulation (0..1),
//  p10 mix, p11 freeze, p12 width (0..2)

class Reverb : public Effect {
public:
    static constexpr int N = 8;

    Reverb() {
        pre_[0].init(48000 / 2 + 16);
        pre_[1].init(48000 / 2 + 16);
        for (int k = 0; k < N; k++) {
            line_[k].init(int(kVast[k] * 2.0f * 48.f) + 1024);
            loopAp_[k].init(int(kVast[k] * 2.0f * 48.f * 0.3f) + 64);
            lfoPh_[k] = k / float(N);
        }
        for (int s = 0; s < 2; s++)
            for (int k = 0; k < 6; k++) inAp_[s][k].init(int(48.f * 60.f) + 64);
    }

    void process(float* io, int frames, const float* p, double, float sr) override {
        const int mode = std::clamp(int(p[2] + 0.5f), 0, 2);
        const float preDT = std::clamp(p[3], 0.f, 500.f) * 0.001f * sr;
        const float size = std::clamp(p[4], 0.f, 1.f);
        const float gc = glideCoef(sr);
        const bool freeze = p[11] > 0.5f;
        const float t60 = std::clamp(p[5], 0.2f, 30.f);
        const float damp = lpCoef(std::clamp(p[6], 500.f, 20000.f), sr);
        const float lowCut = lpCoef(std::clamp(p[7], 10.f, 1000.f), sr);
        const float diff = std::clamp(p[8], 0.f, 1.f);
        const float modAmt = std::clamp(p[9], 0.f, 1.f);
        const float mixT = std::clamp(p[10], 0.f, 1.f);
        const float widthT = std::clamp(p[12], 0.f, 2.f);

        const float* base = mode == 0 ? kDense : mode == 1 ? kLush : kVast;
        const float scale = mode == 0 ? 0.5f + size : mode == 1 ? 0.5f + size : 0.6f + 1.4f * size;
        const float modDepth = (mode == 0 ? 3.f : mode == 1 ? 10.f : 28.f) * modAmt * sr / 48000.f;
        const float apG = 0.45f + 0.3f * diff;
        const float loopG = mode == 0 ? 0.f : (0.25f + 0.35f * diff);
        // decay gains for the target size; the loop glides to them over the block
        float gT[N], g0[N], len[N], g[N];
        for (int k = 0; k < N; k++) {
            const float lt = base[k] * scale * 0.001f * sr;
            gT[k] = freeze ? 1.f : std::pow(10.f, -3.f * lt / (t60 * sr));
            g0[k] = gPrimed_ ? gPrev_[k] : gT[k];
            gPrev_[k] = gT[k];
        }
        gPrimed_ = true;
        const float invFrames = frames > 0 ? 1.f / float(frames) : 0.f;
        // input diffuser lengths (ms): short ones always, long "bloom" ones for VAST
        static const float kIn[6] = {4.77f, 3.59f, 12.73f, 9.31f, 31.7f, 47.3f};
        const int inStages = mode == 2 ? 6 : 4;
        const float inScale = mode == 0 ? 0.6f : 1.f;

        for (int i = 0; i < frames; i++) {
            // knob moves glide: line lengths (size), pre-delay, decay, mix and width
            const float sc = scaleG_.next(scale, gc);
            const float preD = preG_.next(preDT, gc), mix = mixG_.next(mixT, gc), width = widthG_.next(widthT, gc);
            const float tg = float(i + 1) * invFrames;
            for (int k = 0; k < N; k++) {
                len[k] = base[k] * sc * 0.001f * sr;
                g[k] = g0[k] + (gT[k] - g0[k]) * tg;
            }
            float inL = io[i * 2], inR = io[i * 2 + 1];
            // pre-delay, input low cut (one-pole high-pass)
            pre_[0].write(inL);
            pre_[1].write(inR);
            float xl = preD >= 1.f ? pre_[0].read(preD) : inL;
            float xr = preD >= 1.f ? pre_[1].read(preD) : inR;
            hpS_[0] = xl + lowCut * (hpS_[0] - xl); xl -= hpS_[0];
            hpS_[1] = xr + lowCut * (hpS_[1] - xr); xr -= hpS_[1];
            if (freeze) { xl = 0.f; xr = 0.f; }
            // early reflections (DENSE): a few taps from the pre-delayed input
            float erL = 0.f, erR = 0.f;
            if (mode == 0) {
                for (int t = 0; t < 6; t++) {
                    float d = kEr[t] * (0.5f + size) * 0.001f * sr;
                    erL += pre_[(t & 1)].read(preD + d) * kErGain[t];
                    erR += pre_[1 - (t & 1)].read(preD + d * 1.07f) * kErGain[t];
                }
            }
            // input diffusion
            for (int s = 0; s < inStages; s++) {
                float l = kIn[s] * inScale * 0.001f * sr;
                xl = inAp_[0][s].process(xl, l, apG);
                xr = inAp_[1][s].process(xr, l * 1.031f, apG);
            }
            // read the network
            float y[N];
            for (int k = 0; k < N; k++) {
                float m = modDepth * std::sin(6.2831853f * lfoPh_[k]);
                y[k] = line_[k].read(len[k] + modDepth + m);
                lfoPh_[k] += kLfoHz[k] / sr;
                if (lfoPh_[k] >= 1.f) lfoPh_[k] -= 1.f;
            }
            // stereo output taps
            float wl = y[0] - y[2] + y[4] - y[6] + 0.5f * (y[1] + y[5]);
            float wr = y[1] - y[3] + y[5] - y[7] + 0.5f * (y[2] - y[6]);
            // 8-point Hadamard mixing (normalised)
            hadamard(y);
            for (int k = 0; k < N; k++) {
                float v = y[k] * g[k];
                // frequency-dependent decay: one-pole low-pass in the loop
                if (!freeze) { lp_[k] = v + damp * (lp_[k] - v); v = lp_[k]; }
                // in-loop diffusion (LUSH / VAST)
                if (loopG > 0.f) v = loopAp_[k].process(v, len[k] * 0.27f, loopG);
                // inject the input on alternating lines
                v += (k & 1 ? xr : xl) * 0.35f;
                line_[k].write(flushDenormal(v));
            }
            // longer spaces spread the energy over time: lift their level to match
            const float wet = mode == 0 ? 0.3f : mode == 1 ? 0.6f : 0.75f;
            wl = wl * wet + erL * 0.6f;
            wr = wr * wet + erR * 0.6f;
            float m = 0.5f * (wl + wr), s = 0.5f * (wl - wr) * width;
            io[i * 2] = inL * (1.f - mix) + (m + s) * mix;
            io[i * 2 + 1] = inR * (1.f - mix) + (m - s) * mix;
        }
    }

    void reset() override {
        pre_[0].clear(); pre_[1].clear();
        for (int k = 0; k < N; k++) { line_[k].clear(); loopAp_[k].clear(); lp_[k] = 0.f; }
        for (auto& side : inAp_) for (auto& a : side) a.clear();
        hpS_[0] = hpS_[1] = 0.f;
    }

private:
    static inline float flushDenormal(float v) { return std::fabs(v) < 1e-20f ? 0.f : v; }
    static inline void hadamard(float* x) {
        for (int h = 1; h < N; h <<= 1)
            for (int i = 0; i < N; i += h * 2)
                for (int j = i; j < i + h; j++) {
                    float a = x[j], b = x[j + h];
                    x[j] = a + b;
                    x[j + h] = a - b;
                }
        const float norm = 0.35355339f;  // 1 / sqrt(8)
        for (int k = 0; k < N; k++) x[k] *= norm;
    }

    // line lengths in ms (mutually prime-ish sets per character)
    static constexpr float kDense[N] = {9.3f, 11.7f, 13.1f, 15.2f, 17.9f, 19.3f, 21.6f, 24.1f};
    static constexpr float kLush[N] = {31.3f, 37.7f, 41.9f, 47.3f, 53.1f, 59.9f, 67.3f, 73.7f};
    static constexpr float kVast[N] = {63.7f, 79.3f, 89.9f, 101.3f, 113.7f, 127.1f, 139.3f, 151.7f};
    static constexpr float kLfoHz[N] = {0.13f, 0.21f, 0.29f, 0.37f, 0.43f, 0.53f, 0.61f, 0.71f};
    static constexpr float kEr[6] = {7.1f, 11.3f, 17.9f, 23.4f, 31.7f, 43.1f};
    static constexpr float kErGain[6] = {0.8f, 0.7f, 0.6f, 0.5f, 0.4f, 0.3f};

    DelayLine pre_[2];
    DelayLine line_[N];
    Allpass loopAp_[N];
    Allpass inAp_[2][6];
    float lfoPh_[N] = {};
    float lp_[N] = {};
    float hpS_[2] = {};
    Glide scaleG_, preG_, mixG_, widthG_;
    float gPrev_[N] = {};
    bool gPrimed_ = false;
};

inline Effect* createEffect(int type) {
    switch (type) {
        case FX_COMPRESSOR: return new Compressor();
        case FX_CHORUS: return new Chorus();
        case FX_DELAY: return new Delay();
        case FX_REVERB: return new Reverb();
        default: return nullptr;
    }
}

}  // namespace openmk3
