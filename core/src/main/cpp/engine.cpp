// Native audio engine for Open MK3.
//
// AAudio low-latency stream (exclusive when the device allows it) with a real-time
// data callback that mixes sample voices and runs the 16-step sequencer sample-accurately.
//
// Threads:
//   AAudio callback  : renders audio, reads the pattern, consumes pad triggers
//   input thread     : nativeTrigger() (single producer of the trigger ring)
//   UI / Kotlin      : edits the pattern through a shared direct buffer, sets params
//   monitor (Kotlin) : nativeTune() every 250 ms: buffer-size tuning, restart after errors

#include <aaudio/AAudio.h>
#include <android/log.h>
#include <jni.h>
#include <linux/usbdevice_fs.h>
#include <sys/ioctl.h>
#include <sys/resource.h>
#include <unistd.h>
#include <poll.h>
#include <sched.h>

#include <cerrno>
#include <chrono>
#include <condition_variable>
#include <deque>
#include <thread>

#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstring>
#include <ctime>
#include <memory>
#include <mutex>
#include <vector>

#define LOG_TAG "MK3Native"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

namespace {

constexpr int kSounds = 16;
constexpr int kSteps = 16;
constexpr int kMaxVoices = 32;
constexpr int kSampleRate = 48000;
constexpr int kRing = 256;

// Shared pattern buffer layout (floats); must match Pattern.kt.
constexpr int OFF_VEL = 0;
constexpr int OFF_MUTE = 256;
constexpr int OFF_SOLO = 272;
constexpr int OFF_SKIP = 288;
constexpr int OFF_BPM = 544;
constexpr int OFF_SWING = 545;
constexpr int OFF_ANYSOLO = 546;
constexpr int PATTERN_FLOATS = 548;

int64_t nowNanos() {
    timespec ts{};
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return int64_t(ts.tv_sec) * 1000000000LL + ts.tv_nsec;
}

struct SampleData {
    std::vector<float> data;  // interleaved when channels == 2
    int channels = 1;
    int frames = 0;
    int choke = 0;
};

constexpr int kSlots = kSounds + 1;  // 16 pads + 1 browser preview slot

// Short ramps that remove clicks without dulling the attack:
// fade-in over ~0.3 ms when a voice starts, fade-out over ~2 ms when it is choked or stolen.
constexpr int kFadeIn = 16;
constexpr int kFadeOut = 96;

// Per-sound parameters: kSlots x kParams floats in a direct buffer shared with Kotlin
// (SoundParams.kt). Read at voice start (pitch, start/end, envelope) and every callback
// (volume, pan, filter) so knob moves are heard on playing sounds.
constexpr int kParams = 16;
enum {
    P_TUNE = 0,    // semitones, -36..36 (fractional = fine tune)
    P_VOL = 1,     // dB, -60 (silent) .. +12
    P_PAN = 2,     // -1 left .. 1 right
    P_START = 3,   // 0..1 of the sample
    P_END = 4,     // 0..1 of the sample
    P_REV = 5,     // > 0.5 = reverse
    P_ENV = 6,     // 0 one-shot, 1 AHD
    P_ATT = 7,     // seconds
    P_HOLD = 8,    // seconds
    P_DEC = 9,     // seconds (to -80 dB)
    P_FTYPE = 10,  // 0 off, 1 low-pass, 2 band-pass, 3 high-pass
    P_CUT = 11,    // Hz
    P_RES = 12,    // 0..1
};
static const float kDefaultParams[kParams] = {0, 0, 0, 0, 1, 0, 0, 0.001f, 0.05f, 0.5f, 0, 20000, 0, 0, 0, 0};

struct Voice {
    const SampleData* sample = nullptr;
    double pos = 0.0;   // fractional read position (frames)
    double rate = 1.0;  // frames per output frame
    int startF = 0, endF = 0;
    bool rev = false;
    float gain = 0.f;   // velocity gain
    int startOffset = 0;
    int fadeIn = 0;     // frames rendered so far of the fade-in
    int fadeOut = -1;   // frames left of the fade-out, -1 = not fading
    int slot = -1;
    uint64_t age = 0;
    // AHD envelope
    bool ahd = false;
    int envStage = 0;   // 0 attack, 1 hold, 2 decay
    int envCount = 0;
    int attF = 0, holdF = 0;
    float envLevel = 1.f, decCoef = 0.f;
    // state-variable filter (stereo)
    float ic1[2] = {0, 0}, ic2[2] = {0, 0};
};

struct Trigger {
    int slot;
    float vel;
    int64_t tNanos;
};

class Engine {
public:
    // ---- set from Kotlin ----
    std::atomic<float> masterGain{0.7f};
    std::atomic<float*> pattern{nullptr};
    std::atomic<int64_t*> lastTrigMs{nullptr};   // 16 x int64, CLOCK_MONOTONIC ms
    std::atomic<float*> params{nullptr};         // kSlots x kParams
    std::atomic<bool> playRequested{false};
    std::atomic<bool> restartRequested{false};

    // ---- read by Kotlin ----
    std::atomic<int> currentStep{-1};
    std::atomic<double> phase{0.0};
    std::atomic<float> meter{0.f};
    std::atomic<float> worstTrigMs{0.f};
    std::atomic<int> xruns{0};
    std::atomic<int> bufferFrames{0};
    std::atomic<int> burstFrames{0};
    std::atomic<bool> exclusive{false};
    std::atomic<bool> needsRestart{false};
    std::atomic<int> callbackFrames{0};

    // samples: replaced only while the stream is stopped, or swapped atomically per slot
    // Per-slot sample, swapped without locks while playing:
    //   loader thread: new SampleData -> pending[slot]
    //   audio thread : installs pending into current at the top of a callback (stopping the
    //                  slot's voices first), parks the old one in retired
    //   loader thread: frees retired on its next load
    std::atomic<SampleData*> current[kSlots];
    std::atomic<SampleData*> pending[kSlots];
    std::atomic<SampleData*> retired[kSlots];

    AAudioStream* stream = nullptr;
    int32_t deviceId = 0;
    // The MK3 clocks its output from its input stream (USB "implicit feedback"): keeping the
    // input running gives the host the device's clock, so the output doesn't slowly drift and
    // slip (heard as a pop every ~36 s). The input is read and, for now, only metered.
    AAudioStream* inStream = nullptr;
    int32_t inDeviceId = 0;
    std::atomic<float> inPeak{0.f};
    std::atomic<bool> inOpen{false};
    std::mutex streamLock;

    Engine() {
        for (int i = 0; i < kSlots; i++) { current[i] = nullptr; pending[i] = nullptr; retired[i] = nullptr; }
    }

    // ---- single-producer / single-consumer trigger ring ----
    Trigger ring[kRing];
    std::atomic<uint32_t> ringHead{0};  // written by producer
    std::atomic<uint32_t> ringTail{0};  // written by consumer

    std::atomic_flag producerLock = ATOMIC_FLAG_INIT;  // input thread + browser preview

    void pushTrigger(int slot, float vel) {
        while (producerLock.test_and_set(std::memory_order_acquire)) {}
        uint32_t h = ringHead.load(std::memory_order_relaxed);
        uint32_t t = ringTail.load(std::memory_order_acquire);
        if (h - t < kRing) {
            ring[h % kRing] = {slot, vel, nowNanos()};
            ringHead.store(h + 1, std::memory_order_release);
        }
        producerLock.clear(std::memory_order_release);
    }

    // ---- stream management ----
    bool open();
    void close();
    void tune();

    // ---- audio thread ----
    void render(float* out, int32_t frames);

private:
    Voice voices[kMaxVoices];
    uint64_t voiceAge = 0;
    bool wasPlaying = false;
    double seqPhase = 0.0;
    int64_t nextStep = 0;
    float meterAcc = 0.f;
    int meterFrames = 0;

