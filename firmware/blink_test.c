#include <stdio.h>
#include <string.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/gpio.h"
#include "driver/i2c.h"
#include "esp_log.h"
#include "esp_bt.h"
#include "esp_bt_main.h"
#include "esp_gap_bt_api.h"
#include "esp_bt_device.h"
#include "esp_spp_api.h"
#include "nvs_flash.h"

// Pin Definitions
#define LED_PIN     GPIO_NUM_27
#define FAN_PIN     GPIO_NUM_26
#define I2C_SDA     GPIO_NUM_21
#define I2C_SCL     GPIO_NUM_22

// I2C Configuration
#define I2C_MASTER_NUM      I2C_NUM_0
#define I2C_MASTER_FREQ_HZ  10000   
#define LCD_ADDR            0x27
#define LCD_BACKLIGHT       0x08
#define LCD_ENABLE          0x04

// Bluetooth
#define SPP_SERVER_NAME "ESP32_APPLIANCE"
#define DEVICE_NAME "ESP32_Controller"

static const char *TAG = "ESP32";
static uint32_t spp_handle = 0;

// Device States
typedef struct {
    bool led_state;
    bool fan_state;
} DeviceStates;

DeviceStates device_states = {
    .led_state = false,
    .fan_state = false
};

// === GPIO INITIALIZATION ===

void gpio_init_pins(void)
{
    gpio_config_t io_conf = {};
    
    // Configure LED pin
    io_conf.intr_type = GPIO_INTR_DISABLE;
    io_conf.mode = GPIO_MODE_OUTPUT;
    io_conf.pin_bit_mask = (1ULL << LED_PIN);
    io_conf.pull_down_en = 0;
    io_conf.pull_up_en = 0;
    gpio_config(&io_conf);
    
    // Configure FAN pin
    io_conf.pin_bit_mask = (1ULL << FAN_PIN);
    gpio_config(&io_conf);
    
    // Set initial state to OFF
    gpio_set_level(LED_PIN, 0);
    gpio_set_level(FAN_PIN, 0);
    
    ESP_LOGI(TAG, "GPIO pins initialized");
}

// === I2C LCD FUNCTIONS ===

static esp_err_t i2c_master_init(void)
{
    i2c_config_t conf = {
        .mode = I2C_MODE_MASTER,
        .sda_io_num = I2C_SDA,
        .scl_io_num = I2C_SCL,
        .sda_pullup_en = GPIO_PULLUP_ENABLE,
        .scl_pullup_en = GPIO_PULLUP_ENABLE,
        .master.clk_speed = I2C_MASTER_FREQ_HZ,
    };
    
    esp_err_t err = i2c_param_config(I2C_MASTER_NUM, &conf);
    if (err != ESP_OK) {
        return err;
    }
    
    return i2c_driver_install(I2C_MASTER_NUM, conf.mode, 0, 0, 0);
}

static esp_err_t lcd_send_byte(uint8_t data, uint8_t mode)
{
    uint8_t high_nibble = data & 0xF0;
    uint8_t low_nibble = (data << 4) & 0xF0;
    
    uint8_t data_arr[4];
    data_arr[0] = high_nibble | mode | LCD_BACKLIGHT | LCD_ENABLE;
    data_arr[1] = high_nibble | mode | LCD_BACKLIGHT;
    data_arr[2] = low_nibble | mode | LCD_BACKLIGHT | LCD_ENABLE;
    data_arr[3] = low_nibble | mode | LCD_BACKLIGHT;
    
    esp_err_t ret = i2c_master_write_to_device(I2C_MASTER_NUM, LCD_ADDR, 
                                                data_arr, 4, 
                                                pdMS_TO_TICKS(1000));
    vTaskDelay(pdMS_TO_TICKS(2));
    return ret;
}

static void lcd_send_cmd(uint8_t cmd)
{
    lcd_send_byte(cmd, 0);
}

