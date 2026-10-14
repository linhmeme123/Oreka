# Ghi chú kiến thức và thiết kế

## 1. Apache Lucene trong dự án

Lucene là thư viện Java cho tìm kiếm toàn văn. Trong dự án, nó chạy ngay trong Spring Boot, không cần cài một máy chủ Lucene riêng.

**Chỉ mục đảo (inverted index)** ánh xạ mỗi term đến các tài liệu chứa term đó, kèm tần suất và vị trí. Ví dụ `hoc_bong` có thể trỏ tới các URL bài thông báo học bổng. Khi tìm, Lucene tra các term và kết hợp danh sách tài liệu thay vì đọc lại toàn bộ HTML/PDF.

Các đối tượng đang dùng:

- `FSDirectory`: lưu index tại `data/index`.
- `IndexWriter`: thêm/cập nhật/xóa tài liệu; `updateDocument(new Term("url", url), document)` thay bản cũ theo URL.
- `SearcherManager`: quản lý các snapshot tìm kiếm để có thể tìm trong lúc một lượt nhập cập nhật chỉ mục.
- `IndexSearcher`: thực hiện truy vấn và lấy tài liệu theo độ liên quan.
- `Analyzer`: tạo token để lập chỉ mục và truy vấn. Bản này dùng cùng chuẩn hóa/từ điển; đồ thị từ ghép chỉ được flatten khi lập chỉ mục, truy vấn giữ đồ thị để chọn đúng các đường đi.
- `QueryBuilder`: biến từ khóa thành truy vấn term/từ ghép, tránh diễn giải ký tự người dùng như cú pháp truy vấn nâng cao.

Các trường:

| Trường | Cách lưu và dùng |
| --- | --- |
| `url` | `StringField`, khóa chính và liên kết đến bản gốc |
| `title`, `content` | `TextField`, được analyzer xử lý, đồng thời lưu nguyên văn để hiển thị |
| `domain`, `type` | `StringField`, dùng lọc chính xác |
| `hash` | SHA-256 của tiêu đề, loại nội dung và văn bản, kiểm tra thay đổi |
| `contentType`, `updatedAt` | Metadata được lưu để hiển thị/giải thích |
| `checkedAt` | Numeric DocValues, chọn các URL lâu chưa kiểm tra |

**Xếp hạng:** dự án dùng BM25 mặc định của Lucene. Ý tưởng là từ xuất hiện nhiều lần trong một tài liệu giúp tăng điểm nhưng mức tăng giảm dần; từ hiếm trong cả kho có sức phân biệt lớn hơn; độ dài tài liệu cũng được xét. Truy vấn ở tiêu đề được nhân hệ số 3. Các từ/cụm trong một trường được kết hợp bằng AND; kết quả có thể khớp tiêu đề hoặc nội dung. Bộ lọc miền/loại không làm tăng điểm.

Điểm số dùng so sánh kết quả của cùng một truy vấn, không phải xác suất chính xác. Đoạn trích là cửa sổ văn bản gần từ đầu tiên khớp, chưa có highlighter chuyên dụng.

## 2. Apache Tika

Tika cung cấp một giao diện bóc tách văn bản/metadata từ nhiều loại file. Dự án sử dụng `AutoDetectParser`, `Metadata`, `ParseContext` và `BodyContentHandler`.

Với HTML, Jsoup được dùng để có thể bỏ các vùng điều hướng và ưu tiên vùng nội dung. Với PDF, Word và PowerPoint, Tika chọn parser phù hợp. Tài liệu được chuyển về văn bản, chuẩn hóa khoảng trắng, tính hash rồi đưa vào Lucene.

Một file PDF có chữ thật có thể được bóc tách trực tiếp. PDF chỉ chứa ảnh cần OCR; bản demo chủ động tắt OCR và không yêu cầu Tesseract. Dữ liệu rỗng/loại file không hỗ trợ được bỏ qua; lỗi parser được báo qua trạng thái lượt thu thập.

## 3. Tách từ và chuẩn hóa tiếng Việt

