# HUST Search — Đồ án Tìm kiếm thông tin

Bản đầu đơn giản để thu thập bài viết/tài liệu từ `hust.edu.vn` và các miền con, xử lý văn bản tiếng Việt và tìm kiếm bằng Apache Lucene.

## Chức năng

- Scrapy duyệt liên kết trong miền HUST, gồm PDF, DOC/DOCX, PPT/PPTX và TXT. Có thể thêm các miền con vào danh sách URL nguồn.
- Quét thủ công trên giao diện hoặc tự động mỗi 6 giờ khi ứng dụng đang chạy.
- Phát hiện bài mới; dùng SHA-256 của tiêu đề, loại nội dung và văn bản để nhận biết thay đổi.
- Cập nhật tài liệu theo URL, không tạo bản sao cho cùng URL. Nội dung không đổi chỉ cập nhật thời điểm kiểm tra.
- Ưu tiên rà soát những URL đã lâu chưa kiểm tra, đồng thời khám phá các liên kết mới từ trang nguồn.
- Xóa khỏi chỉ mục khi trang trả về HTTP 404/410; lỗi mạng và HTTP 403/500 không xóa dữ liệu cũ. URL chuyển hướng được thay bằng URL đích sau khi xử lý thành công.
- Jsoup lấy văn bản HTML và bỏ các khối điều hướng phổ biến. Apache Tika bóc tách văn bản tài liệu.
- Chuẩn hóa chữ thường, dấu tiếng Việt, `đ → d`, dấu Unicode tách rời; ghép từ bằng từ điển nhỏ và giữ âm tiết gốc.
- Tìm kiếm có dấu/không dấu, ưu tiên tiêu đề, lọc loại nội dung/miền, phân trang và hiển thị đoạn trích.
- Chỉ mục lưu trên đĩa, giữ lại sau khi khởi động lại. Không cần database hay tài khoản dịch vụ.

## Chạy dự án

Yêu cầu **Java 21 trở lên**, **Python 3.10 trở lên** và Maven (hoặc Maven Wrapper đi kèm). Lần đầu cần Internet để tải thư viện.

Chạy các lệnh sau **ngay trong thư mục chứa `pom.xml`**:

```bash
python3 -m venv .venv
.venv/bin/python -m pip install -r crawler/requirements.txt
./mvnw spring-boot:run
```

Nếu máy có Maven sẵn, thay lệnh cuối bằng `mvn spring-boot:run`.

Mở **http://localhost:8080**, bấm **Thu thập ngay**, đợi trạng thái hoàn tất rồi thử `bách khoa`, `nghiên cứu`, `tuyển sinh` hoặc các từ không dấu tương ứng. Lần đầu kho dữ liệu rỗng; dự án không dùng dữ liệu giả. Thu thập có thể mất vài phút tùy website và kết nối mạng.

Windows PowerShell:

```powershell
python -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r crawler\requirements.txt
.\mvnw.cmd spring-boot:run '-Dspring-boot.run.arguments=--app.python=.venv/Scripts/python.exe'
```

Đóng gói và chạy JAR, vẫn ở thư mục dự án để tìm được `crawler/` và `.venv/`:

```bash
./mvnw package
java -jar target/tktt-0.0.1-SNAPSHOT.jar
```

## Cấu hình

Sửa `src/main/resources/application.properties`, sau đó khởi động lại:

| Thuộc tính | Mặc định | Ý nghĩa |
| --- | --- | --- |
| `app.seed-urls` | Trang chủ và `/vi/news/` | Các URL nguồn cách nhau bằng dấu phẩy |
| `app.max-pages` | 100 | Số trang/tài liệu tối đa xuất ra mỗi lượt |
| `app.max-depth` | 3 | Số bước duyệt liên kết từ nguồn |
| `app.download-delay-seconds` | 1 | Khoảng nghỉ giữa các yêu cầu |
| `app.max-file-mb` | 10 | Giới hạn kích thước tải xuống |
| `app.crawl-interval-ms` | 21600000 | Chu kỳ tự động, 6 giờ |
| `app.crawl-timeout-seconds` | 1800 | Giới hạn thời gian một lượt |
| `app.index-directory` | `data/index` | Nơi lưu chỉ mục Lucene |
| `app.python` | `.venv/bin/python` | Python đã cài Scrapy |

Ví dụ thêm một miền con:

```properties
app.seed-urls=https://hust.edu.vn/,https://hust.edu.vn/vi/news/,https://soict.hust.edu.vn/
```

`abc.hust.edu.vn` trong đề là ví dụ về miền con: hãy dùng tên miền có tồn tại và có nội dung công khai. Bộ thu thập tự theo liên kết sang các miền con được tìm thấy; một miền không có liên kết dẫn đến cần được thêm vào nguồn.

Muốn thử nhanh với 10 trang:

```bash
./mvnw spring-boot:run -Dspring-boot.run.arguments=--app.max-pages=10
```

Lịch chạy là lịch trong tiến trình: lần tự động đầu tiên sau 6 giờ khởi động; ứng dụng dừng thì lịch cũng dừng. Nút thu thập luôn có thể chạy ngay. Hai lượt thủ công/tự động không được chạy chồng nhau.

## Cấu trúc và luồng xử lý

```text
Giao diện / lịch @Scheduled
  → ApiController / CrawlService
  → tiến trình Python Scrapy (giới hạn miền, robots.txt, liên kết)
  → manifest JSONL + file tải về trong thư mục tạm
  → DocumentExtractor (Jsoup / Apache Tika)
  → LuceneRepository (so sánh hash, thêm/cập nhật/xóa theo URL)
  → VietnameseAnalyzer → chỉ mục trên đĩa

Từ khóa trên giao diện
  → GET /api/search → cùng quy tắc chuẩn hóa tiếng Việt
  → truy vấn Lucene/BM25 → lọc, phân trang → JSON → kết quả
```

