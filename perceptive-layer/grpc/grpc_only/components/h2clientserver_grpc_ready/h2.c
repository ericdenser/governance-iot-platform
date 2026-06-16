#include "h2.h"

#include <string.h>
#include <errno.h>
#include <sys/socket.h>
#include "log.h"

#include <nghttp2/nghttp2.h>

#define H2_SEND_BLOCK_SIZE	1024

static const char * TAG = "h2";

const char h2_default_port[] = "443";

const char h2_http_status_200[4] = "200";
const char h2_http_status_400[4] = "400";
const char h2_http_status_403[4] = "403";
const char h2_http_status_404[4] = "404";
const char h2_http_status_405[4] = "405";
const char h2_http_status_408[4] = "408";

const char h2_header_path[] = ":path";
const char h2_header_method[] = ":method";
const char h2_header_scheme[] = ":scheme";
const char h2_header_authority[] = ":authority";
const char h2_header_status[] = ":status";
const char h2_header_contenttype[] = "content-type";
const char h2_header_cachecontrol[] = "cache-control";

const char h2_header_common_contenttype_json[] = "application/json";
const char h2_header_common_contenttype_html[] = "text/html";
const char h2_header_common_contenttype_text[] = "text/plain";
const char h2_header_common_cachecontrol_nocache[] = "no-cache";

static const char h2_method_get[] = "GET";
static const char h2_method_put[] = "PUT";
static const char h2_method_post[] = "POST";

const char * h2_method_to_string(enum h2_method m)
{
	const char * r = NULL;
	switch(m){
		case H2_GET:  r = h2_method_get;  break;
		case H2_PUT:  r = h2_method_put;  break;
		case H2_POST: r = h2_method_post; break;
		default: break;
	}
	return r;
}

enum h2_method h2_method_from_string(const char * method, unsigned int length)
{
	if(length == 0){ length = strlen(method); }

	if(length == h2_const_strlen(h2_method_get) && memcmp(h2_method_get, method, h2_const_strlen(h2_method_get)) == 0)
		return H2_GET;
	if(length == h2_const_strlen(h2_method_put) && memcmp(h2_method_put, method, h2_const_strlen(h2_method_put)) == 0)
		return H2_PUT;
	if(length == h2_const_strlen(h2_method_post) && memcmp(h2_method_post, method, h2_const_strlen(h2_method_post)) == 0)
		return H2_POST;

	return H2_METHOD_UNKNOWN;
}

const char * h2_http_status_to_string(enum h2_http_status s)
{
	const char * r = NULL;
	switch(s){
		case H2_OK:                r = h2_http_status_200; break;
		case H2_BAD_REQUEST:       r = h2_http_status_400; break;
		case H2_FORBIDEN:          r = h2_http_status_403; break;
		case H2_NOT_FOUND:         r = h2_http_status_404; break;
		case H2_METHOD_NOT_ALLOWED:r = h2_http_status_405; break;
		case H2_REQUEST_TIMEOUT:   r = h2_http_status_408; break;
		default: break;
	}
	return r;
}

/**
 * Receive data from the server.
 * Supports both TLS (conn->tls != NULL) and plain TCP (conn->tls == NULL).
 */
ssize_t h2_nghttp2_callback_recv(nghttp2_session *session, uint8_t *buf, size_t length,
		int flags, void *user_data)
{
	ssize_t ret;
	struct h2_connection *conn = (struct h2_connection *)user_data;
	if (!conn) {
		return NGHTTP2_ERR_CALLBACK_FAILURE;
	}

	if (conn->tls) {
		ret = esp_tls_conn_read(conn->tls, buf, length);
		if (ret == ESP_TLS_ERR_SSL_WANT_READ || ret == ESP_TLS_ERR_SSL_WANT_WRITE) {
			return NGHTTP2_ERR_WOULDBLOCK;
		}
	} else {
		ret = recv(conn->sockfd, buf, length, MSG_DONTWAIT);
		if (ret < 0) {
			if (errno == EAGAIN || errno == EWOULDBLOCK) {
				return NGHTTP2_ERR_WOULDBLOCK;
			}
		}
	}

	if (ret < 0) {
		return NGHTTP2_ERR_CALLBACK_FAILURE;
	}

	return ret;
}

/**
 * Send data to the server.
 * Supports both TLS (conn->tls != NULL) and plain TCP (conn->tls == NULL).
 */
ssize_t h2_nghttp2_callback_send(nghttp2_session *session, const uint8_t *data, size_t length,
		int flags, void *user_data)
{
	ssize_t ret;
	struct h2_connection *conn = (struct h2_connection *)user_data;
	if (!conn) {
		return NGHTTP2_ERR_CALLBACK_FAILURE;
	}

	if (conn->tls) {
		ret = esp_tls_conn_write(conn->tls, data, length);
		if (ret == ESP_TLS_ERR_SSL_WANT_READ || ret == ESP_TLS_ERR_SSL_WANT_WRITE) {
			return NGHTTP2_ERR_WOULDBLOCK;
		}
	} else {
		ret = send(conn->sockfd, data, length, 0);
		if (ret < 0) {
			if (errno == EAGAIN || errno == EWOULDBLOCK) {
				return NGHTTP2_ERR_WOULDBLOCK;
			}
		}
	}

	if (ret < 0) {
		return NGHTTP2_ERR_CALLBACK_FAILURE;
	}

	return ret;
}
