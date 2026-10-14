import asyncio
import json
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
import scrapy
from scrapy.exceptions import CloseSpider, IgnoreRequest
from scrapy.http import HtmlResponse, Response
from hust_spider import HustSpider, ScopeMiddleware, canonical_url

class SpiderTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.config = {"output_dir": self.temp.name, "seed_urls": ["https://hust.edu.vn/"],
                       "revisit_urls": [], "max_pages": 10, "max_depth": 2}
        self.spider = HustSpider(self.config)
        self.spider.crawler = SimpleNamespace(stats=SimpleNamespace(get_stats=lambda: {}))
    def tearDown(self):
        self.spider.closed("test")
        self.temp.cleanup()
    def test_scope_and_normalization(self):
        self.assertEqual("https://hust.edu.vn/news?a=1&b=2", canonical_url(
            "https://HUST.edu.vn/news?utm_source=test&b=2&a=1#title"))
        self.assertEqual("https://sis.hust.edu.vn/", canonical_url("https://sis.hust.edu.vn"))
        for url in ["https://hust.edu.vn.evil.test/", "https://evilhust.edu.vn/", "file:///etc/passwd",
                    "https://hust.edu.vn:8080/", "https://user@hust.edu.vn/", "https://hust.edu.vn:bad/"]:
            self.assertIsNone(canonical_url(url))
        with self.assertRaises(IgnoreRequest):
            ScopeMiddleware().process_request(scrapy.Request("https://example.com/"))
    def test_discovers_subdomains_and_documents_and_excludes_external_and_assets(self):
        response = HtmlResponse("https://hust.edu.vn/", body=b"""<html><body><article>
          <a href='https://sis.hust.edu.vn/news'>subdomain</a><a href='/guide.pdf'>PDF</a>
          <a href='/guide.docx'>Word</a><a href='https://example.com/'>external</a>
          <a href='/image.png'>image</a></article></body></html>""", encoding="utf-8",
            headers={"Content-Type": "text/html"}, request=scrapy.Request("https://hust.edu.vn/"))
        requests = list(self.spider.parse(response))
        self.assertEqual({r.url for r in requests}, {"https://sis.hust.edu.vn/news", "https://hust.edu.vn/guide.pdf", "https://hust.edu.vn/guide.docx"})
        record = json.loads(Path(self.temp.name, "manifest.jsonl").read_text())
        self.assertEqual(response.body, Path(self.temp.name, record["file"]).read_bytes())
    def test_deletion_is_only_exported_for_404_or_410(self):
        self.assertEqual([], list(self.spider.parse(Response("https://hust.edu.vn/gone", status=410,
            request=scrapy.Request("https://hust.edu.vn/gone")))))
        record = json.loads(Path(self.temp.name, "manifest.jsonl").read_text())
        self.assertEqual(410, record["status"])
        self.assertNotIn("file", record)
    def test_seeds_are_deduplicated(self):
        self.config["revisit_urls"] = ["https://hust.edu.vn/#header", "https://sis.hust.edu.vn/"]
        async def collect():
            return [request async for request in self.spider.start()]
        self.assertEqual(2, len(asyncio.run(collect())))
    def test_depth_and_page_limits(self):
        self.config["max_pages"] = 1
        request = scrapy.Request("https://hust.edu.vn/", meta={"depth": 2})
        response = HtmlResponse(request.url, body=b"<a href='/a'>a</a>", encoding="utf-8",
            headers={"Content-Type": "text/html"}, request=request)
        with self.assertRaises(CloseSpider):
            list(self.spider.parse(response))
        self.assertEqual([], list(self.spider.parse(response)))
        self.assertEqual(1, self.spider.saved)

if __name__ == "__main__":
    unittest.main()
