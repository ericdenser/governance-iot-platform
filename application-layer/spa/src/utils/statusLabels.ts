// Mapa central codigo -> rotulo/tom/icone usado pelo StatusLabel.
// Pra renomear um status ou mudar a cor dele na tela, edite so aqui.

export type StatusTone = 'success' | 'warning' | 'danger' | 'accent' | 'neutral'
export type StatusIcon = 'check' | 'cross' | 'half' | 'pending' | 'dash' | 'alert'

export interface StatusInfo {
  label: string
  tone: StatusTone
  icon: StatusIcon
}

export type StatusKind =
  | 'command'
  | 'batch'
  | 'device'
  | 'health'
  | 'error'
  | 'firmware'
  | 'event'
  | 'result'
  | 'sensor'
  | 'conn'

const s = (label: string, tone: StatusTone, icon: StatusIcon): StatusInfo => ({ label, tone, icon })

const MAP: Record<StatusKind, Record<string, StatusInfo>> = {
  // Resultado do comando por device
  command: {
    PENDING:           s('Pendente', 'neutral', 'pending'),
    COMPLETED_SUCCESS: s('Concluído', 'success', 'check'),
    FAILED:            s('Falhou', 'danger', 'cross'),
    TIMEOUT:           s('Sem resposta', 'danger', 'cross'),
    PUBLISH_FAILED:    s('Falha no envio', 'danger', 'cross'),
    SKIPPED:           s('Ignorado', 'neutral', 'dash'),
  },
  // Status agregado de um comando enviado pra varios devices
  batch: {
    IN_PROGRESS: s('Em andamento', 'neutral', 'pending'),
    SUCCESS:     s('Sucesso', 'success', 'check'),
    PARTIAL:     s('Parcial', 'warning', 'half'),
    FAILED:      s('Falhou', 'danger', 'cross'),
  },
  device: {
    PENDING:          s('Aguardando ativação', 'neutral', 'pending'),
    PROVISIONING:     s('Provisionando', 'neutral', 'pending'),
    ACTIVE:           s('Ativo', 'success', 'check'),
    COMMAND_PENDING:  s('Comando em andamento', 'accent', 'pending'),
    OTA_PENDING:      s('Atualização pendente', 'accent', 'pending'),
    REVOKED:          s('Revogado', 'danger', 'cross'),
    ERROR:            s('Erro', 'danger', 'cross'),
    CRITICAL_BATTERY: s('Bateria crítica', 'danger', 'alert'),
  },
  health: {
    OK:      s('Normal', 'success', 'check'),
    WARNING: s('Alerta', 'warning', 'alert'),
  },
  // Status de tratamento de um erro reportado pelo device
  error: {
    PENDING:      s('Em aberto', 'warning', 'pending'),
    FIXED:        s('Resolvido', 'success', 'check'),
    RETRY_FAILED: s('Falhou ao resolver', 'danger', 'cross'),
    NOT_FIXABLE:  s('Sem correção', 'danger', 'cross'),
  },
  firmware: {
    STAGED:     s('Em preparo', 'neutral', 'pending'),
    DEPLOYED:   s('Publicado', 'success', 'check'),
    DEPRECATED: s('Descontinuado', 'neutral', 'dash'),
    CORRUPTED:  s('Corrompido', 'danger', 'cross'),
  },
  // Conexao derivada do lastSeen (ver utils/connection.ts)
  conn: {
    online:  s('Online', 'success', 'check'),
    offline: s('Offline', 'neutral', 'dash'),
    pending: s('Aguardando ativação', 'neutral', 'pending'),
  },
  sensor: {
    ON:  s('Ativo', 'success', 'check'),
    OFF: s('Inativo', 'neutral', 'dash'),
  },
  // Sucesso/falha de uma acao (auditoria)
  result: {
    OK:   s('OK', 'success', 'check'),
    FAIL: s('Falhou', 'danger', 'cross'),
  },
  event: {
    DEVICE_PROVISIONED:           s('Provisionado', 'success', 'check'),
    DEVICE_PROVISION_FAILED:      s('Falha no provisionamento', 'danger', 'cross'),
    DEVICE_ONLINE:                s('Online', 'success', 'check'),
    DEVICE_OFFLINE:               s('Offline', 'neutral', 'dash'),
    DEVICE_UPDATED:               s('Firmware atualizado', 'success', 'check'),
    DEVICE_UPDATE_FAILED:         s('Falha na atualização', 'danger', 'cross'),
    DEVICE_FIRMWARE_ROLLBACK:     s('Rollback de firmware', 'warning', 'half'),
    DEVICE_COMMAND_COMPLETE:      s('Comando concluído', 'success', 'check'),
    DEVICE_SENSOR_STATUS_CHANGED: s('Sensores alterados', 'neutral', 'dash'),
    DEVICE_CRITICAL_BATTERY:      s('Bateria crítica', 'danger', 'alert'),
    DEVICE_BATTERY_RECOVERED:     s('Bateria recuperada', 'success', 'check'),
    DEVICE_HEALTH_WARNING:        s('Alerta de saúde', 'warning', 'alert'),
    DEVICE_HEALTH_RECOVERED:      s('Saúde normalizada', 'success', 'check'),
    DEVICE_REVOKED:               s('Revogado', 'danger', 'cross'),
  },
}

// Codigo desconhecido: mostra o proprio codigo legivel, sem cor
const humanize = (code: string): string => {
  const t = code.toLowerCase().replace(/_/g, ' ')
  return t.charAt(0).toUpperCase() + t.slice(1)
}

export const statusInfo = (kind: StatusKind, code: string | null | undefined): StatusInfo => {
  if (!code) return s('—', 'neutral', 'dash')
  return MAP[kind][code] ?? s(humanize(code), 'neutral', 'dash')
}

export const statusText = (kind: StatusKind, code: string | null | undefined): string =>
  statusInfo(kind, code).label
