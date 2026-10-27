"""A resolver for the install tests. A name starting with rb- is rebound: the first A query is
answered with PUBLIC and every later one with 127.0.0.1, at TTL 0. Any other query is forwarded
to UPSTREAM, so the relay can still reach the internet. Usage: dns.py PUBLIC UPSTREAM"""
import socket
import sys

public = bytes(int(part) for part in sys.argv[1].split("."))
upstream = (sys.argv[2], 53)
loopback = bytes([127, 0, 0, 1])
asked = {}
sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
sock.bind(("0.0.0.0", 53))


def forward(data):
    relay = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    relay.settimeout(3)
    relay.sendto(data, upstream)
    try:
        return relay.recvfrom(4096)[0]
    except socket.timeout:
        return None
    finally:
        relay.close()


while True:
    data, peer = sock.recvfrom(4096)
    end = 12
    while data[end]:
        end += data[end] + 1
    name = data[12:end]
    qtype = int.from_bytes(data[end + 1:end + 3], "big")
    if name[1:4] != b"rb-":
        reply = forward(data)
        if reply:
            sock.sendto(reply, peer)
        continue
    question = data[12:end + 5]
    answered = b"\x00\x01" if qtype == 1 else b"\x00\x00"
    head = data[:2] + b"\x81\x80\x00\x01" + answered + b"\x00\x00\x00\x00"
    answer = b""
    if qtype == 1:
        asked[name] = asked.get(name, 0) + 1
        address = public if asked[name] == 1 else loopback
        answer = b"\xc0\x0c\x00\x01\x00\x01\x00\x00\x00\x00\x00\x04" + address
    sock.sendto(head + question + answer, peer)
