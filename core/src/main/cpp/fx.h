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
enum FxType { FX_NONE = 0, FX_COMPRESSOR = 1, FX_CHORUS = 2, FX_DELAY = 3, FX_REVERB = 4, FX_FILTER = 5,
              FX_GATE = 6, FX_LIMITER = 7, FX_EQ = 8, FX_FLANGER = 9, FX_PHASER = 10, FX_DISTORTION = 11,
              FX_LOFI = 12, FX_TRANSIENT = 13, FX_SATURATOR = 14, FX_TREMOLO = 15, FX_FREQSHIFT = 16,
              FX_RINGMOD = 17, FX_TYPES = 18 };

class Effect {
public:
    virtual ~Effect() {}
    /** [p] points at this slot's 16 parameters; [bpm] for tempo-synced effects. */
    virtual void process(float* io, int frames, const float* p, double bpm, float sr) = 0;
    /** Silences tails (delay lines, reverb) on the audio thread: "all sounds off". */
    virtual void reset() {}
    /** A level for the screen: gain reduction (dB) for the compressor, else 0. */
    float meter = 0.f;
    /** Side-chain key (stereo, [keyFrames] frames), set by the engine before process(); null: none. */
    const float* key = nullptr;
    int keyFrames = 0;
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
//  p6 knee dB (0..24), p7 makeup dB (0..24), p8 mix (0..1), p9 side-chain (0 off, 1..8 group A..H:
//  the key's level drives the gain reduction, e.g. a kick group ducking a bass)

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
            float level = key && keyFrames > 0
                ? gainToDb(std::max(std::fabs(key[std::min(i, keyFrames - 1) * 2]), std::fabs(key[std::min(i, keyFrames - 1) * 2 + 1])))
                : gainToDb(std::max(std::fabs(l), std::fabs(r)));
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

// ---------------------------------------------------------------------------------------
// Filter: multi-mode, in two families, both zero-delay-feedback (topology-preserving) designs:
//  ladder (Moog style): four one-pole stages in a resonant feedback loop, saturating input;
//    taps give 24 dB and 12 dB low-pass and (by mixing the stages) 24 dB high-pass
//  state-variable (Oberheim SEM style): 12 dB, low / band / high-pass, notch, and a morph from
//    low-pass through notch to high-pass
//  p2 mode, p3 cutoff Hz, p4 resonance (0..1), p5 drive (0..1), p6 morph (0..1), p7 mix,
//  p8 LFO rate Hz, p9 LFO depth (0..1 = +-4 octaves), p10 envelope follow (-1..1 = +-4 octaves)

class Filter : public Effect {
public:
    enum Mode { LADDER_LP24, LADDER_LP12, LADDER_HP24, SVF_LP, SVF_BP, SVF_HP, SVF_NOTCH, SVF_MORPH, MODES };

