// Open MK3: standard 12-tone tuning only (replaces Dexed's tuning.cc, which loads scale
// files through JUCE). The table is the one from Dexed's StandardTuning.
#include "tuning.h"

namespace {
struct StandardTuning : public TuningState {
    StandardTuning() {
        const int base = 50857777;  // (1 << 24) * (log(440) / log(2) - 69/12)
        const int step = (1 << 24) / 12;
        for (int mn = 0; mn < 128; ++mn) table_[mn] = base + step * mn;
    }
    int32_t midinote_to_logfreq(int midinote) override { return table_[midinote < 0 ? 0 : midinote > 127 ? 127 : midinote]; }
    int table_[128];
};
}  // namespace

std::shared_ptr<TuningState> createStandardTuning() { return std::make_shared<StandardTuning>(); }
