import type { DeviceCommands, TargetChip } from '@/types/models'

// Chips suportados e o que cada firmware implementa. Pra liberar um comando
// num chip (ou adicionar um chip novo), edite so aqui.
interface ChipInfo {
  label: string
  commands: DeviceCommands[]
}

export const CHIPS: Record<TargetChip, ChipInfo> = {
  ESP32_S3: { label: 'ESP32-S3', commands: ['UPDATE', 'REBOOT', 'DEEP_SLEEP', 'FIRMWARE_ROLLBACK'] },
  ESP8266:  { label: 'ESP8266',  commands: ['UPDATE'] },
}

export const CHIP_LIST = Object.keys(CHIPS) as TargetChip[]

// Registros antigos (antes do campo existir) sao ESP32-S3
export const chipOf = (chip: TargetChip | null | undefined): TargetChip => chip ?? 'ESP32_S3'

export const chipLabel = (chip: TargetChip | null | undefined): string => CHIPS[chipOf(chip)].label

export const supportsCommand = (chip: TargetChip, cmd: DeviceCommands): boolean =>
  CHIPS[chip].commands.includes(cmd)