    void process(float* io, int frames, const float* p, double, float sr) override {
        const int mode = std::clamp(int(p[2] + 0.5f), 0, MODES - 1);
        const float cutT = std::clamp(p[3], 20.f, 20000.f);
        const float resT = std::clamp(p[4], 0.f, 1.f);
        const float driveT = std::clamp(p[5], 0.f, 1.f);
        const float morphT = std::clamp(p[6], 0.f, 1.f);
        const float mixT = std::clamp(p[7], 0.f, 1.f);
        const float lfoHz = std::clamp(p[8], 0.05f, 20.f);
        const float lfoDepth = std::clamp(p[9], 0.f, 1.f) * 4.f;
        const float envAmt = std::clamp(p[10], -1.f, 1.f) * 4.f;
        const float gc = glideCoef(sr);
        const float envAtt = onePoleCoef(0.005f, sr), envRel = onePoleCoef(0.15f, sr);
        const bool ladder = mode <= LADDER_HP24;
        float g = 0.f;
        for (int i = 0; i < frames; i++) {
            const float cut = cutG_.next(cutT, gc), res = resG_.next(resT, gc);
            const float drive = driveG_.next(driveT, gc), morph = morphG_.next(morphT, gc), mix = mixG_.next(mixT, gc);
            const float inL = io[i * 2], inR = io[i * 2 + 1];
            // envelope follower on the input
            const float a = std::max(std::fabs(inL), std::fabs(inR));
            env_ = a + (a > env_ ? envAtt : envRel) * (env_ - a);
            // control rate (every 16 samples): modulated cutoff -> integrator gain
            if ((ctl_++ & 15) == 0) {
                lfoPh_ += 16.f * lfoHz / sr;
                if (lfoPh_ >= 1.f) lfoPh_ -= 1.f;
                const float lfo = std::sin(6.2831853f * lfoPh_);
                const float oct = lfo * lfoDepth + envAmt * std::min(1.f, env_ * 2.f);
                const float fc = std::clamp(cut * std::exp2(oct), 20.f, 0.45f * sr);
                g_ = std::tan(3.14159265f * fc / sr);
            }
            g = g_;
            const float dg = 1.f + drive * 9.f;  // drive into the saturator
            float out[2];
            const float in[2] = {inL, inR};
            for (int c = 0; c < 2; c++) {
                if (ladder) {
                    // ZDF ladder: y4 = G^4 u + sigma, u = x - k y4 (solved without a unit delay)
                    const float G = g / (1.f + g);
                    const float k = res * 4.1f;
                    float* st = lad_[c];
                    const float b0 = st[0] / (1.f + g), b1 = st[1] / (1.f + g), b2 = st[2] / (1.f + g), b3 = st[3] / (1.f + g);
                    const float G2 = G * G, G3 = G2 * G, G4 = G3 * G;
                    const float sigma = G3 * b0 + G2 * b1 + G * b2 + b3;
                    // bass loss of the ladder at high resonance is partly made up at the input
                    float x = in[c] * (1.f + 0.5f * k * (mode == LADDER_HP24 ? 0.f : 1.f));
                    float u = (x - k * sigma) / (1.f + k * G4);
                    u = std::tanh(u * dg) / std::tanh(dg);
                    float y[4];
                    float v = u;
                    for (int s2 = 0; s2 < 4; s2++) {
                        const float w = (v - st[s2]) * G;
                        y[s2] = w + st[s2];
                        st[s2] = y[s2] + w;
                        v = y[s2];
                    }
                    out[c] = mode == LADDER_LP24 ? y[3]
                           : mode == LADDER_LP12 ? y[1]
                           : u - 4.f * y[0] + 6.f * y[1] - 4.f * y[2] + y[3];
                } else {
                    // ZDF state-variable filter (two integrators)
                    const float R = 1.f - 0.985f * res;  // damping: low = resonant
                    float* st = svf_[c];
                    float x = std::tanh(in[c] * dg) / std::tanh(dg);
                    const float hp = (x - (2.f * R + g) * st[0] - st[1]) / (1.f + 2.f * R * g + g * g);
                    const float bp = g * hp + st[0];
                    st[0] = g * hp + bp;
                    const float lp = g * bp + st[1];
                    st[1] = g * bp + lp;
                    const float notch = lp + hp;
                    switch (mode) {
                        case SVF_LP: out[c] = lp; break;
                        case SVF_BP: out[c] = bp * 2.f * R; break;  // unity peak
                        case SVF_HP: out[c] = hp; break;
                        case SVF_NOTCH: out[c] = notch; break;
                        default:
                            out[c] = morph < 0.5f ? lp + (notch - lp) * (morph * 2.f)
                                                  : notch + (hp - notch) * (morph * 2.f - 1.f);
                    }
                }
                if (!(out[c] == out[c]) || std::fabs(out[c]) > 1e4f) { out[c] = 0.f; reset(); }  // never blow up
            }
            io[i * 2] = inL + (out[0] - inL) * mix;
            io[i * 2 + 1] = inR + (out[1] - inR) * mix;
        }
    }

