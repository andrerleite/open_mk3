#pragma once
#include <juce_core/juce_core.h>
namespace juce::dsp
{
struct FastMathApproximations
{
    /** Pade approximation of sin on [-pi, pi] (same form as JUCE's). */
    template <typename T> static T sin(T x) noexcept
    {
        auto x2 = x * x;
        auto num = -x * (T(-11511339840) + x2 * (T(1640635920) + x2 * (T(-52785432) + x2 * T(479249))));
        auto den = T(11511339840) + x2 * (T(277920720) + x2 * (T(3177720) + x2 * T(18361)));
        return num / den;
    }
    template <typename T> static T cos(T x) noexcept
    {
        auto x2 = x * x;
        auto num = -(T(-39251520) + x2 * (T(18471600) + x2 * (T(-1075032) + T(14615) * x2)));
        auto den = T(39251520) + x2 * (T(1154160) + x2 * (T(16632) + x2 * T(127)));
        return num / den;
    }
};
} // namespace juce::dsp
