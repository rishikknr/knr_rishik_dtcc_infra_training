"""Exercise the real production Nginx headers on both ingress ports.

Run with a local Docker daemon: python3 frontend/test/proxy_integration.py
Uses an isolated nginx container and a synthetic private upstream; no TRACE data.
"""
import json
from pathlib import Path
import subprocess
import tempfile
import time
import unittest
import urllib.request


class ProxyTrustTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory(prefix="trace-proxy-")
        cls.addClassCleanup(cls.temp.cleanup)
        upstream = Path(cls.temp.name) / "upstream.conf"
        upstream.write_text('''server {
  listen 8081;
  location / {
    default_type application/json;
    return 200 '{"host":"$http_host","proto":"$http_x_forwarded_proto","forwarded_host":"$http_x_forwarded_host","for":"$http_x_forwarded_for","forwarded":"$http_forwarded","port":"$http_x_forwarded_port"}';
  }
}''')
        config = Path(__file__).resolve().parents[1] / "nginx.conf"
        cls.container = subprocess.check_output([
            "docker", "run", "--rm", "-d", "--add-host", "backend:127.0.0.1",
            "-p", "127.0.0.1::80", "-p", "127.0.0.1::8080",
            "-v", f"{config}:/etc/nginx/conf.d/default.conf:ro",
            "-v", f"{upstream}:/etc/nginx/conf.d/upstream.conf:ro", "nginx:alpine",
        ], text=True).strip()
        cls.addClassCleanup(lambda: subprocess.run(
            ["docker", "stop", cls.container], check=True, stdout=subprocess.DEVNULL))
        cls.urls = {}
        for port in (80, 8080):
            address = subprocess.check_output(
                ["docker", "port", cls.container, str(port)], text=True).strip()
            cls.urls[port] = f"http://{address}/api/auth/csrf"
        for attempt in range(50):
            try:
                cls.fetch(80)
                break
            except OSError:
                if attempt == 49:
                    raise
                time.sleep(0.1)

    @classmethod
    def fetch(cls, port, **headers):
        # Fake incoming headers must be replaced by the proxy at the trust boundary.
        request = urllib.request.Request(cls.urls[port], headers={
            "Host": "changing-address.trycloudflare.com",
            "X-Forwarded-Proto": "https",
            "X-Forwarded-Host": "attacker.example",
            "X-Forwarded-For": "203.0.113.99",
            "X-Forwarded-Port": "9999",
            "Forwarded": "for=203.0.113.99;proto=https;host=attacker.example",
            **headers,
        })
        with urllib.request.urlopen(request, timeout=3) as response:
            return json.load(response)

    def test_public_ingress_replaces_forged_forwarding_headers(self):
        body = self.fetch(80)
        self.assertEqual(body["proto"], "http")
        self.assertEqual(body["host"], "changing-address.trycloudflare.com")
        self.assertEqual(body["forwarded_host"], body["host"])
        self.assertNotEqual(body["for"], "203.0.113.99")
        self.assertEqual(body["forwarded"], "")
        self.assertEqual(body["port"], "")

    def test_loopback_tunnel_ingress_preserves_https_and_host(self):
        body = self.fetch(8080)
        self.assertEqual(body["proto"], "https")
        self.assertEqual(body["forwarded_host"], "changing-address.trycloudflare.com")
        self.assertEqual(body["forwarded"], "")

    def test_loopback_ingress_does_not_accept_arbitrary_scheme_lists(self):
        body = self.fetch(8080, **{"X-Forwarded-Proto": "https,http"})
        self.assertEqual(body["proto"], "http")


if __name__ == "__main__":
    unittest.main()
