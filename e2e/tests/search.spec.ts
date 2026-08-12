import { test, expect, Page } from '@playwright/test';

async function openReaderAt(page: Page, verseId: number) {
  await page.goto(`/read?vid=${verseId}`);
  await expect(page.locator('#reading-area .verse').first()).toBeVisible();
  await expect(page.locator('#current-reference')).toHaveText(/./);
  await page.locator('#reading-area').click();
}

async function submitSearch(page: Page, query: string) {
  await page.locator('#search-input').fill(query);
  await page.locator('#search-input').press('Enter');
}

test.describe('search and jump-to-reference', () => {
  test('/ focuses the search input', async ({ page }) => {
    await openReaderAt(page, 1);
    await page.keyboard.press('/');
    await expect(page.locator('#search-input')).toBeFocused();
  });

  test('jump to an absolute reference via search', async ({ page }) => {
    await openReaderAt(page, 1);
    await page.keyboard.press('/');
    await submitSearch(page, 'John 3:16');

    await expect(page.locator('#current-reference')).toHaveText('John 3:16');
    await expect(page.locator('.verse.current')).toBeVisible();
    await expect(page.locator('#chapter-title')).toContainText(/John/i);
    // Direct reference jump closes the overlay and clears the input.
    await expect(page.locator('#search-overlay')).toBeHidden();
    await expect(page.locator('#search-input')).toHaveValue('');
  });

  test('jump accepts a short book alias', async ({ page }) => {
    await openReaderAt(page, 1);
    await page.keyboard.press('/');
    await submitSearch(page, 'jn 3:16');
    await expect(page.locator('#current-reference')).toHaveText('John 3:16');
  });

  test('text search opens results overlay', async ({ page }) => {
    await openReaderAt(page, 1);
    await page.keyboard.press('/');
    await submitSearch(page, 'faith');

    await expect(page.locator('#search-overlay')).toBeVisible();
    await expect(page.locator('#search-results-title')).toBeVisible();
    await expect(page.locator('#search-results-list .search-result-item').first()).toBeVisible();
    await expect(
      page.locator('#search-results-list .search-result-ref').first()
    ).toHaveText(/\S+:\d+/);
  });

  test('clicking a search result jumps to that verse', async ({ page }) => {
    await openReaderAt(page, 1);
    await page.keyboard.press('/');
    await submitSearch(page, 'faith');

    const first = page.locator('#search-results-list .search-result-item').first();
    await expect(first).toBeVisible();
    const ref = ((await first.locator('.search-result-ref').textContent()) || '').trim();
    expect(ref.length).toBeGreaterThan(0);

    await first.click();
    await expect(page.locator('#search-overlay')).toBeHidden();
    await expect(page.locator('#current-reference')).toHaveText(ref);
    await expect(page.locator('.verse.current')).toBeVisible();
  });

  test('Escape closes search results', async ({ page }) => {
    await openReaderAt(page, 1);
    await page.keyboard.press('/');
    await submitSearch(page, 'faith');
    await expect(page.locator('#search-overlay')).toBeVisible();

    await page.keyboard.press('Escape');
    await expect(page.locator('#search-overlay')).toBeHidden();
  });

  test('close button dismisses search results', async ({ page }) => {
    await openReaderAt(page, 1);
    await page.keyboard.press('/');
    await submitSearch(page, 'begotten');
    await expect(page.locator('#search-overlay')).toBeVisible();

    await page.locator('#search-close').click();
    await expect(page.locator('#search-overlay')).toBeHidden();
  });

  test('invalid reference falls through to text search', async ({ page }) => {
    await openReaderAt(page, 1);
    await page.keyboard.press('/');
    // Not a parseable reference; should open the results UI (even if empty).
    await submitSearch(page, 'xyzzy-not-a-verse-qqq');
    await expect(page.locator('#search-overlay')).toBeVisible();
  });
});
