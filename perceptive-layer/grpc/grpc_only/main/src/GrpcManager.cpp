#include "GrpcManager.h"
#include "WifiManager.h"
#include "AppState.h"

extern "C" {
#include "h2client.h"
#include "pb_encode.h"
#include "pb_decode.h"
#include "messaging.pb.h"
}

#include "esp_log.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include <string.h>
#include <stdint.h>

#define BROKER_HOST       "172.16.39.40"
#define BROKER_PORT       "50051"
#define BROKER_BASE_URL   "http://" BROKER_HOST ":" BROKER_PORT
#define PATH_SEND_MSG     "/messaging.Messaging/SendMessage"
#define PATH_SUBSCRIBE    "/messaging.Messaging/Subscribe"
#define GRPC_CONTENT_TYPE "application/grpc+proto"

#define SUBSCRIBE_TIMEOUT_MS  3600000

static const char* TAG = "GrpcManager";
static bool g_ready = false;
static GrpcManager::MessageCallback s_callback;

static char         s_sub_topic[128] = {};
static uint8_t      s_sub_buf[1024]  = {};
static size_t       s_sub_len        = 0;
static TaskHandle_t s_sub_task       = NULL;

/* ---- nanopb encode callback — escreve um const char* como string protobuf ---- */

static bool encode_string_cb(pb_ostream_t* stream, const pb_field_t* field, void* const* arg)
{
    const char* str = (const char*)(*arg);
    if (!str) str = "";
    return pb_encode_tag_for_field(stream, field) &&
           pb_encode_string(stream, (const uint8_t*)str, strlen(str));
}

/* ---- nanopb decode callback — lê string protobuf para buffer fixo ----------- */

typedef struct { char* buf; size_t size; } str_ctx_t;

static bool decode_string_cb(pb_istream_t* stream, const pb_field_t* field, void** arg)
{
    str_ctx_t* ctx = (str_ctx_t*)(*arg);
    size_t len  = stream->bytes_left;
    size_t copy = (len < ctx->size - 1) ? len : ctx->size - 1;

    if (!pb_read(stream, (uint8_t*)ctx->buf, copy)) return false;
    ctx->buf[copy] = '\0';

    /* drena bytes que nao couberam no buffer */
    while (stream->bytes_left > 0) {
        uint8_t dummy;
        if (!pb_read(stream, &dummy, 1)) return false;
    }
    return true;
}

/* ---- gRPC framing (5-byte prefix: flag + tamanho big-endian) ----------------- */

static size_t grpc_frame(uint8_t* out, const uint8_t* proto, size_t plen)
{
    out[0] = 0x00;
    out[1] = (uint8_t)((plen >> 24) & 0xFF);
    out[2] = (uint8_t)((plen >> 16) & 0xFF);
    out[3] = (uint8_t)((plen >>  8) & 0xFF);
    out[4] = (uint8_t)(plen         & 0xFF);
    memcpy(out + 5, proto, plen);
    return 5 + plen;
}

/* ---- decodifica uma mensagem recebida pelo Subscribe e dispara o callback ---- */

static void dispatch_message(const uint8_t* pb, size_t pb_len)
{
    char topic_buf[128] = {};
    char from_buf[64]   = {};
    char msg_buf[512]   = {};

    str_ctx_t ctx_topic   = { topic_buf, sizeof(topic_buf) };
    str_ctx_t ctx_from    = { from_buf,  sizeof(from_buf)  };
    str_ctx_t ctx_message = { msg_buf,   sizeof(msg_buf)   };

    messaging_Message msg = messaging_Message_init_zero;
    msg.topic.funcs.decode   = decode_string_cb;  msg.topic.arg   = &ctx_topic;
    msg.from.funcs.decode    = decode_string_cb;  msg.from.arg    = &ctx_from;
    msg.message.funcs.decode = decode_string_cb;  msg.message.arg = &ctx_message;

    pb_istream_t stream = pb_istream_from_buffer(pb, pb_len);
    if (!pb_decode(&stream, messaging_Message_fields, &msg)) {
        ESP_LOGE(TAG, "pb_decode falhou: %s", PB_GET_ERROR(&stream));
        return;
    }

    if (s_callback && msg_buf[0] != '\0') {
        ESP_LOGI(TAG, "Comando recebido — topico: %s | de: %s", topic_buf, from_buf);
        /* ATENCAO: roda no contexto do h2client_task — nao chamar publish() daqui */
        s_callback(std::string(topic_buf), std::string(msg_buf));
    }
}

/* ---- callback de dados do Subscribe (chamado pelo h2client a cada chunk) ----- */

static void subscribe_data_callback(const char* data, size_t len)
{
    if (s_sub_len + len > sizeof(s_sub_buf)) {
        ESP_LOGW(TAG, "subscribe buffer cheio, descartando");
        s_sub_len = 0;
    }
    memcpy(s_sub_buf + s_sub_len, data, len);
    s_sub_len += len;

    size_t pos = 0;
    while (pos + 5 <= s_sub_len) {
        uint32_t msg_len = ((uint32_t)s_sub_buf[pos+1] << 24) |
                           ((uint32_t)s_sub_buf[pos+2] << 16) |
                           ((uint32_t)s_sub_buf[pos+3] <<  8) |
                           (uint32_t)s_sub_buf[pos+4];
        if (pos + 5 + msg_len > s_sub_len) break;

        dispatch_message(s_sub_buf + pos + 5, msg_len);
        pos += 5 + msg_len;
    }

    if (pos > 0) {
        s_sub_len -= pos;
        if (s_sub_len > 0) memmove(s_sub_buf, s_sub_buf + pos, s_sub_len);
    }
}

/* ---- task de subscribe persistente ----------------------------------------- */

