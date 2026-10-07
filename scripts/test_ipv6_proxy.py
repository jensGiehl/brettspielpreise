import importlib.util
import pathlib
import socket
import threading
import unittest
from unittest.mock import MagicMock, patch


spec = importlib.util.spec_from_file_location("ipv6_proxy", pathlib.Path(__file__).with_name("ipv6_proxy.py"))
proxy = importlib.util.module_from_spec(spec)
spec.loader.exec_module(proxy)


class ProxyTests(unittest.TestCase):
    def test_allowlist_rejects_wildcards_and_literals(self):
        self.assertEqual(proxy.allowed_hosts("www.brettspiel-angebote.de, CDN.example.org"),
                         {"www.brettspiel-angebote.de", "cdn.example.org"})
        for value in ("*", "127.0.0.1", "example.org:443", "", "example.org/path"):
            with self.assertRaises(ValueError):
                proxy.allowed_hosts(value)

    def test_upstream_uses_only_ipv6_and_reports_source(self):
        upstream = MagicMock()
        upstream.getsockname.return_value = ("2001:4860::999", 40000, 0, 0)
        address = ("2001:4860:4860::8888", 443, 0, 0)
        with patch.object(proxy.socket, "getaddrinfo", return_value=[(socket.AF_INET6, socket.SOCK_STREAM, 6, "", address)]) as dns:
            with patch.object(proxy.socket, "socket", return_value=upstream) as factory:
                self.assertIs(proxy.connect_ipv6("example.org", 443), upstream)
                dns.assert_called_once_with("example.org", 443, socket.AF_INET6, socket.SOCK_STREAM)
                factory.assert_called_once_with(socket.AF_INET6, socket.SOCK_STREAM, 6)
                upstream.connect.assert_called_once_with(address)

    def test_private_ipv6_is_never_connected(self):
        for address in ("::1", "fd00::123", "fe80::123"):
            with patch.object(proxy.socket, "getaddrinfo", return_value=[(socket.AF_INET6, socket.SOCK_STREAM, 6, "", (address, 443, 0, 0))]):
                with patch.object(proxy.socket, "socket") as factory:
                    with self.assertRaises(OSError):
                        proxy.connect_ipv6("example.org", 443)
                    factory.assert_not_called()

    def test_local_proxy_rejects_unapproved_targets_and_non_https_ports(self):
        with proxy.LocalProxy(0, {"www.brettspiel-angebote.de"}) as server:
            thread = threading.Thread(target=server.serve_forever, daemon=True)
            thread.start()
            try:
                for request in (b"CONNECT other.example:443 HTTP/1.1\r\n\r\n", b"CONNECT www.brettspiel-angebote.de:80 HTTP/1.1\r\n\r\n"):
                    with socket.create_connection(server.server_address) as client:
                        client.sendall(request)
                        self.assertIn(b"403 Forbidden", client.recv(1024))
            finally:
                server.shutdown()
                thread.join(2)


if __name__ == "__main__":
    unittest.main()
