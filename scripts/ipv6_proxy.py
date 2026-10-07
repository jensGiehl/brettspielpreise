import ipaddress
import logging
import os
import re
import select
import socket
import socketserver
import threading
import time


LOG = logging.getLogger("ipv6-proxy")


def allowed_hosts(value):
    hosts = {host.strip().lower() for host in value.split(",") if host.strip()}
    if not hosts or any(not re.fullmatch(r"[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?", host) for host in hosts):
        raise ValueError("Use explicit DNS host names; wildcards and IP addresses are not supported")
    for host in hosts:
        try:
            ipaddress.ip_address(host)
        except ValueError:
            continue
        raise ValueError("IP literals are not supported")
    return hosts


def connect_ipv6(host, port, timeout=5):
    deadline = time.monotonic() + timeout
    addresses = socket.getaddrinfo(host, port, socket.AF_INET6, socket.SOCK_STREAM)
    for family, kind, protocol, _, address in addresses:
        if not ipaddress.ip_address(address[0]).is_global:
            continue
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            break
        upstream = socket.socket(family, kind, protocol)
        upstream.settimeout(remaining)
        try:
            upstream.connect(address)
            LOG.info("IPv6 tunnel host=%s remote=%s source=%s", host, address[0], upstream.getsockname()[0])
            return upstream
        except OSError:
            upstream.close()
    raise OSError("No reachable public IPv6 upstream")


class TunnelHandler(socketserver.BaseRequestHandler):
    def handle(self):
        self.request.settimeout(5)
        try:
            header = bytearray()
            while not header.endswith(b"\r\n\r\n") and len(header) < 8192:
                chunk = self.request.recv(1)
                if not chunk:
                    return
                header.extend(chunk)
            line = bytes(header).split(b"\r\n", 1)[0].decode("ascii")
            match = re.fullmatch(r"CONNECT ([a-zA-Z0-9.-]+):443 HTTP/1\.[01]", line)
            if not header.endswith(b"\r\n\r\n") or not match or match[1].lower() not in self.server.allowed:
                LOG.warning("Denied CONNECT target=%s", match[1].lower() if match else "invalid-request")
                self.request.sendall(b"HTTP/1.1 403 Forbidden\r\nConnection: close\r\n\r\n")
                return
            with connect_ipv6(match[1].lower(), 443) as upstream:
                self.request.sendall(b"HTTP/1.1 200 Connection Established\r\n\r\n")
                self.request.settimeout(10)
                upstream.settimeout(10)
                ends = [self.request, upstream]
                deadline = time.monotonic() + 60
                while time.monotonic() < deadline:
                    readable, _, _ = select.select(ends, [], [], min(10, max(0, deadline - time.monotonic())))
                    for source in readable:
                        chunk = source.recv(65536)
                        if not chunk:
                            return
                        target = upstream if source is self.request else self.request
                        target.sendall(chunk)
        except (OSError, UnicodeError):
            LOG.warning("Tunnel unavailable")
            try:
                self.request.sendall(b"HTTP/1.1 502 Bad Gateway\r\nConnection: close\r\n\r\n")
            except OSError:
                pass


class LocalProxy(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True
    request_queue_size = 16

    def __init__(self, port, allowed):
        self.allowed = allowed
        self.slots = threading.BoundedSemaphore(16)
        super().__init__(("127.0.0.1", port), TunnelHandler)

    def process_request(self, request, client_address):
        if self.slots.acquire(blocking=False):
            try:
                super().process_request(request, client_address)
            except Exception:
                self.slots.release()
                raise
        else:
            self.shutdown_request(request)

    def process_request_thread(self, request, client_address):
        try:
            super().process_request_thread(request, client_address)
        finally:
            self.slots.release()


if __name__ == "__main__":
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(name)s %(levelname)s %(message)s")
    hosts = allowed_hosts(os.getenv("IPV6_PROXY_ALLOWED_HOSTS", "www.brettspiel-angebote.de,brettspiel-angebote.de"))
    with LocalProxy(8891, hosts) as server:
        LOG.info("Listening on 127.0.0.1:8891; IPv6 only; allowedHosts=%s", sorted(hosts))
        server.serve_forever()
