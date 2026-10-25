#pragma once

// Driver so pode ser instalado uma vez
class ChipTempManager {
public:
    static void init();
    static bool read(float& celsius);
};
