idf_component_register(
    SRCS "src/gov_sensor_ds3231.cpp"
    INCLUDE_DIRS "include"
    REQUIRES governance_core gov_hal esp-idf-lib__ds3231
)
