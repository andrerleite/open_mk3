// Open MK3 stub for the MTS-ESP tuning client: there is never a tuning master, so Dexed
// uses its own (standard) tuning.
#pragma once
struct MTSClient {};
inline bool MTS_HasMaster(MTSClient*) { return false; }
inline double MTS_NoteToFrequency(MTSClient*, char midinote, char) { return 440.0 * __builtin_pow(2.0, (midinote - 69) / 12.0); }
