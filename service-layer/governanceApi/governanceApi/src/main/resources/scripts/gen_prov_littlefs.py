#!/usr/bin/env python3
"""Gera a imagem LittleFS com /prov.txt para o firmware ESP8266.

Uso:
  pip install littlefs-python
  python3 gen_prov_littlefs.py --ssid REDE --password SENHA --token TOKEN \
      --device-id UUID --fw-version 1.0.0 -o littlefs.bin

Gravar (sketch exportado + imagem):
  python3 -m esptool --chip esp8266 --port /dev/ttyUSB0 --baud 460800 write_flash \
      0x0 FIRM_ESP8266_MQTT_PROTOBUFF.ino.bin 0x200000 littlefs.bin
"""
import argparse

from littlefs import LittleFS

FS_OFFSET = 0x200000            # spiffs_start
FS_SIZE = 0x3FA000 - 0x200000   # spiffs_end - spiffs_start = 2072576 bytes
BLOCK_SIZE = 8192               # spiffs_blocksize
PAGE_SIZE = 256                 # spiffs_pagesize (so o mklittlefs usa)


def build_image(values: dict) -> bytes:
    fs = LittleFS(
        block_size=BLOCK_SIZE,
        block_count=FS_SIZE // BLOCK_SIZE,   # 253
        read_size=64,                        # LittleFS.h do core
        prog_size=64,
        cache_size=64,
        lookahead_size=64,
        block_cycles=16,
        # com os defaults da lib o core nao monta e formata a flash
        name_max=32,                         # LFS_NAME_MAX do core
        disk_version=0x00020000,             # lfs 2.5 do core so monta disk v2.0
    )
    content = "".join(f"{k}={v}\n" for k, v in values.items())
    with fs.open("prov.txt", "w") as f:
        f.write(content)
    return bytes(fs.context.buffer)


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--ssid", required=True)
    p.add_argument("--password", required=True)
    p.add_argument("--token", required=True)
    p.add_argument("--device-id", required=True)
    p.add_argument("--fw-version", required=True)
    p.add_argument("-o", "--output", default="littlefs.bin")
    a = p.parse_args()

    image = build_image({
        "wifi_ssid": a.ssid,
        "wifi_pass": a.password,
        "prov_token": a.token,
        "device_id": a.device_id,
        "fw_version": a.fw_version,
    })
    with open(a.output, "wb") as f:
        f.write(image)
    print(f"{a.output}: {len(image)} bytes -> gravar em 0x{FS_OFFSET:X}")


if __name__ == "__main__":
    main()
