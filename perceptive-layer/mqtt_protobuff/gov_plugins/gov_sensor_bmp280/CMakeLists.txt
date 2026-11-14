idf_component_register(
    SRCS "src/gov_sensor_bmp280.cpp"
    INCLUDE_DIRS "include"
    REQUIRES governance_core gov_hal esp-idf-lib__bmp280
)
