"""한 SKU/한 월 D0 원본. ZIP 시간까지 고정하여 hash를 재현한다."""
import calendar
import io
import re
import zipfile
from datetime import date, datetime

HEADERS = [None, 'SKU바코드', '고객상품코드', '상품정보', '유통기한', '입고일자', '로트번호',
           '제조일자', '원가', '판매예약채널명', '판매예약채널구분', 'sku_mixed_id', 'SKU명',
           '벤더상품코드', '벤더명', 'workplace_code', 'workplace_name']


def make_fixture(manifest):
    import openpyxl
    d0, d1 = date.fromisoformat(manifest['d0Date']), date.fromisoformat(manifest['d1Date'])
    month_index = d0.year * 12 + d0.month - 1 - 2
    year, zero_month = divmod(month_index, 12)
    month = zero_month + 1
    start = date(year, month, 1)
    total = (d1 - start).days + 1
    barcode = 'SYN-D0-' + manifest['operationId'].replace('-', '')[:16]
    w = openpyxl.Workbook()
    w.properties.created = w.properties.modified = datetime(2000, 1, 1)
    s = w.active
    s.title = '수불부'
    for col, value in enumerate(HEADERS, 1):
        s.cell(1, col, value)
    for col, value in {2: barcode, 6: start.isoformat(), 9: 10, 12: barcode + '-UNIT',
                       13: '입고정정합성상품', 16: 'SYNTHETIC-ONLY', 17: '전용합성창고'}.items():
        s.cell(5, col, value)
    for day in range(1, calendar.monthrange(year, month)[1] + 1):
        col = 18 + (day - 1) * 4
        s.cell(2, col, f'{day:02d} 일')
        for offset, label in enumerate(('입고', '출고', '조정', '재고')):
            s.cell(3, col + offset, label)
        for offset, value in enumerate((0, 1, 0, total + 1 - day)):
            s.cell(5, col + offset, value)
    raw = io.BytesIO()
    w.save(raw)
    output = io.BytesIO()
    with zipfile.ZipFile(raw) as source, zipfile.ZipFile(output, 'w', zipfile.ZIP_DEFLATED) as target:
        for name in sorted(source.namelist()):
            info = zipfile.ZipInfo(name, (2000, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o600 << 16
            content = source.read(name)
            if name == 'docProps/core.xml':
                content = re.sub(rb'(<dcterms:(?:created|modified)[^>]*>)[^<]+',
                                 rb'\g<1>2000-01-01T00:00:00Z', content)
            target.writestr(info, content)
    return f'{year:04d}-{month:02d}.xlsx', barcode, output.getvalue()