Khoảng trắng trong tiếng Việt thường phân cách âm tiết, chưa chắc phân cách từ: `sinh viên`, `học bổng`, `đại học` là các ví dụ từ nhiều âm tiết. Chỉ dùng bộ tách từ theo khoảng trắng khó thể hiện đầy đủ các từ này.

Bản đơn giản dùng chuỗi xử lý:

```text
Chuẩn hóa đ/Đ và dấu Unicode tách rời
  → StandardTokenizer
  → LowerCaseFilter
  → ASCIIFoldingFilter
  → SynonymGraphFilter với từ điển ghép từ
  → FlattenGraphFilter khi lập chỉ mục
```

Từ điển ở `src/main/resources/vietnamese-words.txt`. Ví dụ:

```text
Đại học Bách khoa Hà Nội
→ dai_hoc, dai, hoc, bach_khoa, bach, khoa, ha_noi, ha, noi
```

Giữ các token âm tiết giúp tìm `khoa` vẫn có kết quả, trong khi token ghép giúp thể hiện `bách khoa`. `đào tạo` và `dao tao` được chuẩn hóa về cùng dạng. Đây là phương pháp từ điển minh họa: từ chưa có trong từ điển vẫn được tìm bằng âm tiết, nhưng chưa phân đoạn được đầy đủ từ tiếng Việt.

Hướng mở rộng là VnCoreNLP/RDRSegmenter hoặc thư viện tách từ tiếng Việt khác. Khi thay analyzer cần dùng nhất quán cho nội dung lẫn truy vấn và tạo lại chỉ mục.

## 4. Thu thập mới và cập nhật định kỳ

1. Bắt đầu từ các nguồn cấu hình và một nhóm URL trong chỉ mục lâu chưa kiểm tra.
2. Scrapy lấy trang trong miền `hust.edu.vn` hoặc miền kết thúc bằng `.hust.edu.vn`; kiểm tra cả các yêu cầu chuyển hướng.
3. Theo liên kết, loại bỏ tài nguyên ảnh/script/font, giới hạn độ sâu và kích thước tải xuống. Ưu tiên liên kết tài liệu và bài `.html`.
4. Xuất manifest JSONL và file nội dung trong thư mục tạm riêng từng lượt.
5. Java bóc tách văn bản, tính hash, so sánh bản cũ bằng URL.
6. URL mới → thêm; hash khác → thay thế; hash giống → chỉ cập nhật thời gian kiểm tra; HTTP 404/410 → xóa.
7. Commit và làm mới searcher; cập nhật bộ đếm, xóa file tạm.

Mỗi lần tạo Scrapy queue mới, vì dùng queue cũ với bộ lọc URL đã từng thấy sẽ ngăn quét lại trang để nhận biết thay đổi. `checkedAt` được lưu bền vững bằng DocValues, giúp luân phiên rà soát các tài liệu cũ qua các lượt chạy.

Tính nhất quán nằm ở từng tài liệu: Lucene thay thế theo URL và commit sau mỗi thao tác. Lượt thu thập có thể hoàn thành một phần nếu vài tài liệu lỗi, nhưng một URL không có hai bản đang hoạt động. Một executor điều phối các lượt chạy và từ chối chạy chồng; `SearcherManager` cho phép tìm kiếm trên snapshot hợp lệ trong khi nhập.

## Tài liệu tham khảo

- [Apache Lucene — tài liệu chính thức](https://lucene.apache.org/core/): chỉ mục, analyzer, truy vấn và xếp hạng.
- [Apache Tika — tài liệu chính thức](https://tika.apache.org/): bóc tách định dạng và metadata.
- [Scrapy — cấu hình](https://docs.scrapy.org/en/latest/topics/settings.html): robots.txt, giới hạn và downloader.
- [Scrapy — queue và việc tiếp tục lượt thu thập](https://docs.scrapy.org/en/latest/topics/jobs.html).
- [VnCoreNLP — nhóm tác giả](https://github.com/vncorenlp/VnCoreNLP): hướng mở rộng cho tách từ tiếng Việt theo mô hình.
