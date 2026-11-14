#pragma once

#include "driver/uart.h"
#include "driver/gpio.h"
#include <time.h>

#define GPS_BUF_SIZE 1024

struct GpsFix {
    float     latitude;
    float     longitude;
    float     altitude;
    struct tm time;
    bool      hasAltitude;
};

class GpsManager {
public:
    GpsManager();

    void init(int tx_pin, int rx_pin, uart_port_t port = UART_NUM_1);

    float getLat();
    float getLon();
    float getAlt();
    bool  hasFix();

    // Leitura one-shot bloqueante: inicializa UART, aguarda fix válido (RMC + opcional GGA),
    // fecha UART e preenche `out`. Usar no boot/time_sync — não chamar com UART já aberta.
    static bool waitForFix(int tx_pin, int rx_pin, uart_port_t port,
                           GpsFix& out, int timeout_ms = 5000);

    // Task contínua: atualiza lat/lon/alt enquanto o device está acordado.
    void run();
    static void taskWrapper(void* _this);

private:
    uart_port_t _uart_port;
    bool        _isInitialized;
    float       _latitude;
    float       _longitude;
    float       _altitude;
    bool        _hasFix;
};
