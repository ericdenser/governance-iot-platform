/* nanopb header — gerado manualmente para device_telemetry.proto
 *
 * proto source:
 *   message SensorReading {
 *     string key   = 1;
 *     float  value = 2;
 *   }
 *   message DeviceTelemetry {
 *     string                   device_id = 1;
 *     repeated SensorReading   readings  = 2;
 *     uint64                   timestamp = 3;  // Unix epoch seconds (DS1307 / SNTP)
 *   }
 */

#ifndef PB_DEVICE_TELEMETRY_PB_H_INCLUDED
#define PB_DEVICE_TELEMETRY_PB_H_INCLUDED
#include <pb.h>

#if PB_PROTO_HEADER_VERSION != 40
#error Regenerate this file with the current version of nanopb generator.
#endif

/* ---- Limites estáticos ---- */

#define TELEMETRY_MAX_READINGS  16
#define SENSOR_KEY_MAX_SIZE     32

/* ---- Struct definitions ---- */

typedef struct _SensorReading {
    char  key[SENSOR_KEY_MAX_SIZE]; /* field 1 — string (static) */
    float value;                    /* field 2 — float  (static) */
} SensorReading;

typedef struct _DeviceTelemetry {
    pb_callback_t device_id;                        /* field 1 — string (callback) */
    uint64_t      timestamp;                        /* field 3 — uint64 Unix epoch seconds */
    pb_size_t     readings_count;                   /* count must immediately precede array */
    SensorReading readings[TELEMETRY_MAX_READINGS]; /* field 2 — repeated SensorReading (static) */
} DeviceTelemetry;

#ifdef __cplusplus
extern "C" {
#endif

/* ---- Inicializadores ---- */

#define SensorReading_init_default  { "", 0.0f }
#define SensorReading_init_zero     SensorReading_init_default

#define DeviceTelemetry_init_default { \
    {{NULL}, NULL}, \
    0,              \
    0,              \
    {}              \
}
#define DeviceTelemetry_init_zero DeviceTelemetry_init_default

/* ---- Tags de campo ---- */

#define SensorReading_key_tag   1
#define SensorReading_value_tag 2

#define DeviceTelemetry_device_id_tag  1
#define DeviceTelemetry_readings_tag   2
#define DeviceTelemetry_timestamp_tag  3

/* ---- Descritores de campo (usados pelo PB_BIND em .pb.c) ---- */

#define SensorReading_FIELDLIST(X, a) \
X(a, STATIC, SINGULAR, STRING, key,   1) \
X(a, STATIC, SINGULAR, FLOAT,  value, 2)

#define SensorReading_CALLBACK NULL
#define SensorReading_DEFAULT  NULL

#define DeviceTelemetry_FIELDLIST(X, a) \
X(a, CALLBACK, SINGULAR, STRING,  device_id, 1) \
X(a, STATIC,   REPEATED, MESSAGE, readings,  2) \
X(a, STATIC,   SINGULAR, UINT64,  timestamp, 3)

#define DeviceTelemetry_CALLBACK pb_default_field_callback
#define DeviceTelemetry_DEFAULT  NULL

/* Submessage type resolver — PB_SUBMSG_DESCRIPTOR expands to &(SensorReading_msg) */
#define DeviceTelemetry_readings_MSGTYPE SensorReading

/* ---- Descritores das mensagens ---- */

extern const pb_msgdesc_t SensorReading_msg;
#define SensorReading_fields &SensorReading_msg

extern const pb_msgdesc_t DeviceTelemetry_msg;
#define DeviceTelemetry_fields &DeviceTelemetry_msg

#ifdef __cplusplus
}
#endif

#endif /* PB_DEVICE_TELEMETRY_PB_H_INCLUDED */
