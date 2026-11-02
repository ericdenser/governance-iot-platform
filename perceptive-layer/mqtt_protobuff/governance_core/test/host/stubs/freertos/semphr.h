#pragma once
static inline SemaphoreHandle_t xSemaphoreCreateMutex(void) { static int m; return &m; }
static inline int xSemaphoreTake(SemaphoreHandle_t, unsigned) { return 1; }
static inline int xSemaphoreGive(SemaphoreHandle_t) { return 1; }