static void subscribe_task_fn(void* /*arg*/)
{
    while (true) {
        if (!g_ready) { vTaskDelay(pdMS_TO_TICKS(500)); continue; }

        /* Codifica RegisterRequest { topic: s_sub_topic } */
        uint8_t proto_buf[64] = {};
        messaging_RegisterRequest req = messaging_RegisterRequest_init_zero;
        req.topic.funcs.encode = encode_string_cb;
        req.topic.arg          = (void*)s_sub_topic;

        pb_ostream_t ostream = pb_ostream_from_buffer(proto_buf, sizeof(proto_buf));
        if (!pb_encode(&ostream, messaging_RegisterRequest_fields, &req)) {
            ESP_LOGE(TAG, "pb_encode RegisterRequest falhou: %s", PB_GET_ERROR(&ostream));
            vTaskDelay(pdMS_TO_TICKS(3000));
            continue;
        }

        uint8_t grpc_buf[69] = {};
        size_t  grpc_len = grpc_frame(grpc_buf, proto_buf, ostream.bytes_written);
        s_sub_len = 0;

        struct h2client_request h2req = {};
        h2req.method                   = H2_POST;
        h2req.url                      = BROKER_BASE_URL PATH_SUBSCRIBE;
        h2req.requestbody.method       = H2_HANDLEBODY_BUFFER;
        h2req.requestbody.body         = (const char*)grpc_buf;
        h2req.requestbody.size         = (unsigned int)grpc_len;
        h2req.requestbody.written      = 0;
        h2req.requestbody.content_type = (char*)GRPC_CONTENT_TYPE;
        h2req.responsebody.method      = H2_HANDLEBODY_CALLBACK;
        h2req.responsebody.callback    = subscribe_data_callback;
        h2req.timeout_ms               = SUBSCRIBE_TIMEOUT_MS;

        ESP_LOGI(TAG, "Subscribe: conectando ao topico [%s]", s_sub_topic);
        bool ok = h2client_do_request(&h2req);

        if (ok) {
            ESP_LOGI(TAG, "Subscribe: stream fechado pelo broker, reconectando...");
        } else {
            ESP_LOGW(TAG, "Subscribe: conexao perdida, reconectando em 3s...");
            vTaskDelay(pdMS_TO_TICKS(3000));
        }
    }
}

/* ---- interface publica ------------------------------------------------------ */

void GrpcManager::init()
{
    if (g_ready) return;

    int err = h2client_initialize();
    if (err != H2_ERROR_OK) {
        ESP_LOGE(TAG, "h2client_initialize falhou: %d", err);
        
        return;
    }

    std::string mac   = WifiManager::getMacAddress();
    std::string topic = "commands/" + mac;
    strncpy(s_sub_topic, topic.c_str(), sizeof(s_sub_topic) - 1);

    xTaskCreate(subscribe_task_fn, "grpc_sub", 8192, NULL, 4, &s_sub_task);

    g_ready = true;
    ESP_LOGI(TAG, "gRPC client inicializado. Subscribe: [%s]", s_sub_topic);
    AppState::transition(DeviceState::PROVISIONING_SUCCESS, {TAG, "init"});
}

bool GrpcManager::isReady()
{
    return g_ready;
}

void GrpcManager::setCallback(MessageCallback cb)
{
    s_callback = cb;
    ESP_LOGI(TAG, "Callback de mensagens gRPC registrado.");
}

void GrpcManager::publish(const char* payload, const char* topic)
{
    if (!g_ready) return;

    std::string mac = WifiManager::getMacAddress();

    /* Codifica Message { topic, from=MAC, message=payload } */
    uint8_t proto_buf[768] = {};
    messaging_Message msg = messaging_Message_init_zero;
    msg.topic.funcs.encode   = encode_string_cb;  msg.topic.arg   = (void*)topic;
    msg.from.funcs.encode    = encode_string_cb;  msg.from.arg    = (void*)mac.c_str();
    msg.message.funcs.encode = encode_string_cb;  msg.message.arg = (void*)payload;

    pb_ostream_t ostream = pb_ostream_from_buffer(proto_buf, sizeof(proto_buf));
    if (!pb_encode(&ostream, messaging_Message_fields, &msg)) {
        ESP_LOGE(TAG, "pb_encode Message falhou: %s", PB_GET_ERROR(&ostream));
        return;
    }

    uint8_t grpc_buf[773] = {};
    size_t  grpc_len = grpc_frame(grpc_buf, proto_buf, ostream.bytes_written);

    struct h2client_request req = {};
    req.method                   = H2_POST;
    req.url                      = BROKER_BASE_URL PATH_SEND_MSG;
    req.requestbody.method       = H2_HANDLEBODY_BUFFER;
    req.requestbody.body         = (const char*)grpc_buf;
    req.requestbody.size         = (unsigned int)grpc_len;
    req.requestbody.written      = 0;
    req.requestbody.content_type = (char*)GRPC_CONTENT_TYPE;
    req.responsebody.method      = H2_HANDLEBODY_NONE;
    req.timeout_ms               = 5000;

    bool ok = h2client_do_request(&req);
    if (!ok) {
        /* Conexão pode ter sido fechada pelo broker (GoAway). O h2client abre
         * nova conexão automaticamente na segunda tentativa. */
        ESP_LOGW(TAG, "Falha na 1ª tentativa (conexão expirada?), reabrindo...");
        req.requestbody.written = 0;
        ok = h2client_do_request(&req);
    }
    if (ok) {
        ESP_LOGI(TAG, "Publicado (topico=%s): %s", topic, payload);
    } else {
        ESP_LOGE(TAG, "Falha ao publicar no topico %s", topic);
    }
}
