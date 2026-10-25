#pragma once

enum class AirLevel { UNKNOWN, GOOD, MODERATE, POOR, BAD };

// CO2 estimado pela curva do datasheet; depende de R0 calibrado
class Mq135Manager {
public:
    // false = desabilitado ou sem sinal no AO
    static bool read(float& ppm);

    static int warmupLeftS();

    static AirLevel    classify(float ppm);
    static const char* levelText(AirLevel level);
};
