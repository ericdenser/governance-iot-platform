/* nanopb binding definitions para device_telemetry.proto */

#include "device_telemetry.pb.h"

#if PB_PROTO_HEADER_VERSION != 40
#error Regenerate this file with the current version of nanopb generator.
#endif

/* SensorReading deve ser vinculado antes de DeviceTelemetry,
 * pois DeviceTelemetry referencia SensorReading_fields. */
PB_BIND(SensorReading,   SensorReading,   AUTO)
PB_BIND(DeviceTelemetry, DeviceTelemetry, AUTO)
