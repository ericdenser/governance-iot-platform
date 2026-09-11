#pragma once
#include <bmp280.h>

class BmpManager {
    private:
        bmp280_t dev;

    public:

        void init();
        bool readTemp(float &temp);
        bool readPressure(float &pressure);
        bool readHumidity(float &humidity);
};



