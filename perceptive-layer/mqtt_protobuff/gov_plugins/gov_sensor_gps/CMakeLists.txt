idf_component_register(
    SRCS "src/gov_sensor_gps.cpp" "src/GpsManager.cpp" "src/minmea.c"
    INCLUDE_DIRS "include"
    PRIV_INCLUDE_DIRS "src"
    REQUIRES governance_core driver esp_driver_uart
)
