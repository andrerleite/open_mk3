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
#include <thread>
#ifndef TEST
#include <android/performance_hint.h>
#endif
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

#include "fx.h"
#include "synth.h"
#include "fm.h"
#include "obxf.h"
#include <mutex>
#include <vector>

#define LOG_TAG "MK3Native"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

namespace {

constexpr int kGroups = 8;
constexpr int kPads = 16;
constexpr int kSounds = kGroups * kPads;  // 8 groups x 16 sounds
constexpr int kSteps = 64;  // longest pattern: 4 bars of 16ths
constexpr int kMaxVoices = 64;
constexpr int kSampleRate = 48000;
constexpr int kRing = 256;

// Shared pattern buffer layout (floats); must match Pattern.kt.
// Globals, then two banks per group: the playing pattern and the one queued to play next.
constexpr int kExtraNotes = 3;                            // a step can hold up to 4 notes (chords)
constexpr int OFF_MUTE = 0;                               // kSounds flags
constexpr int OFF_SOLO = OFF_MUTE + kSounds;              // kSounds flags
constexpr int OFF_BPM = OFF_SOLO + kSounds;
constexpr int OFF_SWING = OFF_BPM + 1;
constexpr int OFF_GMUTE = OFF_SWING + 1;                  // kGroups flags
constexpr int OFF_GSOLO = OFF_GMUTE + kGroups;            // kGroups flags
constexpr int OFF_ACTIVE = OFF_GSOLO + kGroups;           // kGroups: bank playing (written by the engine)
constexpr int OFF_PENDING = OFF_ACTIVE + kGroups;         // kGroups: bank to switch to at the loop end (-1 none)
constexpr int OFF_GSTEP = OFF_PENDING + kGroups;          // kGroups: current step (engine), -1 stopped
constexpr int OFF_METRO = OFF_GSTEP + kGroups;            // metronome on
constexpr int OFF_COUNTIN = OFF_METRO + 1;                // 1 = count in one bar at the next start
constexpr int OFF_COUNTING = OFF_COUNTIN + 1;             // engine: 1 while counting in
constexpr int OFF_GVOL = OFF_COUNTING + 1;                // kGroups: group volume (dB)
constexpr int OFF_GPAN = OFF_GVOL + kGroups;              // kGroups: group pan (-1..1)
constexpr int OFF_GFILT = OFF_GPAN + kGroups;             // kGroups: performance filter (-1 LP .. 0 off .. 1 HP)
constexpr int OFF_METROVOL = OFF_GFILT + kGroups;        // metronome level 0..1
constexpr int OFF_COUNTBARS = OFF_METROVOL + 1;           // count-in length in bars (1, 2, 4)
constexpr int OFF_GSWING = OFF_GFILT + kGroups + 6;       // kGroups: extra swing per group (CHANNEL groove)
constexpr int OFF_GORIGIN = OFF_GSWING + kGroups;         // kGroups: global step where the group's pattern started
constexpr int OFF_SWITCHAT = OFF_GORIGIN + kGroups;       // global step where every queued pattern switches (-1 none)
constexpr int OFF_NOW = OFF_SWITCHAT + 1;                 // engine: global step playing now
constexpr int OFF_PFX = OFF_NOW + 1;                      // perform FX: type, engaged, amount, (spare)
constexpr int OFF_GSEND = OFF_PFX + 4;                    // kGroups x 2: send levels to returns A / B
constexpr int OFF_BANKS = OFF_GSEND + kGroups * 2;
// per bank (pad = 0..15 within the group, cell = pad * kSteps + step)
constexpr int B_VEL = 0;
constexpr int B_NOTE = B_VEL + kPads * kSteps;
constexpr int B_XVEL = B_NOTE + kPads * kSteps;
constexpr int B_XNOTE = B_XVEL + kPads * kSteps * kExtraNotes;
constexpr int B_SKIP = B_XNOTE + kPads * kSteps * kExtraNotes;
constexpr int B_NLEN = B_SKIP + kPads * kSteps;           // note length in steps (0 = one step)
constexpr int B_OFF = B_NLEN + kPads * kSteps;            // note start within the step, 0..1
constexpr int kAutoLanes = 16;                            // automation lanes per pattern
constexpr int kLaneFloats = 2 + kSteps;                   // pad + 1 (0 = empty), parameter, a value per step (NaN = none)
constexpr int B_AUTO = B_OFF + kPads * kSteps;
constexpr int B_LEN = B_AUTO + kAutoLanes * kLaneFloats;  // pattern length in steps (1..64)
constexpr int BANK_FLOATS = B_LEN + 1;
constexpr int PATTERN_FLOATS = OFF_BANKS + kGroups * 2 * BANK_FLOATS;
constexpr int kRootNote = 60;                             // C3: the note a pad plays in pad mode

static inline float* bankOf(float* p, int g, int b) { return p + OFF_BANKS + (g * 2 + b) * BANK_FLOATS; }
static inline int activeBank(const float* p, int g) { return p[OFF_ACTIVE + g] > 0.5f ? 1 : 0; }
static inline int bankLen(const float* b) { int l = int(b[B_LEN] + 0.5f); return l <= 0 ? 16 : std::min(l, kSteps); }

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

constexpr int kSlots = kSounds + 1;  // 128 sounds + 1 browser / editor preview slot

// Short ramps that remove clicks without dulling the attack:
// fade-in over ~0.3 ms when a voice starts, fade-out over ~2 ms when it is choked or stolen.
constexpr int kFadeIn = 16;
constexpr int kFadeOut = 96;

// Per-sound parameters: kSlots x kParams floats in a direct buffer shared with Kotlin
// (SoundParams.kt). Indices are stable: saved settings depend on them.
constexpr int kParams = 64;
enum {
    P_TUNE = 0,     // semitones, -36..36 (fractional = fine tune)
    P_VOL = 1,      // dB, -60 (silent) .. +12
    P_PAN = 2,      // -1 left .. 1 right
    P_START = 3,    // 0..1 of the sample
    P_END = 4,      // 0..1 of the sample
    P_REV = 5,      // > 0.5 = reverse
    P_ENV = 6,      // amp envelope: 0 one-shot, 1 AHD, 2 ADSR
    P_ATT = 7,      // seconds
    P_HOLD = 8,     // seconds (AHD)
    P_DEC = 9,      // seconds
    P_FTYPE = 10,   // 0 off, 1 low-pass 12, 2 band-pass, 3 high-pass, 4 low-pass 24
    P_CUT = 11,     // Hz
    P_RES = 12,     // 0..1
    P_SUS = 13,     // 0..1 (ADSR)
    P_REL = 14,     // seconds (ADSR)
    P_POLY = 15,    // voices per sound, 1..16
    P_ENGINE = 16,  // 0 standard, 1 vintage 12-bit/26 kHz, 2 vintage 12-bit/40 kHz
    P_LOOP = 17,    // > 0.5 = loop (AHD / ADSR only)
    P_LSTART = 18,  // 0..1 of the sample
    P_LEND = 19,    // 0..1 of the sample
    P_XFADE = 20,   // 0..1 of the loop length
    P_DRIVE = 21,   // 0..1 saturation before the filter
    P_MATT = 22,    // modulation envelope attack, seconds
    P_MDEC = 23,    // modulation envelope decay, seconds
    P_MPITCH = 24,  // modulation envelope -> pitch, semitones
    P_MCUT = 25,    // modulation envelope -> cutoff, octaves
    P_LWAVE = 26,   // LFO: 0 sine, 1 triangle, 2 saw, 3 square, 4 random
    P_LRATE = 27,   // Hz
    P_LPITCH = 28,  // LFO -> pitch, semitones
    P_LCUT = 29,    // LFO -> cutoff, octaves
    P_LPAN = 30,    // LFO -> pan, 0..1
    P_LVOL = 31,    // LFO -> volume (tremolo), 0..1
    P_VVOL = 32,    // velocity -> volume, 0..1
    P_VCUT = 33,    // velocity -> cutoff, octaves
    P_GATE = 34,    // > 0.5 = gate: the sound stops (with RELEASE) when the pad is let go
    P_SRC = 35,     // sound source: 0 sampler, 1 synth
    P_SMODEL = 36,  // synth model 0..23
    P_SNOTE = 37,   // synth base note (MIDI)
    P_SHARM = 38,   // synth harmonics 0..1
    P_STIMB = 39,   // synth timbre 0..1
    P_SMORPH = 40,  // synth morph 0..1
    P_SDEC = 41,    // synth low-pass gate decay 0..1
    P_SCOL = 42,    // synth low-pass gate colour 0..1
    P_SLPG = 43,    // > 0.5 = the synth's own decaying gate shapes notes
    P_SAUX = 44,    // synth output: 0 main .. 1 auxiliary
    P_SFM = 45,     // synth internal envelope -> pitch, -1..1
    P_STMOD = 46,   // synth internal envelope -> timbre, -1..1
    P_SMMOD = 47,   // synth internal envelope -> morph, -1..1
    P_COLOUR = 48,  // pad colour (UI only)
    P_BEND = 49,    // live pitch bend, semitones (touch strip PITCH)
    P_MODW = 50,    // live modulation 0..1 (touch strip MOD): closes a low-pass filter
    P_FMBANK = 51,  // FM source: bank (0-2 built-in, 3.. user cartridges)
    P_FMPROG = 52,  // FM source: voice 0..31
    P_FMBRIGHT = 53,  // FM: modulator levels -1..1
    P_FMATT = 54,   // FM: attack -1..1
    P_FMREL = 55,   // FM: release -1..1
    P_FMFB = 56,    // FM: feedback 0..7, < 0 = the patch's
    P_CHOKE = 57,   // choke group 1..16 (0 = the sound's default: none, or built-in hats)
};
static float kDefaultParams[kParams];
static bool initDefaults() {
    float* d = kDefaultParams;
    d[P_END] = 1; d[P_ATT] = 0.001f; d[P_HOLD] = 0.05f; d[P_DEC] = 0.5f; d[P_CUT] = 20000;
    d[P_SUS] = 0.7f; d[P_REL] = 0.2f; d[P_POLY] = 1; d[P_LEND] = 1; d[P_MATT] = 0.001f;
    d[P_MDEC] = 0.2f; d[P_LRATE] = 4; d[P_VVOL] = 1;
    d[P_SMODEL] = 8; d[P_SNOTE] = 48; d[P_SHARM] = 0.5f; d[P_STIMB] = 0.5f; d[P_SMORPH] = 0.5f;
    d[P_SDEC] = 0.5f; d[P_SCOL] = 0.5f; d[P_SLPG] = 1; d[P_FMFB] = -1;
    return true;
}
static bool gDefaultsReady = initDefaults();

struct Voice {
    const SampleData* sample = nullptr;
    double pos = 0.0;     // fractional read position (frames)
    int startF = 0, endF = 0;
    bool rev = false;
    float vel = 0.f;
    int startOffset = 0;
    int fadeIn = 0;       // frames rendered so far of the fade-in
    int fadeOut = -1;     // frames left of the fade-out, -1 = not fading
    int slot = -1;
    uint64_t age = 0;
    int note = kRootNote;
    int choke = 0;
    int64_t startAt = 0;
    bool fromSeq = false;
    int gate = -1;        // frames until note-off (sequencer), -1 = held until released
    bool released = false;
    // amp envelope
    int envType = 0;      // 0 one-shot, 1 AHD, 2 ADSR
    int envStage = 0;     // 0 attack, 1 hold, 2 decay, 3 sustain, 4 release
    int envCount = 0;
    float envLevel = 1.f;
    // modulation envelope (attack / decay)
    int mStage = 0;
    int mCount = 0;
    float mLevel = 0.f;
    // LFO
    float lfoPhase = 0.f;
    float lfoHeld = 0.f;
    uint32_t rnd = 1;
    // filter (two state-variable stages, stereo)
    float ic1[2] = {0, 0}, ic2[2] = {0, 0}, jc1[2] = {0, 0}, jc2[2] = {0, 0};
    // vintage engine sample-and-hold
    float vPhase = 1.f;
    float vHeld[2] = {0, 0};
    // gate mode release level
    float gateLevel = 1.f;
    // synth source: index into the synth pool (-1 = sampler), silence counter
    int synth = -1;
    int fm = -1;          // FM source: index into the FM pool
    bool fmUp = false;    // note-off sent to the FM voice
    int pinst = -1;       // POLY source: instance playing this note (the voice only times it)
    int quietBlocks = 0;
    int ageBlocks = 0;
    float zoneShift = 0.f;  // semitones: the zone's root key relative to 60
};

// Synth voices have no sample: they point here so "voice in use" checks stay uniform.
static SampleData gSynthDummy = [] { SampleData d; d.frames = 1; d.data.assign(1, 0.f); return d; }();

constexpr int kSynthVoices = 16;
constexpr int kFmVoices = 16;
constexpr int kPolyInst = 4;  // POLY (OB-Xf) instances: each plays one sound, polyphonically
constexpr int kFxSlots = 4;
constexpr int kChains = kSounds + kGroups + 3;   // per sound, per group, master, returns A / B
constexpr int kChainGroup0 = kSounds;
constexpr int kChainMaster = kSounds + kGroups;
constexpr int kChainReturn0 = kChainMaster + 1;
constexpr int kChunk = 256;                       // frames rendered per pass (bus sizes)
constexpr int kSynthMaxBlockFrames = 16;

struct Trigger {
    int slot;
    float vel;
    int64_t tNanos;
    int type;   // 0 note-on, 1 note-off, 2 kill, 3 repeat hold, 4 repeat release, 5 repeat pressure
    int note;   // MIDI note (60 = the sound as sampled)
    int chord;  // notes started together (a chord is never cut short by its own notes)
};

/** A pad held while NOTE REPEAT / the arpeggiator is on. */
struct Held {
    int slot = -1;
    int note = kRootNote;
    float vel = 0.f;
    int chord = 1;
    uint64_t order = 0;
};
constexpr int kMaxHeld = 32;

class Engine {
public:
    // ---- set from Kotlin ----
    std::atomic<float> masterGain{0.7f};
    std::atomic<float> cpuPeak{0.f};
    // time per section in the current callback, and the breakdown of the slowest one (us)
    int64_t secAcc[4] = {};
    std::atomic<int> slowest[4] = {};
    int overload = 0;
    std::atomic<int> shed{0};  // POLY notes released by the overload guard
    double cpuSum = 0.0;
    int64_t cpuN = 0;
    // ---- perform FX on the master (played with the touch strip) ----
    static constexpr int kPfxRing = 1 << 17;   // ~2.7 s of the master output
    float pfxRing[kPfxRing][2] = {};
    int pfxW = 0;
    bool pfxWas = false;
    int pfxType = 0;
    int pfxStart = 0, pfxLen = 0, pfxPos = 0;  // stutter: loop of the last pfxLen frames
    double pfxRead = 0.0;                      // tape stop: read position
    float pfxSpeed = 1.f;
    float pfxMix = 0.f;                        // 0 dry .. 1 effect (smoothed in and out)
    float pfxHold[2] = {0, 0};
    float pfxHoldPh = 1.f;
    void runPerform(float* out, int frames, const float* p, double bpm);
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
    // ---- sample zones: extra samples of a sound, each for a key and velocity range ----
    static constexpr int kZones = 16;
    std::atomic<SampleData*> zCur[kSounds][kZones];
    std::atomic<SampleData*> zPending[kSounds][kZones];
    std::atomic<SampleData*> zRetired[kSounds][kZones];
    std::atomic<bool> zClear[kSounds][kZones];
    std::atomic<bool> zDirty{false};
    /** Zone mapping: root key, lowest / highest key, lowest / highest velocity (1..127). */
    float zMap[kSounds][kZones][5];

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
        for (int i = 0; i < kSlots; i++) { current[i] = nullptr; pending[i] = nullptr; retired[i] = nullptr; playPos[i] = -1.f; }
        for (int i = 0; i < kSounds; i++)
            for (int z = 0; z < kZones; z++) {
                zCur[i][z] = nullptr; zPending[i][z] = nullptr; zRetired[i][z] = nullptr; zClear[i][z] = false;
                zMap[i][z][0] = 60; zMap[i][z][1] = 0; zMap[i][z][2] = 127; zMap[i][z][3] = 1; zMap[i][z][4] = 127;
            }
        for (int c = 0; c < kChains; c++)
            for (int k = 0; k < kFxSlots; k++) { fxPending[c][k] = nullptr; fxRetired[c][k] = nullptr; }
        for (auto& m : activeMask) m = 0;
    }

