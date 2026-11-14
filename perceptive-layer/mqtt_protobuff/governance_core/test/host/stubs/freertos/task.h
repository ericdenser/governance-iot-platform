#pragma once
// The sampler task never runs on host; tests call gov_sensors_sample_due() directly
extern int g_tasks_created;
static inline int xTaskCreate(void (*)(void*), const char*, int, void*, int, void*) { g_tasks_created++; return 1; }
static inline void vTaskDelay(unsigned) {}