    void startVoice(int slot, float vel, int offset);
    void runSequencer(int frames);
};

Engine gEngine;

// =========================================================================================
// Sample recorder: fed by the controller's input (USB or AAudio path) and by phone playback
// capture (pushed from Java). Only the selected source writes; buffers are allocated on arm
// (Java thread), never in the audio path.
// =========================================================================================

class Recorder {
public:
    enum { IDLE = 0, ARMED = 1, RECORDING = 2 };
    enum { SRC_INPUT = 0, SRC_PHONE = 1 };
    std::atomic<int> state{IDLE};
    std::atomic<int> source{SRC_INPUT};
    std::atomic<int> frames{0};
    std::atomic<float> threshold{0.02f};
    std::atomic<float> peakInput{0.f};
    std::atomic<float> peakPhone{0.f};
    std::vector<float> buf;  // stereo interleaved
    int maxFrames = 0;
    std::atomic<int> writers{0};  // feed() calls currently past the state check

    /** Waits until no audio thread is inside feed(), so buf can be touched safely. */
    void quiesce() {
        state.store(IDLE);
        while (writers.load(std::memory_order_acquire) > 0) std::this_thread::yield();
    }

    void arm(int src, bool waitForThreshold, float thr, int seconds) {
        quiesce();
        source.store(src);
        threshold.store(thr);
        maxFrames = std::max(1, seconds) * kSampleRate;
        buf.assign(size_t(maxFrames) * 2, 0.f);
        frames.store(0);
        state.store(waitForThreshold ? ARMED : RECORDING);
    }

    void stop() { quiesce(); }

