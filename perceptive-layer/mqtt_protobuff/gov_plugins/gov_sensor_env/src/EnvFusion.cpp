#include "EnvFusion.h"

#include <math.h>
#include <algorithm>

void EnvFusion::push(float v) {
    hist_[hist_next_] = v;
    hist_next_ = (hist_next_ + 1) % HISTORY;
    if (hist_len_ < HISTORY) hist_len_++;
}

float EnvFusion::reference() const {
    float tmp[HISTORY];
    std::copy(hist_, hist_ + hist_len_, tmp);
    std::sort(tmp, tmp + hist_len_);
    return (hist_len_ % 2) ? tmp[hist_len_ / 2]
                           : 0.5f * (tmp[hist_len_ / 2 - 1] + tmp[hist_len_ / 2]);
}

bool EnvFusion::fuse(const float* values, const bool* valid, int n, float& out,
                     uint8_t* used_mask) {
    if (n > MAX_SOURCES) n = MAX_SOURCES;

    float lo = INFINITY, hi = -INFINITY;
    int count = 0;
    for (int i = 0; i < n; i++) {
        if (!valid[i] || !isfinite(values[i])) continue;
        lo = std::min(lo, values[i]);
        hi = std::max(hi, values[i]);
        count++;
    }
    if (count == 0) return false;

    bool agree = (hi - lo) <= max_spread_;
    bool have_ref = hist_len_ >= MIN_HISTORY;

    // Anchor = value the accepted sources must be close to
    float anchor = 0.0f;
    bool  filter = !agree && have_ref;
    if (filter) {
        float ref = reference();
        float best = INFINITY;
        for (int i = 0; i < n; i++) {
            if (!valid[i] || !isfinite(values[i])) continue;
            float d = fabsf(values[i] - ref);
            if (d < best) { best = d; anchor = values[i]; }
        }
    }

    float sum = 0.0f;
    int used = 0;
    uint8_t mask = 0;
    for (int i = 0; i < n; i++) {
        if (!valid[i] || !isfinite(values[i])) continue;
        if (filter && fabsf(values[i] - anchor) > max_spread_) continue;
        sum += values[i];
        used++;
        mask |= (uint8_t)(1u << i);
    }

    out = sum / used;
    if (used_mask) *used_mask = mask;
    if (agree || have_ref) push(out);
    return true;
}
