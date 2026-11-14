idf_component_register(
    SRCS "src/gov_sensor_chip_temp.cpp"
    INCLUDE_DIRS "include"
    REQUIRES governance_core driver esp_driver_tsens
)
