// Open MK3: the small part of JUCE's API the OB-Xf synth engine uses (no JUCE code).
#pragma once
#include <algorithm>
#include <array>
#include <iostream>
#include <map>
#include <sstream>
#include <unordered_map>
#include <unordered_set>
#include <vector>
#include <atomic>
#include <cmath>
#include <cstdint>
#include <memory>
#include <random>
#include <string>

#define JUCE_DECLARE_NON_COPYABLE(c) c(const c &) = delete; c &operator=(const c &) = delete;
#define JUCE_DECLARE_NON_MOVEABLE(c) c(c &&) = delete; c &operator=(c &&) = delete;
#define JUCE_LEAK_DETECTOR(c)
#define jassert(x)
#define jassertfalse

namespace juce
{
template <typename T> struct MathConstants
{
    static constexpr T pi = static_cast<T>(3.141592653589793238L);
    static constexpr T twoPi = static_cast<T>(2 * 3.141592653589793238L);
    static constexpr T halfPi = static_cast<T>(3.141592653589793238L / 2);
    static constexpr T euler = static_cast<T>(2.71828182845904523536L);
    static constexpr T sqrt2 = static_cast<T>(1.41421356237309504880L);
};

template <typename T> constexpr T jlimit(T lo, T hi, T v) noexcept { return v < lo ? lo : (hi < v ? hi : v); }
template <typename T> constexpr T jmax(T a, T b) noexcept { return a < b ? b : a; }
template <typename T> constexpr T jmin(T a, T b) noexcept { return b < a ? b : a; }
template <typename T> constexpr T jmap(T v, T srcLo, T srcHi, T dstLo, T dstHi) noexcept
{
    return dstLo + (dstHi - dstLo) * (v - srcLo) / (srcHi - srcLo);
}
template <typename T> constexpr T jmap(T v, T dstLo, T dstHi) noexcept { return dstLo + (dstHi - dstLo) * v; }
template <typename T> inline int roundToInt(T v) noexcept { return (int)std::lround(v); }

/** Small fast PRNG with JUCE's Random interface (xorshift; not JUCE's algorithm). */
class Random
{
  public:
    Random() : s(seedCounter().fetch_add(0x9E3779B97F4A7C15ull) | 1u) {}
    explicit Random(int64_t seed) : s(uint64_t(seed) * 0x9E3779B97F4A7C15ull | 1u) {}
    void setSeed(int64_t seed) { s = uint64_t(seed) * 0x9E3779B97F4A7C15ull | 1u; }
    int nextInt() noexcept { return (int)(next() >> 32); }
    int nextInt(int max) noexcept { return max <= 0 ? 0 : (int)((next() >> 33) % uint64_t(max)); }
    float nextFloat() noexcept { return float(next() >> 40) * (1.0f / 16777216.0f); }
    double nextDouble() noexcept { return double(next() >> 11) * (1.0 / 9007199254740992.0); }
    bool nextBool() noexcept { return (next() >> 63) != 0; }
    static Random &getSystemRandom() { static thread_local Random r; return r; }

  private:
    uint64_t s;
    uint64_t next() noexcept { s ^= s << 13; s ^= s >> 7; s ^= s << 17; return s; }
    static std::atomic<uint64_t> &seedCounter() { static std::atomic<uint64_t> c{0x1234567ull}; return c; }
};

struct ByteOrder
{
    static constexpr uint32_t swap(uint32_t v) noexcept
    {
        return (v >> 24) | ((v >> 8) & 0xff00u) | ((v << 8) & 0xff0000u) | (v << 24);
    }
    static uint32_t littleEndianInt(const void *p) noexcept
    {
        auto b = static_cast<const uint8_t *>(p);
        return uint32_t(b[0]) | uint32_t(b[1]) << 8 | uint32_t(b[2]) << 16 | uint32_t(b[3]) << 24;
    }
    static uint32_t bigEndianInt(const void *p) noexcept { return swap(littleEndianInt(p)); }
    static uint32_t swapIfLittleEndian(uint32_t v) noexcept { return swap(v); }
};

using String = std::string;

/** Lock-free single-producer / single-consumer ring indices (JUCE AbstractFifo interface). */
class AbstractFifo
{
  public:
    explicit AbstractFifo(int capacity) : size(capacity) {}
    int getTotalSize() const noexcept { return size; }
    int getNumReady() const noexcept
    {
        const int s = start.load(std::memory_order_acquire), e = end.load(std::memory_order_acquire);
        return e >= s ? e - s : size - (s - e);
    }
    int getFreeSpace() const noexcept { return size - getNumReady() - 1; }
    struct Scope
    {
        int startIndex1, blockSize1, startIndex2, blockSize2;
    };
    void prepareToWrite(int n, int &s1, int &b1, int &s2, int &b2) const noexcept
    {
        const int e = end.load(std::memory_order_relaxed);
        n = std::min(n, getFreeSpace());
        s1 = e; b1 = std::min(n, size - e); s2 = 0; b2 = std::max(0, n - b1);
    }
    void finishedWrite(int n) noexcept { end.store((end.load(std::memory_order_relaxed) + n) % size, std::memory_order_release); }
    void prepareToRead(int n, int &s1, int &b1, int &s2, int &b2) const noexcept
    {
        const int s = start.load(std::memory_order_relaxed);
        n = std::min(n, getNumReady());
        s1 = s; b1 = std::min(n, size - s); s2 = 0; b2 = std::max(0, n - b1);
    }
    void finishedRead(int n) noexcept { start.store((start.load(std::memory_order_relaxed) + n) % size, std::memory_order_release); }
    Scope write(int n) noexcept
    {
        Scope sc{}; prepareToWrite(n, sc.startIndex1, sc.blockSize1, sc.startIndex2, sc.blockSize2);
        finishedWrite(sc.blockSize1 + sc.blockSize2); return sc;
    }
    Scope read(int n) noexcept
    {
        Scope sc{}; prepareToRead(n, sc.startIndex1, sc.blockSize1, sc.startIndex2, sc.blockSize2);
        finishedRead(sc.blockSize1 + sc.blockSize2); return sc;
    }

  private:
    int size;
    std::atomic<int> start{0}, end{0};
};
} // namespace juce
