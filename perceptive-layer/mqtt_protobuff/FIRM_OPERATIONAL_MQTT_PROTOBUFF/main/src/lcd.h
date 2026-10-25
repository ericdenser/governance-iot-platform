#pragma once

void display_init(int width, int height);

// Mesmo contrato do hook on_state_change do governance_core
void display_show_state(const char* state, const char* detail, bool is_error);

void display_next_screen();

void display_set_sensors(const char* csv);
