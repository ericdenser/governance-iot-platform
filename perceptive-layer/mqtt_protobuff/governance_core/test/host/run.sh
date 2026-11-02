#!/bin/sh
# Builds and runs the sensor kernel test on the host (no board needed)
set -e
cd "$(dirname "$0")"
g++ -std=c++17 -Wall -Wextra -Wno-missing-field-initializers -I stubs -I ../../include \
    test_gov_sensor.cpp ../../src/gov_sensor.cpp -o /tmp/test_gov_sensor
/tmp/test_gov_sensor
