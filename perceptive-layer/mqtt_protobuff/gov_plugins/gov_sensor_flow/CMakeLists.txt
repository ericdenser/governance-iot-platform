idf_component_register(
    SRCS "src/gov_sensor_flow.cpp"
    INCLUDE_DIRS "include"
    REQUIRES governance_core driver esp_timer
)
