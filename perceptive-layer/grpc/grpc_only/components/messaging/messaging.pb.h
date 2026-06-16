/* nanopb header for messaging.proto (mbroker-main/proto/messaging.proto)
 *
 * proto source:
 *   package messaging;
 *   message RegisterRequest { string topic   = 1; }
 *   message Message         { string topic   = 1;
 *                             string from    = 2;
 *                             string message = 3; }
 *   message MessageSentResponse { string status = 1; }
 */

#ifndef PB_MESSAGING_PB_H_INCLUDED
#define PB_MESSAGING_PB_H_INCLUDED
#include <pb.h>

#if PB_PROTO_HEADER_VERSION != 40
#error Regenerate this file with the current version of nanopb generator.
#endif

/* ---- Struct definitions ---- */

typedef struct _messaging_RegisterRequest {
    pb_callback_t topic;
} messaging_RegisterRequest;

typedef struct _messaging_Message {
    pb_callback_t topic;
    pb_callback_t from;
    pb_callback_t message;
} messaging_Message;

typedef struct _messaging_MessageSentResponse {
    pb_callback_t status;
} messaging_MessageSentResponse;

#ifdef __cplusplus
extern "C" {
#endif

/* ---- Initializers ---- */

#define messaging_RegisterRequest_init_default      {{{NULL}, NULL}}
#define messaging_RegisterRequest_init_zero         {{{NULL}, NULL}}

#define messaging_Message_init_default              {{{NULL}, NULL}, {{NULL}, NULL}, {{NULL}, NULL}}
#define messaging_Message_init_zero                 {{{NULL}, NULL}, {{NULL}, NULL}, {{NULL}, NULL}}

#define messaging_MessageSentResponse_init_default  {{{NULL}, NULL}}
#define messaging_MessageSentResponse_init_zero     {{{NULL}, NULL}}

/* ---- Field tags ---- */

#define messaging_RegisterRequest_topic_tag         1
#define messaging_Message_topic_tag                 1
#define messaging_Message_from_tag                  2
#define messaging_Message_message_tag               3
#define messaging_MessageSentResponse_status_tag    1

/* ---- Field descriptors (used by PB_BIND in .pb.c) ---- */

#define messaging_RegisterRequest_FIELDLIST(X, a) \
X(a, CALLBACK, SINGULAR, STRING, topic,   1)
#define messaging_RegisterRequest_CALLBACK pb_default_field_callback
#define messaging_RegisterRequest_DEFAULT  NULL

#define messaging_Message_FIELDLIST(X, a) \
X(a, CALLBACK, SINGULAR, STRING, topic,   1) \
X(a, CALLBACK, SINGULAR, STRING, from,    2) \
X(a, CALLBACK, SINGULAR, STRING, message, 3)
#define messaging_Message_CALLBACK pb_default_field_callback
#define messaging_Message_DEFAULT  NULL

#define messaging_MessageSentResponse_FIELDLIST(X, a) \
X(a, CALLBACK, SINGULAR, STRING, status,  1)
#define messaging_MessageSentResponse_CALLBACK pb_default_field_callback
#define messaging_MessageSentResponse_DEFAULT  NULL

/* ---- Message descriptors ---- */

extern const pb_msgdesc_t messaging_RegisterRequest_msg;
extern const pb_msgdesc_t messaging_Message_msg;
extern const pb_msgdesc_t messaging_MessageSentResponse_msg;

/* Convenience aliases (pb_encode/pb_decode expect a pb_msgdesc_t*) */
#define messaging_RegisterRequest_fields     &messaging_RegisterRequest_msg
#define messaging_Message_fields             &messaging_Message_msg
#define messaging_MessageSentResponse_fields &messaging_MessageSentResponse_msg

#ifdef __cplusplus
}
#endif

#endif /* PB_MESSAGING_PB_H_INCLUDED */
