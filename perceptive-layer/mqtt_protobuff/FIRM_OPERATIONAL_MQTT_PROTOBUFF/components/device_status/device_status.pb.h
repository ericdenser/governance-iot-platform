/* nanopb header — gerado manualmente para device_status.proto
 *
 * proto source:
 *   message DeviceStatus {
 *     string mac        = 1;
 *     float  fw_version = 2;
 *     string ssid       = 3;
 *     uint32 state      = 4;
 *     string detail     = 5;
 *   }
 */

#ifndef PB_DEVICE_STATUS_PB_H_INCLUDED
#define PB_DEVICE_STATUS_PB_H_INCLUDED
#include <pb.h>

#if PB_PROTO_HEADER_VERSION != 40
#error Regenerate this file with the current version of nanopb generator.
#endif

/* ---- Struct definition ---- */

typedef struct _DeviceStatus {
    pb_callback_t mac;         /* field 1 — string */
    float         fw_version;  /* field 2 — float  (static) */
    pb_callback_t ssid;        /* field 3 — string */
    uint32_t      state;       /* field 4 — uint32 (static) */
    pb_callback_t detail;      /* field 5 — string (opcional) */
} DeviceStatus;

#ifdef __cplusplus
extern "C" {
#endif

/* ---- Inicializadores ---- */

#define DeviceStatus_init_default { \
    {{NULL}, NULL},  \
    0,               \
    {{NULL}, NULL},  \
    0,               \
    {{NULL}, NULL}   \
}
#define DeviceStatus_init_zero DeviceStatus_init_default

/* ---- Tags de campo ---- */

#define DeviceStatus_mac_tag         1
#define DeviceStatus_fw_version_tag  2
#define DeviceStatus_ssid_tag        3
#define DeviceStatus_state_tag       4
#define DeviceStatus_detail_tag      5

/* ---- Descritores de campo (usados pelo PB_BIND em .pb.c) ---- */

#define DeviceStatus_FIELDLIST(X, a) \
X(a, CALLBACK, SINGULAR, STRING, mac,        1) \
X(a, STATIC,   SINGULAR, FLOAT,  fw_version, 2) \
X(a, CALLBACK, SINGULAR, STRING, ssid,       3) \
X(a, STATIC,   SINGULAR, UINT32, state,      4) \
X(a, CALLBACK, SINGULAR, STRING, detail,     5)

#define DeviceStatus_CALLBACK pb_default_field_callback
#define DeviceStatus_DEFAULT  NULL

/* ---- Descritor da mensagem ---- */

extern const pb_msgdesc_t DeviceStatus_msg;
#define DeviceStatus_fields &DeviceStatus_msg

#ifdef __cplusplus
}
#endif

#endif /* PB_DEVICE_STATUS_PB_H_INCLUDED */
