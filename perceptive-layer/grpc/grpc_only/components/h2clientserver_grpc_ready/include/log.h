#pragma once

// Minimal shim to replace the original ESP32log dependency.
// Maps the library's log(level, TAG, fmt, ...) calls to ESP-IDF logging.

#include <stdarg.h>
#include "esp_log.h"
#define WARNING WARN

// The original code uses these level names.
typedef enum {
	DEBUG = 0,
	INFO,
	WARN,
	ERROR
} log_level_t;

static inline esp_log_level_t _h2_to_esp_level(log_level_t lvl)
{
	switch (lvl) {
		case DEBUG: return ESP_LOG_DEBUG;
		case INFO:  return ESP_LOG_INFO;
		case WARN:  return ESP_LOG_WARN;
		case ERROR: return ESP_LOG_ERROR;
		default:    return ESP_LOG_INFO;
	}
}

// Signature expected by h2clientserver: log(INFO, TAG, "...")
static inline void log(log_level_t lvl, const char *tag, const char *fmt, ...)
{
	va_list ap;
	va_start(ap, fmt);
	esp_log_writev(_h2_to_esp_level(lvl), tag, fmt, ap);
	va_end(ap);
}