    /** Audio-thread safe: no allocation, no locks. */
    void feed(const float* st, int n, int src) {
        std::atomic<float>& pk = src == SRC_INPUT ? peakInput : peakPhone;
        float peak = pk.load(std::memory_order_relaxed);
        for (int i = 0; i < n * 2; i++) {
            float a = std::fabs(st[i]);
            if (a > peak) peak = a;
        }
        pk.store(peak, std::memory_order_relaxed);
        if (source.load(std::memory_order_relaxed) != src) return;
        writers.fetch_add(1, std::memory_order_acq_rel);
        struct Exit { std::atomic<int>& w; ~Exit() { w.fetch_sub(1, std::memory_order_release); } } exitGuard{writers};
        int s = state.load(std::memory_order_acquire);
        int start = 0;
        if (s == ARMED) {
            float thr = threshold.load(std::memory_order_relaxed);
            for (; start < n; start++)
                if (std::fabs(st[start * 2]) >= thr || std::fabs(st[start * 2 + 1]) >= thr) break;
            if (start >= n) return;
            state.store(RECORDING, std::memory_order_release);
        } else if (s != RECORDING) {
            return;
        }
        int f = frames.load(std::memory_order_relaxed);
        int take = std::min(n - start, maxFrames - f);
        if (take > 0) {
            std::memcpy(&buf[size_t(f) * 2], st + start * 2, sizeof(float) * size_t(take) * 2);
            frames.store(f + take, std::memory_order_release);
        }
        if (f + take >= maxFrames) state.store(IDLE, std::memory_order_release);  // full
    }
};

Recorder gRec;


aaudio_data_callback_result_t dataCallback(AAudioStream*, void* user, void* audio, int32_t frames) {
    static_cast<Engine*>(user)->render(static_cast<float*>(audio), frames);
    return AAUDIO_CALLBACK_RESULT_CONTINUE;
}

aaudio_data_callback_result_t inputCallback(AAudioStream*, void* user, void* audio, int32_t frames) {
    auto* e = static_cast<Engine*>(user);
    const float* in = static_cast<const float*>(audio);
    float peak = e->inPeak.load(std::memory_order_relaxed);
    for (int i = 0; i < frames * 2; i++) {
        float a = std::fabs(in[i]);
        if (a > peak) peak = a;
    }
    e->inPeak.store(peak, std::memory_order_relaxed);
    gRec.feed(in, frames, Recorder::SRC_INPUT);
    return AAUDIO_CALLBACK_RESULT_CONTINUE;
}

void errorCallback(AAudioStream*, void* user, aaudio_result_t error) {
    LOGI("stream error %d (%s): restart requested", error, AAudio_convertResultToText(error));
    static_cast<Engine*>(user)->needsRestart.store(true);
}

static AAudioStream* openInput(Engine* e, int32_t deviceId) {
    AAudioStreamBuilder* b = nullptr;
    if (AAudio_createStreamBuilder(&b) != AAUDIO_OK) return nullptr;
    AAudioStreamBuilder_setDirection(b, AAUDIO_DIRECTION_INPUT);
    AAudioStreamBuilder_setPerformanceMode(b, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
    AAudioStreamBuilder_setSharingMode(b, AAUDIO_SHARING_MODE_SHARED);
    AAudioStreamBuilder_setFormat(b, AAUDIO_FORMAT_PCM_FLOAT);
    AAudioStreamBuilder_setChannelCount(b, 2);
    AAudioStreamBuilder_setSampleRate(b, kSampleRate);
    AAudioStreamBuilder_setInputPreset(b, AAUDIO_INPUT_PRESET_UNPROCESSED);
    AAudioStreamBuilder_setDeviceId(b, deviceId);
    AAudioStreamBuilder_setDataCallback(b, inputCallback, e);
    AAudioStreamBuilder_setErrorCallback(b, errorCallback, e);
    AAudioStream* s = nullptr;
    aaudio_result_t r = AAudioStreamBuilder_openStream(b, &s);
    AAudioStreamBuilder_delete(b);
    if (r != AAUDIO_OK) {
        LOGI("input open failed: %s", AAudio_convertResultToText(r));
        return nullptr;
    }
    if (AAudioStream_requestStart(s) != AAUDIO_OK) {
        AAudioStream_close(s);
        return nullptr;
    }
    LOGI("input open: rate=%d device=%d", AAudioStream_getSampleRate(s), AAudioStream_getDeviceId(s));
    return s;
}

bool Engine::open() {
    std::lock_guard<std::mutex> g(streamLock);
    if (stream) return true;
    // Input first, so the device clock is running before the output starts.
    if (inDeviceId > 0 && !inStream) inStream = openInput(this, inDeviceId);
    inOpen.store(inStream != nullptr);
    AAudioStreamBuilder* b = nullptr;
    if (AAudio_createStreamBuilder(&b) != AAUDIO_OK) return false;
    AAudioStreamBuilder_setDirection(b, AAUDIO_DIRECTION_OUTPUT);
    AAudioStreamBuilder_setPerformanceMode(b, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
    AAudioStreamBuilder_setSharingMode(b, AAUDIO_SHARING_MODE_EXCLUSIVE);
    AAudioStreamBuilder_setFormat(b, AAUDIO_FORMAT_PCM_FLOAT);
    AAudioStreamBuilder_setChannelCount(b, 2);
    AAudioStreamBuilder_setSampleRate(b, kSampleRate);
    AAudioStreamBuilder_setUsage(b, AAUDIO_USAGE_MEDIA);
    AAudioStreamBuilder_setContentType(b, AAUDIO_CONTENT_TYPE_MUSIC);
    if (deviceId > 0) AAudioStreamBuilder_setDeviceId(b, deviceId);
    AAudioStreamBuilder_setDataCallback(b, dataCallback, this);
    AAudioStreamBuilder_setErrorCallback(b, errorCallback, this);

    aaudio_result_t r = AAudioStreamBuilder_openStream(b, &stream);
    if (r != AAUDIO_OK) {
        LOGI("exclusive open failed (%s), trying shared", AAudio_convertResultToText(r));
        AAudioStreamBuilder_setSharingMode(b, AAUDIO_SHARING_MODE_SHARED);
        r = AAudioStreamBuilder_openStream(b, &stream);
    }
    AAudioStreamBuilder_delete(b);
    if (r != AAUDIO_OK) {
        LOGI("openStream failed: %s", AAudio_convertResultToText(r));
        stream = nullptr;
        return false;
    }
    int32_t burst = AAudioStream_getFramesPerBurst(stream);
    burstFrames.store(burst);
    exclusive.store(AAudioStream_getSharingMode(stream) == AAUDIO_SHARING_MODE_EXCLUSIVE);
    // Start at three bursts (6 ms at 96): two proved too tight; tune() grows it if needed.
    AAudioStream_setBufferSizeInFrames(stream, burst * 3);
    bufferFrames.store(AAudioStream_getBufferSizeInFrames(stream));
    xruns.store(0);
    needsRestart.store(false);
    r = AAudioStream_requestStart(stream);
    if (r != AAUDIO_OK) {
        LOGI("requestStart failed: %s", AAudio_convertResultToText(r));
        AAudioStream_close(stream);
        stream = nullptr;
        return false;
    }
    LOGI("stream open: rate=%d burst=%d buffer=%d capacity=%d exclusive=%d device=%d",
         AAudioStream_getSampleRate(stream), burst, bufferFrames.load(),
         AAudioStream_getBufferCapacityInFrames(stream), exclusive.load() ? 1 : 0,
         AAudioStream_getDeviceId(stream));
    return true;
}

void Engine::close() {
    std::lock_guard<std::mutex> g(streamLock);
    if (stream) {
        AAudioStream_requestStop(stream);
        AAudioStream_close(stream);
        stream = nullptr;
    }
    if (inStream) {
        AAudioStream_requestStop(inStream);
        AAudioStream_close(inStream);
        inStream = nullptr;
    }
    inOpen.store(false);
}

void Engine::tune() {
    std::lock_guard<std::mutex> g(streamLock);
    if (!stream) return;
    int32_t x = AAudioStream_getXRunCount(stream);
    if (x > xruns.load()) {
        int32_t burst = burstFrames.load();
        int32_t cur = AAudioStream_getBufferSizeInFrames(stream);
        // Grow one burst per tune() call that saw new glitches, capped at ~20 ms.
        int32_t cap = std::min(AAudioStream_getBufferCapacityInFrames(stream), kSampleRate / 50);
        if (cur + burst <= cap) {
            AAudioStream_setBufferSizeInFrames(stream, cur + burst);
            bufferFrames.store(AAudioStream_getBufferSizeInFrames(stream));
        }
        xruns.store(x);
    }
}

void Engine::startVoice(int slot, float vel, int offset) {
    if (slot < 0 || slot >= kSlots) return;
    const SampleData* s = current[slot].load(std::memory_order_acquire);
    if (!s || s->frames <= 0) return;
    // Each pad is monophonic, like a drum machine: a new hit fades out the previous one
    // instead of piling on top of its tail (which smears the attack).
    for (auto& v : voices)
        if (v.sample && v.slot == slot && v.fadeOut < 0) v.fadeOut = kFadeOut;
    if (s->choke != 0) {
        // e.g. closed hat silences open hat: fade instead of cutting
        for (auto& v : voices)
            if (v.sample && v.sample->choke == s->choke && v.fadeOut < 0) v.fadeOut = kFadeOut;
    }
    // A free voice, else the oldest one (with 32 voices this is rare).
    Voice* best = nullptr;
    Voice* oldest = &voices[0];
    for (auto& v : voices) {
        if (!v.sample) { best = &v; break; }
        if (v.age < oldest->age) oldest = &v;
    }
    if (!best) best = oldest;
    float* pb = params.load(std::memory_order_acquire);
    const float* P = pb ? pb + slot * kParams : kDefaultParams;
    float a = std::clamp(P[P_START], 0.f, 1.f);
    float b = std::clamp(P[P_END], 0.f, 1.f);
    int sf = int(a * float(s->frames));
    int ef = int(b * float(s->frames));
    if (ef <= sf) ef = std::min(s->frames, sf + 1);
    if (sf >= s->frames) return;
    best->sample = s;
    best->startF = sf;
    best->endF = ef;
    best->rev = P[P_REV] > 0.5f;
    best->pos = best->rev ? double(ef - 1) : double(sf);
    best->rate = std::pow(2.0, double(std::clamp(P[P_TUNE], -48.f, 48.f)) / 12.0);
    best->ahd = P[P_ENV] > 0.5f;
    best->envStage = 0;
    best->envCount = 0;
    best->attF = int(std::max(0.f, P[P_ATT]) * kSampleRate);
    best->holdF = int(std::max(0.f, P[P_HOLD]) * kSampleRate);
    best->envLevel = best->attF > 0 ? 0.f : 1.f;
    best->decCoef = float(std::exp(std::log(1e-4) / (std::max(0.005f, P[P_DEC]) * kSampleRate)));
    best->ic1[0] = best->ic1[1] = best->ic2[0] = best->ic2[1] = 0.f;
    best->age = ++voiceAge;
    best->gain = vel * vel;  // perceptual curve
    best->startOffset = offset;
    best->fadeIn = 0;
    best->fadeOut = -1;
    best->slot = slot;
    if (int64_t* lt = lastTrigMs.load(std::memory_order_acquire); lt && slot < kSounds) {
        lt[slot] = nowNanos() / 1000000LL + int64_t(offset) * 1000 / kSampleRate;
    }
}

void Engine::runSequencer(int frames) {
    float* p = pattern.load(std::memory_order_acquire);
    bool on = playRequested.load(std::memory_order_acquire) && p != nullptr;
    if (on && (!wasPlaying || restartRequested.exchange(false))) {
        seqPhase = 0.0;
        nextStep = 0;
        for (int i = 0; i < kSounds * kSteps; i++) p[OFF_SKIP + i] = 0.f;
    }
    wasPlaying = on;
    if (!on) {
        currentStep.store(-1, std::memory_order_relaxed);
        return;
    }
    double bpm = p[OFF_BPM];
    if (!(bpm >= 20.0 && bpm <= 400.0)) bpm = 120.0;
    double swing = p[OFF_SWING];
    if (!(swing >= 0.0 && swing <= 0.5)) swing = 0.0;
    bool anySolo = p[OFF_ANYSOLO] > 0.5f;
    double stepLen = kSampleRate * 60.0 / bpm / 4.0;
    double start = seqPhase;
    double end = start + frames / stepLen;
    while (true) {
        int64_t n = nextStep;
        double t = double(n) + ((n & 1) ? swing : 0.0);
        if (t >= end) break;
        // Round to the nearest frame; a step that rounds onto the next callback waits for it.
        long off = std::lround((t - start) * stepLen);
        if (off >= frames) break;
        if (off < 0) off = 0;
        int step = int(n % kSteps);
        for (int s = 0; s < kSounds; s++) {
            float v = p[OFF_VEL + s * kSteps + step];
            if (v <= 0.f) continue;
            float& skip = p[OFF_SKIP + s * kSteps + step];
            if (skip > 0.5f) { skip = 0.f; continue; }
            bool audible = anySolo ? p[OFF_SOLO + s] > 0.5f : p[OFF_MUTE + s] < 0.5f;
            if (audible) startVoice(s, v, int(off));
        }
        currentStep.store(step, std::memory_order_relaxed);
        nextStep = n + 1;
    }
    seqPhase = end;
    phase.store(end, std::memory_order_relaxed);
}

void Engine::render(float* out, int32_t frames) {
    callbackFrames.store(frames, std::memory_order_relaxed);
    // 0. sample swaps requested by the loader
    for (int slot = 0; slot < kSlots; slot++) {
        SampleData* p = pending[slot].exchange(nullptr, std::memory_order_acq_rel);
        if (!p) continue;
        SampleData* old = current[slot].load(std::memory_order_relaxed);
        for (auto& v : voices)
            if (v.sample && v.sample == old) v.sample = nullptr;  // nothing may keep reading it
        current[slot].store(p, std::memory_order_release);
        SampleData* prev = retired[slot].exchange(old, std::memory_order_acq_rel);
        (void)prev;  // only non-null if two swaps beat one load: a tiny, bounded leak
    }
    // 1. pad triggers: start at the top of this callback
    int64_t now = nowNanos();
    uint32_t t = ringTail.load(std::memory_order_relaxed);
    uint32_t h = ringHead.load(std::memory_order_acquire);
    while (t != h) {
        const Trigger& tr = ring[t % kRing];
        float ms = float(now - tr.tNanos) / 1e6f;
        if (ms > worstTrigMs.load(std::memory_order_relaxed)) worstTrigMs.store(ms, std::memory_order_relaxed);
        startVoice(tr.slot, tr.vel, 0);
        t++;
    }
    ringTail.store(t, std::memory_order_release);

    // 2. sequencer
    runSequencer(frames);

    // 3. voices
    std::memset(out, 0, sizeof(float) * frames * 2);
    float* pb = params.load(std::memory_order_acquire);
    for (auto& v : voices) {
        const SampleData* s = v.sample;
        if (!s) continue;
        const float* P = pb ? pb + v.slot * kParams : kDefaultParams;
        // per-callback parameters
        float vol = P[P_VOL] <= -59.9f ? 0.f : std::pow(10.f, std::clamp(P[P_VOL], -60.f, 12.f) / 20.f);
        float pan = std::clamp(P[P_PAN], -1.f, 1.f);
        float gl = pan > 0.f ? 1.f - pan : 1.f;
        float gr = pan < 0.f ? 1.f + pan : 1.f;
        int ftype = int(P[P_FTYPE] + 0.5f);
        float fa1 = 0.f, fa2 = 0.f, fa3 = 0.f, fk = 2.f;
        if (ftype >= 1 && ftype <= 3) {
            float fc = std::clamp(P[P_CUT], 20.f, 20000.f);
            float fg = std::tan(3.14159265f * std::min(fc, 0.45f * kSampleRate) / kSampleRate);
            fk = 2.f - 1.96f * std::clamp(P[P_RES], 0.f, 1.f);
            fa1 = 1.f / (1.f + fg * (fg + fk));
            fa2 = fg * fa1;
            fa3 = fg * fa2;
        } else {
            ftype = 0;
        }
        const float* d = s->data.data();
        const int n = s->frames;
        const bool st = s->channels == 2;
        const int ch = s->channels;
        auto at = [&](int f, int c) { return d[std::clamp(f, 0, n - 1) * ch + c]; };
        int i = v.startOffset;
        v.startOffset = 0;
        const float g = v.gain * vol;
        const double step = v.rev ? -v.rate : v.rate;
        bool done = false;
        for (; i < frames; i++) {
            if (v.rev ? v.pos < double(v.startF) : v.pos >= double(v.endF)) { done = true; break; }
            float env = 1.f;
            if (v.fadeIn < kFadeIn) env = float(++v.fadeIn) / kFadeIn;
            if (v.fadeOut >= 0) {
                env *= float(v.fadeOut) / kFadeOut;
                if (--v.fadeOut <= 0) { done = true; break; }
            }
            if (v.ahd) {
                if (v.envStage == 0) {
                    if (v.envCount >= v.attF) { v.envStage = 1; v.envCount = 0; v.envLevel = 1.f; }
                    else v.envLevel = float(++v.envCount) / float(v.attF);
                } else if (v.envStage == 1) {
                    if (++v.envCount >= v.holdF) v.envStage = 2;
                } else {
                    v.envLevel *= v.decCoef;
                    if (v.envLevel < 1e-4f) { done = true; break; }
                }
                env *= v.envLevel;
            }
            // 4-point Hermite interpolation (exact at rate 1, clean when pitched)
            int ip = int(std::floor(v.pos));
            float t = float(v.pos - ip);
            float xs[2];
            for (int c = 0; c < ch; c++) {
                float y0 = at(ip - 1, c), y1 = at(ip, c), y2 = at(ip + 1, c), y3 = at(ip + 2, c);
                if (t == 0.f) { xs[c] = y1; continue; }
                float c1 = 0.5f * (y2 - y0);
                float c2 = y0 - 2.5f * y1 + 2.f * y2 - 0.5f * y3;
                float c3 = 0.5f * (y3 - y0) + 1.5f * (y1 - y2);
                xs[c] = ((c3 * t + c2) * t + c1) * t + y1;
            }
            if (!st) xs[1] = xs[0];
            if (ftype) {
                for (int c = 0; c < 2; c++) {
                    float x = xs[c];
                    float v3 = x - v.ic2[c];
                    float v1 = fa1 * v.ic1[c] + fa2 * v3;
                    float v2 = v.ic2[c] + fa2 * v.ic1[c] + fa3 * v3;
                    v.ic1[c] = 2.f * v1 - v.ic1[c];
                    v.ic2[c] = 2.f * v2 - v.ic2[c];
                    xs[c] = ftype == 1 ? v2 : ftype == 2 ? fk * v1 : x - fk * v1 - v2;
                }
            }
            float ge = g * env;
            out[i * 2] += xs[0] * ge * gl;
            out[i * 2 + 1] += xs[1] * ge * gr;
            v.pos += step;
        }
        if (done) v.sample = nullptr;
    }

    // 4. master gain, soft clip, meter
    float g = masterGain.load(std::memory_order_relaxed);
    float peak = meterAcc;
    for (int i = 0; i < frames * 2; i++) {
        float y = tanhf(out[i] * g);
        out[i] = y;
        float a = std::fabs(y);
        if (a > peak) peak = a;
    }
    meterAcc = peak;
    meterFrames += frames;
    if (meterFrames >= kSampleRate / 10) {
        meter.store(meterAcc, std::memory_order_relaxed);
        meterAcc = 0.f;
        meterFrames = 0;
    }
}

// =========================================================================================
// Direct USB audio (userspace UAC2 streaming over usbfs isochronous URBs)
//
// Android's USB audio driver doesn't follow the MK3's clock, so its buffer slips every
// 30-70 s (a pop). Here we stream ourselves: the MK3's isochronous IN endpoint carries
// "implicit feedback" -- every IN packet's frame count is exactly how many frames the next
// OUT packet must carry. One thread reaps both directions and renders audio on demand.
// =========================================================================================

class UsbAudio {
public:
    std::atomic<bool> running{false};
    std::atomic<int> lastError{0};
    std::atomic<long long> packetsIn{0};
    std::atomic<long long> framesIn{0};
    std::atomic<int> late{0};        // OUT queue ran dry before we refilled it
    std::atomic<int> fifoEmpty{0};   // had to guess a packet size (no IN count available)
    std::atomic<int> fifoTrim{0};    // dropped IN counts because OUT fell behind
    std::atomic<int> inErrors{0};    // IN packets with a non-zero status
    std::atomic<int> inBig{0};       // IN packets larger than nominal+1 (device catching up after a gap)
    std::atomic<int> maxBatch{0};    // most URBs completed between two wake-ups
    std::atomic<int64_t> startNs{0};
    std::atomic<int> hist[16];       // IN packet sizes in frames (15 = larger)
    std::atomic<int> stalls8{0};     // > 8 ms between two reaps that found completed URBs
    std::atomic<int> stalls16{0};
    std::atomic<int> maxStallUs{0};
    std::atomic<long long> cbPumps{0};   // pumps done by the real-time AAudio callback
    std::atomic<long long> thPumps{0};   // pumps done by the fallback thread
    std::atomic<int> cbPolicy{-1};       // scheduling policy of the callback thread (1 = FIFO)
    std::atomic<int> driverBurst{0};
    std::atomic<int> queueMs{0};          // current queue length (grows after gaps)

    bool start(int fd, int outEp, int outMax, int inEp, int inMax,
               int outCh, int inCh, int subslot, int urbs, int packets, int hidEp, int hidMax);
    void stop();
    /** Next HID report: length, 0 on timeout, -1 if streaming has stopped. */
    int readHid(uint8_t* out, int cap, int timeoutMs);

private:
    struct Urb {
        usbdevfs_urb* u = nullptr;
        std::vector<uint8_t> buf;
        bool out = false;
        bool hid = false;   // interrupt-IN URB for the MK3's controls
        bool spare = false; // pre-allocated, submitted only when the queue grows
        std::atomic<bool> inflight{false};
    };

    // HID reports read by this thread (no timeouts/cancels on the shared fd, which stalled
    // the isochronous stream), handed to Java through a small queue.
    std::mutex hidLock_;
    std::condition_variable hidCv_;
    std::deque<std::vector<uint8_t>> hidQueue_;
    int hidCount_ = 0;

    int fd_ = -1, outEp_ = 0, outMax_ = 0, inEp_ = 0, inMax_ = 0;
    int outCh_ = 4, inCh_ = 2, subslot_ = 4, packets_ = 8;
    int nominal_ = kSampleRate / 8000;  // frames per 125 us microframe = 6
    std::vector<std::unique_ptr<Urb>> urbs_;
    std::thread thread_;
    std::atomic<bool> stopReq_{false};
    std::deque<int> fifo_;
    std::vector<float> mix_;

    // The URB queue is serviced by "pumps": non-blocking reap + refill, guarded by pumpLock_.
    // The primary pump runs in the data callback of a silent AAudio stream on the phone's
    // own output, because Android runs those callbacks at real-time priority (an app's own
    // thread can't get that, and was descheduled for > 8 ms, starving the USB queue).
    std::mutex pumpLock_;
    int inflightOut_ = 0, inflightIn_ = 0, inflightHid_ = 0;
    int activeUrbs_ = 0, maxUrbs_ = 0;  // per direction; grows by one after each gap
    std::vector<Urb*> spareOut_, spareIn_;
    int64_t lastReapNs_ = 0;
    std::atomic<int64_t> lastCbNs_{0};
    std::vector<Urb*> batch_;
    AAudioStream* driver_ = nullptr;

    bool submit(Urb& u);
    void fillOut(Urb& u);
    void handleIn(Urb& u);
    void pump(bool fromCallback);
    void fallbackLoop();
    bool openDriver();
    void closeDriver();

public:
    void pumpFromCallback();
private:
    void putSample(uint8_t* p, float x) const;
    float getSample(const uint8_t* p) const;
};

UsbAudio gUsb;

void UsbAudio::putSample(uint8_t* p, float x) const {
    if (x > 1.f) x = 1.f;
    if (x < -1.f) x = -1.f;
    int32_t v = int32_t(x * 8388607.f);  // 24-bit, left-justified in the subslot
    switch (subslot_) {
        case 4: { int32_t w = v * 256; std::memcpy(p, &w, 4); break; }
        case 3: p[0] = uint8_t(v); p[1] = uint8_t(v >> 8); p[2] = uint8_t(v >> 16); break;
        default: { int16_t w = int16_t(v >> 8); std::memcpy(p, &w, 2); break; }
    }
}

float UsbAudio::getSample(const uint8_t* p) const {
    switch (subslot_) {
        case 4: { int32_t w; std::memcpy(&w, p, 4); return float(w) / 2147483648.f; }
        case 3: { int32_t w = int32_t(uint32_t(p[0]) << 8 | uint32_t(p[1]) << 16 | uint32_t(p[2]) << 24); return float(w) / 2147483648.f; }
        default: { int16_t w; std::memcpy(&w, p, 2); return float(w) / 32768.f; }
    }
}

bool UsbAudio::submit(Urb& u) {
    u.u->status = 0;
    u.u->actual_length = 0;
    u.u->start_frame = 0;
    u.u->error_count = 0;
    u.u->usercontext = &u;
    u.inflight.store(true);
    if (ioctl(fd_, USBDEVFS_SUBMITURB, u.u) < 0) {
        u.inflight.store(false);
        lastError.store(errno);
        LOGI("usb: submit %s failed: %s", u.hid ? "HID" : (u.out ? "OUT" : "IN"), strerror(errno));
        return false;
    }
    return true;
}

void UsbAudio::fillOut(Urb& u) {
    const int frameBytes = outCh_ * subslot_;
    const int maxFrames = outMax_ / frameBytes;
    int frames[64];
    int total = 0;
    for (int i = 0; i < packets_; i++) {
        int f;
        if (!fifo_.empty()) { f = fifo_.front(); fifo_.pop_front(); }
        else { f = nominal_; fifoEmpty.fetch_add(1, std::memory_order_relaxed); }
        if (f < 0) f = 0;
        if (f > maxFrames) f = maxFrames;
        frames[i] = f;
        total += f;
    }
    mix_.resize(size_t(total) * 2);
    if (total > 0) gEngine.render(mix_.data(), total);
    uint8_t* p = u.buf.data();
    int k = 0;
    for (int i = 0; i < packets_; i++) {
        u.u->iso_frame_desc[i].length = unsigned(frames[i] * frameBytes);
        u.u->iso_frame_desc[i].actual_length = 0;
        u.u->iso_frame_desc[i].status = 0;
        for (int f = 0; f < frames[i]; f++, k++) {
            float l = mix_[k * 2], r = mix_[k * 2 + 1];
            for (int c = 0; c < outCh_; c++) {
                // Stereo on outputs 1/2, copied to 3/4 so both MK3 outputs play.
                float x = c >= 4 ? 0.f : ((c & 1) ? r : l);
                putSample(p, x);
                p += subslot_;
            }
        }
    }
    u.u->buffer_length = int(p - u.buf.data());
}

void UsbAudio::handleIn(Urb& u) {
    const int frameBytes = inCh_ * subslot_;
    const uint8_t* base = u.buf.data();
    float peak = gEngine.inPeak.load(std::memory_order_relaxed);
    for (int i = 0; i < packets_; i++) {
        int len = int(u.u->iso_frame_desc[i].actual_length);
        bool okPkt = u.u->iso_frame_desc[i].status == 0;
        int f = okPkt ? len / frameBytes : nominal_;
        if (!okPkt) inErrors.fetch_add(1, std::memory_order_relaxed);
        if (f > nominal_ + 1) inBig.fetch_add(1, std::memory_order_relaxed);
        hist[f < 15 ? f : 15].fetch_add(1, std::memory_order_relaxed);
        fifo_.push_back(f);
        packetsIn.fetch_add(1, std::memory_order_relaxed);
        framesIn.fetch_add(f, std::memory_order_relaxed);
        const uint8_t* p = base + size_t(i) * size_t(inMax_);
        float st[2 * 16];
        int nf = std::min(f, 16);
        for (int k = 0; k < nf; k++) {
            float l = getSample(p + size_t(k * inCh_) * size_t(subslot_));
            float r = inCh_ > 1 ? getSample(p + size_t(k * inCh_ + 1) * size_t(subslot_)) : l;
            st[k * 2] = l;
            st[k * 2 + 1] = r;
            float a = std::max(std::fabs(l), std::fabs(r));
            if (a > peak) peak = a;
        }
        if (nf > 0) gRec.feed(st, nf, Recorder::SRC_INPUT);
        u.u->iso_frame_desc[i].length = unsigned(inMax_);
        u.u->iso_frame_desc[i].actual_length = 0;
        u.u->iso_frame_desc[i].status = 0;
    }
    gEngine.inPeak.store(peak, std::memory_order_relaxed);
    // Keep the OUT side at most a few ms behind the device clock.
    while (fifo_.size() > size_t(packets_ * (maxUrbs_ + 4))) { fifo_.pop_front(); fifoTrim.fetch_add(1, std::memory_order_relaxed); }
}

void UsbAudio::pump(bool fromCallback) {
    if (stopReq_.load()) return;
    batch_.clear();
    usbdevfs_urb* done = nullptr;
    while (ioctl(fd_, USBDEVFS_REAPURBNDELAY, &done) == 0) batch_.push_back(static_cast<Urb*>(done->usercontext));
    if (batch_.empty()) return;
    int64_t now = nowNanos();
    if (lastReapNs_ != 0) {
        int us = int((now - lastReapNs_) / 1000);
        if (us > 8000) stalls8.fetch_add(1, std::memory_order_relaxed);
        if (us > 16000) stalls16.fetch_add(1, std::memory_order_relaxed);
        if (us > maxStallUs.load(std::memory_order_relaxed)) maxStallUs.store(us, std::memory_order_relaxed);
    }
    lastReapNs_ = now;
    (fromCallback ? cbPumps : thPumps).fetch_add(1, std::memory_order_relaxed);

    int outDone = 0;
    for (Urb* u : batch_) {
        u->inflight.store(false);
        if (u->hid) inflightHid_--;
        else if (u->out) { inflightOut_--; outDone++; } else inflightIn_--;
        int st = u->u->status;
        if (st == -ENODEV || st == -ESHUTDOWN || st == -EPROTO) {
            lastError.store(-st);
            LOGI("usb: URB status %d, stopping", st);
            stopReq_.store(true);
        }
    }
    if (stopReq_.load()) {
        running.store(false);
        hidCv_.notify_all();
        return;
    }
    // Every queued OUT URB had already played out: the device got a gap (audible).
    if (outDone > 0 && inflightOut_ == 0) {
        late.fetch_add(1, std::memory_order_relaxed);
        // Grow the queue by one URB per direction (up to 16 ms): a rare long stall costs one
        // click instead of recurring. Latency compensation follows bufferFrames.
        if (activeUrbs_ < maxUrbs_ && !spareOut_.empty() && !spareIn_.empty()) {
            Urb* o = spareOut_.back(); spareOut_.pop_back();
            Urb* i = spareIn_.back(); spareIn_.pop_back();
            o->spare = i->spare = false;
            if (submit(*i)) inflightIn_++;
            fillOut(*o);
            if (submit(*o)) inflightOut_++;
            activeUrbs_++;
            gEngine.bufferFrames.store(activeUrbs_ * packets_ * nominal_);
            queueMs.store(activeUrbs_ * packets_ / 8);
        }
    }
    maxBatch.store(std::max(maxBatch.load(std::memory_order_relaxed), int(batch_.size())), std::memory_order_relaxed);
    for (Urb* u : batch_) if (u->hid) {
        if (u->u->status == 0 && u->u->actual_length > 0) {
            std::lock_guard<std::mutex> g(hidLock_);
            if (hidQueue_.size() < 256) hidQueue_.emplace_back(u->buf.begin(), u->buf.begin() + u->u->actual_length);
            hidCv_.notify_one();
        }
        u->u->buffer_length = int(u->buf.size());
        if (submit(*u)) inflightHid_++;
    }
    // IN first, so the OUT refill uses the freshest packet sizes.
    for (Urb* u : batch_) if (!u->out && !u->hid) { handleIn(*u); if (submit(*u)) inflightIn_++; }
    for (Urb* u : batch_) if (u->out && !u->hid) { fillOut(*u); if (submit(*u)) inflightOut_++; }
}

void UsbAudio::pumpFromCallback() {
    if (cbPolicy.load(std::memory_order_relaxed) < 0) cbPolicy.store(sched_getscheduler(0));
    lastCbNs_.store(nowNanos(), std::memory_order_relaxed);
    if (pumpLock_.try_lock()) {
        pump(true);
        pumpLock_.unlock();
    }
}

static aaudio_data_callback_result_t usbDriverCallback(AAudioStream*, void* user, void* audio, int32_t frames) {
    std::memset(audio, 0, sizeof(float) * size_t(frames) * 2);  // the phone's output stays silent
    static_cast<UsbAudio*>(user)->pumpFromCallback();
    return AAUDIO_CALLBACK_RESULT_CONTINUE;
}

static void usbDriverError(AAudioStream*, void*, aaudio_result_t error) {
    LOGI("usb: driver stream error %s (fallback thread takes over)", AAudio_convertResultToText(error));
}

bool UsbAudio::openDriver() {
    AAudioStreamBuilder* b = nullptr;
    if (AAudio_createStreamBuilder(&b) != AAUDIO_OK) return false;
    AAudioStreamBuilder_setDirection(b, AAUDIO_DIRECTION_OUTPUT);
    AAudioStreamBuilder_setPerformanceMode(b, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
    AAudioStreamBuilder_setSharingMode(b, AAUDIO_SHARING_MODE_EXCLUSIVE);
    AAudioStreamBuilder_setFormat(b, AAUDIO_FORMAT_PCM_FLOAT);
    AAudioStreamBuilder_setChannelCount(b, 2);
    AAudioStreamBuilder_setUsage(b, AAUDIO_USAGE_MEDIA);
    AAudioStreamBuilder_setDataCallback(b, usbDriverCallback, this);
    AAudioStreamBuilder_setErrorCallback(b, usbDriverError, this);
    aaudio_result_t r = AAudioStreamBuilder_openStream(b, &driver_);
    if (r != AAUDIO_OK) {
        AAudioStreamBuilder_setSharingMode(b, AAUDIO_SHARING_MODE_SHARED);
        r = AAudioStreamBuilder_openStream(b, &driver_);
    }
    AAudioStreamBuilder_delete(b);
    if (r != AAUDIO_OK) {
        LOGI("usb: driver stream open failed: %s", AAudio_convertResultToText(r));
        driver_ = nullptr;
        return false;
    }
    int32_t burst = AAudioStream_getFramesPerBurst(driver_);
    AAudioStream_setBufferSizeInFrames(driver_, burst * 2);
    driverBurst.store(burst);
    if (AAudioStream_requestStart(driver_) != AAUDIO_OK) {
        AAudioStream_close(driver_);
        driver_ = nullptr;
        return false;
    }
    LOGI("usb: driver stream open: burst=%d rate=%d exclusive=%d", burst, AAudioStream_getSampleRate(driver_),
         AAudioStream_getSharingMode(driver_) == AAUDIO_SHARING_MODE_EXCLUSIVE ? 1 : 0);
    return true;
}

void UsbAudio::closeDriver() {
    if (!driver_) return;
    AAudioStream_requestStop(driver_);
    AAudioStream_close(driver_);
    driver_ = nullptr;
}

/** Services the queue only while the real-time callback isn't (not started, or stopped). */
void UsbAudio::fallbackLoop() {
    setpriority(PRIO_PROCESS, 0, -19);
    while (!stopReq_.load()) {
        int64_t sinceCb = nowNanos() - lastCbNs_.load(std::memory_order_relaxed);
        if (sinceCb < 20000000LL) {  // callback active
            usleep(5000);
            continue;
        }
        pollfd p{fd_, POLLOUT, 0};
        poll(&p, 1, 2);
        std::lock_guard<std::mutex> g(pumpLock_);
        pump(false);
    }
}

int UsbAudio::readHid(uint8_t* out, int cap, int timeoutMs) {
    std::unique_lock<std::mutex> g(hidLock_);
    if (hidQueue_.empty()) {
        if (!running.load()) return -1;
        hidCv_.wait_for(g, std::chrono::milliseconds(timeoutMs), [this] { return !hidQueue_.empty() || !running.load(); });
    }
    if (hidQueue_.empty()) return running.load() ? 0 : -1;
    std::vector<uint8_t> r = std::move(hidQueue_.front());
    hidQueue_.pop_front();
    int n = std::min(cap, int(r.size()));
    std::memcpy(out, r.data(), size_t(n));
    return n;
}

bool UsbAudio::start(int fd, int outEp, int outMax, int inEp, int inMax,
                     int outCh, int inCh, int subslot, int urbs, int packets, int hidEp, int hidMax) {
    stop();
    if (packets < 1 || packets > 64 || urbs < 2 || urbs > 32) return false;
    fd_ = fd; outEp_ = outEp; outMax_ = outMax; inEp_ = inEp; inMax_ = inMax;
    outCh_ = outCh; inCh_ = inCh; subslot_ = subslot; packets_ = packets;
    stopReq_.store(false);
    lastError.store(0);
    late.store(0); fifoEmpty.store(0); packetsIn.store(0); framesIn.store(0);
    fifoTrim.store(0); inErrors.store(0); inBig.store(0); maxBatch.store(0); startNs.store(0);
    for (auto& h : hist) h.store(0);
    fifo_.clear();
    urbs_.clear();
    size_t urbSize = sizeof(usbdevfs_urb) + size_t(packets) * sizeof(usbdevfs_iso_packet_desc);
    // Pre-allocate room to grow to 16 ms (allocating in the real-time callback is not OK).
    maxUrbs_ = std::max(urbs, 16 * 8 / packets);
    activeUrbs_ = urbs;
    spareOut_.clear();
    spareIn_.clear();
    for (int dir = 0; dir < 2; dir++) {
        bool out = dir == 0;
        for (int i = 0; i < maxUrbs_; i++) {
            auto u = std::make_unique<Urb>();
            u->out = out;
            u->spare = i >= urbs;
            if (u->spare) (out ? spareOut_ : spareIn_).push_back(u.get());
            u->buf.assign(size_t(packets) * size_t(out ? outMax : inMax), 0);
            u->u = static_cast<usbdevfs_urb*>(calloc(1, urbSize));
            u->u->type = USBDEVFS_URB_TYPE_ISO;
            u->u->endpoint = static_cast<unsigned char>(out ? outEp : inEp);
            u->u->flags = USBDEVFS_URB_ISO_ASAP;
            u->u->buffer = u->buf.data();
            u->u->buffer_length = int(u->buf.size());
            u->u->number_of_packets = packets;
            for (int p = 0; p < packets; p++) u->u->iso_frame_desc[p].length = unsigned(out ? 0 : inMax);
            urbs_.push_back(std::move(u));
        }
    }
    if (hidEp != 0 && hidMax > 0) {
        for (int i = 0; i < 2; i++) {
            auto u = std::make_unique<Urb>();
            u->hid = true;
            u->buf.assign(size_t(hidMax), 0);
            u->u = static_cast<usbdevfs_urb*>(calloc(1, sizeof(usbdevfs_urb)));
            u->u->type = USBDEVFS_URB_TYPE_INTERRUPT;
            u->u->endpoint = static_cast<unsigned char>(hidEp);
            u->u->buffer = u->buf.data();
            u->u->buffer_length = hidMax;
            urbs_.push_back(std::move(u));
            hidCount_++;
        }
    }
    {
        std::lock_guard<std::mutex> g(hidLock_);
        hidQueue_.clear();
    }
    hidCount_ = 0;
    for (auto& u : urbs_) if (u->hid) hidCount_++;
    gEngine.burstFrames.store(packets * nominal_);
    gEngine.bufferFrames.store(urbs * packets * nominal_);
    queueMs.store(urbs * packets / 8);
    gEngine.exclusive.store(true);
    gEngine.xruns.store(0);
    stalls8.store(0); stalls16.store(0); maxStallUs.store(0); cbPumps.store(0); thPumps.store(0);
    cbPolicy.store(-1); driverBurst.store(0); lastCbNs_.store(0); lastReapNs_ = 0;
    {
        std::lock_guard<std::mutex> g(pumpLock_);
        inflightOut_ = inflightIn_ = inflightHid_ = 0;
        for (auto& u : urbs_) {
            if (u->spare) continue;
            if (u->hid) { if (submit(*u)) inflightHid_++; }
            else if (!u->out) { if (submit(*u)) inflightIn_++; }
        }
        for (auto& u : urbs_) if (u->out && !u->hid && !u->spare) { fillOut(*u); if (submit(*u)) inflightOut_++; }
        if (inflightOut_ == 0 || inflightIn_ == 0) {
            LOGI("usb: endpoints refused URBs (out=%d in=%d)", inflightOut_, inflightIn_);
            stopReq_.store(true);
        }
        if (inflightHid_ == 0 && hidCount_ > 0) LOGI("usb: HID URBs could not be queued");
    }
    if (stopReq_.load()) {
        stop();
        return false;
    }
    startNs.store(nowNanos());
    running.store(true);
    LOGI("usb: streaming, %d OUT / %d IN / %d HID URBs in flight", inflightOut_, inflightIn_, inflightHid_);
    thread_ = std::thread([this] { fallbackLoop(); });
    if (!openDriver()) LOGI("usb: no real-time driver stream; using the fallback thread only");
    return true;
}

void UsbAudio::stop() {
    stopReq_.store(true);
    closeDriver();                                  // no more callback pumps
    if (thread_.joinable()) thread_.join();         // no more fallback pumps
    if (fd_ >= 0) {
        for (auto& u : urbs_)
            if (u->inflight.load()) ioctl(fd_, USBDEVFS_DISCARDURB, u->u);
        // Discarded URBs still have to be reaped before their memory can be freed.
        for (int i = 0; i < 300; i++) {
            bool any = false;
            for (auto& u : urbs_) any |= u->inflight.load();
            if (!any) break;
            usbdevfs_urb* done = nullptr;
            if (ioctl(fd_, USBDEVFS_REAPURBNDELAY, &done) == 0) static_cast<Urb*>(done->usercontext)->inflight.store(false);
            else usleep(1000);
        }
    }
    for (auto& u : urbs_) free(u->u);
    urbs_.clear();
    spareOut_.clear();
    spareIn_.clear();
    running.store(false);
    hidCv_.notify_all();
}

}  // namespace

// =========================================================================================
// JNI
// =========================================================================================

#define JNI_FN(name) Java_org_openmk3_core_audio_NativeEngine_##name

extern "C" {

JNIEXPORT jboolean JNICALL JNI_FN(nativeStart)(JNIEnv*, jobject, jint deviceId, jint inputDeviceId) {
    gEngine.deviceId = deviceId;
    gEngine.inDeviceId = inputDeviceId;
    return gEngine.open() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL JNI_FN(nativeStop)(JNIEnv*, jobject) { gEngine.close(); }

/** Returns true if the stream had to be (and was) reopened. */
JNIEXPORT jboolean JNICALL JNI_FN(nativeTune)(JNIEnv*, jobject) {
    if (gEngine.needsRestart.load()) {
        gEngine.close();
        gEngine.open();
        return JNI_TRUE;
    }
    gEngine.tune();
    return JNI_FALSE;
}

JNIEXPORT void JNICALL JNI_FN(nativeLoadSample)(JNIEnv* env, jobject, jint slot, jfloatArray data, jint channels,
                                                jint choke) {
    if (slot < 0 || slot >= kSlots) return;
    auto* s = new SampleData();
    jsize n = env->GetArrayLength(data);
    s->data.resize(size_t(n));
    env->GetFloatArrayRegion(data, 0, n, s->data.data());
    s->channels = channels == 2 ? 2 : 1;
    s->frames = int(n / s->channels);
    s->choke = choke;
    // Free what the audio thread retired last time, then hand over the new sample.
    delete gEngine.retired[slot].exchange(nullptr, std::memory_order_acq_rel);
    // Always via the audio thread (installed at the top of its next callback), so a sample
    // can never be freed while a voice still plays it. A replaced pending one was never
    // installed, so deleting it is safe.
    delete gEngine.pending[slot].exchange(s, std::memory_order_acq_rel);
}

JNIEXPORT void JNICALL JNI_FN(nativeSetPatternBuffer)(JNIEnv* env, jobject, jobject buf) {
    auto* p = static_cast<float*>(env->GetDirectBufferAddress(buf));
    if (p && env->GetDirectBufferCapacity(buf) >= PATTERN_FLOATS * 4) gEngine.pattern.store(p);
}

JNIEXPORT void JNICALL JNI_FN(nativeSetTrigBuffer)(JNIEnv* env, jobject, jobject buf) {
    auto* p = static_cast<int64_t*>(env->GetDirectBufferAddress(buf));
    if (p && env->GetDirectBufferCapacity(buf) >= kSounds * 8) gEngine.lastTrigMs.store(p);
}

JNIEXPORT void JNICALL JNI_FN(nativeTrigger)(JNIEnv*, jobject, jint slot, jfloat vel) {
    if (slot < 0 || slot >= kSlots) return;
    gEngine.pushTrigger(slot, vel);
}

JNIEXPORT void JNICALL JNI_FN(nativeSetMasterGain)(JNIEnv*, jobject, jfloat g) { gEngine.masterGain.store(g); }

JNIEXPORT void JNICALL JNI_FN(nativeTransport)(JNIEnv*, jobject, jint cmd) {
    switch (cmd) {
        case 0: gEngine.playRequested.store(false); break;                      // stop
        case 1: gEngine.playRequested.store(true); break;                       // play
        case 2: gEngine.restartRequested.store(true); gEngine.playRequested.store(true); break;  // restart
        default: break;
    }
}

JNIEXPORT jint JNICALL JNI_FN(nativeCurrentStep)(JNIEnv*, jobject) { return gEngine.currentStep.load(); }
JNIEXPORT jdouble JNICALL JNI_FN(nativePhase)(JNIEnv*, jobject) { return gEngine.phase.load(); }
JNIEXPORT jfloat JNICALL JNI_FN(nativeMeter)(JNIEnv*, jobject) { return gEngine.meter.load(); }
JNIEXPORT jint JNICALL JNI_FN(nativeXruns)(JNIEnv*, jobject) { return gEngine.xruns.load(); }
JNIEXPORT jint JNICALL JNI_FN(nativeBufferFrames)(JNIEnv*, jobject) { return gEngine.bufferFrames.load(); }
JNIEXPORT jint JNICALL JNI_FN(nativeBurstFrames)(JNIEnv*, jobject) { return gEngine.burstFrames.load(); }
JNIEXPORT jint JNICALL JNI_FN(nativeCallbackFrames)(JNIEnv*, jobject) { return gEngine.callbackFrames.load(); }
JNIEXPORT jboolean JNICALL JNI_FN(nativeExclusive)(JNIEnv*, jobject) { return gEngine.exclusive.load() ? JNI_TRUE : JNI_FALSE; }

JNIEXPORT jboolean JNICALL JNI_FN(nativeInputOpen)(JNIEnv*, jobject) { return gEngine.inOpen.load() ? JNI_TRUE : JNI_FALSE; }
JNIEXPORT jfloat JNICALL JNI_FN(nativeTakeInputPeak)(JNIEnv*, jobject) { return gEngine.inPeak.exchange(0.f); }

JNIEXPORT jboolean JNICALL JNI_FN(nativeUsbStart)(JNIEnv*, jobject, jint fd, jint outEp, jint outMax, jint inEp,
                                                 jint inMax, jint outCh, jint inCh, jint subslot, jint urbs,
                                                 jint packets, jint hidEp, jint hidMax) {
    return gUsb.start(fd, outEp, outMax, inEp, inMax, outCh, inCh, subslot, urbs, packets, hidEp, hidMax) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL JNI_FN(nativeHidRead)(JNIEnv* env, jobject, jbyteArray buf, jint timeoutMs) {
    uint8_t tmp[1024];
    jsize cap = std::min<jsize>(env->GetArrayLength(buf), jsize(sizeof(tmp)));
    int n = gUsb.readHid(tmp, cap, timeoutMs);
    if (n > 0) env->SetByteArrayRegion(buf, 0, n, reinterpret_cast<const jbyte*>(tmp));
    return n;
}
JNIEXPORT void JNICALL JNI_FN(nativeUsbStop)(JNIEnv*, jobject) { gUsb.stop(); }
JNIEXPORT jboolean JNICALL JNI_FN(nativeUsbRunning)(JNIEnv*, jobject) { return gUsb.running.load() ? JNI_TRUE : JNI_FALSE; }
JNIEXPORT jint JNICALL JNI_FN(nativeUsbError)(JNIEnv*, jobject) { return gUsb.lastError.load(); }
JNIEXPORT jint JNICALL JNI_FN(nativeUsbLate)(JNIEnv*, jobject) { return gUsb.late.load(); }
JNIEXPORT jint JNICALL JNI_FN(nativeUsbGuessed)(JNIEnv*, jobject) { return gUsb.fifoEmpty.load(); }

/** One-line diagnostics: wall-clock device rate, packet-size histogram, error counters. */
JNIEXPORT jstring JNICALL JNI_FN(nativeUsbStats)(JNIEnv* env, jobject) {
    char buf[512];
    double secs = (nowNanos() - gUsb.startNs.load()) / 1e9;
    double rate = secs > 1 ? double(gUsb.framesIn.load()) / secs : 0;
    double pps = secs > 1 ? double(gUsb.packetsIn.load()) / secs : 0;
    int n = snprintf(buf, sizeof(buf),
                     "queue=%dms in %.1f Hz (%.0f pkt/s) late=%d stalls>8ms=%d >16ms=%d max=%.1fms pumps cb=%lld(policy %d, burst %d) "
                     "thread=%lld trim=%d err=%d big=%d maxBatch=%d sizes:",
                     gUsb.queueMs.load(), rate, pps, gUsb.late.load(), gUsb.stalls8.load(), gUsb.stalls16.load(), gUsb.maxStallUs.load() / 1000.0,
                     gUsb.cbPumps.load(), gUsb.cbPolicy.load(), gUsb.driverBurst.load(), gUsb.thPumps.load(),
                     gUsb.fifoTrim.load(), gUsb.inErrors.load(), gUsb.inBig.load(), gUsb.maxBatch.load());
    for (int i = 0; i < 16 && n < int(sizeof(buf)) - 16; i++) {
        int c = gUsb.hist[i].load();
        if (c) n += snprintf(buf + n, sizeof(buf) - n, " %d:%d", i, c);
    }
    return env->NewStringUTF(buf);
}
/** Device clock relative to nominal, in ppm, measured from IN packet sizes. */
JNIEXPORT jdouble JNICALL JNI_FN(nativeUsbClockPpm)(JNIEnv*, jobject) {
    long long p = gUsb.packetsIn.load(), f = gUsb.framesIn.load();
    if (p < 8000) return 0.0;
    return (double(f) / double(p) / (kSampleRate / 8000.0) - 1.0) * 1e6;
}

JNIEXPORT void JNICALL JNI_FN(nativeSetParamBuffer)(JNIEnv* env, jobject, jobject buf) {
    if (env->GetDirectBufferCapacity(buf) < jlong(kSlots * kParams * sizeof(float))) return;
    gEngine.params.store(static_cast<float*>(env->GetDirectBufferAddress(buf)), std::memory_order_release);
}

JNIEXPORT void JNICALL JNI_FN(nativeRecArm)(JNIEnv*, jobject, jint source, jboolean threshold, jfloat thr, jint seconds) {
    gRec.arm(source, threshold == JNI_TRUE, thr, seconds);
}
JNIEXPORT void JNICALL JNI_FN(nativeRecStop)(JNIEnv*, jobject) { gRec.stop(); }
JNIEXPORT jint JNICALL JNI_FN(nativeRecState)(JNIEnv*, jobject) { return gRec.state.load(); }
JNIEXPORT jint JNICALL JNI_FN(nativeRecFrames)(JNIEnv*, jobject) { return gRec.frames.load(); }
JNIEXPORT jfloat JNICALL JNI_FN(nativeRecTakePeak)(JNIEnv*, jobject, jint source) {
    return (source == Recorder::SRC_INPUT ? gRec.peakInput : gRec.peakPhone).exchange(0.f);
}
/** Copies the recorded frames (stereo interleaved); call after stopping. */
JNIEXPORT jfloatArray JNICALL JNI_FN(nativeRecTake)(JNIEnv* env, jobject) {
    int n = gRec.frames.load();
    jfloatArray a = env->NewFloatArray(n * 2);
    if (a && n > 0) env->SetFloatArrayRegion(a, 0, n * 2, gRec.buf.data());
    return a;
}
/** Phone playback capture: interleaved stereo float frames from the capture thread. */
JNIEXPORT void JNICALL JNI_FN(nativeRecFeedPhone)(JNIEnv* env, jobject, jfloatArray data, jint frames) {
    jfloat* p = env->GetFloatArrayElements(data, nullptr);
    if (!p) return;
    gRec.feed(p, frames, Recorder::SRC_PHONE);
    env->ReleaseFloatArrayElements(data, p, JNI_ABORT);
}

JNIEXPORT jfloat JNICALL JNI_FN(nativeTakeWorstTrigMs)(JNIEnv*, jobject) {
    return gEngine.worstTrigMs.exchange(0.f);
}

}  // extern "C"
