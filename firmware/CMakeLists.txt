idf_component_register(
    SRCS "blink_test.c"
    INCLUDE_DIRS "."
    REQUIRES bt nvs_flash driver
)