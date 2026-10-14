const $ = (id) => document.getElementById(id);
let page = 0;
let activeSearch = null;
let searchSequence = 0;
let crawlStarting = false;
const labels = { IDLE: 'Chưa thu thập', RUNNING: 'Đang xử lý', COMPLETED: 'Hoàn tất', PARTIAL: 'Hoàn tất một phần', FAILED: 'Thu thập thất bại' };

async function request(url, options) {
  const response = await fetch(url, options);
  const data = await response.json();
  if (!response.ok) throw new Error(data.detail || data.message || 'Không thực hiện được yêu cầu.');
  return data;
}

function element(tag, className, text) {
  const node = document.createElement(tag);
  node.className = className;
  node.textContent = text;
  return node;
}

async function search() {
  if (!activeSearch) return;
  const sequence = ++searchSequence;
  $('search-button').disabled = true;
  $('search-message').hidden = false;
  $('search-message').textContent = 'Đang tìm kiếm…';
  $('results').replaceChildren();
  $('result-count').textContent = '';
  $('pagination').hidden = true;
  try {
    const parameters = new URLSearchParams({ ...activeSearch, page, size: 10 });
    const data = await request(`/api/search?${parameters}`);
    if (sequence !== searchSequence) return;
    $('result-count').textContent = `${data.total.toLocaleString('vi-VN')} kết quả`;
    $('search-message').hidden = data.results.length > 0;
    $('search-message').textContent = 'Không tìm thấy kết quả. Thử từ khóa khác hoặc thu thập thêm dữ liệu.';
    for (const item of data.results) {
      const card = element('article', 'result', '');
      const meta = element('div', 'meta', '');
      meta.append(element('span', 'badge', item.type === 'article' ? 'Bài viết' : 'Tài liệu'), element('span', '', item.domain));
      const title = element('h3', '', '');
      const link = element('a', '', item.title);
      const url = new URL(item.url);
      if (['https:', 'http:'].includes(url.protocol) && (url.hostname === 'hust.edu.vn' || url.hostname.endsWith('.hust.edu.vn'))) {
        link.href = url.href;
        link.target = '_blank';
        link.rel = 'noopener noreferrer';
      }
      title.append(link);
      card.append(meta, title, element('p', '', item.snippet), element('p', 'date', `Lập chỉ mục: ${new Date(item.updatedAt).toLocaleString('vi-VN')}`));
      $('results').append(card);
    }
    $('pagination').hidden = data.total <= 10;
    $('previous').disabled = page === 0;
    $('next').disabled = (page + 1) * 10 >= data.total || page >= 100;
    $('page-label').textContent = `Trang ${page + 1} / ${Math.ceil(data.total / 10)}`;
  } catch (error) {
    if (sequence !== searchSequence) return;
    $('search-message').hidden = false;
    $('search-message').textContent = error.message;
  } finally {
    if (sequence === searchSequence) $('search-button').disabled = false;
  }
}

function showCrawl(status) {
  $('crawl-state').textContent = labels[status.state] || status.state;
  $('status-dot').className = `status-dot ${status.state.toLowerCase()}`;
  $('crawl-message').textContent = status.message;
  $('crawl-button').disabled = status.state === 'RUNNING' || crawlStarting;
  $('crawl-button').textContent = status.state === 'RUNNING' ? 'Đang thu thập…' : 'Thu thập ngay';
  for (const key of ['added', 'updated', 'unchanged', 'deleted', 'skipped', 'errors']) $(key).textContent = status[key];
  $('last-run').textContent = status.finishedAt ? `Lượt gần nhất: ${new Date(status.finishedAt).toLocaleString('vi-VN')}` : '';
}

async function refreshStats() {
  try {
    const data = await request('/api/stats');
    $('document-count').textContent = data.documents.toLocaleString('vi-VN');
    $('crawl-settings').textContent = `Tự động rà soát mỗi ${data.crawlIntervalMs / 3600000} giờ. Mỗi lượt lấy tối đa ${data.maxPages} trang và tài liệu.`;
    showCrawl(data.crawl);
  } catch (error) {
    $('crawl-state').textContent = 'Mất kết nối';
    $('crawl-message').textContent = error.message;
    $('crawl-button').disabled = true;
  }
}

$('search-form').addEventListener('submit', (event) => {
  event.preventDefault();
  const q = $('query').value.trim();
  if (!q) return;
  activeSearch = { q, type: $('type').value, domain: $('domain').value.trim() };
  page = 0;
  search();
});
$('previous').addEventListener('click', () => { page--; search(); });
$('next').addEventListener('click', () => { page++; search(); });
$('crawl-button').addEventListener('click', async () => {
  crawlStarting = true;
  $('crawl-button').disabled = true;
  try { showCrawl(await request('/api/crawl', { method: 'POST' })); }
  catch (error) { $('crawl-message').textContent = error.message; }
  finally { crawlStarting = false; await refreshStats(); }
});
refreshStats();
setInterval(refreshStats, 3000);
