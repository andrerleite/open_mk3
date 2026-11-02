// Open MK3 "POLY" source: wrapper around the OB-Xf synth engine (see obxf.h).
#include "obxf.h"

#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

#include "Handlers.h"
#include "ParameterList.h"

namespace openmk3 {
namespace {

struct Table {
    std::vector<Handler> handlers;
    std::vector<float> defaults;
    Table() {
        const auto& map = obxf::getHandlerMap();
        for (const auto& p : ParameterList) {
            auto it = map.find(p.ID);
            handlers.push_back(it != map.end() ? it->second : Handler([](SynthEngine&, float) {}));
            const float range = p.meta.maxVal - p.meta.minVal;
            defaults.push_back(range > 0.f ? (p.meta.defaultVal - p.meta.minVal) / range : 0.f);
        }
    }
};

const Table& table() {
    static const Table t;
    return t;
}

class PolySynthImpl final : public PolySynth {
public:
    explicit PolySynthImpl(float sr) {
        engine.setSampleRate(sr);
        const auto& t = table();
        for (int i = 0; i < int(t.handlers.size()); i++) t.handlers[i](engine, t.defaults[i]);
        engine.processPitchWheel(0.f);
        engine.processModWheel(0.f);
    }
    void setParam(int i, float v) override {
        const auto& t = table();
        if (i >= 0 && i < int(t.handlers.size())) t.handlers[i](engine, v < 0.f ? 0.f : v > 1.f ? 1.f : v);
    }
    void noteOn(int note, float velocity) override { engine.processNoteOn(note, velocity, 0); }
    void noteOff(int note) override { engine.processNoteOff(note, 0.f, 0); }
    void allSoundOff() override { engine.allSoundOff(); }
    void pitchWheel(float v) override { engine.processPitchWheel(v); }
    void modWheel(float v) override { engine.processModWheel(v); }
    void render(float* l, float* r, int n) override {
        std::memset(l, 0, sizeof(float) * n);
        std::memset(r, 0, sizeof(float) * n);
        engine.processBlock(l, r, n);
    }

private:
    SynthEngine engine;
};

}  // namespace

int PolySynth::paramCount() { return int(ParameterList.size()); }
const char* PolySynth::paramId(int i) { return ParameterList[i].ID.c_str(); }
const char* PolySynth::paramName(int i) { return ParameterList[i].meta.name.c_str(); }
const char* PolySynth::paramGroup(int i) { return ParameterList[i].meta.groupName.c_str(); }
float PolySynth::paramDefault(int i) { return table().defaults[i]; }
int PolySynth::paramSteps(int i) {
    const auto& m = ParameterList[i].meta;
    using T = sst::basic_blocks::params::ParamMetaData::Type;
    if (m.type == T::BOOL) return 1;
    if (m.type == T::INT) return int(m.maxVal - m.minVal + 0.5f);
    return 0;
}

void PolySynth::display(int i, float v, char* out, int size) {
    const auto& m = ParameterList[i].meta;
    const float native = m.minVal + (m.maxVal - m.minVal) * (v < 0.f ? 0.f : v > 1.f ? 1.f : v);
    auto s = m.valueToString(native);
    std::snprintf(out, size_t(size), "%s", s ? s->c_str() : "");
}

PolySynth* PolySynth::create(float sampleRate) { return new PolySynthImpl(sampleRate); }

}  // namespace openmk3
