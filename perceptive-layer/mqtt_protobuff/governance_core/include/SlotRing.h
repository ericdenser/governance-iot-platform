#pragma once

#include <stddef.h>
#include <stdint.h>
#include <string.h>

// Circular buffer of byte records in caller-provided storage.
// When full, push() overwrites the oldest record.
template <size_t SLOT_BYTES>
class SlotRing {
public:
    struct Slot {
        uint32_t seq;
        uint16_t len;
        uint8_t  data[SLOT_BYTES];
    };

    void attach(Slot* storage, size_t capacity) {
        slots_ = storage;
        cap_ = capacity;
        head_ = tail_ = count_ = 0;
        dropped_ = 0;
    }

    size_t   capacity() const { return cap_; }
    size_t   count() const { return count_; }
    uint32_t dropped() const { return dropped_; }

    // false if there is no storage or the record does not fit in a slot
    bool push(const uint8_t* data, size_t len) {
        if (slots_ == nullptr || cap_ == 0 || len > SLOT_BYTES) return false;
        if (count_ == cap_) {
            tail_ = (tail_ + 1) % cap_;
            count_--;
            dropped_++;
        }
        Slot& s = slots_[head_];
        s.seq = next_seq_++;
        s.len = (uint16_t)len;
        memcpy(s.data, data, len);
        head_ = (head_ + 1) % cap_;
        count_++;
        return true;
    }

    // Copies the oldest record without removing it; seq identifies it for pop()
    bool peek(uint8_t* out, size_t* len, uint32_t* seq) const {
        if (count_ == 0) return false;
        const Slot& s = slots_[tail_];
        memcpy(out, s.data, s.len);
        *len = s.len;
        *seq = s.seq;
        return true;
    }

    // Removes the oldest record only if it is still the one that was peeked
    // (it may have been overwritten meanwhile)
    void pop(uint32_t seq) {
        if (count_ == 0 || slots_[tail_].seq != seq) return;
        tail_ = (tail_ + 1) % cap_;
        count_--;
    }

private:
    Slot*    slots_    = nullptr;
    size_t   cap_      = 0;
    size_t   head_     = 0;
    size_t   tail_     = 0;
    size_t   count_    = 0;
    uint32_t dropped_  = 0;
    uint32_t next_seq_ = 1;
};
