/* nanopb header — gerado manualmente para device_status.proto
 *
 * proto source:
 *   message DeviceStatus {
 *     string device_id  = 1;
 *     string mac        = 2;
 *     float  fw_version = 3;
 *     string ssid       = 4;
 *     uint32 state      = 5;
 *     string detail     = 6;
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
    pb_callback_t device_id;   /* field 1 — string */
    pb_callback_t mac;         /* field 2 — string */
    float         fw_version;  /* field 3 — float  (static) */
    pb_callback_t ssid;        /* field 4 — string */
    uint32_t      state;       /* field 5 — uint32 (static) */
    pb_callback_t detail;      /* field 6 — string (opcional) */
} DeviceStatus;

#ifdef __cplusplus
extern "C" {
#endif

/* ---- Inicializadores ---- */

#define DeviceStatus_init_default { \
    {{NULL}, NULL},  \
    {{NULL}, NULL},  \
    0,               \
    {{NULL}, NULL},  \
    0,               \
    {{NULL}, NULL}   \
}
#define DeviceStatus_init_zero DeviceStatus_init_default

/* ---- Tags de campo ---- */

#define DeviceStatus_device_id_tag   1
#define DeviceStatus_mac_tag         2
#define DeviceStatus_fw_version_tag  3
#define DeviceStatus_ssid_tag        4
#define DeviceStatus_state_tag       5
#define DeviceStatus_detail_tag      6

/* ---- Descritores de campo (usados pelo PB_BIND em .pb.c) ---- */

#define DeviceStatus_FIELDLIST(X, a) \
X(a, CALLBACK, SINGULAR, STRING, device_id,  1) \
X(a, CALLBACK, SINGULAR, STRING, mac,        2) \
X(a, STATIC,   SINGULAR, FLOAT,  fw_version, 3) \
X(a, CALLBACK, SINGULAR, STRING, ssid,       4) \
X(a, STATIC,   SINGULAR, UINT32, state,      5) \
X(a, CALLBACK, SINGULAR, STRING, detail,     6)

#define DeviceStatus_CALLBACK pb_default_field_callback
#define DeviceStatus_DEFAULT  NULL

/* ---- Descritor da mensagem ---- */

extern const pb_msgdesc_t DeviceStatus_msg;
#define DeviceStatus_fields &DeviceStatus_msg

#ifdef __cplusplus
}
#endif

#endif /* PB_DEVICE_STATUS_PB_H_INCLUDED */
