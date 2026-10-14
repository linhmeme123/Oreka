"""Runs the real Scrapy engine against synthetic HTTP responses, without networking."""
import json
import subprocess
import sys
import tempfile
import textwrap
import unittest
from pathlib import Path


class EngineTests(unittest.TestCase):
    def test_real_engine_scope_robots_documents_redirects_and_shutdown(self):
        with tempfile.TemporaryDirectory() as staging:
            config = {"output_dir": staging, "seed_urls": ["https://hust.edu.vn/"],
                      "revisit_urls": [], "max_pages": 20, "max_depth": 2, "delay": 0,
                      "max_file_bytes": 1024 * 1024}
            script = textwrap.dedent('''
                import json, sys
                from pathlib import Path
                from urllib.parse import urlsplit
                import hust_spider
                from scrapy.http import HtmlResponse, Response
                from scrapy.crawler import CrawlerProcess
                config = json.loads(sys.argv[1])
                class FixtureDownloader:
                    def process_request(self, request):
                        host = urlsplit(request.url).hostname
                        assert host == 'hust.edu.vn' or host.endswith('.hust.edu.vn')
                        with Path(config['output_dir'], 'requests.txt').open('a') as log:
                            log.write(request.url + '\\n')
                        path = urlsplit(request.url).path
                        if path == '/robots.txt':
                            return Response(request.url, body=b'User-agent: *\\nDisallow: /blocked\\n', request=request)
                        if path == '/redirect':
                            return Response(request.url, status=302, headers={'Location': 'https://example.com/outside'}, request=request)
                        if path == '/alias':
                            return Response(request.url, status=301, headers={'Location': '/new.html'}, request=request)
                        if path == '/gone':
                            return Response(request.url, status=410, request=request)
                        if path == '/doc.pdf':
                            return Response(request.url, body=b'%PDF-1.4 fixture', headers={'Content-Type': 'application/pdf'}, request=request)
                        body = b"<html><article><h1>HUST news</h1></article></html>"
                        if path == '/':
                            body = b"<a href='/blocked'>blocked</a><a href='/redirect'>external redirect</a><a href='/alias'>alias</a><a href='/gone'>gone</a><a href='/doc.pdf'>PDF</a><a href='https://sis.hust.edu.vn/article.html'>article</a>"
                        return HtmlResponse(request.url, body=body, encoding='utf-8', headers={'Content-Type': 'text/html'}, request=request)
                class FixtureProcess(CrawlerProcess):
                    def __init__(self, settings):
                        settings['DOWNLOADER_MIDDLEWARES'][FixtureDownloader] = 900
                        super().__init__(settings=settings)
                hust_spider.CrawlerProcess = FixtureProcess
                hust_spider.run(config)
            ''')
            result = subprocess.run([sys.executable, "-c", script, json.dumps(config)],
                                    cwd=Path(__file__).parent, capture_output=True, text=True, timeout=30)
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertNotIn("Error caught on signal handler", result.stderr)
            records = [json.loads(line) for line in Path(staging, "manifest.jsonl").read_text().splitlines()]
            self.assertEqual(5, len(records))
            self.assertIn("https://sis.hust.edu.vn/article.html", [record["url"] for record in records])
            self.assertTrue(any(record.get("content_type") == "application/pdf" for record in records))
            self.assertTrue(any(record["status"] == 410 for record in records))
            redirected = next(record for record in records if record["url"].endswith("/new.html"))
            self.assertEqual(["https://hust.edu.vn/alias"], redirected["aliases"])
            requests = Path(staging, "requests.txt").read_text()
            self.assertNotIn("/blocked", requests)
            self.assertNotIn("example.com", requests)
            summary = json.loads(Path(staging, "summary.json").read_text())
            self.assertGreaterEqual(summary["errors"], 1)
            # A tight budget must favor a document over subdomain navigation links.
            limited = Path(staging, "limited")
            limited.mkdir()
            config.update(output_dir=str(limited), max_pages=2)
            result = subprocess.run([sys.executable, "-c", script, json.dumps(config)],
                                    cwd=Path(__file__).parent, capture_output=True, text=True, timeout=30)
            self.assertEqual(0, result.returncode, result.stderr)
            records = [json.loads(line) for line in (limited / "manifest.jsonl").read_text().splitlines()]
            self.assertEqual(2, len(records))
            self.assertEqual("application/pdf", records[1]["content_type"])
            self.assertEqual("page_limit", json.loads((limited / "summary.json").read_text())["reason"])


if __name__ == "__main__":
    unittest.main()