static void lcd_send_data(uint8_t data)
{
    lcd_send_byte(data, 1);
}

static void lcd_init(void)
{
    vTaskDelay(pdMS_TO_TICKS(50));
    
    lcd_send_cmd(0x03);
    vTaskDelay(pdMS_TO_TICKS(5));
    lcd_send_cmd(0x03);
    vTaskDelay(pdMS_TO_TICKS(5));
    lcd_send_cmd(0x03);
    vTaskDelay(pdMS_TO_TICKS(5));
    lcd_send_cmd(0x02);
    
    lcd_send_cmd(0x28); // 4-bit mode, 2 lines, 5x8 font
    lcd_send_cmd(0x0C); // Display on, cursor off
    lcd_send_cmd(0x06); // Increment cursor
    lcd_send_cmd(0x01); // Clear display
    vTaskDelay(pdMS_TO_TICKS(2));
    
    ESP_LOGI(TAG, "LCD initialized");
}

static void lcd_clear(void)
{
    lcd_send_cmd(0x01);
    vTaskDelay(pdMS_TO_TICKS(2));
}

static void lcd_set_cursor(uint8_t row, uint8_t col)
{
    uint8_t row_offsets[] = {0x00, 0x40, 0x14, 0x54};
    if (row > 3) row = 0;
    lcd_send_cmd(0x80 | (col + row_offsets[row]));
}

static void lcd_print(const char *str)
{
    while (*str) {
        lcd_send_data(*str++);
    }
}

// === CONTROL FUNCTIONS ===

void set_led(bool state)
{
    device_states.led_state = state;
    gpio_set_level(LED_PIN, state ? 1 : 0);
    ESP_LOGI(TAG, "LED set to: %s", state ? "ON" : "OFF");
}

void set_fan(bool state)
{
    device_states.fan_state = state;
    gpio_set_level(FAN_PIN, state ? 1 : 0);
    ESP_LOGI(TAG, "FAN set to: %s", state ? "ON" : "OFF");
}

void toggle_led(void)
{
    set_led(!device_states.led_state);
}

void toggle_fan(void)
{
    set_fan(!device_states.fan_state);
}

void update_lcd_display(void)
{
    lcd_clear();
    lcd_set_cursor(0, 0);
    lcd_print("LED: ");
    lcd_print(device_states.led_state ? "ON " : "OFF");
    
    lcd_set_cursor(1, 0);
    lcd_print("FAN: ");
    lcd_print(device_states.fan_state ? "ON " : "OFF");
}

void send_status_to_app(void)
{
    if (spp_handle != 0) {
        char status[50];
        sprintf(status, "LED:%d,FAN:%d\n", 
                device_states.led_state ? 1 : 0, 
                device_states.fan_state ? 1 : 0);
        esp_spp_write(spp_handle, strlen(status), (uint8_t *)status);
        ESP_LOGI(TAG, "Status sent: %s", status);
    }
}

// === BLUETOOTH COMMAND HANDLER ===

void process_command(char *cmd)
{
    ESP_LOGI(TAG, "Processing command: %s", cmd);
    
    if (strcmp(cmd, "LED_ON") == 0) {
        set_led(true);
        update_lcd_display();
        send_status_to_app();
    }
    else if (strcmp(cmd, "LED_OFF") == 0) {
        set_led(false);
        update_lcd_display();
        send_status_to_app();
    }
    else if (strcmp(cmd, "LED_TOGGLE") == 0) {
        toggle_led();
        update_lcd_display();
        send_status_to_app();
    }
    else if (strcmp(cmd, "FAN_ON") == 0) {
        set_fan(true);
        update_lcd_display();
        send_status_to_app();
    }
    else if (strcmp(cmd, "FAN_OFF") == 0) {
        set_fan(false);
        update_lcd_display();
        send_status_to_app();
    }
    else if (strcmp(cmd, "FAN_TOGGLE") == 0) {
        toggle_fan();
        update_lcd_display();
        send_status_to_app();
    }
    else if (strcmp(cmd, "STATUS") == 0) {
        send_status_to_app();
    }
    else {
        ESP_LOGW(TAG, "Unknown command: %s", cmd);
    }
}

