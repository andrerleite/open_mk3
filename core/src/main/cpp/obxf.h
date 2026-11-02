// Open MK3 "POLY" source: the OB-Xf synthesizer engine (third_party/obxf, GPL-3.0, by the
// Surge Synth Team and the OB-Xd / OB-Xf authors). Keeps the OB-Xf headers (C++20) out of the
// engine. One instance is a whole polyphonic synth playing one sound.
#pragma once

namespace openmk3 {

constexpr int kPolyParams = 104;  // OB-Xf parameters, in OB-Xf's own order (0..1 each)

class PolySynth {
public:
    static int paramCount();
    static const char* paramId(int i);
    static const char* paramName(int i);
    static const char* paramGroup(int i);
    /** Default (0..1) of parameter [i]. */
    static float paramDefault(int i);
    /** Number of steps of a stepped parameter (1 = on / off), 0 = continuous. */
    static int paramSteps(int i);
    /** Text for parameter [i] at value [v] (0..1), as OB-Xf displays it. */
    static void display(int i, float v, char* out, int size);

    static PolySynth* create(float sampleRate);  // allocates: never call from the audio thread
    virtual ~PolySynth() {}
    virtual void setParam(int i, float v) = 0;
    virtual void noteOn(int note, float velocity) = 0;  // velocity 0..1
    virtual void noteOff(int note) = 0;
    virtual void allSoundOff() = 0;
    /** -1..1 (the patch sets the range). */
    virtual void pitchWheel(float v) = 0;
    /** 0..1: vibrato amount. */
    virtual void modWheel(float v) = 0;
    /** Renders [n] stereo frames (adds nothing: overwrites l and r). */
    virtual void render(float* l, float* r, int n) = 0;
};

}  // namespace openmk3