    // ---- single-producer / single-consumer trigger ring ----
    Trigger ring[kRing];
    std::atomic<uint32_t> ringHead{0};  // written by producer
    std::atomic<uint32_t> ringTail{0};  // written by consumer

    std::atomic_flag producerLock = ATOMIC_FLAG_INIT;  // input thread + browser preview

    void pushTrigger(int slot, float vel, int type = 0, int note = kRootNote, int chord = 1) {
        while (producerLock.test_and_set(std::memory_order_acquire)) {}
        uint32_t h = ringHead.load(std::memory_order_relaxed);
        uint32_t t = ringTail.load(std::memory_order_acquire);
        if (h - t < kRing) {
            ring[h % kRing] = {slot, vel, nowNanos(), type, note, chord};
            ringHead.store(h + 1, std::memory_order_release);
        }
        producerLock.clear(std::memory_order_release);
    }

    // ---- synth pool and effects (created on Java threads, swapped in by the audio thread) ----
    openmk3::SynthVoice* synthPool[kSynthVoices] = {};
    Voice* synthOwner[kSynthVoices] = {};
    std::atomic<float*> fxParams{nullptr};  // kChains x kFxSlots x kFxParams
    openmk3::Effect* fxCur[kChains][kFxSlots] = {};
    std::atomic<openmk3::Effect*> fxPending[kChains][kFxSlots];
    std::atomic<openmk3::Effect*> fxRetired[kChains][kFxSlots];
    std::atomic<int> fxDirty{0};
    // oscilloscope tap: 0 master, 1 group (index), 2 sound (index)
    std::atomic<int> scopeKind{0};
    std::atomic<int> scopeIndex{0};
    static constexpr int kScope = 8192;
    float scopeRing[kScope] = {};
    std::atomic<int> scopeW{0};

    openmk3::FmVoice* fmPool[kFmVoices] = {};
    Voice* fmOwner[kFmVoices] = {};

    // ---- POLY source (OB-Xf): a few whole-synth instances, bound to sounds on demand ----
    struct PolyEvent { int at; int type; int note; float vel; };  // type 0 on, 1 off, 2 all off
    struct PolyInst {
        openmk3::PolySynth* synth = nullptr;
        int slot = -1;               // sound it plays (-1 = free)
        uint64_t lastUse = 0;
        int quietChunks = 0;         // chunks without notes or sound
        int notes = 0;               // notes on (approximate)
        float cur[openmk3::kPolyParams];  // parameters applied (NaN = apply at the next render)
        PolyEvent ev[64];
        int evN = 0;
        void push(const PolyEvent& e) { if (evN < 64) ev[evN++] = e; }
    };
    PolyInst polyInst[kPolyInst];
    /** Each sound's POLY patch (0..1 per parameter), written by Java. */
    float polyPatch[kSounds][openmk3::kPolyParams];
    bool polyPatchInit = false;
    void initPolyPatches() {
        if (polyPatchInit) return;
        for (auto& pp : polyPatch)
            for (int i = 0; i < openmk3::kPolyParams; i++) pp[i] = openmk3::PolySynth::paramDefault(i);
        polyPatchInit = true;
    }

    void ensureSynths() {
        for (auto& sv : synthPool)
            if (!sv) sv = openmk3::SynthVoice::create();
        openmk3::FmVoice::initTables(kSampleRate);
        for (auto& f : fmPool)
            if (!f) f = openmk3::FmVoice::create();
        initPolyPatches();
        for (auto& pi : polyInst) {
            if (!pi.synth) pi.synth = openmk3::PolySynth::create(float(kSampleRate));
            for (float& c : pi.cur) c = NAN;
        }
    }

    /** The instance for [slot]'s next note: its own, else a quiet one, else the least recent. */
    int polyFor(int slot) {
        int best = -1;
        for (int k = 0; k < kPolyInst; k++) if (polyInst[k].synth && polyInst[k].slot == slot) return k;
        for (int k = 0; k < kPolyInst; k++) {
            auto& pi = polyInst[k];
            if (!pi.synth) continue;
            if (pi.slot < 0) { best = k; break; }
            if (best < 0) { best = k; continue; }
            auto& b = polyInst[best];
            const bool q = pi.quietChunks > 20, bq = b.quietChunks > 20;
            if ((q && !bq) || (q == bq && pi.lastUse < b.lastUse)) best = k;
        }
        if (best < 0) return -1;
        auto& pi = polyInst[best];
        if (pi.slot >= 0) {
            // taken from another sound: its notes stop
            pi.push({0, 2, 0, 0.f});
            for (auto& v : voices) if (v.sample && v.pinst == best) { v.pinst = -1; v.sample = nullptr; }
        }
        pi.slot = slot;
        pi.notes = 0;
        for (float& c : pi.cur) c = NAN;
        return best;
    }

    /** Renders the POLY instances into their sounds' buses. */
    void renderPoly(int frames, float* pb, const float* fp, float* out, float* scopeTap, int sk, int si, uint32_t* active);

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