// === BLUETOOTH SPP CALLBACK ===

static void esp_spp_cb(esp_spp_cb_event_t event, esp_spp_cb_param_t *param)
{
    switch (event) {
    case ESP_SPP_INIT_EVT:
        ESP_LOGI(TAG, "ESP_SPP_INIT_EVT");
        esp_bt_dev_set_device_name(DEVICE_NAME);
        esp_bt_gap_set_scan_mode(ESP_BT_CONNECTABLE, ESP_BT_GENERAL_DISCOVERABLE);
        esp_spp_start_srv(ESP_SPP_SEC_NONE, ESP_SPP_ROLE_SLAVE, 0, SPP_SERVER_NAME);
        break;
        
    case ESP_SPP_SRV_OPEN_EVT:
        ESP_LOGI(TAG, "ESP_SPP_SRV_OPEN_EVT: Client connected");
        spp_handle = param->srv_open.handle;
        
        // Update LCD to show connected
        lcd_clear();
        lcd_set_cursor(0, 0);
        lcd_print("BT Connected!");
        vTaskDelay(pdMS_TO_TICKS(1000));
        update_lcd_display();
        
        // Send initial status to app
        send_status_to_app();
        break;
        
    case ESP_SPP_CLOSE_EVT:
        ESP_LOGI(TAG, "ESP_SPP_CLOSE_EVT: Client disconnected");
        spp_handle = 0;
        
        // Update LCD to show disconnected
        lcd_clear();
        lcd_set_cursor(0, 0);
        lcd_print("BT Disconnected");
        vTaskDelay(pdMS_TO_TICKS(1000));
        update_lcd_display();
        break;
        
    case ESP_SPP_DATA_IND_EVT:
        ESP_LOGI(TAG, "ESP_SPP_DATA_IND_EVT len=%d", param->data_ind.len);
        
        // Process received command
        char command[64] = {0};
        if (param->data_ind.len < sizeof(command)) {
            memcpy(command, param->data_ind.data, param->data_ind.len);
            command[param->data_ind.len] = '\0';
            
            // Remove newline/carriage return
            char *newline = strchr(command, '\n');
            if (newline) *newline = '\0';
            char *carriage = strchr(command, '\r');
            if (carriage) *carriage = '\0';
            
            process_command(command);
        }
        break;
        
    case ESP_SPP_CONG_EVT:
        ESP_LOGI(TAG, "ESP_SPP_CONG_EVT");
        break;
        
    case ESP_SPP_WRITE_EVT:
        ESP_LOGI(TAG, "ESP_SPP_WRITE_EVT");
        break;
        
    default:
        break;
    }
}

static void esp_bt_gap_cb(esp_bt_gap_cb_event_t event, esp_bt_gap_cb_param_t *param)
{
    switch (event) {
    case ESP_BT_GAP_AUTH_CMPL_EVT:
        if (param->auth_cmpl.stat == ESP_BT_STATUS_SUCCESS) {
            ESP_LOGI(TAG, "Authentication success: %s", param->auth_cmpl.device_name);
        } else {
            ESP_LOGE(TAG, "Authentication failed, status:%d", param->auth_cmpl.stat);
        }
        break;
    default:
        break;
    }
}

// === BLUETOOTH INITIALIZATION ===