| Thành phần | Trách nhiệm |
| --- | --- |
| `crawler/hust_spider.py` | Duyệt web và xuất dữ liệu, không lập chỉ mục |
| `crawl/CrawlService.java` | Điều phối tiến trình, lịch chạy, nhập kết quả và trạng thái |
| `document/DocumentExtractor.java` | Bóc tách văn bản và tính hash |
| `search/VietnameseAnalyzer.java` | Chuẩn hóa và từ điển ghép từ dùng chung khi lập chỉ mục/truy vấn |
| `search/LuceneRepository.java` | Lưu dữ liệu, cập nhật, truy vấn và metadata rà soát |
| `web/ApiController.java` | API cho giao diện |
| `static/` | HTML/CSS/JavaScript thuần, không cần Node.js |

Phần Java giữ lại khung Spring Boot và các thư viện Jsoup, Tika, Lucene ban đầu. Đã bỏ JPA/PostgreSQL, Lombok và các phụ thuộc kiểm thử tương ứng vì bản này không dùng chúng; không có logic thu thập hay tìm kiếm cũ cần thay thế. Không cần `lucene-queryparser`: API nhận từ khóa thông thường và dùng `QueryBuilder` của Lucene để tạo truy vấn.

## API

| Phương thức | Endpoint | Chức năng |
| --- | --- | --- |
| GET | `/api/search?q=hoc%20bong&page=0&size=10` | Tìm kiếm; `page` bắt đầu từ 0 |
| GET | `/api/search?q=HUST&type=document&domain=hust.edu.vn` | Lọc tài liệu và miền chính xác |
| POST | `/api/crawl` | Bắt đầu thu thập; 202 nếu nhận việc, 409 nếu đã chạy |
| GET | `/api/crawl/status` | Trạng thái và số tài liệu mới/cập nhật/không đổi/xóa/lỗi |
| GET | `/api/stats` | Số tài liệu trong chỉ mục và trạng thái thu thập |
| GET | `/api/analyze?text=Đại%20học%20Bách%20khoa` | Quan sát token để giải thích xử lý tiếng Việt |
| GET | `/actuator/health` | Kiểm tra ứng dụng đang hoạt động |

## Kiểm thử

Cài Scrapy trước khi chạy kiểm thử Java: bài kiểm thử điều phối sử dụng Python trong `.venv`.

```bash
./mvnw test
.venv/bin/python -m unittest discover -s crawler -v
```

Kiểm thử bao gồm HTML → trích xuất → Lucene → tìm kiếm, PDF/Word với Tika, có dấu/không dấu và Unicode tách rời, ưu tiên tiêu đề, bộ lọc, phân trang, chống trùng URL, cập nhật/xóa, lưu lại sau khi mở chỉ mục, chọn URL lâu chưa quét, điều phối thủ công/định kỳ, chống chạy chồng, lỗi thiếu Python và kiểm tra tham số API. Kiểm thử Python chạy cả Scrapy engine thật với HTTP giả lập để kiểm tra robots.txt, chuyển hướng, miền con, tài liệu và xuất manifest; không cần Internet.

## Giới hạn của bản đầu

- Đây là crawler HTML tĩnh: chưa thực thi JavaScript, chưa đăng nhập, chưa OCR PDF scan/ảnh.
- Mỗi lượt có giới hạn trang và độ sâu, nên không đảm bảo quét hết HUST hoặc tìm mọi bài mới. Duyệt lại từ trang nguồn có thể gặp lại cùng liên kết; nên tăng giới hạn hoặc bổ sung nguồn khi mở rộng.
- Có thể lưu cả trang danh mục/trang thông tin; chưa có bộ phân loại bài viết chuyên biệt cho từng miền con. Selector nội dung hiện dùng quy tắc chung và có thể cần chỉnh theo website.
- Các nguồn có thể từ chối bot, lỗi DNS, robots.txt hoặc kết nối. Trạng thái `PARTIAL`/`FAILED` phản ánh lỗi; website từ chối không bị vượt qua.
- Ghép từ bằng từ điển nhỏ là giải pháp minh họa, chưa có độ chính xác như VnCoreNLP/RDRSegmenter; không xử lý đầy đủ ngữ cảnh/nhập nhằng. Bỏ dấu giúp truy vấn thuận tiện nhưng gộp cả các từ khác nghĩa mà cùng dạng không dấu.
- Chưa bỏ stopwords để tránh xóa nhầm âm tiết trong từ ghép. Tìm các từ khóa thông thường bằng AND; chưa cung cấp cú pháp truy vấn Lucene nâng cao.
- Chỉ lưu văn bản đã trích và metadata trong Lucene; file tải về được xóa sau lượt nhập. Không lưu lịch sử các phiên bản. Giới hạn văn bản 500.000 ký tự; tài liệu vượt giới hạn Tika có thể bị bỏ qua và tính là lỗi.
- Nhập chỉ mục sau khi Scrapy kết thúc; số liệu từng tài liệu xuất hiện ở giai đoạn nhập. Nếu tiến trình thu thập bị lỗi/timeout, lượt đó không nhập dữ liệu.
- Chỉ chạy một ứng dụng dùng chung một thư mục chỉ mục. Giao diện/API mặc định chỉ nghe trên `127.0.0.1`, dành cho demo cá nhân.
- Sửa từ điển/quy tắc analyzer cần tạo chỉ mục mới rồi thu thập lại; sửa đường dẫn `app.index-directory` để giữ bản cũ nếu cần.

Xem [ghi chú Lucene, Tika và tiếng Việt](docs/THIET_KE.md) để chuẩn bị báo cáo/thuyết trình.
