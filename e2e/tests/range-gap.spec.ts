import { test, expect, APIRequestContext } from '@playwright/test';

async function verseIdFor(request: APIRequestContext, ref: string): Promise<number> {
  const res = await request.get(`/api/reference?ref=${encodeURIComponent(ref)}`);
  expect(res.ok()).toBeTruthy();
  const data = await res.json();
  expect(data.verseId, `expected verseId for ${ref}`).toBeTruthy();
  return data.verseId as number;
}

test.describe('range reader omitted-verse gap', () => {
  test('inserts a column-local gap between non-consecutive spans', async ({ page, request }) => {
    const v10 = await verseIdFor(request, 'Ephesians 6:10');
    const v11 = await verseIdFor(request, 'Ephesians 6:11');
    const v13 = await verseIdFor(request, 'Ephesians 6:13');
    const v18 = await verseIdFor(request, 'Ephesians 6:18');
    expect(v11).toBe(v10 + 1);
    expect(v13).toBe(v11 + 2);

    await page.goto(`/read/range?v=${v10}-${v11},${v13}-${v18}`);
    await expect(page.locator('#reading-area .verse').first()).toBeVisible();

    const gap = page.locator('#reading-area .verse-range-gap');
    await expect(gap).toHaveCount(1);
    await expect(gap).toHaveAttribute('role', 'separator');
    await expect(gap).toHaveAttribute('aria-label', 'Omitted verses');

    const between = await page.locator('#reading-area').evaluate(() => {
      const verses = [...document.querySelectorAll('#reading-area .verse')];
      const eleven = verses.find(v => v.querySelector('.verse-number')?.textContent === '11');
      const thirteen = verses.find(v => v.querySelector('.verse-number')?.textContent === '13');
      const rule = document.querySelector('#reading-area .verse-range-gap');
      if (!eleven || !thirteen || !rule) return false;
      return !!(eleven.compareDocumentPosition(rule) & Node.DOCUMENT_POSITION_FOLLOWING
        && rule.compareDocumentPosition(thirteen) & Node.DOCUMENT_POSITION_FOLLOWING);
    });
    expect(between).toBe(true);
  });

  test('consecutive range has no omitted-verse gap', async ({ page, request }) => {
    const v10 = await verseIdFor(request, 'Ephesians 6:10');
    const v18 = await verseIdFor(request, 'Ephesians 6:18');
    await page.goto(`/read/range?v=${v10}-${v18}`);
    await expect(page.locator('#reading-area .verse').first()).toBeVisible();
    await expect(page.locator('#reading-area .verse-range-gap')).toHaveCount(0);
  });

  test('later page that starts at a skipped span still shows the gap', async ({ page, request }) => {
    const v10 = await verseIdFor(request, 'Ephesians 6:10');
    const v11 = await verseIdFor(request, 'Ephesians 6:11');
    const v13 = await verseIdFor(request, 'Ephesians 6:13');
    const v18 = await verseIdFor(request, 'Ephesians 6:18');

    await page.setViewportSize({ width: 900, height: 520 });
    await page.goto(`/read/range?v=${v10}-${v11},${v13}-${v18}`);
    await expect(page.locator('#reading-area .verse').first()).toBeVisible();

    const verseNum = (n: string) =>
      page.locator('#reading-area .verse').filter({ has: page.locator('.verse-number', { hasText: new RegExp(`^${n}$`) }) });

    for (let i = 0; i < 12; i++) {
      if (await verseNum('13').count() === 0 && await verseNum('11').count() > 0) break;
      await page.locator('#font-increase').click();
    }
    await expect(verseNum('13')).toHaveCount(0);
    await expect(verseNum('11')).toHaveCount(1);

    await page.locator('#reading-area').click();
    await page.keyboard.press('l');
    await expect(verseNum('13')).toBeVisible();

    const gapBefore13 = await page.locator('#reading-area').evaluate(() => {
      const thirteen = [...document.querySelectorAll('#reading-area .verse')]
        .find(v => v.querySelector('.verse-number')?.textContent === '13');
      const rule = document.querySelector('#reading-area .verse-range-gap');
      if (!thirteen || !rule) return false;
      return !!(rule.compareDocumentPosition(thirteen) & Node.DOCUMENT_POSITION_FOLLOWING);
    });
    expect(gapBefore13).toBe(true);
    await expect(page.locator('#reading-area .verse-range-gap')).toHaveCount(1);
  });
});
