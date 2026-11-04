#pragma once

#include <stddef.h>
#include <stdint.h>

// Encoded telemetry kept while MQTT is down, sent oldest first when it is back
namespace TelemetryBuffer {

constexpr size_t SLOT_BYTES = 512;

// PSRAM when available; without it the capacity is reduced
bool     init(size_t capacity);
bool     push(const uint8_t* data, size_t len);
bool     peek(uint8_t* out, size_t* len, uint32_t* seq);
void     pop(uint32_t seq);
size_t   count();
size_t   capacity();
uint32_t dropped();

}  // namespace TelemetryBuffer
