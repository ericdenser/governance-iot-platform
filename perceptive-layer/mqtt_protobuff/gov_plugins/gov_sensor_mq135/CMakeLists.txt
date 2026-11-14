idf_component_register(
    SRCS "src/gov_sensor_mq135.cpp"
    INCLUDE_DIRS "include"
    REQUIRES governance_core gov_hal esp_timer
)
