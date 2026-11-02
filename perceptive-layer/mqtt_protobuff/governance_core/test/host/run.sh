#!/bin/sh
# Builds and runs the sensor kernel tests on the host (no board needed)
set -e
cd "$(dirname "$0")"
for t in test_gov_sensor test_gov_sensor_flags; do
    g++ -std=c++17 -Wall -Wextra -Wno-missing-field-initializers -I stubs -I ../../include \
        $t.cpp ../../src/gov_sensor.cpp -o /tmp/$t
    /tmp/$t
done
