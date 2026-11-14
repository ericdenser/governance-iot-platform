#pragma once

#include <stdint.h>

// Same quantity from several sensors: mean when they agree, else drops the ones far from the recent median
class EnvFusion {
public:
    static constexpr int MAX_SOURCES = 4;
    static constexpr int HISTORY     = 15;  // 30 s at the 2 s sample period
    static constexpr int MIN_HISTORY = 3;

    explicit EnvFusion(float max_spread) : max_spread_(max_spread) {}

    // used_mask: bit i set = values[i] entered the result
    bool fuse(const float* values, const bool* valid, int n, float& out,
              uint8_t* used_mask = nullptr);

private:
    float max_spread_;
    float hist_[HISTORY] = {};
    int   hist_len_  = 0;
    int   hist_next_ = 0;

    void  push(float v);
    float reference() const;
};
