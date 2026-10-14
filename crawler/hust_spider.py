"""Bounded HUST crawl. Each run uses a fresh Scrapy queue so pages are revisited."""
import json
import sys
from pathlib import Path
from urllib.parse import parse_qsl, urlencode, urlsplit, urlunsplit

import scrapy
from scrapy import signals
from scrapy.crawler import CrawlerProcess
from scrapy.exceptions import CloseSpider, IgnoreRequest
from scrapy.linkextractors import LinkExtractor


def canonical_url(url):
    """Single URL policy used for seeds, discovered links and redirects."""
    try:
        parsed = urlsplit(url)
        host = (parsed.hostname or "").lower()
        if (parsed.scheme not in {"http", "https"}
                or not (host == "hust.edu.vn" or host.endswith(".hust.edu.vn"))
                or parsed.username or parsed.password
                or parsed.port not in {None, 80, 443}):
            return None
        query = [(key, value) for key, value in parse_qsl(parsed.query, keep_blank_values=True)
                 if not key.lower().startswith("utm_") and key.lower() not in {"fbclid", "gclid"}]
        # Preserve meaningful query parameters, remove fragment and default port.
        netloc = host
        if parsed.port and not ((parsed.scheme == "http" and parsed.port == 80)
                                or (parsed.scheme == "https" and parsed.port == 443)):
            return None
        return urlunsplit((parsed.scheme, netloc, parsed.path or "/", urlencode(sorted(query)), ""))
    except ValueError:
        return None


class ScopeMiddleware:
    def process_request(self, request):
        if canonical_url(request.url) is None:
            raise IgnoreRequest("URL outside HUST scope")


class HustSpider(scrapy.Spider):
    name = "hust"
    allowed_domains = ["hust.edu.vn"]
    handle_httpstatus_list = [404, 410]

    def __init__(self, config, **kwargs):
        super().__init__(**kwargs)
        self.config = config
        self.output = Path(config["output_dir"])
        self.output.mkdir(parents=True, exist_ok=True)
        self.manifest = (self.output / "manifest.jsonl").open("w", encoding="utf-8")
        self.saved = 0
        self.errors = 0
        self.skipped = 0
        self.links = LinkExtractor(allow_domains=self.allowed_domains, deny_extensions=[
            "jpg", "jpeg", "png", "gif", "svg", "webp", "ico", "css", "js", "mp4", "mp3",
            "zip", "rar", "7z", "exe", "woff", "woff2", "ttf"])

    @classmethod
    def from_crawler(cls, crawler, *args, **kwargs):
        spider = super().from_crawler(crawler, *args, **kwargs)
        crawler.signals.connect(spider.spider_error, signal=signals.spider_error)
        return spider

    async def start(self):
        seen = set()
        # Seed/listing pages are always visited; use half the budget for the oldest known pages.
        for url in self.config["seed_urls"] + self.config.get("revisit_urls", []):
            canonical = canonical_url(url)
            if canonical is None:
                raise ValueError(f"Invalid HUST seed URL: {url}")
            if canonical not in seen:
                seen.add(canonical)
                yield scrapy.Request(canonical, callback=self.parse, errback=self.failed, priority=100)

    def parse(self, response):
        url = canonical_url(response.url)
        if url is None:
            self.skipped += 1
            return
        if self.saved >= self.config["max_pages"]:
            return
        if response.status in {404, 410}:
            self.write_manifest({"url": url, "status": response.status, "aliases": self.aliases(response, url)})
            return
        content_type = response.headers.get("Content-Type", b"").decode("latin-1")
        is_html = "html" in content_type.lower() or isinstance(response, scrapy.http.HtmlResponse)
        if not is_html and not (content_type.startswith("text/plain") or any(
                marker in content_type.lower() for marker in ("pdf", "msword", "officedocument", "powerpoint", "octet-stream"))):
            self.skipped += 1
            return
        filename = f"{self.saved:06d}.body"
        (self.output / filename).write_bytes(response.body)
        self.write_manifest({"url": url, "status": response.status, "content_type": content_type,
                             "file": filename, "aliases": self.aliases(response, url)})
        if is_html and response.meta.get("depth", 0) < self.config["max_depth"]:
            for link in self.links.extract_links(response):
                target = canonical_url(link.url)
                if target:
                    path = urlsplit(target).path.lower()
                    if path.endswith((".pdf", ".doc", ".docx", ".ppt", ".pptx")):
                        priority = 20
                    elif "/news/" in path and path.endswith(".html"):
                        priority = 10
                    else:
                        priority = 5 if path.endswith(".html") else 0
                    yield scrapy.Request(target, callback=self.parse, errback=self.failed, priority=priority)

    @staticmethod
    def aliases(response, final_url):
        return [url for raw in response.meta.get("redirect_urls", [])
                if (url := canonical_url(raw)) and url != final_url]

    def write_manifest(self, item):
        self.manifest.write(json.dumps(item, ensure_ascii=False) + "\n")
        self.manifest.flush()
        self.saved += 1
        if self.saved >= self.config["max_pages"]:
            raise CloseSpider("page_limit")

    def failed(self, failure):
        # A failed request must never remove an existing indexed document.
        self.errors += 1
        self.logger.warning("Request failed: %s", failure.request.url)

    def spider_error(self, failure, response, spider):
        self.errors += 1
        self.logger.error("Spider error: %s", failure)

    def closed(self, reason):
        self.manifest.close()
        stats = self.crawler.stats.get_stats()
        robots_errors = sum(value for key, value in stats.items() if key.startswith("robotstxt/exception_count/"))
        (self.output / "summary.json").write_text(json.dumps({
            "saved": self.saved, "errors": self.errors + robots_errors, "skipped": self.skipped,
            "reason": reason,
        }), encoding="utf-8")


def run(config):
    # Validate every configured seed before making any network request.
    for url in config["seed_urls"] + config.get("revisit_urls", []):
        if canonical_url(url) is None:
            raise ValueError(f"Invalid HUST seed URL: {url}")
    process = CrawlerProcess(settings={
        "USER_AGENT": "HustSearchCourseBot/1.0 (educational information-retrieval project)",
        "ROBOTSTXT_OBEY": True,
        "CONCURRENT_REQUESTS": 1,
        "CONCURRENT_REQUESTS_PER_DOMAIN": 1,
        "DOWNLOAD_DELAY": config["delay"],
        "DOWNLOAD_TIMEOUT": 20,
        "DOWNLOAD_MAXSIZE": config["max_file_bytes"],
        "DOWNLOAD_WARNSIZE": config["max_file_bytes"],
        "DEPTH_LIMIT": config["max_depth"],
        "DEPTH_PRIORITY": 1,
        # Enforce content priority across hosts; the domain-aware queue can favor
        # a fast subdomain's navigation pages over news waiting on the main site.
        "SCHEDULER_PRIORITY_QUEUE": "scrapy.pqueues.ScrapyPriorityQueue",
        "RETRY_TIMES": 1,
        "COOKIES_ENABLED": False,
        "REDIRECT_MAX_TIMES": 5,
        "LOG_LEVEL": "INFO",
        "TELNETCONSOLE_ENABLED": False,
        "DOWNLOADER_MIDDLEWARES": {ScopeMiddleware: 40},
    })
    crawler = process.create_crawler(HustSpider)
    process.crawl(crawler, config=config)
    process.start()
    if not (Path(config["output_dir"]) / "summary.json").exists():
        raise RuntimeError("Scrapy did not finish successfully")


if __name__ == "__main__":
    run(json.loads(Path(sys.argv[1]).read_text(encoding="utf-8")))