    void startVoice(int slot, float vel, int offset, int gate, bool fromSeq, int note = kRootNote, int chord = 1);
    void releaseSlot(int slot, int note = -1);
    void runRepeat(int frames);
    // notes that start inside a step (off-grid): fired when their moment comes
    struct Pending { double at; int slot; float vel; int note; int chord; int gate; };
    static constexpr int kPending = 512;
    Pending pend[kPending];
    int pendCount = 0;
    // metronome click (a short sine burst, higher on the first beat of the bar)
    int clickDelay = -1;    // frames into the chunk where a click starts
    int clickPos = -1;      // frames rendered of the current click
    float clickFreq = 1500.f;
    float clickLevel = 0.7f;
    void addClick(float* out, int frames);
    void recordEvent(float* p, int sound, double at, int note, float vel);
    Held held[kMaxHeld];
    uint64_t heldOrder = 0;
    bool rpIdle = true;
    bool rpPlayingBase = false;
    double rpPhase = 0.0;   // free-running phase (steps) while the transport is stopped
    double rpNext = 0.0;    // next repeat / arp tick (steps)
    int arpIndex = 0;
    int arpDir = 1;
    uint32_t arpRnd = 12345;
public:
    // NOTE REPEAT / ARP settings (set from Kotlin)
    std::atomic<int> repeatMode{0};      // 0 off, 1 note repeat, 2 arpeggiator
    std::atomic<float> repeatRate{1.f};  // in 16th steps (1 = 1/16, 2 = 1/8, 2/3 = 1/16T ...)
    std::atomic<float> repeatGate{0.5f}; // note length, fraction of the rate
    std::atomic<int> arpType{0};         // 0 up, 1 down, 2 up-down, 3 random, 4 as played, 5 chord
    std::atomic<int> arpOctaves{1};
    std::atomic<int> repeatDyn{0};       // velocity shape: 0 as played, 1 full, 2 accent, 3 ramp up, 4 ramp down, 5 random
    std::atomic<int> repeatRhythm{0};    // index into kRhythms: which ticks play
    int rpTick = 0;
    std::atomic<bool> recording{false};  // repeat / arp hits are written into the pattern
    std::atomic<bool> recorded{false};   // set when the engine wrote into the pattern (Kotlin syncs)
private:
    void killSlot(int slot);
    int fxResetChunks = 0;
    int64_t framesDone = 0;  // frames rendered so far (absolute time for duplicate checks)
    void renderChunk(float* out, int32_t frames);
    void freeSynth(Voice& v) {
        if (v.pinst >= 0) {  // a POLY note taken over: its note ends
            polyInst[v.pinst].push({0, 1, v.note, 0.f});
            polyInst[v.pinst].notes = std::max(0, polyInst[v.pinst].notes - 1);
            v.pinst = -1;
        }
        if (v.synth >= 0 && synthOwner[v.synth] == &v) synthOwner[v.synth] = nullptr;
        v.synth = -1;
        if (v.fm >= 0 && fmOwner[v.fm] == &v) fmOwner[v.fm] = nullptr;
        v.fm = -1;
    }
    bool chainActive(const float* fp, int chain) const {
        if (!fp) return false;
        for (int k = 0; k < kFxSlots; k++) {
            const float* q = fp + (chain * kFxSlots + k) * openmk3::kFxParams;
            if (fxCur[chain][k] && q[0] > 0.5f && q[1] < 0.5f) return true;
        }
        return false;
    }
    // A chain whose input and output have been silent for ~1.5 s sleeps (its effects are not run)
    // until sound arrives again: idle reverbs and delays cost nothing.
    static constexpr int kChainSleep = int(1.5 * kSampleRate / kChunk);
    int chainQuiet[kChains] = {};
    static float peakOf(const float* b, int n) {
        float p = 0.f;
        for (int i = 0; i < n; i++) p = std::max(p, std::fabs(b[i]));
        return p;
    }
    bool chainAsleep(int chain) const { return chainQuiet[chain] > kChainSleep; }
    void runChain(const float* fp, int chain, float* buf, int frames, double bpm) {
        const float in = peakOf(buf, frames * 2);
        if (in < 1e-6f && chainAsleep(chain)) return;  // silent in, silent out
        bool any = false;
        for (int k = 0; k < kFxSlots; k++) {
            const float* q = fp + (chain * kFxSlots + k) * openmk3::kFxParams;
            openmk3::Effect* e = fxCur[chain][k];
            if (e && q[0] > 0.5f && q[1] < 0.5f) { e->process(buf, frames, q, bpm, float(kSampleRate)); any = true; }
        }
        if (!any) return;
        if (in < 1e-6f && peakOf(buf, frames * 2) < 2e-5f) chainQuiet[chain]++;
        else chainQuiet[chain] = 0;
    }
    float soundBus[kSounds][kChunk * 2];
    float gfIc1[kGroups][2] = {}, gfIc2[kGroups][2] = {};  // performance filter state
    bool soundUsed[kSounds];
    float groupBus[kGroups][kChunk * 2];
    float sendBus[2][kChunk * 2];
    float synthOut[kSynthMaxBlockFrames];
    float synthAux[kSynthMaxBlockFrames];
public:
    std::atomic<uint32_t> activeMask[(kSlots + 31) / 32];  // bit per slot with a voice playing
    std::atomic<float> playPos[kSlots];                    // newest voice's read position (frames), -1 = silent
private:
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
    // Keep our own output out of phone-audio capture: being captured moves it off the fast path.
    AAudioStreamBuilder_setAllowedCapturePolicy(b, AAUDIO_ALLOW_CAPTURE_BY_NONE);
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

void Engine::releaseSlot(int slot, int note) {
    for (auto& v : voices)
        if (v.sample && v.slot == slot && !v.fromSeq && (note < 0 || v.note == note)) v.released = true;
}

void Engine::killSlot(int slot) {
    for (auto& v : voices)
        if (v.sample && (slot < 0 || v.slot == slot) && v.fadeOut < 0) v.fadeOut = kFadeOut;
    if (slot < 0) fxResetChunks = 3;  // everything off: effect tails too (after the fade-outs)
    for (auto& pi : polyInst)
        if (pi.slot >= 0 && (slot < 0 || pi.slot == slot)) { pi.push({0, 2, 0, 0.f}); pi.notes = 0; }
}

void Engine::startVoice(int slot, float vel, int offset, int gate, bool fromSeq, int note, int chord) {
    if (slot < 0 || slot >= kSlots) return;
    float* pb = params.load(std::memory_order_acquire);
    const float* P = pb ? pb + slot * kParams : kDefaultParams;
    const int src = slot < kSounds ? int(P[P_SRC] + 0.5f) : 0;
    const bool isFm = src == 2;
    const bool isPoly = src == 3;
    const bool isSynth = src == 1 || isFm || isPoly;  // no sample: all use the dummy
    const SampleData* s = isSynth ? &gSynthDummy : current[slot].load(std::memory_order_acquire);
    // zones: the first zone whose key and velocity ranges hold this note replaces the sample
    float zoneShift = 0.f;
    if (!isSynth && slot < kSounds) {
        const int nn = std::clamp(note, 0, 127), vv = std::clamp(int(vel * 127.f + 0.5f), 1, 127);
        for (int z = 0; z < kZones; z++) {
            const SampleData* zs = zCur[slot][z].load(std::memory_order_acquire);
            if (!zs || zs->frames <= 0) continue;
            const float* m = zMap[slot][z];
            if (nn < m[1] || nn > m[2] || vv < m[3] || vv > m[4]) continue;
            s = zs;
            zoneShift = m[0] - float(kRootNote);
            break;
        }
    }
    if (!s || s->frames <= 0) return;
    // Polyphony per sound (default 1, like a drum machine pad): a new hit fades out the
    // oldest voices of this sound beyond the limit instead of piling on their tails.
    // The same note of the same sound starting at the same moment twice (a recorded note
    // and the live repeat that recorded it) would only cut itself off: play it once.
    const int64_t startAt = framesDone + offset;
    for (auto& v : voices)
        if (v.sample && v.slot == slot && v.note == std::clamp(note, 0, 127) && std::llabs(v.startAt - startAt) < 48) return;
    // POLYPHONY is strict (1 = monophonic, also for chords); POLY: its patch sets the polyphony
    (void)chord;
    int poly = isPoly ? 64 : std::clamp(int(P[P_POLY] + 0.5f), 1, 16);
    while (true) {
        int count = 0;
        Voice* oldestOfSlot = nullptr;
        for (auto& v : voices) {
            if (!v.sample || v.slot != slot || v.fadeOut >= 0) continue;
            count++;
            if (!oldestOfSlot || v.age < oldestOfSlot->age) oldestOfSlot = &v;
        }
        if (count < poly || !oldestOfSlot) break;
        oldestOfSlot->fadeOut = kFadeOut;
    }
    const int choke = P[P_CHOKE] > 0.5f ? std::clamp(int(P[P_CHOKE] + 0.5f), 1, 16) : s->choke;
    if (choke != 0) {
        // a sound silences the others of its choke group (e.g. closed hat -> open hat): fade, not cut
        for (auto& v : voices)
            if (v.sample && v.slot != slot && v.choke == choke && v.fadeOut < 0) v.fadeOut = kFadeOut;
    }
    // A free voice, else the oldest one (with 32 voices this is rare).
    Voice* best = nullptr;
    Voice* oldest = &voices[0];
    for (auto& v : voices) {
        if (!v.sample) { best = &v; break; }
        if (v.age < oldest->age) oldest = &v;
    }
    if (!best) best = oldest;
    freeSynth(*best);
    int synthIdx = -1;
    int fmIdx = -1;
    int polyIdx = -1;
    if (isPoly) {
        polyIdx = polyFor(slot);
        if (polyIdx < 0) return;
    } else if (isFm) {
        for (int k = 0; k < kFmVoices; k++)
            if (fmPool[k] && !fmOwner[k]) { fmIdx = k; break; }
        if (fmIdx < 0) {
            Voice* o = nullptr;
            for (auto& v : voices)
                if (v.sample && v.fm >= 0 && (!o || v.age < o->age)) o = &v;
            if (!o) return;
            fmIdx = o->fm;
            o->fm = -1;
            o->sample = nullptr;
        }
    } else if (isSynth) {
        for (int k = 0; k < kSynthVoices; k++)
            if (synthPool[k] && !synthOwner[k]) { synthIdx = k; break; }
        if (synthIdx < 0) {
            // all synth voices busy: take the oldest one's
            Voice* o = nullptr;
            for (auto& v : voices)
                if (v.sample && v.synth >= 0 && (!o || v.age < o->age)) o = &v;
            if (!o) return;
            synthIdx = o->synth;
            o->synth = -1;
            o->sample = nullptr;
        }
    }
    float a = std::clamp(P[P_START], 0.f, 1.f);
    float b = std::clamp(P[P_END], 0.f, 1.f);
    int sf = int(a * float(s->frames));
    int ef = int(b * float(s->frames));
    if (ef <= sf) ef = std::min(s->frames, sf + 1);
    if (sf >= s->frames && !isSynth) return;
    Voice& v = *best;
    v = Voice();
    v.sample = s;
    v.startF = sf;
    v.endF = ef;
    v.rev = P[P_REV] > 0.5f;
    v.pos = v.rev ? double(ef - 1) : double(sf);
    v.vel = vel;
    v.startOffset = offset;
    v.slot = slot;
    v.age = ++voiceAge;
    v.fromSeq = fromSeq;
    v.note = std::clamp(note, 0, 127);
    v.choke = choke;
    v.startAt = startAt;
    v.gate = gate;
    v.envType = std::clamp(int(P[P_ENV] + 0.5f), 0, 2);
    v.envLevel = (v.envType == 0 || P[P_ATT] <= 0.0005f) ? 1.f : 0.f;
    v.rnd = uint32_t(voiceAge * 2654435761u) | 1u;
    v.zoneShift = zoneShift;
    if (isSynth) {
        v.startF = 0;
        v.endF = 1;
        v.pos = 0.0;
        v.rev = false;
        if (isPoly) {
            v.pinst = polyIdx;
            auto& pi = polyInst[polyIdx];
            pi.lastUse = voiceAge;
            pi.quietChunks = 0;
            pi.notes++;
            pi.push({std::max(0, offset), 0, std::clamp(note, 0, 127), std::clamp(vel, 0.01f, 1.f)});
        } else if (isFm) {
            v.fm = fmIdx;
            fmOwner[fmIdx] = &v;
            openmk3::FmEdit e;
            e.brightness = std::clamp(P[P_FMBRIGHT], -1.f, 1.f);
            e.attack = std::clamp(P[P_FMATT], -1.f, 1.f);
            e.release = std::clamp(P[P_FMREL], -1.f, 1.f);
            e.feedback = P[P_FMFB] < -0.5f ? -1 : std::clamp(int(P[P_FMFB] + 0.5f), 0, 7);
            fmPool[fmIdx]->noteOn(int(P[P_FMBANK] + 0.5f), int(P[P_FMPROG] + 0.5f), e,
                                  std::clamp(note, 0, 127), std::clamp(int(vel * 127.f), 1, 127));
        } else {
            v.synth = synthIdx;
            synthOwner[synthIdx] = &v;
        }
    }
    if (int64_t* lt = lastTrigMs.load(std::memory_order_acquire); lt && slot < kSounds) {
        lt[slot] = nowNanos() / 1000000LL + int64_t(offset) * 1000 / kSampleRate;
    }
}

void Engine::runSequencer(int frames) {
    float* p = pattern.load(std::memory_order_acquire);
    bool on = playRequested.load(std::memory_order_acquire) && p != nullptr;
    // Always consume the restart flag: PLAY sets it too, and leaving it set made the
    // pattern restart (and step 1 sound twice) one callback after starting.
    bool restart = restartRequested.exchange(false);
    if (p && !on) {
        // stopped: queued patterns switch right away
        for (int g = 0; g < kGroups; g++) {
            int pend = int(p[OFF_PENDING + g]);
            if (pend >= 0) { p[OFF_ACTIVE + g] = float(pend); p[OFF_PENDING + g] = -1.f; }
        }
    }
    if (on && (!wasPlaying || restart)) {
        nextStep = 0;
        if (p[OFF_COUNTIN] > 0.5f) {  // clicks first (1, 2 or 4 bars)
            nextStep = -16 * std::clamp(int(p[OFF_COUNTBARS] + 0.5f), 1, 4);
            p[OFF_COUNTIN] = 0.f;
            p[OFF_COUNTING] = 1.f;
        }
        seqPhase = double(nextStep);
        pendCount = 0;
        for (int g = 0; g < kGroups; g++) p[OFF_GORIGIN + g] = 0.f;
        for (int g = 0; g < kGroups; g++)
            for (int b = 0; b < 2; b++) {
                float* bk = bankOf(p, g, b);
                for (int i = 0; i < kPads * kSteps; i++) bk[B_SKIP + i] = 0.f;
            }
    }
    if (!on && wasPlaying) {
        // Stopping ends the notes the sequencer started (their release phase plays out).
        for (auto& v : voices)
            if (v.sample && v.fromSeq) v.gate = 0;
        p[OFF_COUNTING] = 0.f;
        pendCount = 0;
    }
    wasPlaying = on;
    if (!on) {
        currentStep.store(-1, std::memory_order_relaxed);
        if (p) for (int g = 0; g < kGroups; g++) p[OFF_GSTEP + g] = -1.f;
        return;
    }
    double bpm = p[OFF_BPM];
    if (!(bpm >= 20.0 && bpm <= 400.0)) bpm = 120.0;
    double swing = p[OFF_SWING];
    if (!(swing >= 0.0 && swing <= 0.5)) swing = 0.0;
    // Audibility: group mute/solo, then sound mute/solo within the group (a solo only
    // silences the other sounds of its own group).
    bool groupOn[kGroups];
    bool anyGroupSolo = false;
    for (int g = 0; g < kGroups; g++) anyGroupSolo |= p[OFF_GSOLO + g] > 0.5f;
    bool soloInGroup[kGroups];
    for (int g = 0; g < kGroups; g++) {
        groupOn[g] = p[OFF_GMUTE + g] < 0.5f && (!anyGroupSolo || p[OFF_GSOLO + g] > 0.5f);
        soloInGroup[g] = false;
        for (int k = 0; k < kPads; k++) soloInGroup[g] |= p[OFF_SOLO + g * kPads + k] > 0.5f;
    }
    double stepLen = kSampleRate * 60.0 / bpm / 4.0;
    double start = seqPhase;
    double end = start + frames / stepLen;
    const bool metro = p[OFF_METRO] > 0.5f;
    while (true) {
        int64_t n = nextStep;
        double t = double(n) + ((n & 1) ? swing : 0.0);
        if (t >= end) break;
        // Round to the nearest frame; a step that rounds onto the next callback waits for it.
        long off = std::lround((t - start) * stepLen);
        if (off >= frames) break;
        if (off < 0) off = 0;
        // metronome: every beat (and through the count-in)
        if ((metro || n < 0) && ((n % 4) + 4) % 4 == 0) {
            clickDelay = int(off);
            clickPos = 0;
            clickFreq = (((n % 16) + 16) % 16 == 0) ? 2400.f : 1500.f;
            clickLevel = std::clamp(p[OFF_METROVOL], 0.f, 1.f);
        }
        if (n < 0) { nextStep = n + 1; continue; }  // counting in
        if (n == 0) p[OFF_COUNTING] = 0.f;
        p[OFF_NOW] = float(n);
        // song sections: every queued pattern starts together at this step
        const bool switchAll = p[OFF_SWITCHAT] >= 0.f && int64_t(p[OFF_SWITCHAT]) <= n;
        if (switchAll) p[OFF_SWITCHAT] = -1.f;
        for (int g = 0; g < kGroups; g++) {
            // queued pattern: switch when this group's pattern starts over
            int ab = activeBank(p, g);
            float* bk = bankOf(p, g, ab);
            int len = bankLen(bk);
            const int64_t origin = int64_t(p[OFF_GORIGIN + g]);
            if (switchAll || (n - origin) % len == 0) {
                int pend = int(p[OFF_PENDING + g]);
                p[OFF_GORIGIN + g] = float(n);
                if (pend >= 0) {
                    p[OFF_ACTIVE + g] = float(pend);
                    p[OFF_PENDING + g] = -1.f;
                    ab = pend;
                    bk = bankOf(p, g, ab);
                    len = bankLen(bk);
                }
            }
            const int step = int((((n - int64_t(p[OFF_GORIGIN + g])) % len) + len) % len);
            p[OFF_GSTEP + g] = float(step);
            // automation: set the recorded parameter values for this step
            if (float* pb = params.load(std::memory_order_relaxed)) {
                for (int ln = 0; ln < kAutoLanes; ln++) {
                    const float* lane = bk + B_AUTO + ln * kLaneFloats;
                    int pad = int(lane[0]) - 1, prm = int(lane[1]);  // pad + 1 (0 = empty lane)
                    if (pad < 0 || pad >= kPads || prm < 0 || prm >= kParams) continue;
                    float v = lane[2 + step];
                    if (v == v) pb[(g * kPads + pad) * kParams + prm] = v;  // v == v: not NaN
                }
            }
            for (int pad = 0; pad < kPads; pad++) {
                const int s = g * kPads + pad;
                const int cell = pad * kSteps + step;
                float v = bk[B_VEL + cell];
                int extras = 0;
                for (int x = 0; x < kExtraNotes; x++) extras += bk[B_XVEL + cell * kExtraNotes + x] > 0.f;
                if (v <= 0.f && extras == 0) continue;
                float& skip = bk[B_SKIP + cell];
                if (skip > 0.5f) { skip = 0.f; continue; }
                bool audible = groupOn[g] && (soloInGroup[g] ? p[OFF_SOLO + s] > 0.5f : p[OFF_MUTE + s] < 0.5f);
                if (!audible) continue;
                const int chord = (v > 0.f) + extras;
                float nl = bk[B_NLEN + cell];
                const int gate = std::max(16, int((nl > 0.f ? nl : 1.f) * stepLen));
                // the note's own offset plus the group's extra swing on odd 16ths
                const float gsw = (n & 1) ? std::clamp(p[OFF_GSWING + g], 0.f, 0.5f) : 0.f;
                const float noff = std::clamp(bk[B_OFF + cell] + gsw, 0.f, 1.499f);
                auto fire = [&](float vel, int note) {
                    if (noff < 0.001f) { startVoice(s, vel, int(off), gate, true, note, chord); return; }
                    if (pendCount < kPending) pend[pendCount++] = {t + noff, s, vel, note, chord, gate};
                };
                if (v > 0.f) {
                    int note = int(bk[B_NOTE + cell] + 0.5f);
                    fire(v, note > 0 ? note : kRootNote);
                }
                for (int x = 0; x < kExtraNotes; x++) {
                    float xv = bk[B_XVEL + cell * kExtraNotes + x];
                    if (xv > 0.f) fire(xv, int(bk[B_XNOTE + cell * kExtraNotes + x] + 0.5f));
                }
            }
        }
        currentStep.store(int(n % 16), std::memory_order_relaxed);
        nextStep = n + 1;
    }
    // off-grid notes whose moment falls in this chunk
    for (int k = 0; k < pendCount;) {
        if (pend[k].at < end) {
            int o = std::clamp(int(std::lround((pend[k].at - start) * stepLen)), 0, frames - 1);
            startVoice(pend[k].slot, pend[k].vel, o, pend[k].gate, true, pend[k].note, pend[k].chord);
            pend[k] = pend[--pendCount];
        } else k++;
    }
    seqPhase = end;
    phase.store(end, std::memory_order_relaxed);
}

void Engine::addClick(float* out, int frames) {
    if (clickPos < 0) return;
    const int len = kSampleRate / 40;  // 25 ms
    int i = std::max(0, clickDelay);
    clickDelay = 0;
    for (; i < frames && clickPos < len; i++, clickPos++) {
        float t = float(clickPos) / kSampleRate;
        float env = std::exp(-t * 180.f);
        float x = 0.5f * clickLevel * env * std::sin(6.2831853f * clickFreq * t);
        out[i * 2] += x;
        out[i * 2 + 1] += x;
    }
    if (clickPos >= len) clickPos = -1;
}

/** Writes a live (repeat / arp) hit into the pattern at the nearest step. */
void Engine::recordEvent(float* p, int sound, double at, int note, float vel) {
    if (!p || sound < 0 || sound >= kSounds) return;
    long long n = std::llround(at);
    if (n < 0) return;  // count-in
    const int g = sound / kPads, pad = sound % kPads;
    float* bk = bankOf(p, g, activeBank(p, g));
    const int len = bankLen(bk);
    int step = int(n % len);
    const int cell = pad * kSteps + step;
    // main hit, or the extra note slot holding this note, or a free one
    float& mv = bk[B_VEL + cell];
    int mn = int(bk[B_NOTE + cell] + 0.5f);
    if (mn <= 0) mn = kRootNote;
    if (mv <= 0.f || mn == note) {
        mv = std::max(mv, vel);
        bk[B_NOTE + cell] = float(note);
    } else {
        int free = -1;
        bool done = false;
        for (int x = 0; x < kExtraNotes && !done; x++) {
            float& xv = bk[B_XVEL + cell * kExtraNotes + x];
            float& xn = bk[B_XNOTE + cell * kExtraNotes + x];
            if (xv > 0.f && int(xn + 0.5f) == note) { xv = std::max(xv, vel); done = true; }
            else if (xv <= 0.f && free < 0) free = x;
        }
        if (!done && free >= 0) {
            bk[B_XVEL + cell * kExtraNotes + free] = vel;
            bk[B_XNOTE + cell * kExtraNotes + free] = float(note);
        }
    }
    // already heard now: don't play it again when the playhead reaches that step this loop
    if (double(n) >= seqPhase) bk[B_SKIP + cell] = 1.f;
    recorded.store(true, std::memory_order_release);
}

/** Repeat / arp rhythms: x = tick plays, . = rest (cycled). */
static const char* const kRhythms[] = {"x", "x.", "xx.", "x.x", "xxx.", "x..x..x.", "xx.x.xx.", "x...x.x."};
constexpr int kRhythmCount = int(sizeof(kRhythms) / sizeof(kRhythms[0]));

void Engine::runRepeat(int frames) {
    const int mode = repeatMode.load(std::memory_order_relaxed);
    int count = 0;
    for (auto& hd : held) count += hd.slot >= 0;
    if (mode == 0) for (auto& hd : held) hd.slot = -1;  // switched off: forget held pads
    if (mode == 0 || count == 0) { rpIdle = true; arpIndex = 0; arpDir = 1; rpTick = 0; return; }
    const char* rhythm = kRhythms[std::clamp(repeatRhythm.load(std::memory_order_relaxed), 0, kRhythmCount - 1)];
    const int rhythmLen = int(strlen(rhythm));
    const int dyn = repeatDyn.load(std::memory_order_relaxed);
    float* p = pattern.load(std::memory_order_acquire);
    double bpm = p ? p[OFF_BPM] : 120.0;
    if (!(bpm >= 20.0 && bpm <= 400.0)) bpm = 120.0;
    const double stepLen = kSampleRate * 60.0 / bpm / 4.0;
    const double rate = std::clamp(double(repeatRate.load(std::memory_order_relaxed)), 0.25, 16.0);
    const bool playing = wasPlaying && playRequested.load(std::memory_order_relaxed);
    const double start = playing ? seqPhase : rpPhase;
    if (rpIdle || playing != rpPlayingBase || rpNext < start - 1e-9 || rpNext - start > rate + 1e-9) {
        // first press: start right away when stopped, on the next grid point when playing
        if (!playing && rpIdle) { rpPhase = 0.0; rpNext = 0.0; }
        else rpNext = std::ceil(start / rate - 1e-9) * rate;
        rpIdle = false;
        rpPlayingBase = playing;
    }
    const double startNow = playing ? seqPhase : rpPhase;
    const double end = startNow + frames / stepLen;
    const int gateFrames = std::max(32, int(rate * stepLen * std::clamp(double(repeatGate.load()), 0.05, 1.0)));
    const bool rec = recording.load(std::memory_order_relaxed) && playing && rpNext >= 0.0;
    while (rpNext < end) {
        int off = std::clamp(int(std::lround((rpNext - startNow) * stepLen)), 0, frames - 1);
        const int tick = rpTick++;
        if (rhythm[tick % rhythmLen] != 'x') { rpNext += rate; continue; }  // a rest
        // velocity shape over groups of 4 ticks
        auto shape = [&](float v) {
            const int k = tick % 4;
            switch (dyn) {
                case 1: return 1.f;
                case 2: return k == 0 ? std::min(1.f, v * 1.2f) : v * 0.6f;
                case 3: return v * (0.4f + 0.2f * k);
                case 4: return v * (1.f - 0.2f * k);
                case 5: arpRnd ^= arpRnd << 13; arpRnd ^= arpRnd >> 17; arpRnd ^= arpRnd << 5;
                        return v * (0.45f + 0.55f * float(arpRnd % 1000u) / 999.f);
                default: return v;
            }
        };
        if (mode == 1) {
            for (auto& hd : held) {
                if (hd.slot < 0) continue;
                const float v = shape(hd.vel);
                startVoice(hd.slot, v, off, gateFrames, true, hd.note, hd.chord);
                if (rec && rate >= 1.0 - 1e-6) recordEvent(p, hd.slot, rpNext, hd.note, v);
            }
        } else {
            // arpeggiator: the held notes (sorted, or in playing order) over 1-4 octaves
            Held list[kMaxHeld];
            int n = 0;
            for (auto& hd : held) if (hd.slot >= 0) list[n++] = hd;
            const int type = arpType.load(std::memory_order_relaxed);
            if (type == 4) std::sort(list, list + n, [](const Held& a, const Held& b) { return a.order < b.order; });
            else std::sort(list, list + n, [](const Held& a, const Held& b) { return a.slot * 128 + a.note < b.slot * 128 + b.note; });
            const int oct = std::clamp(arpOctaves.load(std::memory_order_relaxed), 1, 4);
            const int total = n * oct;
            if (type == 5) {
                for (int o = 0; o < oct; o++)
                    for (int k = 0; k < n; k++)
                        if (o == arpIndex % oct) startVoice(list[k].slot, shape(list[k].vel), off, gateFrames, true, std::min(127, list[k].note + 12 * o), n);
                arpIndex++;
            } else {
                int idx;
                if (type == 3) {
                    arpRnd ^= arpRnd << 13; arpRnd ^= arpRnd >> 17; arpRnd ^= arpRnd << 5;
                    idx = int(arpRnd % uint32_t(total));
                } else if (type == 2 && total > 1) {
                    if (arpIndex >= total) { arpIndex = total - 2; arpDir = -1; }
                    if (arpIndex < 0) { arpIndex = 1; arpDir = 1; }
                    idx = arpIndex;
                    arpIndex += arpDir;
                } else {
                    idx = arpIndex % total;
                    arpIndex = (arpIndex + 1) % total;
                    if (type == 1) idx = total - 1 - idx;
                }
                const Held& hd = list[idx % n];
                const int note = std::min(127, hd.note + 12 * (idx / n));
                const float v = shape(hd.vel);
                startVoice(hd.slot, v, off, gateFrames, true, note, 1);
                if (rec && rate >= 1.0 - 1e-6) recordEvent(p, hd.slot, rpNext, note, v);
            }
        }
        rpNext += rate;
    }
    if (!playing) rpPhase = end;
}

// ---- performance hint: tells Android how long each audio callback works, so the scheduler
// keeps the audio thread on fast cores at a high enough clock (screen off especially) ----
#ifndef TEST
static std::atomic<APerformanceHintSession*> gHint{nullptr};
static std::atomic<int> gHintState{0};  // 0 not tried, 1 no manager, 2 session refused, 3 on
static std::atomic<int> gHintTid{0};
static std::atomic<int64_t> gHintTarget{0};
static std::atomic<int> gHintFor{0};  // audio thread the session was made for (or refused)
/** Creates the session for the current audio thread. Binder calls: called from Java's monitor
 *  thread, never from the audio thread. Returns the state. */
static int hintPoll() {
    const int tid = gHintTid.load();
    if (tid == 0 || tid == gHintFor.load()) return gHintState.load();
    gHintFor.store(tid);
    APerformanceHintManager* m = APerformanceHint_getManager();
    if (!m) { gHintState.store(1); return 1; }
    int32_t t = tid;
    APerformanceHintSession* s = APerformanceHint_createSession(m, &t, 1, gHintTarget.load());
    if (!s) { gHintState.store(2); return 2; }
    APerformanceHintSession* old = gHint.exchange(s);
    if (old) APerformanceHint_closeSession(old);
    gHintState.store(3);
    return 3;
}
#endif

void Engine::render(float* out, int32_t frames) {
    callbackFrames.store(frames, std::memory_order_relaxed);
    const int64_t t0 = nowNanos();
    for (auto& a : secAcc) a = 0;
#ifndef TEST
    {
        // target: half of the callback's time, so there is always headroom
        const int64_t target = int64_t(frames) * 500000000LL / kSampleRate;
        const int tid = int(gettid());
        if (tid != gHintTid.load(std::memory_order_relaxed)) {
            gHintTarget.store(target, std::memory_order_relaxed);
            gHintTid.store(tid, std::memory_order_relaxed);  // the monitor makes the session
        } else if (target != gHintTarget.load(std::memory_order_relaxed)) {
            gHintTarget.store(target, std::memory_order_relaxed);
            if (auto* s = gHint.load(std::memory_order_acquire)) APerformanceHint_updateTargetWorkDuration(s, target);
        }
    }
#endif
    for (int off = 0; off < frames; off += kChunk)
        renderChunk(out + off * 2, std::min(kChunk, int(frames) - off));
    // CPU load: render time over the audio it made (peak and average, read by the heartbeat)
    const int64_t took = nowNanos() - t0;
#ifndef TEST
    if (auto* s = gHint.load(std::memory_order_acquire)) APerformanceHint_reportActualWorkDuration(s, took > 0 ? took : 1);
#endif
    const double load = double(took) / (double(frames) * 1e9 / kSampleRate);
    if (load > cpuPeak.load(std::memory_order_relaxed)) {
        cpuPeak.store(float(load), std::memory_order_relaxed);
        for (int k = 0; k < 4; k++) slowest[k].store(int(secAcc[k] / 1000), std::memory_order_relaxed);
    }
    cpuSum += load;
    cpuN++;
    // overload guard: three late-running callbacks in a row release the oldest POLY note, so
    // the audio thread (real-time priority) never starves the screen and the controller
    if (load > 0.85) {
        if (++overload >= 3) {
            overload = 0;
            Voice* o = nullptr;
            for (auto& v : voices)
                if (v.sample && v.pinst >= 0 && !v.released && (!o || v.age < o->age)) o = &v;
            if (o) { o->released = true; o->fromSeq = false; shed.fetch_add(1, std::memory_order_relaxed); }
        }
    } else overload = 0;
}

/**
 * Perform FX on the master: 1 STUTTER (repeats the last 1/4 .. 1/32 note, amount picks the
 * length), 2 TAPE STOP (slows to a halt), 3 TRANCE GATE (1/16 chops, amount = depth),
 * 4 LO-FI (sample rate and bit reduction). Type 0 (FILTER) works per group elsewhere.
 */
void Engine::runPerform(float* out, int frames, const float* p, double bpm) {
    const int type = std::clamp(int(p[OFF_PFX] + 0.5f), 0, 4);
    const bool on = p[OFF_PFX + 1] > 0.5f && type != 0;
    const float amt = std::clamp(p[OFF_PFX + 2], 0.f, 1.f);
    if (!(bpm >= 20.0 && bpm <= 400.0)) bpm = 120.0;
    const double stepLen = kSampleRate * 60.0 / bpm / 4.0;
    if (on && (!pfxWas || type != pfxType)) {  // engaged
        pfxType = type;
        pfxPos = 0;
        pfxLen = 0;
        pfxRead = double(pfxW);
        pfxSpeed = 1.f;
    }
    pfxWas = on;
    const float target = on ? 1.f : 0.f;
    for (int i = 0; i < frames; i++) {
        const float dry[2] = {out[i * 2], out[i * 2 + 1]};
        pfxRing[pfxW][0] = dry[0];
        pfxRing[pfxW][1] = dry[1];
        pfxW = (pfxW + 1) & (kPfxRing - 1);
        pfxMix += (target - pfxMix) * 0.004f;  // ~5 ms
        if (pfxMix < 1e-4f && !on) { pfxMix = 0.f; continue; }
        float wet[2] = {dry[0], dry[1]};
        switch (pfxType) {
            case 1: {  // stutter
                static constexpr float kLens[] = {4.f, 2.f, 1.f, 0.5f};
                const int len = std::max(64, int(stepLen * kLens[std::min(3, int(amt * 4.f))]));
                if (len != pfxLen) {
                    pfxLen = len;
                    pfxStart = (pfxW - len - 1 + kPfxRing) & (kPfxRing - 1);
                    pfxPos = 0;
                }
                const int r = (pfxStart + pfxPos) & (kPfxRing - 1);
                // short fades at the loop seam
                const int xf = std::min(96, pfxLen / 4);
                float g = 1.f;
                if (pfxPos < xf) g = float(pfxPos) / float(xf);
                else if (pfxPos > pfxLen - xf) g = float(pfxLen - pfxPos) / float(xf);
                wet[0] = pfxRing[r][0] * g;
                wet[1] = pfxRing[r][1] * g;
                if (++pfxPos >= pfxLen) pfxPos = 0;
                break;
            }
            case 2: {  // tape stop: the strip sets how far it has slowed
                const float want = 1.f - amt;
                pfxSpeed += (want - pfxSpeed) * 0.0005f;
                pfxRead += pfxSpeed;
                const double lag = double(pfxW) - pfxRead;
                if (lag < 0.0 || lag > kPfxRing - 4) pfxRead = double(pfxW) - 1.0;
                const int ip = int(std::floor(pfxRead));
                const float t = float(pfxRead - ip);
                const int a = ip & (kPfxRing - 1), b = (ip + 1) & (kPfxRing - 1);
                const float lvl = std::min(1.f, pfxSpeed * 4.f);  // fades as it stops
                wet[0] = (pfxRing[a][0] * (1.f - t) + pfxRing[b][0] * t) * lvl;
                wet[1] = (pfxRing[a][1] * (1.f - t) + pfxRing[b][1] * t) * lvl;
                break;
            }
            case 3: {  // trance gate, locked to the 16ths
                const double ph = std::fmod((wasPlaying ? seqPhase : double(framesDone) / stepLen) + double(i) / stepLen, 1.0);
                const float open = ph < 0.5 ? 1.f : 1.f - amt;
                pfxHold[0] += (open - pfxHold[0]) * 0.02f;
                wet[0] = dry[0] * pfxHold[0];
                wet[1] = dry[1] * pfxHold[0];
                break;
            }
            case 4: {  // lo-fi
                const float step = 1.f / (1.f + amt * 23.f);
                pfxHoldPh += step;
                if (pfxHoldPh >= 1.f) {
                    pfxHoldPh -= 1.f;
                    const float q = std::exp2(15.f - amt * 11.f);
                    pfxHold[0] = std::round(dry[0] * q) / q;
                    pfxHold[1] = std::round(dry[1] * q) / q;
                }
                wet[0] = pfxHold[0];
                wet[1] = pfxHold[1];
                break;
            }
            default: break;
        }
        out[i * 2] = dry[0] + (wet[0] - dry[0]) * pfxMix;
        out[i * 2 + 1] = dry[1] + (wet[1] - dry[1]) * pfxMix;
    }
}

void Engine::renderPoly(int frames, float* pb, const float* fp, float* out, float* scopeTap, int sk, int si, uint32_t* active) {
    float l[kChunk], r[kChunk];
    for (int k = 0; k < kPolyInst; k++) {
        auto& pi = polyInst[k];
        if (!pi.synth || pi.slot < 0) continue;
        const int slot = pi.slot;
        if (pi.evN == 0 && pi.notes == 0 && pi.quietChunks > 400) continue;  // asleep (~2 s silent)
        const float* P = pb ? pb + slot * kParams : kDefaultParams;
        // patch edits (and a fresh binding) reach the synth before this chunk's notes
        const float* want = polyPatch[slot];
        for (int i = 0; i < openmk3::kPolyParams; i++)
            if (!(pi.cur[i] == want[i])) { pi.cur[i] = want[i]; pi.synth->setParam(i, want[i]); }
        // touch strip: PITCH bends over the patch's bend range, MOD drives vibrato
        const float rangeUp = std::max(1.f, want[36] * 48.f);
        pi.synth->pitchWheel(std::clamp(std::clamp(P[P_BEND], -24.f, 24.f) / rangeUp, -1.f, 1.f));
        pi.synth->modWheel(std::clamp(P[P_MODW], 0.f, 1.f));
        // events in time order, the audio rendered between them
        std::stable_sort(pi.ev, pi.ev + pi.evN, [](const PolyEvent& a, const PolyEvent& b) { return a.at < b.at; });
        int pos = 0, e = 0;
        while (pos < frames) {
            while (e < pi.evN && pi.ev[e].at <= pos) {
                const auto& ev = pi.ev[e++];
                if (ev.type == 0) pi.synth->noteOn(ev.note, ev.vel);
                else if (ev.type == 1) pi.synth->noteOff(ev.note);
                else pi.synth->allSoundOff();
            }
            const int next = e < pi.evN ? std::min(frames, std::max(pos + 1, pi.ev[e].at)) : frames;
            pi.synth->render(l + pos, r + pos, next - pos);
            pos = next;
        }
        while (e < pi.evN) {  // events at or past the end of the chunk
            const auto& ev = pi.ev[e++];
            if (ev.type == 0) pi.synth->noteOn(ev.note, ev.vel);
            else if (ev.type == 1) pi.synth->noteOff(ev.note);
            else pi.synth->allSoundOff();
        }
        pi.evN = 0;
        // the sound's volume and pan, then its bus
        const float volDb = std::clamp(P[P_VOL], -60.f, 12.f);
        const float vol = volDb <= -59.9f ? 0.f : std::pow(10.f, volDb / 20.f);
        const float pan = std::clamp(P[P_PAN], -1.f, 1.f);
        const float gl = vol * (pan > 0.f ? 1.f - pan : 1.f), gr = vol * (pan < 0.f ? 1.f + pan : 1.f);
        float* dst;
        if (chainActive(fp, slot)) {
            dst = soundBus[slot];
            if (!soundUsed[slot]) { std::memset(dst, 0, sizeof(float) * frames * 2); soundUsed[slot] = true; }
        } else dst = groupBus[slot / kPads];
        float* tap = (sk == 2 && slot == si) ? scopeTap : nullptr;
        float peak = 0.f;
        for (int i = 0; i < frames; i++) {
            const float ol = l[i] * gl, orr = r[i] * gr;
            dst[i * 2] += ol;
            dst[i * 2 + 1] += orr;
            if (tap) { tap[i * 2] += ol; tap[i * 2 + 1] += orr; }
            peak = std::max(peak, std::max(std::fabs(l[i]), std::fabs(r[i])));
        }
        if (pi.notes > 0 || peak > 1e-4f) { pi.quietChunks = 0; active[slot / 32] |= 1u << (slot % 32); }
        else pi.quietChunks++;
    }
    (void)out;
}

void Engine::renderChunk(float* out, int32_t frames) {
    const int64_t chunkStart = nowNanos();
    struct Advance { int64_t& f; int n; ~Advance() { f += n; } } advance{framesDone, frames};
    // effects swapped in by Java (allocation happens there; frees happen there too)
    if (fxDirty.exchange(0, std::memory_order_acq_rel)) {
        for (int c = 0; c < kChains; c++)
            for (int k = 0; k < kFxSlots; k++) {
                openmk3::Effect* e = fxPending[c][k].exchange(nullptr, std::memory_order_acq_rel);
                if (!e) continue;
                openmk3::Effect* old = fxCur[c][k];
                fxCur[c][k] = e;
                openmk3::Effect* prev = fxRetired[c][k].exchange(old, std::memory_order_acq_rel);
                (void)prev;  // freed by the Java thread on its next change
            }
    }
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
    // 0b. zone swaps (same rules as samples)
    if (zDirty.exchange(false, std::memory_order_acq_rel))
    for (int slot = 0; slot < kSounds; slot++)
        for (int z = 0; z < kZones; z++) {
            const bool clear = zClear[slot][z].exchange(false, std::memory_order_acq_rel);
            SampleData* p = zPending[slot][z].exchange(nullptr, std::memory_order_acq_rel);
            if (!p && !clear) continue;
            SampleData* old = zCur[slot][z].load(std::memory_order_relaxed);
            if (old)
                for (auto& v : voices)
                    if (v.sample == old) v.sample = nullptr;
            zCur[slot][z].store(p, std::memory_order_release);
            SampleData* prev = zRetired[slot][z].exchange(old, std::memory_order_acq_rel);
            (void)prev;
        }
    // 1. pad triggers: start at the top of this callback
    int64_t now = nowNanos();
    uint32_t t = ringTail.load(std::memory_order_relaxed);
    uint32_t h = ringHead.load(std::memory_order_acquire);
    while (t != h) {
        const Trigger& tr = ring[t % kRing];
        if (tr.type == 1) {
            releaseSlot(tr.slot, tr.note);
        } else if (tr.type == 2) {
            killSlot(tr.slot);
        } else if (tr.type == 3) {   // a pad held for NOTE REPEAT / ARP
            for (auto& hd : held) if (hd.slot < 0) { hd = {tr.slot, tr.note, tr.vel, tr.chord, ++heldOrder}; break; }
        } else if (tr.type == 4) {
            for (auto& hd : held) if (hd.slot == tr.slot && hd.note == tr.note) hd.slot = -1;
        } else if (tr.type == 5) {   // pressure: repeat velocity follows the pad
            for (auto& hd : held) if (hd.slot == tr.slot && hd.note == tr.note) hd.vel = tr.vel;
        } else {
            float ms = float(now - tr.tNanos) / 1e6f;
            if (ms > worstTrigMs.load(std::memory_order_relaxed)) worstTrigMs.store(ms, std::memory_order_relaxed);
            startVoice(tr.slot, tr.vel, 0, -1, false, tr.note, tr.chord);
        }
        t++;
    }
    ringTail.store(t, std::memory_order_release);

    // 2. note repeat / arpeggiator (before the sequencer advances, from the same phase), sequencer
    runRepeat(frames);
    runSequencer(frames);

    if (fxResetChunks > 0) {
        fxResetChunks--;
        for (auto& chain : fxCur) for (auto* e : chain) if (e) e->reset();
    }
    const int64_t tVoices = nowNanos();
    // 3. voices -> sound buses (sounds with effects) or group buses
    std::memset(out, 0, sizeof(float) * frames * 2);
    for (auto& gb : groupBus) std::memset(gb, 0, sizeof(float) * frames * 2);
    std::memset(soundUsed, 0, sizeof(soundUsed));
    float* pb = params.load(std::memory_order_acquire);
    const float* fp = fxParams.load(std::memory_order_acquire);
    const int sk = scopeKind.load(std::memory_order_relaxed);
    const int si = scopeIndex.load(std::memory_order_relaxed);
    float scopeTap[kChunk * 2];
    if (sk == 2) std::memset(scopeTap, 0, sizeof(float) * frames * 2);
    uint32_t active[(kSlots + 31) / 32] = {};
    uint64_t newest[kSlots] = {};
    float newestPos[kSlots];
    for (int k = 0; k < kSlots; k++) newestPos[k] = -1.f;
    constexpr int kBlock = 16;  // control rate: modulation and filter coefficients per 16 frames
    constexpr float kSr = float(kSampleRate);
    for (auto& v : voices) {
        const SampleData* s = v.sample;
        if (!s) continue;
        const float* P = pb ? pb + v.slot * kParams : kDefaultParams;
        if (v.pinst >= 0) {
            // POLY note: the instance makes the sound; the voice only times the note-off
            auto& pi = polyInst[v.pinst];
            int i = std::min(v.startOffset, frames - 1);
            v.startOffset = 0;
            int off = -1;
            if (v.fadeOut >= 0 || v.released || v.gate == 0) off = i;
            else if (v.gate > 0) {
                if (v.gate <= frames - i) off = i + v.gate - 1;
                else v.gate -= frames - i;
            }
            if (off >= 0) {
                pi.push({off, 1, v.note, 0.f});
                pi.notes = std::max(0, pi.notes - 1);
                v.pinst = -1;
                v.sample = nullptr;
            } else active[v.slot / 32] |= 1u << (v.slot % 32);
            continue;
        }
        // ---- parameters read every callback (knob moves affect playing notes) ----
        const float volDb = std::clamp(P[P_VOL], -60.f, 12.f);
        const float vol = volDb <= -59.9f ? 0.f : std::pow(10.f, volDb / 20.f);
        const float vv = std::clamp(P[P_VVOL], 0.f, 1.f);
        const float velGain = 1.f - vv + vv * v.vel * v.vel;
        const float basePan = std::clamp(P[P_PAN], -1.f, 1.f);
        const float tune = std::clamp(P[P_TUNE], -48.f, 48.f);
        int ftype = int(P[P_FTYPE] + 0.5f);
        if (ftype < 1 || ftype > 4) ftype = 0;
        const float modw = std::clamp(P[P_MODW], 0.f, 1.f);
        if (ftype == 0 && modw > 0.01f) ftype = 1;  // MOD on a sound without a filter: add a low-pass
        const float baseCut = std::clamp(P[P_CUT], 20.f, 20000.f) * std::exp2(-7.f * modw);
        const float bend = std::clamp(P[P_BEND], -24.f, 24.f);
        const float fk = 2.f - 1.96f * std::clamp(P[P_RES], 0.f, 1.f);
        const float drive = std::clamp(P[P_DRIVE], 0.f, 1.f);
        const float dg = 1.f + drive * 15.f;
        const float dnorm = 1.f / std::tanh(dg);
        const int engine = int(P[P_ENGINE] + 0.5f);
        const float vStep = engine == 1 ? 26040.f / kSr : engine == 2 ? 40000.f / kSr : 0.f;
        // envelopes (frames / per-sample coefficients)
        const int attF = int(std::max(0.f, P[P_ATT]) * kSr);
        const int holdF = int(std::max(0.f, P[P_HOLD]) * kSr);
        const float decCoef = float(std::exp(std::log(1e-4) / (std::max(0.005f, P[P_DEC]) * kSr)));
        const float sus = std::clamp(P[P_SUS], 0.f, 1.f);
        const float relCoef = float(std::exp(std::log(1e-4) / (std::max(0.005f, P[P_REL]) * kSr)));
        const int mAttF = int(std::max(0.f, P[P_MATT]) * kSr);
        const float mDecCoef = float(std::exp(std::log(1e-3) / (std::max(0.005f, P[P_MDEC]) * kSr)));
        const float mPitch = P[P_MPITCH], mCut = P[P_MCUT];
        const int lWave = int(P[P_LWAVE] + 0.5f);
        const float lInc = std::clamp(P[P_LRATE], 0.01f, 40.f) / kSr;
        const float lPitch = P[P_LPITCH], lCut = P[P_LCUT];
        const float lPan = std::clamp(P[P_LPAN], 0.f, 1.f), lVol = std::clamp(P[P_LVOL], 0.f, 1.f);
        const float velCut = P[P_VCUT] * (v.vel - 1.f);
        // destination bus
        float* dst;
        if (v.slot >= kSounds) dst = out;  // preview: straight to the output
        else if (chainActive(fp, v.slot)) {
            dst = soundBus[v.slot];
            if (!soundUsed[v.slot]) { std::memset(dst, 0, sizeof(float) * frames * 2); soundUsed[v.slot] = true; }
        } else dst = groupBus[v.slot / kPads];
        float* tap = (sk == 2 && v.slot == si) ? scopeTap : nullptr;
        // synth source
        const bool fmv = v.fm >= 0 && fmPool[v.fm];
        const bool synth = (v.synth >= 0 && synthPool[v.synth]) || fmv;
        openmk3::SynthPatch sp;
        if (synth) {
            sp.model = int(P[P_SMODEL] + 0.5f);
            sp.harmonics = std::clamp(P[P_SHARM], 0.f, 1.f);
            sp.timbre = std::clamp(P[P_STIMB], 0.f, 1.f);
            sp.morph = std::clamp(P[P_SMORPH], 0.f, 1.f);
            sp.decay = std::clamp(P[P_SDEC], 0.f, 1.f);
            sp.colour = std::clamp(P[P_SCOL], 0.f, 1.f);
            sp.fmAmount = std::clamp(P[P_SFM], -1.f, 1.f);
            sp.timbreAmount = std::clamp(P[P_STMOD], -1.f, 1.f);
            sp.morphAmount = std::clamp(P[P_SMMOD], -1.f, 1.f);
            sp.lpg = P[P_SLPG] > 0.5f;
        }
        const float auxMix = std::clamp(P[P_SAUX], 0.f, 1.f);
        // gate mode: note-off fades the sound with RELEASE (a synth without its own gate
        // and without an envelope always behaves like this, or it would never stop)
        // synth notes always end on note-off (with RELEASE): a model's own gate can leave a
        // faint residual that never counts as silent, so the note would hang
        const bool gateMode = P[P_GATE] > 0.5f || (synth && !fmv);  // FM voices release with their own envelopes
        // loop (only with a releasing envelope, so a looped sound always ends)
        const int n = s->frames;
        const bool loop = !synth && v.envType != 0 && P[P_LOOP] > 0.5f;
        int ls = 0, le = 0, xf = 0;
        if (loop) {
            ls = std::clamp(int(std::clamp(P[P_LSTART], 0.f, 1.f) * n), v.startF, v.endF - 1);
            le = std::clamp(int(std::clamp(P[P_LEND], 0.f, 1.f) * n), ls + 1, v.endF);
            xf = std::min(int(std::clamp(P[P_XFADE], 0.f, 1.f) * float(le - ls)), ls);  // needs audio before ls
        }
        const double loopLen = double(le - ls);

        const float* d = s->data.data();
        const int ch = s->channels;
        auto at = [&](int f, int c) { return d[std::clamp(f, 0, n - 1) * ch + c]; };
        auto interp = [&](double pos, int c) {
            int ip = int(std::floor(pos));
            float t = float(pos - ip);
            float y1 = at(ip, c);
            if (t == 0.f) return y1;
            float y0 = at(ip - 1, c), y2 = at(ip + 1, c), y3 = at(ip + 2, c);
            float c1 = 0.5f * (y2 - y0);
            float c2 = y0 - 2.5f * y1 + 2.f * y2 - 0.5f * y3;
            float c3 = 0.5f * (y3 - y0) + 1.5f * (y1 - y2);
            return ((c3 * t + c2) * t + c1) * t + y1;
        };

        int i = v.startOffset;
        v.startOffset = 0;
        bool done = false;
        while (i < frames && !done) {
            // ---- control-rate values for this block ----
            float lfo;
            switch (lWave) {
                case 1: lfo = 1.f - 4.f * std::fabs(v.lfoPhase - 0.5f); break;           // triangle
                case 2: lfo = 2.f * v.lfoPhase - 1.f; break;                               // saw
                case 3: lfo = v.lfoPhase < 0.5f ? 1.f : -1.f; break;                       // square
                case 4: lfo = v.lfoHeld; break;                                            // random
                default: lfo = std::sin(6.2831853f * v.lfoPhase); break;                   // sine
            }
            const float me = v.mLevel;
            const double rate = std::pow(2.0, double(tune + bend + float(v.note - kRootNote) - v.zoneShift + me * mPitch + lfo * lPitch) / 12.0);
            const double step = v.rev ? -rate : rate;
            float fa1 = 0.f, fa2 = 0.f, fa3 = 0.f;
            if (ftype) {
                float fc = baseCut * std::exp2(me * mCut + lfo * lCut + velCut);
                fc = std::clamp(fc, 20.f, 0.45f * kSr);
                float fg = std::tan(3.14159265f * fc / kSr);
                fa1 = 1.f / (1.f + fg * (fg + fk));
                fa2 = fg * fa1;
                fa3 = fg * fa2;
            }
            const float pan = std::clamp(basePan + lfo * lPan, -1.f, 1.f);
            const float trem = 1.f - lVol * (0.5f - 0.5f * lfo);
            const float g = vol * velGain * trem;
            const float gl = g * (pan > 0.f ? 1.f - pan : 1.f);
            const float gr = g * (pan < 0.f ? 1.f + pan : 1.f);

            const int blockEnd = std::min(frames, i + kBlock);
            float blockPeak = 0.f;
            if (fmv) {
                if (v.released && !v.fmUp) { fmPool[v.fm]->noteOff(); v.fmUp = true; }
                fmPool[v.fm]->setBend(tune + bend + me * mPitch + lfo * lPitch);
                fmPool[v.fm]->render(synthOut, blockEnd - i);
                for (int k = 0; k < blockEnd - i; k++) synthAux[k] = synthOut[k];
            } else if (synth) {
                sp.note = std::clamp(P[P_SNOTE], 0.f, 127.f) + float(v.note - kRootNote) + tune + bend + me * mPitch + lfo * lPitch;
                synthPool[v.synth]->render(sp, !v.released, synthOut, synthAux, blockEnd - i);
            }
            const int blockStart = i;
            for (; i < blockEnd; i++) {
                // end of the sample region / loop
                if (synth) {
                } else if (v.rev) {
                    if (v.pos < double(v.startF)) {
                        if (loop) v.pos += loopLen; else { done = true; break; }
                    } else if (loop && v.pos < double(ls)) v.pos += loopLen;
                } else {
                    if (loop && v.pos >= double(le)) v.pos -= loopLen;
                    if (v.pos >= double(v.endF)) { done = true; break; }
                }
                // gate (sequencer notes) and note-off
                if (v.gate > 0 && --v.gate == 0) v.released = true;
                else if (v.gate == 0) v.released = true;
                // fades
                float env = 1.f;
                if (v.fadeIn < kFadeIn) env = float(++v.fadeIn) / kFadeIn;
                if (v.fadeOut >= 0) {
                    env *= float(v.fadeOut) / kFadeOut;
                    if (--v.fadeOut <= 0) { done = true; break; }
                }
                // amp envelope
                if (v.envType == 1) {  // AHD: ignores note-off
                    if (v.envStage == 0) {
                        if (v.envCount >= attF) { v.envStage = 1; v.envCount = 0; v.envLevel = 1.f; }
                        else v.envLevel = float(++v.envCount) / float(attF);
                    } else if (v.envStage == 1) {
                        if (++v.envCount >= holdF) v.envStage = 2;
                    } else {
                        v.envLevel *= decCoef;
                        if (v.envLevel < 1e-4f) { done = true; break; }
                    }
                } else if (v.envType == 2) {  // ADSR
                    if (v.released && v.envStage < 4) v.envStage = 4;
                    if (v.envStage == 0) {
                        if (v.envCount >= attF) { v.envStage = 2; v.envLevel = 1.f; }
                        else v.envLevel = float(++v.envCount) / float(attF);
                    } else if (v.envStage == 2) {
                        v.envLevel = sus + (v.envLevel - sus) * decCoef;
                        if (std::fabs(v.envLevel - sus) < 1e-4f) v.envStage = 3;
                    } else if (v.envStage == 4) {
                        v.envLevel *= relCoef;
                        if (v.envLevel < 1e-4f) { done = true; break; }
                    }
                }
                env *= v.envLevel;
                if (gateMode && v.released && v.envType != 2) {
                    v.gateLevel *= relCoef;
                    if (v.gateLevel < 1e-4f) { done = true; break; }
                    env *= v.gateLevel;
                }
                // read the sample (crossfading into the loop start near the loop end)
                float xs[2];
                if (synth) {
                    int k = i - blockStart;
                    xs[0] = xs[1] = synthOut[k] * (1.f - auxMix) + synthAux[k] * auxMix;
                    blockPeak = std::max(blockPeak, std::fabs(xs[0]));
                } else
                for (int c = 0; c < ch; c++) {
                    float y = interp(v.pos, c);
                    if (loop && xf > 0 && !v.rev && v.pos >= double(le - xf)) {
                        float tt = float((v.pos - double(le - xf)) / double(xf));
                        y = y * (1.f - tt) + interp(v.pos - loopLen, c) * tt;
                    }
                    xs[c] = y;
                }
                if (!synth && ch == 1) xs[1] = xs[0];
                // vintage engines: reduced sample rate and 12-bit resolution
                if (vStep > 0.f) {
                    v.vPhase += vStep;
                    if (v.vPhase >= 1.f) {
                        v.vPhase -= 1.f;
                        for (int c = 0; c < 2; c++) v.vHeld[c] = std::round(xs[c] * 2048.f) / 2048.f;
                    }
                    xs[0] = v.vHeld[0];
                    xs[1] = v.vHeld[1];
                }
                if (drive > 0.001f)
                    for (int c = 0; c < 2; c++) xs[c] = std::tanh(xs[c] * dg) * dnorm;
                if (ftype) {
                    for (int c = 0; c < 2; c++) {
                        float x = xs[c];
                        float v3 = x - v.ic2[c];
                        float v1 = fa1 * v.ic1[c] + fa2 * v3;
                        float v2 = v.ic2[c] + fa2 * v.ic1[c] + fa3 * v3;
                        v.ic1[c] = 2.f * v1 - v.ic1[c];
                        v.ic2[c] = 2.f * v2 - v.ic2[c];
                        float y = ftype == 2 ? fk * v1 : ftype == 3 ? x - fk * v1 - v2 : v2;
                        if (ftype == 4) {  // second low-pass stage: 24 dB/octave
                            float w3 = y - v.jc2[c];
                            float w1 = fa1 * v.jc1[c] + fa2 * w3;
                            float w2 = v.jc2[c] + fa2 * v.jc1[c] + fa3 * w3;
                            v.jc1[c] = 2.f * w1 - v.jc1[c];
                            v.jc2[c] = 2.f * w2 - v.jc2[c];
                            y = w2;
                        }
                        xs[c] = y;
                    }
                }
                const float ol = xs[0] * env * gl, orr = xs[1] * env * gr;
                dst[i * 2] += ol;
                dst[i * 2 + 1] += orr;
                if (tap) { tap[i * 2] += ol; tap[i * 2 + 1] += orr; }
                if (!synth) v.pos += step;
            }
            // a synth whose own gate has closed: end the voice once it stays silent
            if (synth && !done) {
                v.ageBlocks++;
                if (blockPeak < 3e-4f) v.quietBlocks++; else v.quietBlocks = 0;
                if (fmv) { if (v.fmUp && !fmPool[v.fm]->playing()) done = true; }
                else if (v.ageBlocks > 30 && v.quietBlocks > 40 && (sp.lpg || v.released)) done = true;
            }
            // ---- advance control-rate modulators by the block just rendered ----
            const int adv = kBlock;
            if (v.mStage == 0) {
                v.mCount += adv;
                if (v.mCount >= mAttF) { v.mStage = 1; v.mLevel = 1.f; }
                else v.mLevel = float(v.mCount) / float(std::max(1, mAttF));
            } else {
                v.mLevel *= std::pow(mDecCoef, float(adv));
            }
            v.lfoPhase += lInc * adv;
            if (v.lfoPhase >= 1.f) {
                v.lfoPhase -= std::floor(v.lfoPhase);
                v.rnd ^= v.rnd << 13; v.rnd ^= v.rnd >> 17; v.rnd ^= v.rnd << 5;
                v.lfoHeld = float(v.rnd & 0xFFFF) / 32767.5f - 1.f;
            }
        }
        if (done) {
            v.sample = nullptr;
            freeSynth(v);
        } else {
            active[v.slot / 32] |= 1u << (v.slot % 32);
            if (v.age > newest[v.slot]) { newest[v.slot] = v.age; newestPos[v.slot] = float(v.pos); }
        }
    }
    const int64_t tPoly = nowNanos();
    renderPoly(frames, pb, fp, out, sk == 2 ? scopeTap : nullptr, sk, si, active);
    const int64_t tFx = nowNanos();
    secAcc[0] += tVoices - chunkStart;  // triggers, sequencer, swaps
    secAcc[1] += tPoly - tVoices;       // voices
    secAcc[2] += tFx - tPoly;           // POLY synths
    for (int k = 0; k < (kSlots + 31) / 32; k++) activeMask[k].store(active[k], std::memory_order_relaxed);
    for (int k = 0; k < kSlots; k++) playPos[k].store(newestPos[k], std::memory_order_relaxed);

    // 4. effects: sound buses -> group buses -> master
    double bpm = 120.0;
    if (float* pp = pattern.load(std::memory_order_acquire)) bpm = pp[OFF_BPM];
    if (fp) {
        for (int snd = 0; snd < kSounds; snd++) {
            // effects keep running after the notes end (reverb and delay tails)
            if (!chainActive(fp, snd)) continue;
            if (!soundUsed[snd] && chainAsleep(snd)) continue;  // nothing playing, tails done
            if (!soundUsed[snd]) std::memset(soundBus[snd], 0, sizeof(float) * frames * 2);
            runChain(fp, snd, soundBus[snd], frames, bpm);
            float* gb = groupBus[snd / kPads];
            for (int k = 0; k < frames * 2; k++) gb[k] += soundBus[snd][k];
        }
    }
    float* pp2 = pattern.load(std::memory_order_acquire);
    std::memset(sendBus, 0, sizeof(sendBus));
    bool sendUsed[2] = {false, false};
    for (int g = 0; g < kGroups; g++) {
        if (fp) runChain(fp, kChainGroup0 + g, groupBus[g], frames, bpm);
        float* gb = groupBus[g];
        float gl = 1.f, gr = 1.f, filt = 0.f;
        if (pp2) {
            float db = std::clamp(pp2[OFF_GVOL + g], -60.f, 12.f);
            float gain = db <= -59.9f ? 0.f : std::pow(10.f, db / 20.f);
            float pan = std::clamp(pp2[OFF_GPAN + g], -1.f, 1.f);
            gl = gain * (pan > 0.f ? 1.f - pan : 1.f);
            gr = gain * (pan < 0.f ? 1.f + pan : 1.f);
            filt = std::clamp(pp2[OFF_GFILT + g], -1.f, 1.f);
        }
        // performance filter (touch strip): low-pass to the left, high-pass to the right
        if (std::fabs(filt) > 0.01f) {
            float fc = filt < 0.f ? 20000.f * std::pow(0.005f, -filt) : 20.f * std::pow(400.f, filt);
            float fg = std::tan(3.14159265f * std::clamp(fc, 20.f, 20000.f) / kSampleRate);
            const float fk = 1.2f;
            float a1 = 1.f / (1.f + fg * (fg + fk)), a2 = fg * a1, a3 = fg * a2;
            for (int k = 0; k < frames; k++)
                for (int c = 0; c < 2; c++) {
                    float x = gb[k * 2 + c];
                    float v3 = x - gfIc2[g][c];
                    float v1 = a1 * gfIc1[g][c] + a2 * v3;
                    float v2 = gfIc2[g][c] + a2 * gfIc1[g][c] + a3 * v3;
                    gfIc1[g][c] = 2.f * v1 - gfIc1[g][c];
                    gfIc2[g][c] = 2.f * v2 - gfIc2[g][c];
                    gb[k * 2 + c] = filt < 0.f ? v2 : x - fk * v1 - v2;
                }
        }
        for (int k = 0; k < frames; k++) {
            out[k * 2] += gb[k * 2] * gl;
            out[k * 2 + 1] += gb[k * 2 + 1] * gr;
        }
        // sends (after the group fader) to the return chains
        if (pp2)
            for (int a = 0; a < 2; a++) {
                const float sl = std::clamp(pp2[OFF_GSEND + g * 2 + a], 0.f, 1.f);
                if (sl < 1e-4f) continue;
                sendUsed[a] = true;
                for (int k = 0; k < frames; k++) {
                    sendBus[a][k * 2] += gb[k * 2] * gl * sl;
                    sendBus[a][k * 2 + 1] += gb[k * 2 + 1] * gr * sl;
                }
            }
    }
    // returns: their effects run even after the sends stop (tails)
    if (fp)
        for (int a = 0; a < 2; a++) {
            if (!sendUsed[a] && (!chainActive(fp, kChainReturn0 + a) || chainAsleep(kChainReturn0 + a))) continue;
            runChain(fp, kChainReturn0 + a, sendBus[a], frames, bpm);
            for (int k = 0; k < frames * 2; k++) out[k] += sendBus[a][k];
        }
    if (fp) runChain(fp, kChainMaster, out, frames, bpm);
    if (float* pp = pattern.load(std::memory_order_acquire)) runPerform(out, frames, pp, bpm);
    addClick(out, frames);
    // oscilloscope (before the master volume, so the shape is what the sound makes)
    {
        const float* src = sk == 2 ? scopeTap : sk == 1 ? groupBus[std::clamp(si, 0, kGroups - 1)] : out;
        int w = scopeW.load(std::memory_order_relaxed);
        for (int k = 0; k < frames; k++) {
            scopeRing[w] = 0.5f * (src[k * 2] + src[k * 2 + 1]);
            w = (w + 1) & (kScope - 1);
        }
        scopeW.store(w, std::memory_order_release);
    }

    secAcc[3] += nowNanos() - tFx;      // effects and mixing
    // 5. master gain, soft clip, meter
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
    // HID reports for the Java reader: a fixed ring (the audio pump never allocates)
    static constexpr int kHidRing = 256;
    std::vector<uint8_t> hidRing_;     // kHidRing x hidSlot_ bytes, sized at start
    int hidLen_[kHidRing] = {};
    int hidSlot_ = 0, hidHead_ = 0, hidCount_q = 0;
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
            if (hidCount_q < kHidRing && hidSlot_ > 0) {
                const int idx = (hidHead_ + hidCount_q) % kHidRing;
                const int n = std::min(u->u->actual_length, hidSlot_);
                std::memcpy(hidRing_.data() + size_t(idx) * size_t(hidSlot_), u->buf.data(), size_t(n));
                hidLen_[idx] = n;
                hidCount_q++;
            }
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
    // Keep our own output out of phone-audio capture: being captured moves it off the fast path.
    AAudioStreamBuilder_setAllowedCapturePolicy(b, AAUDIO_ALLOW_CAPTURE_BY_NONE);
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
    if (hidCount_q == 0) {
        if (!running.load()) return -1;
        hidCv_.wait_for(g, std::chrono::milliseconds(timeoutMs), [this] { return hidCount_q > 0 || !running.load(); });
    }
    if (hidCount_q == 0) return running.load() ? 0 : -1;
    const int n = std::min(cap, hidLen_[hidHead_]);
    std::memcpy(out, hidRing_.data() + size_t(hidHead_) * size_t(hidSlot_), size_t(n));
    hidHead_ = (hidHead_ + 1) % kHidRing;
    hidCount_q--;
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
        hidHead_ = 0; hidCount_q = 0;
        hidSlot_ = std::max(64, int(hidMax));
        hidRing_.assign(size_t(kHidRing) * size_t(hidSlot_), 0);
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

/** Sample zone [zone] of sound [slot] (data == null clears it), with its key / velocity mapping. */
JNIEXPORT void JNICALL JNI_FN(nativeSetZone)(JNIEnv* env, jobject, jint slot, jint zone, jfloatArray data, jint channels,
                                             jint root, jint lo, jint hi, jint vlo, jint vhi) {
    if (slot < 0 || slot >= kSounds || zone < 0 || zone >= Engine::kZones) return;
    float* m = gEngine.zMap[slot][zone];
    m[0] = float(std::clamp(int(root), 0, 127));
    m[1] = float(std::clamp(int(lo), 0, 127));
    m[2] = float(std::clamp(int(hi), 0, 127));
    m[3] = float(std::clamp(int(vlo), 1, 127));
    m[4] = float(std::clamp(int(vhi), 1, 127));
    if (!data) {
        delete gEngine.zPending[slot][zone].exchange(nullptr, std::memory_order_acq_rel);
        gEngine.zClear[slot][zone].store(true, std::memory_order_release);
        gEngine.zDirty.store(true, std::memory_order_release);
        return;
    }
    auto* s = new SampleData();
    jsize n = env->GetArrayLength(data);
    s->data.resize(size_t(n));
    env->GetFloatArrayRegion(data, 0, n, s->data.data());
    s->channels = channels == 2 ? 2 : 1;
    s->frames = int(n / s->channels);
    delete gEngine.zRetired[slot][zone].exchange(nullptr, std::memory_order_acq_rel);
    delete gEngine.zPending[slot][zone].exchange(s, std::memory_order_acq_rel);
    gEngine.zDirty.store(true, std::memory_order_release);
}
/** Changes only the key / velocity mapping of a zone. */
JNIEXPORT void JNICALL JNI_FN(nativeZoneMap)(JNIEnv*, jobject, jint slot, jint zone, jint root, jint lo, jint hi, jint vlo, jint vhi) {
    if (slot < 0 || slot >= kSounds || zone < 0 || zone >= Engine::kZones) return;
    float* m = gEngine.zMap[slot][zone];
    m[0] = float(std::clamp(int(root), 0, 127));
    m[1] = float(std::clamp(int(lo), 0, 127));
    m[2] = float(std::clamp(int(hi), 0, 127));
    m[3] = float(std::clamp(int(vlo), 1, 127));
    m[4] = float(std::clamp(int(vhi), 1, 127));
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

/** Performance hint: makes the session for the audio thread if needed; returns its state
 *  (0 no audio thread yet, 1 unsupported, 2 refused, 3 on). */
JNIEXPORT jint JNICALL JNI_FN(nativeHintPoll)(JNIEnv*, jobject) {
#ifndef TEST
    return hintPoll();
#else
    return 0;
#endif
}

/** Audio CPU load since the last call: [average, peak] as fractions of real time. */
JNIEXPORT jfloatArray JNICALL JNI_FN(nativeCpuLoad)(JNIEnv* env, jobject) {
#ifndef TEST
    const float hint = float(gHintState.load());
#else
    const float hint = 0.f;
#endif
    float v[8] = {gEngine.cpuN > 0 ? float(gEngine.cpuSum / double(gEngine.cpuN)) : 0.f,
                  gEngine.cpuPeak.exchange(0.f), float(gEngine.shed.exchange(0)), hint,
                  float(gEngine.slowest[0].load()), float(gEngine.slowest[1].load()),
                  float(gEngine.slowest[2].load()), float(gEngine.slowest[3].load())};
    gEngine.cpuSum = 0.0;
    gEngine.cpuN = 0;
    jfloatArray a = env->NewFloatArray(8);
    env->SetFloatArrayRegion(a, 0, 8, v);
    return a;
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

JNIEXPORT void JNICALL JNI_FN(nativeRelease)(JNIEnv*, jobject, jint slot) {
    gEngine.pushTrigger(slot, 0.f, 1, -1);
}
/** Note-on with a pitch ([note] 60 = as sampled) and the chord size it belongs to. */
JNIEXPORT void JNICALL JNI_FN(nativeNoteOn)(JNIEnv*, jobject, jint slot, jint note, jfloat vel, jint chord) {
    gEngine.pushTrigger(slot, vel, 0, note, chord);
}
JNIEXPORT void JNICALL JNI_FN(nativeNoteOff)(JNIEnv*, jobject, jint slot, jint note) {
    gEngine.pushTrigger(slot, 0.f, 1, note);
}
/** NOTE REPEAT / ARP: a pad (note) is held (type 3), let go (4) or its pressure changed (5). */
JNIEXPORT void JNICALL JNI_FN(nativeRepeatHold)(JNIEnv*, jobject, jint slot, jint note, jfloat vel, jint chord, jint type) {
    gEngine.pushTrigger(slot, vel, type, note, chord);
}
JNIEXPORT void JNICALL JNI_FN(nativeRepeatShape)(JNIEnv*, jobject, jint dynamics, jint rhythm) {
    gEngine.repeatDyn.store(dynamics);
    gEngine.repeatRhythm.store(rhythm);
}

JNIEXPORT void JNICALL JNI_FN(nativeRepeatConfig)(JNIEnv*, jobject, jint mode, jfloat rate, jfloat gate, jint arpType, jint octaves) {
    gEngine.repeatMode.store(mode);
    gEngine.repeatRate.store(rate);
    gEngine.repeatGate.store(gate);
    gEngine.arpType.store(arpType);
    gEngine.arpOctaves.store(octaves);
}
JNIEXPORT void JNICALL JNI_FN(nativeSetRecording)(JNIEnv*, jobject, jboolean on) {
    gEngine.recording.store(on == JNI_TRUE);
}
/** True (once) after the engine wrote repeat / arp hits into the pattern. */
JNIEXPORT jboolean JNICALL JNI_FN(nativeTakeRecorded)(JNIEnv*, jobject) {
    return gEngine.recorded.exchange(false) ? JNI_TRUE : JNI_FALSE;
}
/** Fades out every voice of [slot] (-1 = everything). */
JNIEXPORT void JNICALL JNI_FN(nativeKill)(JNIEnv*, jobject, jint slot) {
    gEngine.pushTrigger(slot, 0.f, 2);
}
JNIEXPORT void JNICALL JNI_FN(nativeInitSynths)(JNIEnv*, jobject) { gEngine.ensureSynths(); }
/** POLY source: parameter [index] (0..1) of sound [slot]'s patch. */
JNIEXPORT void JNICALL JNI_FN(nativePolySet)(JNIEnv*, jobject, jint slot, jint index, jfloat value) {
    if (slot < 0 || slot >= kSounds || index < 0 || index >= openmk3::kPolyParams) return;
    gEngine.initPolyPatches();
    gEngine.polyPatch[slot][index] = std::clamp(value, 0.f, 1.f);
}
JNIEXPORT jint JNICALL JNI_FN(nativePolyParamCount)(JNIEnv*, jobject) { return openmk3::PolySynth::paramCount(); }
JNIEXPORT jstring JNICALL JNI_FN(nativePolyParamName)(JNIEnv* env, jobject, jint i) {
    return env->NewStringUTF(openmk3::PolySynth::paramName(std::clamp(int(i), 0, openmk3::kPolyParams - 1)));
}
JNIEXPORT jstring JNICALL JNI_FN(nativePolyParamGroup)(JNIEnv* env, jobject, jint i) {
    return env->NewStringUTF(openmk3::PolySynth::paramGroup(std::clamp(int(i), 0, openmk3::kPolyParams - 1)));
}
JNIEXPORT jfloat JNICALL JNI_FN(nativePolyParamDefault)(JNIEnv*, jobject, jint i) {
    return openmk3::PolySynth::paramDefault(std::clamp(int(i), 0, openmk3::kPolyParams - 1));
}
JNIEXPORT jint JNICALL JNI_FN(nativePolyParamSteps)(JNIEnv*, jobject, jint i) {
    return openmk3::PolySynth::paramSteps(std::clamp(int(i), 0, openmk3::kPolyParams - 1));
}
JNIEXPORT jstring JNICALL JNI_FN(nativePolyDisplay)(JNIEnv* env, jobject, jint i, jfloat v) {
    char b[64];
    openmk3::PolySynth::display(std::clamp(int(i), 0, openmk3::kPolyParams - 1), v, b, sizeof(b));
    return env->NewStringUTF(b);
}

/** Installs a DX7 cartridge (.syx) as user FM bank [index]. */
JNIEXPORT jboolean JNICALL JNI_FN(nativeFmLoadBank)(JNIEnv* env, jobject, jint index, jbyteArray data) {
    jsize n = env->GetArrayLength(data);
    std::vector<uint8_t> b(static_cast<size_t>(n));
    env->GetByteArrayRegion(data, 0, n, reinterpret_cast<jbyte*>(b.data()));
    return openmk3::FmVoice::setUserBank(index, b.data(), int(n)) ? JNI_TRUE : JNI_FALSE;
}
JNIEXPORT jstring JNICALL JNI_FN(nativeFmPatchName)(JNIEnv* env, jobject, jint bank, jint program) {
    char name[11];
    openmk3::FmVoice::patchName(bank, program, name);
    return env->NewStringUTF(name);
}
JNIEXPORT void JNICALL JNI_FN(nativeSetFxBuffer)(JNIEnv* env, jobject, jobject buf) {
    if (env->GetDirectBufferCapacity(buf) < jlong(kChains * kFxSlots * openmk3::kFxParams * sizeof(float))) return;
    gEngine.fxParams.store(static_cast<float*>(env->GetDirectBufferAddress(buf)), std::memory_order_release);
}
/** Creates the effect for a slot (type 0 = empty) and hands it to the audio thread. */
JNIEXPORT void JNICALL JNI_FN(nativeSetFx)(JNIEnv*, jobject, jint chain, jint slot, jint type) {
    if (chain < 0 || chain >= kChains || slot < 0 || slot >= kFxSlots) return;
    openmk3::Effect* e = openmk3::createEffect(type);
    if (!e) e = new openmk3::Compressor();  // placeholder for "empty": skipped while its type is 0
    delete gEngine.fxRetired[chain][slot].exchange(nullptr, std::memory_order_acq_rel);
    delete gEngine.fxPending[chain][slot].exchange(e, std::memory_order_acq_rel);
    gEngine.fxDirty.store(1, std::memory_order_release);
}
JNIEXPORT jfloat JNICALL JNI_FN(nativeFxMeter)(JNIEnv*, jobject, jint chain, jint slot) {
    if (chain < 0 || chain >= kChains || slot < 0 || slot >= kFxSlots) return 0.f;
    openmk3::Effect* e = gEngine.fxCur[chain][slot];
    return e ? e->meter : 0.f;
}
JNIEXPORT void JNICALL JNI_FN(nativeSetScopeTap)(JNIEnv*, jobject, jint kind, jint index) {
    gEngine.scopeKind.store(kind);
    gEngine.scopeIndex.store(index);
}
/** Copies the newest [out.length] scope samples (oldest first). */
JNIEXPORT void JNICALL JNI_FN(nativeScope)(JNIEnv* env, jobject, jfloatArray out) {
    int n = std::min(int(env->GetArrayLength(out)), Engine::kScope);
    int w = gEngine.scopeW.load(std::memory_order_acquire);
    float tmp[Engine::kScope];
    for (int k = 0; k < n; k++) tmp[k] = gEngine.scopeRing[(w - n + k) & (Engine::kScope - 1)];
    env->SetFloatArrayRegion(out, 0, n, tmp);
}
JNIEXPORT jint JNICALL JNI_FN(nativeActiveSlots)(JNIEnv*, jobject, jint word) {
    if (word < 0 || word >= (kSlots + 31) / 32) return 0;
    return jint(gEngine.activeMask[word].load());
}
JNIEXPORT jfloat JNICALL JNI_FN(nativePlayPos)(JNIEnv*, jobject, jint slot) {
    if (slot < 0 || slot >= kSlots) return -1.f;
    return gEngine.playPos[slot].load(std::memory_order_relaxed);
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
