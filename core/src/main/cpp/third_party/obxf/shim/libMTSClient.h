// Open MK3: MTS-ESP microtuning is not used; these stand-ins keep the engine at 12-TET.
#pragma once
struct MTSClient;
inline MTSClient *MTS_RegisterClient() { return nullptr; }
inline void MTS_DeregisterClient(MTSClient *) {}
inline bool MTS_HasMaster(MTSClient *) { return false; }
inline const char *MTS_GetScaleName(MTSClient *) { return ""; }
inline double MTS_RetuningInSemitones(MTSClient *, char, char) { return 0.0; }
