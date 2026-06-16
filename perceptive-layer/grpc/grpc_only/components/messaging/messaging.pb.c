/* nanopb binding definitions for messaging.proto */

#include "messaging.pb.h"

#if PB_PROTO_HEADER_VERSION != 40
#error Regenerate this file with the current version of nanopb generator.
#endif

PB_BIND(messaging_RegisterRequest,     messaging_RegisterRequest,     AUTO)
PB_BIND(messaging_Message,             messaging_Message,             AUTO)
PB_BIND(messaging_MessageSentResponse, messaging_MessageSentResponse, AUTO)