void bluetooth_init(void)
{
    ESP_LOGI(TAG, "Initializing Bluetooth...");
    
    esp_err_t ret;
    
    // Initialize NVS
    ret = nvs_flash_init();
    if (ret == ESP_ERR_NVS_NO_FREE_PAGES || ret == ESP_ERR_NVS_NEW_VERSION_FOUND) {
        ESP_ERROR_CHECK(nvs_flash_erase());
        ret = nvs_flash_init();
    }
    ESP_ERROR_CHECK(ret);
    
    // Release BLE memory (we only need classic BT)
    //ESP_ERROR_CHECK(esp_bt_controller_mem_release(ESP_BT_MODE_BLE));
    
    // Initialize BT controller
    esp_bt_controller_config_t bt_cfg = BT_CONTROLLER_INIT_CONFIG_DEFAULT();
    ret = esp_bt_controller_init(&bt_cfg);
    if (ret) {
        ESP_LOGE(TAG, "Bluetooth controller initialize failed: %s", esp_err_to_name(ret));
        return;
    }
    
    ret = esp_bt_controller_enable(ESP_BT_MODE_BTDM);
    if (ret) {
        ESP_LOGE(TAG, "Bluetooth controller enable failed: %s", esp_err_to_name(ret));
        return;
    }
    
    ret = esp_bluedroid_init();
    if (ret) {
        ESP_LOGE(TAG, "Bluedroid initialize failed: %s", esp_err_to_name(ret));
        return;
    }
    
    ret = esp_bluedroid_enable();
    if (ret) {
        ESP_LOGE(TAG, "Bluedroid enable failed: %s", esp_err_to_name(ret));
        return;
    }
    
    // Register GAP callback
    ret = esp_bt_gap_register_callback(esp_bt_gap_cb);
    if (ret) {
        ESP_LOGE(TAG, "GAP register failed: %s", esp_err_to_name(ret));
        return;
    }
    
    // Register SPP callback
    ret = esp_spp_register_callback(esp_spp_cb);
    if (ret) {
        ESP_LOGE(TAG, "SPP register failed: %s", esp_err_to_name(ret));
        return;
    }
    
    ret = esp_spp_init(ESP_SPP_MODE_CB);
    if (ret) {
        ESP_LOGE(TAG, "SPP init failed: %s", esp_err_to_name(ret));
        return;
    }
    
    // Set device to pairable and discoverable
    esp_bt_sp_param_t param_type = ESP_BT_SP_IOCAP_MODE;
    esp_bt_io_cap_t iocap = ESP_BT_IO_CAP_NONE;
    esp_bt_gap_set_security_param(param_type, &iocap, sizeof(uint8_t));
    
    ESP_LOGI(TAG, "Bluetooth initialized successfully");
    ESP_LOGI(TAG, "Device name: %s", DEVICE_NAME);
}

// === MAIN ===

void app_main(void)
{
    ESP_LOGI(TAG, "Starting ESP32 Appliance Controller");
    
    // Initialize hardware
    gpio_init_pins();
    
    esp_err_t ret = i2c_master_init();
    if (ret != ESP_OK) {
        ESP_LOGE(TAG, "I2C initialization failed!");
    }
    
    vTaskDelay(pdMS_TO_TICKS(100));
    lcd_init();
    
    // Welcome message
    lcd_clear();
    lcd_set_cursor(0, 0);
    lcd_print("Initializing...");
    
    // Initialize Bluetooth
    bluetooth_init();
    
    // === FIX: Re-initialize LCD after Bluetooth ===
    vTaskDelay(pdMS_TO_TICKS(500));
    lcd_init();  // Re-init LCD to clear any corruption
    
    vTaskDelay(pdMS_TO_TICKS(100));
    lcd_clear();
    lcd_set_cursor(0, 0);
    lcd_print("Waiting for BT");
    lcd_set_cursor(1, 0);
    lcd_print("connection...");
    
    // Initial display after BT ready
    vTaskDelay(pdMS_TO_TICKS(2000));
    update_lcd_display();
    
    ESP_LOGI(TAG, "System ready. Waiting for Bluetooth connection...");
    
    // Main loop - keep system alive
    while(1) {
        vTaskDelay(pdMS_TO_TICKS(1000));
    }
}