    void reset() override {
        for (auto& c : lad_) for (float& v : c) v = 0.f;
        for (auto& c : svf_) for (float& v : c) v = 0.f;
        env_ = 0.f;
    }

private:
    float lad_[2][4] = {};
    float svf_[2][2] = {};
    float g_ = 0.1f, env_ = 0.f, lfoPh_ = 0.f;
    unsigned ctl_ = 0;
    Glide cutG_, resG_, driveG_, morphG_, mixG_;
};


// ---------------------------------------------------------------------------------------
// Gate: opens above the threshold, holds, then closes down to RANGE.
//  p2 threshold dB, p3 attack ms, p4 hold ms, p5 release ms, p6 range dB (attenuation when closed)

class Gate : public Effect {
public:
    void process(float* io, int frames, const float* p, double, float sr) override {
        const float thr = dbToGain(std::clamp(p[2], -80.f, 0.f));
        const float att = onePoleCoef(std::clamp(p[3], 0.05f, 100.f) / 1000.f, sr);
        const int hold = int(std::clamp(p[4], 0.f, 1000.f) / 1000.f * sr);
        const float rel = onePoleCoef(std::clamp(p[5], 5.f, 3000.f) / 1000.f, sr);
        const float floorG = dbToGain(-std::clamp(p[6], 0.f, 80.f));
        float closed = 0.f;
        for (int i = 0; i < frames; i++) {
            float l = io[i * 2], r = io[i * 2 + 1];
            float lvl = key && keyFrames > 0 ? std::max(std::fabs(key[std::min(i, keyFrames - 1) * 2]), std::fabs(key[std::min(i, keyFrames - 1) * 2 + 1]))
                                             : std::max(std::fabs(l), std::fabs(r));
            if (lvl >= thr) holdLeft_ = hold;
            const float target = (lvl >= thr || holdLeft_ > 0) ? 1.f : floorG;
            if (holdLeft_ > 0) holdLeft_--;
            const float c = target > g_ ? att : rel;
            g_ = target + c * (g_ - target);
            io[i * 2] = l * g_;
            io[i * 2 + 1] = r * g_;
            closed = std::max(closed, gainToDb(1.f / std::max(g_, 1e-6f)));
        }
        meter = closed;
    }
private:
    float g_ = 1.f;
    int holdLeft_ = 0;
};

// ---------------------------------------------------------------------------------------
// Limiter: 1.5 ms look-ahead, the gain reaches the needed reduction before the peak arrives.
//  p2 ceiling dB (-24..0), p3 release ms, p4 input gain dB (0..24)

class Limiter : public Effect {
public:
    Limiter() { dl_.init(256); dr_.init(256); }
    void process(float* io, int frames, const float* p, double, float sr) override {
        const float ceil = dbToGain(std::clamp(p[2], -24.f, 0.f));
        const float rel = onePoleCoef(std::clamp(p[3], 5.f, 2000.f) / 1000.f, sr);
        const float inG = dbToGain(std::clamp(p[4], 0.f, 24.f));
        const int la = std::clamp(int(0.0015f * sr), 1, 200);
        const float attC = std::exp(-1.f / float(la) * 3.f);
        float maxGr = 0.f;
        for (int i = 0; i < frames; i++) {
            float l = io[i * 2] * inG, r = io[i * 2 + 1] * inG;
            dl_.write(l); dr_.write(r);
            const float pk = std::max(std::fabs(l), std::fabs(r));
            // peak hold over the look-ahead window
            if (pk >= hold_) { hold_ = pk; holdLeft_ = la; } else if (--holdLeft_ <= 0) { hold_ *= 0.999f; }
            const float target = hold_ > ceil ? ceil / hold_ : 1.f;
            g_ = target < g_ ? target + attC * (g_ - target) : target + rel * (g_ - target);
            const float ol = dl_.tap(la) * g_, orr = dr_.tap(la) * g_;
            io[i * 2] = std::clamp(ol, -ceil, ceil);
            io[i * 2 + 1] = std::clamp(orr, -ceil, ceil);
            maxGr = std::max(maxGr, gainToDb(1.f / std::max(g_, 1e-6f)));
        }
        meter = maxGr;
    }
    void reset() override { dl_.clear(); dr_.clear(); hold_ = 0.f; g_ = 1.f; }
private:
    DelayLine dl_, dr_;
    float hold_ = 0.f, g_ = 1.f;
    int holdLeft_ = 0;
};

// ---------------------------------------------------------------------------------------
// Biquad (RBJ cookbook), stereo.

struct Biquad {
    float b0 = 1, b1 = 0, b2 = 0, a1 = 0, a2 = 0;
    float z1[2] = {}, z2[2] = {};
    void peak(float f, float gDb, float q, float sr) {
        const float A = std::pow(10.f, gDb / 40.f), w = 6.2831853f * std::clamp(f, 10.f, sr * 0.45f) / sr;
        const float al = std::sin(w) / (2.f * std::max(q, 0.1f)), c = std::cos(w);
        set(1 + al * A, -2 * c, 1 - al * A, 1 + al / A, -2 * c, 1 - al / A);
    }
    void shelf(float f, float gDb, bool high, float sr) {
        const float A = std::pow(10.f, gDb / 40.f), w = 6.2831853f * std::clamp(f, 10.f, sr * 0.45f) / sr;
        const float c = std::cos(w), al = std::sin(w) / 2.f * std::sqrt(2.f), sA = 2.f * std::sqrt(A) * al;
        if (!high) set(A * ((A + 1) - (A - 1) * c + sA), 2 * A * ((A - 1) - (A + 1) * c), A * ((A + 1) - (A - 1) * c - sA),
                       (A + 1) + (A - 1) * c + sA, -2 * ((A - 1) + (A + 1) * c), (A + 1) + (A - 1) * c - sA);
        else set(A * ((A + 1) + (A - 1) * c + sA), -2 * A * ((A - 1) + (A + 1) * c), A * ((A + 1) + (A - 1) * c - sA),
                 (A + 1) - (A - 1) * c + sA, 2 * ((A - 1) - (A + 1) * c), (A + 1) - (A - 1) * c - sA);
    }
    void set(float nb0, float nb1, float nb2, float na0, float na1, float na2) {
        b0 = nb0 / na0; b1 = nb1 / na0; b2 = nb2 / na0; a1 = na1 / na0; a2 = na2 / na0;
    }
    inline float run(float x, int c) {
        const float y = b0 * x + z1[c];
        z1[c] = b1 * x - a1 * y + z2[c];
        z2[c] = b2 * x - a2 * y;
        return y;
    }
};

// ---------------------------------------------------------------------------------------
// EQ: low shelf, two peaks, high shelf.
//  p2 low freq, p3 low gain dB · p4 mid 1 freq, p5 gain, p6 Q · p7 mid 2 freq, p8 gain, p9 Q
//  p10 high freq, p11 high gain dB

class Eq : public Effect {
public:
    void process(float* io, int frames, const float* p, double, float sr) override {
        float key[10];
        for (int k = 0; k < 10; k++) key[k] = p[2 + k];
        if (std::memcmp(key, last_, sizeof(key)) != 0) {
            std::memcpy(last_, key, sizeof(key));
            b_[0].shelf(p[2], std::clamp(p[3], -24.f, 24.f), false, sr);
            b_[1].peak(p[4], std::clamp(p[5], -24.f, 24.f), p[6], sr);
            b_[2].peak(p[7], std::clamp(p[8], -24.f, 24.f), p[9], sr);
            b_[3].shelf(p[10], std::clamp(p[11], -24.f, 24.f), true, sr);
        }
        for (int i = 0; i < frames; i++)
            for (int c = 0; c < 2; c++) {
                float x = io[i * 2 + c];
                for (auto& b : b_) x = b.run(x, c);
                io[i * 2 + c] = x;
            }
    }
private:
    Biquad b_[4];
    float last_[10] = {-1};
};

// ---------------------------------------------------------------------------------------
// Flanger: a short modulated delay with feedback.
//  p2 rate Hz, p3 depth, p4 feedback (-0.95..0.95), p5 mix, p6 delay ms (0.1..10), p7 stereo (LFO phase)

class Flanger : public Effect {
public:
    Flanger() { dl_.init(48000 / 40); dr_.init(48000 / 40); }
    void process(float* io, int frames, const float* p, double, float sr) override {
        const float inc = std::clamp(p[2], 0.01f, 10.f) / sr;
        const float depth = std::clamp(p[3], 0.f, 1.f), fb = std::clamp(p[4], -0.95f, 0.95f);
        const float mixT = std::clamp(p[5], 0.f, 1.f), base = std::clamp(p[6], 0.1f, 10.f) * 0.001f * sr;
        const float ph = std::clamp(p[7], 0.f, 1.f) * 0.5f;
        const float gc = glideCoef(sr);
        for (int i = 0; i < frames; i++) {
            const float mix = mixG_.next(mixT, gc);
            phase_ += inc; if (phase_ >= 1.f) phase_ -= 1.f;
            const float ml = 0.5f - 0.5f * std::cos(6.2831853f * phase_);
            float pr = phase_ + ph; if (pr >= 1.f) pr -= 1.f;
            const float mr = 0.5f - 0.5f * std::cos(6.2831853f * pr);
            const float l = io[i * 2], r = io[i * 2 + 1];
            // the delay sweeps from DELAY down towards zero by DEPTH
            const float yl = dl_.read(1.f + base * (1.f - depth * 0.95f * ml));
            const float yr = dr_.read(1.f + base * (1.f - depth * 0.95f * mr));
            dl_.write(l + yl * fb); dr_.write(r + yr * fb);
            io[i * 2] = l * (1.f - mix * 0.5f) + yl * mix * 0.5f;
            io[i * 2 + 1] = r * (1.f - mix * 0.5f) + yr * mix * 0.5f;
        }
    }
    void reset() override { dl_.clear(); dr_.clear(); }
private:
    DelayLine dl_, dr_;
    float phase_ = 0.f;
    Glide mixG_;
};

// ---------------------------------------------------------------------------------------
// Phaser: a chain of modulated first-order all-passes with feedback.
//  p2 rate Hz, p3 depth, p4 feedback (-0.95..0.95), p5 mix, p6 stages (2..12), p7 centre Hz

class Phaser : public Effect {
public:
    void process(float* io, int frames, const float* p, double, float sr) override {
        const float inc = std::clamp(p[2], 0.01f, 10.f) / sr;
        const float depth = std::clamp(p[3], 0.f, 1.f), fb = std::clamp(p[4], -0.95f, 0.95f);
        const float mixT = std::clamp(p[5], 0.f, 1.f);
        const int stages = std::clamp(int(p[6] + 0.5f), 1, 6) * 2;
        const float centre = std::clamp(p[7], 100.f, 8000.f);
        const float gc = glideCoef(sr);
        for (int i = 0; i < frames; i++) {
            const float mix = mixG_.next(mixT, gc);
            phase_ += inc; if (phase_ >= 1.f) phase_ -= 1.f;
            for (int c = 0; c < 2; c++) {
                float ph = phase_ + (c ? 0.25f : 0.f); if (ph >= 1.f) ph -= 1.f;
                const float m = std::sin(6.2831853f * ph);
                const float f = centre * std::exp2(depth * 2.f * m);
                const float t = std::tan(3.14159265f * std::clamp(f, 20.f, sr * 0.45f) / sr);
                const float a = (t - 1.f) / (t + 1.f);
                float x = io[i * 2 + c] + last_[c] * fb;
                for (int k = 0; k < stages; k++) {
                    const float y = a * x + s_[c][k];
                    s_[c][k] = x - a * y;
                    x = y;
                }
                last_[c] = x;
                io[i * 2 + c] = io[i * 2 + c] * (1.f - mix * 0.5f) + x * mix * 0.5f;
            }
        }
    }
    void reset() override { std::memset(s_, 0, sizeof(s_)); last_[0] = last_[1] = 0.f; }
private:
    float s_[2][12] = {};
    float last_[2] = {};
    float phase_ = 0.f;
    Glide mixG_;
};

// ---------------------------------------------------------------------------------------
// Distortion: drive into a shaper (soft, hard, fold, tube), a tone low-pass, output level.
//  p2 drive dB (0..48), p3 type (0 soft, 1 hard, 2 fold, 3 tube), p4 tone Hz, p5 mix, p6 output dB

class Distortion : public Effect {
public:
    void process(float* io, int frames, const float* p, double, float sr) override {
        const float gT = dbToGain(std::clamp(p[2], 0.f, 48.f));
        const int type = std::clamp(int(p[3] + 0.5f), 0, 3);
        const float tone = lpCoef(std::clamp(p[4], 200.f, 20000.f), sr);
        const float mixT = std::clamp(p[5], 0.f, 1.f), outT = dbToGain(std::clamp(p[6], -24.f, 12.f));
        const float gc = glideCoef(sr);
        for (int i = 0; i < frames; i++) {
            const float g = gG_.next(gT, gc), mix = mixG_.next(mixT, gc), out = outG_.next(outT, gc);
            for (int c = 0; c < 2; c++) {
                const float x = io[i * 2 + c];
                float y = x * g;
                switch (type) {
                    case 0: y = std::tanh(y); break;
                    case 1: y = std::clamp(y, -1.f, 1.f); break;
                    case 2: y = std::sin(std::clamp(y, -20.f, 20.f) * 1.5707963f); break;
                    default: y = y >= 0.f ? 1.f - std::exp(-y) : -(1.f - std::exp(y * 1.4f)) / 1.4f * 1.2f; break;  // asymmetric
                }
                lp_[c] = y + tone * (lp_[c] - y);
                // the gain compensates the drive, so turning it up mostly changes the colour
                const float wet = lp_[c] * out / std::max(1.f, std::sqrt(g) * 0.5f);
                io[i * 2 + c] = x * (1.f - mix) + wet * mix;
            }
        }
    }
private:
    float lp_[2] = {};
    Glide gG_, mixG_, outG_;
};

// ---------------------------------------------------------------------------------------
// Lo-fi: sample-rate and bit reduction.
//  p2 sample rate Hz (500..48000), p3 bits (1..16), p4 mix

class Lofi : public Effect {
public:
    void process(float* io, int frames, const float* p, double, float sr) override {
        const float step = std::clamp(p[2], 500.f, sr) / sr;
        const float q = std::exp2(std::clamp(p[3], 1.f, 16.f) - 1.f);
        const float mix = std::clamp(p[4], 0.f, 1.f);
        for (int i = 0; i < frames; i++) {
            ph_ += step;
            if (ph_ >= 1.f) { ph_ -= 1.f; h_[0] = io[i * 2]; h_[1] = io[i * 2 + 1]; }
            for (int c = 0; c < 2; c++) {
                const float y = std::round(h_[c] * q) / q;
                io[i * 2 + c] = io[i * 2 + c] * (1.f - mix) + y * mix;
            }
        }
    }
private:
    float ph_ = 1.f, h_[2] = {};
};

// ---------------------------------------------------------------------------------------
// Transient shaper: a fast and a slow envelope; where the fast one leads (attacks) ATTACK sets
// the gain, where it trails (the tail) SUSTAIN does.
//  p2 attack (-1..1), p3 sustain (-1..1), p4 output dB

class Transient : public Effect {
public:
    void process(float* io, int frames, const float* p, double, float sr) override {
        const float atk = std::clamp(p[2], -1.f, 1.f), sus = std::clamp(p[3], -1.f, 1.f);
        const float out = dbToGain(std::clamp(p[4], -24.f, 12.f));
        const float fa = onePoleCoef(0.001f, sr), fr = onePoleCoef(0.03f, sr);
        const float sa = onePoleCoef(0.03f, sr), srl = onePoleCoef(0.3f, sr);
        for (int i = 0; i < frames; i++) {
            const float l = io[i * 2], r = io[i * 2 + 1];
            const float x = std::max(std::fabs(l), std::fabs(r));
            fast_ = x + (x > fast_ ? fa : fr) * (fast_ - x);
            slow_ = x + (x > slow_ ? sa : srl) * (slow_ - x);
            const float ratio = (fast_ + 1e-5f) / (slow_ + 1e-5f);
            float g = ratio > 1.f ? std::pow(ratio, atk) : std::pow(1.f / ratio, sus);
            g = std::clamp(g, 0.25f, 4.f) * out;  // ±12 dB
            io[i * 2] = l * g;
            io[i * 2 + 1] = r * g;
        }
    }
private:
    float fast_ = 0.f, slow_ = 0.f;
};

// ---------------------------------------------------------------------------------------
// Saturator: tape-like warmth: a soft, slightly asymmetric curve with a gentle high roll-off,
// level-compensated.  p2 drive dB (0..24), p3 warmth (asymmetry 0..1), p4 tone Hz, p5 mix

class Saturator : public Effect {
public:
    void process(float* io, int frames, const float* p, double, float sr) override {
        const float g = dbToGain(std::clamp(p[2], 0.f, 24.f));
        const float bias = std::clamp(p[3], 0.f, 1.f) * 0.3f;
        const float tone = lpCoef(std::clamp(p[4], 1000.f, 20000.f), sr);
        const float mix = std::clamp(p[5], 0.f, 1.f);
        const float comp = 1.f / std::tanh(g * 0.5f + 1e-3f) * 0.5f;
        const float dc = std::tanh(bias);
        for (int i = 0; i < frames; i++)
            for (int c = 0; c < 2; c++) {
                const float x = io[i * 2 + c];
                float y = (std::tanh(x * g * 0.5f + bias) - dc) * comp;
                lp_[c] = y + tone * (lp_[c] - y);
                hp_[c] += 0.0005f * (lp_[c] - hp_[c]);  // removes the offset the bias adds
                io[i * 2 + c] = x * (1.f - mix) + (lp_[c] - hp_[c]) * mix;
            }
    }
private:
    float lp_[2] = {}, hp_[2] = {};
};

// ---------------------------------------------------------------------------------------
// Tremolo / auto-pan: an LFO on the level (or on the balance), free in Hz or synced.
//  p2 rate Hz, p3 depth, p4 shape (0 sine, 1 triangle, 2 square), p5 mode (0 tremolo, 1 auto-pan),
//  p6 sync (0 off, 1 on: p7 = note value 0 1/1 .. 5 1/32)

class Tremolo : public Effect {
public:
    void process(float* io, int frames, const float* p, double bpm, float sr) override {
        static constexpr float kDiv[] = {4.f, 2.f, 1.f, 0.5f, 0.25f, 0.125f};  // in beats
        float hz = std::clamp(p[2], 0.05f, 30.f);
        if (p[6] > 0.5f) hz = float(bpm / 60.0) / kDiv[std::clamp(int(p[7] + 0.5f), 0, 5)];
        const float inc = hz / sr, depth = std::clamp(p[3], 0.f, 1.f);
        const int shape = std::clamp(int(p[4] + 0.5f), 0, 2);
        const bool pan = p[5] > 0.5f;
        for (int i = 0; i < frames; i++) {
            ph_ += inc; if (ph_ >= 1.f) ph_ -= 1.f;
            float m = shape == 0 ? std::sin(6.2831853f * ph_) : shape == 1 ? 1.f - 4.f * std::fabs(ph_ - 0.5f) : (ph_ < 0.5f ? 1.f : -1.f);
            sm_ += 0.02f * (m - sm_);  // softens the square's edges
            if (shape == 2) m = sm_;
            if (pan) {
                const float a = 0.5f + 0.5f * m * depth;
                io[i * 2] *= std::sqrt(1.f - a) * 1.4142f;
                io[i * 2 + 1] *= std::sqrt(a) * 1.4142f;
            } else {
                const float g = 1.f - depth * (0.5f - 0.5f * m);
                io[i * 2] *= g; io[i * 2 + 1] *= g;
            }
        }
    }
private:
    float ph_ = 0.f, sm_ = 0.f;
};

// ---------------------------------------------------------------------------------------
// Hilbert pair (two all-pass chains 90° apart over the audio band), for frequency shifting.

struct Hilbert {
    // coefficients of the two 4-stage second-order all-pass chains (a classic 90° design)
    static constexpr float kA[4] = {0.6923878f, 0.9360654f, 0.9882295f, 0.9987488f};
    static constexpr float kB[4] = {0.4021921f, 0.8561711f, 0.9722910f, 0.9952885f};
    float xa[4][2] = {}, ya[4][2] = {}, xb[4][2] = {}, yb[4][2] = {};
    float bDelay = 0.f;
    static inline float stage(float x, float a2, float* xs, float* ys) {
        const float y = a2 * (x + ys[1]) - xs[1];
        xs[1] = xs[0]; xs[0] = x; ys[1] = ys[0]; ys[0] = y;
        return y;
    }
    inline void run(float x, float& re, float& im) {
        float a = x, b = x;
        for (int k = 0; k < 4; k++) { a = stage(a, kA[k] * kA[k], xa[k], ya[k]); b = stage(b, kB[k] * kB[k], xb[k], yb[k]); }
        re = a; im = bDelay; bDelay = b;
    }
};

// ---------------------------------------------------------------------------------------
// Frequency shifter: every frequency moves by the same number of Hz (inharmonic, metallic).
//  p2 shift Hz (-2000..2000), p3 mix, p4 stereo (the right channel shifts the other way)

class FreqShifter : public Effect {
public:
    void process(float* io, int frames, const float* p, double, float sr) override {
        const float hz = std::clamp(p[2], -2000.f, 2000.f), mix = std::clamp(p[3], 0.f, 1.f);
        const bool stereo = p[4] > 0.5f;
        const float inc = hz / sr;
        for (int i = 0; i < frames; i++) {
            ph_ += inc; ph_ -= std::floor(ph_);
            const float c = std::cos(6.2831853f * ph_), s = std::sin(6.2831853f * ph_);
            for (int ch = 0; ch < 2; ch++) {
                float re, im;
                h_[ch].run(io[i * 2 + ch], re, im);
                const float y = (ch == 1 && stereo) ? re * c - im * s : re * c + im * s;
                io[i * 2 + ch] = io[i * 2 + ch] * (1.f - mix) + y * mix;
            }
        }
    }
private:
    Hilbert h_[2];
    float ph_ = 0.f;
};

// ---------------------------------------------------------------------------------------
// Ring modulator: the signal times a sine (bell-like, robotic).  p2 frequency Hz, p3 mix

class RingMod : public Effect {
public:
    void process(float* io, int frames, const float* p, double, float sr) override {
        const float inc = std::clamp(p[2], 1.f, 5000.f) / sr, mix = std::clamp(p[3], 0.f, 1.f);
        for (int i = 0; i < frames; i++) {
            ph_ += inc; ph_ -= std::floor(ph_);
            const float m = std::sin(6.2831853f * ph_);
            for (int c = 0; c < 2; c++) io[i * 2 + c] = io[i * 2 + c] * (1.f - mix) + io[i * 2 + c] * m * mix;
        }
    }
private:
    float ph_ = 0.f;
};

inline Effect* createEffect(int type) {
    switch (type) {
        case FX_COMPRESSOR: return new Compressor();
        case FX_CHORUS: return new Chorus();
        case FX_DELAY: return new Delay();
        case FX_REVERB: return new Reverb();
        case FX_FILTER: return new Filter();
        case FX_GATE: return new Gate();
        case FX_LIMITER: return new Limiter();
        case FX_EQ: return new Eq();
        case FX_FLANGER: return new Flanger();
        case FX_PHASER: return new Phaser();
        case FX_DISTORTION: return new Distortion();
        case FX_LOFI: return new Lofi();
        case FX_TRANSIENT: return new Transient();
        case FX_SATURATOR: return new Saturator();
        case FX_TREMOLO: return new Tremolo();
        case FX_FREQSHIFT: return new FreqShifter();
        case FX_RINGMOD: return new RingMod();
        default: return nullptr;
    }
}

}  // namespace openmk3
