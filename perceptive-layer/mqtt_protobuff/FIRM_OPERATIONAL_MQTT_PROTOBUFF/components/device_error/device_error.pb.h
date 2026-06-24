/* nanopb header — gerado manualmente para device_error.proto
 *
 * proto source:
 *   message DeviceError {
 *     string device_id    = 1;
 *     string mac          = 2;
 *     string fw_version   = 3;
 *     string ssid         = 4;
 *     uint32 error_code   = 6;
 *     string error_msg    = 7;
 *     string error_source = 8;
 *     string extra        = 9;
 *     bool   resolved     = 10;
 *   }
 */

#ifndef PB_DEVICE_ERROR_PB_H_INCLUDED
#define PB_DEVICE_ERROR_PB_H_INCLUDED
#include <pb.h>

#if PB_PROTO_HEADER_VERSION != 40
#error Regenerate this file with the current version of nanopb generator.
#endif

/* ---- Struct definition ---- */

typedef struct _DeviceError {
    pb_callback_t device_id;    /* field 1  — string */
    pb_callback_t mac;          /* field 2  — string */
    pb_callback_t fw_version;   /* field 3  — string */
    pb_callback_t ssid;         /* field 4  — string */
    uint32_t      error_code;   /* field 6  — uint32 (static) */
    pb_callback_t error_msg;    /* field 7  — string */
    pb_callback_t error_source; /* field 8  — string */
    pb_callback_t extra;        /* field 9  — string (opcional) */
    bool          resolved;     /* field 10 — bool   (static) */
    uint64_t      timestamp;    /* field 11 — uint64 Unix epoch seconds */
} DeviceError;

#ifdef __cplusplus
extern "C" {
#endif

/* ---- Inicializadores ---- */

#define DeviceError_init_default { \
    {{NULL}, NULL},  \
    {{NULL}, NULL},  \
    {{NULL}, NULL},  \
    {{NULL}, NULL},  \
    0,               \
    {{NULL}, NULL},  \
    {{NULL}, NULL},  \
    {{NULL}, NULL},  \
    false,           \
    0                \
}
#define DeviceError_init_zero DeviceError_init_default

/* ---- Tags de campo ---- */

#define DeviceError_device_id_tag    1
#define DeviceError_mac_tag          2
#define DeviceError_fw_version_tag   3
#define DeviceError_ssid_tag         4
#define DeviceError_error_code_tag   6
#define DeviceError_error_msg_tag    7
#define DeviceError_error_source_tag 8
#define DeviceError_extra_tag        9
#define DeviceError_resolved_tag     10
#define DeviceError_timestamp_tag    11

/* ---- Descritores de campo (usados pelo PB_BIND em .pb.c) ---- */

#define DeviceError_FIELDLIST(X, a) \
X(a, CALLBACK, SINGULAR, STRING, device_id,    1)  \
X(a, CALLBACK, SINGULAR, STRING, mac,          2)  \
X(a, CALLBACK, SINGULAR, STRING, fw_version,   3)  \
X(a, CALLBACK, SINGULAR, STRING, ssid,         4)  \
X(a, STATIC,   SINGULAR, UINT32, error_code,   6)  \
X(a, CALLBACK, SINGULAR, STRING, error_msg,    7)  \
X(a, CALLBACK, SINGULAR, STRING, error_source, 8)  \
X(a, CALLBACK, SINGULAR, STRING, extra,        9)  \
X(a, STATIC,   SINGULAR, BOOL,   resolved,     10) \
X(a, STATIC,   SINGULAR, UINT64, timestamp,    11)

#define DeviceError_CALLBACK pb_default_field_callback
#define DeviceError_DEFAULT  NULL

/* ---- Descritor da mensagem ---- */

extern const pb_msgdesc_t DeviceError_msg;
#define DeviceError_fields &DeviceError_msg

#ifdef __cplusplus
}
#endif

#endif /* PB_DEVICE_ERROR_PB_H_INCLUDED */
