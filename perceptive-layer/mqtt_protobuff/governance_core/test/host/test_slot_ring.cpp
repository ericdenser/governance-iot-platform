// Telemetry ring buffer: ./run.sh
#include <stdio.h>
#include <string.h>
#include "SlotRing.h"

static int failures = 0;
#define CHECK(cond) do { if (!(cond)) { printf("FAIL %s:%d  %s\n", __FILE__, __LINE__, #cond); failures++; } } while (0)

typedef SlotRing<8> Ring;

static void push_str(Ring& r, const char* s) { r.push((const uint8_t*)s, strlen(s)); }

static bool peek_str(Ring& r, char* out, uint32_t* seq) {
    size_t len = 0;
    if (!r.peek((uint8_t*)out, &len, seq)) return false;
    out[len] = '\0';
    return true;
}

int main() {
    Ring::Slot storage[3];
    Ring r;
    char buf[16];
    uint32_t seq = 0;

    // no storage yet
    CHECK(!r.push((const uint8_t*)"a", 1));
    r.attach(storage, 3);

    // oldest first
    push_str(r, "t1"); push_str(r, "t2");
    CHECK(r.count() == 2);
    CHECK(peek_str(r, buf, &seq) && strcmp(buf, "t1") == 0);
    r.pop(seq);
    CHECK(peek_str(r, buf, &seq) && strcmp(buf, "t2") == 0);
    r.pop(seq);
    CHECK(r.count() == 0 && !peek_str(r, buf, &seq));

    // full: overwrites the oldest and counts it
    push_str(r, "a"); push_str(r, "b"); push_str(r, "c"); push_str(r, "d");
    CHECK(r.count() == 3 && r.dropped() == 1);
    CHECK(peek_str(r, buf, &seq) && strcmp(buf, "b") == 0);

    // the peeked record gets overwritten before pop: pop must not drop the next one
    push_str(r, "e");
    r.pop(seq);
    CHECK(r.count() == 3);
    CHECK(peek_str(r, buf, &seq) && strcmp(buf, "c") == 0);

    // record bigger than a slot is refused
    CHECK(!r.push((const uint8_t*)"123456789", 9));
    CHECK(r.count() == 3);

    printf("%s (%d failure(s))\n", failures ? "FAILED" : "OK", failures);
    return failures ? 1 : 0;
}
