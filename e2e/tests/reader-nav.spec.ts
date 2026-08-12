import { test, expect, Page } from '@playwright/test';

async function openReaderAt(page: Page, verseId: number) {
  await page.goto(`/read?vid=${verseId}`);
  await expect(page.locator('#reading-area .verse').first()).toBeVisible();
  await expect(page.locator('#current-reference')).not.toHaveText(/^\s*$/);
  // Ensure shortcuts hit the reader, not a focused control.
  await page.locator('#reading-area').click();
}

async function currentRef(page: Page) {
  return (await page.locator('#current-reference').textContent())?.trim() ?? '';
}

async function currentVerseId(page: Page) {
  return page.locator('.verse.current').getAttribute('data-verse-id');
}

async function pageInfoText(page: Page) {
  return (await page.locator('#page-info').textContent())?.trim() ?? '';
}

async function readingAreaScrollTop(page: Page) {
  return page.locator('#reading-area').evaluate((el) => el.scrollTop);
}

/** True when the highlighted verse is among the verses currently on the page. */
async function currentVerseIsOnPage(page: Page) {
  const id = await currentVerseId(page);
  if (!id) return false;
  return (await page.locator(`#reading-area .verse[data-verse-id="${id}"]`).count()) > 0;
}

test.describe('reader page-turn and keyboard navigation', () => {
  test('opens at the requested verse', async ({ page }) => {
    await openReaderAt(page, 1);
    await expect(page.locator('#current-reference')).toHaveText('Genesis 1:1');
    await expect(page.locator('.verse.current')).toHaveAttribute('data-verse-id', '1');
    await expect(page.locator('#chapter-title')).toContainText(/Genesis/i);
  });

  test('j/k move by verse', async ({ page }) => {
    await openReaderAt(page, 1);
    await page.keyboard.press('j');
    await expect(page.locator('#current-reference')).toHaveText('Genesis 1:2');
    await expect(page.locator('.verse.current')).toHaveAttribute('data-verse-id', '2');

    await page.keyboard.press('k');
    await expect(page.locator('#current-reference')).toHaveText('Genesis 1:1');
    await expect(page.locator('.verse.current')).toHaveAttribute('data-verse-id', '1');
  });

  test('arrow keys move by verse', async ({ page }) => {
    await openReaderAt(page, 1);
    await page.keyboard.press('ArrowDown');
    await expect(page.locator('#current-reference')).toHaveText('Genesis 1:2');
    await page.keyboard.press('ArrowUp');
    await expect(page.locator('#current-reference')).toHaveText('Genesis 1:1');
  });

  test('l/h turn pages without scrolling the reading area', async ({ page }) => {
    await openReaderAt(page, 1);
    const beforeInfo = await pageInfoText(page);
    expect(beforeInfo.length).toBeGreaterThan(0);
    const beforeVerse = await currentVerseId(page);

    // nextPage jumps to the first verse of the next page (last-on-page + 1)
    await page.keyboard.press('l');
    await expect.poll(async () => pageInfoText(page)).not.toBe(beforeInfo);
    await expect.poll(async () => currentVerseId(page)).not.toBe(beforeVerse);
    expect(await readingAreaScrollTop(page)).toBe(0);

    // prevPage restores the prior page range; highlight stays on a verse on that page
    // (often the last verse of the restored page), not necessarily the original highlight.
    await page.keyboard.press('h');
    await expect.poll(async () => pageInfoText(page)).toBe(beforeInfo);
    expect(await currentVerseIsOnPage(page)).toBe(true);
    expect(await readingAreaScrollTop(page)).toBe(0);
  });

  test('arrow left/right turn pages', async ({ page }) => {
    await openReaderAt(page, 1);
    const beforeInfo = await pageInfoText(page);

    await page.keyboard.press('ArrowRight');
    await expect.poll(async () => pageInfoText(page)).not.toBe(beforeInfo);
    expect(await readingAreaScrollTop(page)).toBe(0);

    await page.keyboard.press('ArrowLeft');
    await expect.poll(async () => pageInfoText(page)).toBe(beforeInfo);
    expect(await currentVerseIsOnPage(page)).toBe(true);
  });

  test(',/. move by chapter', async ({ page }) => {
    await openReaderAt(page, 1);
    await page.keyboard.press('.');
    await expect(page.locator('#current-reference')).toHaveText('Genesis 2:1');
    await expect(page.locator('#chapter-title')).toContainText(/Genesis\s*2/i);

    await page.keyboard.press(',');
    await expect(page.locator('#current-reference')).toHaveText('Genesis 1:1');
  });

  test('</> move by book', async ({ page }) => {
    await openReaderAt(page, 1);
    // Use the literal key values the app switches on (Shift+. can arrive as '.').
    await page.keyboard.press('>');
    await expect(page.locator('#current-reference')).toHaveText('Exodus 1:1');
    await expect(page.locator('#chapter-title')).toContainText(/Exodus/i);

    await page.keyboard.press('<');
    await expect(page.locator('#current-reference')).toHaveText('Genesis 1:1');
  });

  test('mobile next/prev buttons turn pages', async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 });
    await openReaderAt(page, 1);

    const beforeInfo = await pageInfoText(page);
    const beforeVerse = await currentVerseId(page);

    await expect(page.locator('#mobile-next')).toBeVisible();
    await page.locator('#mobile-next').click();
    await expect.poll(async () => pageInfoText(page)).not.toBe(beforeInfo);
    await expect.poll(async () => currentVerseId(page)).not.toBe(beforeVerse);
    expect(await readingAreaScrollTop(page)).toBe(0);

    await page.locator('#mobile-prev').click();
    await expect.poll(async () => pageInfoText(page)).toBe(beforeInfo);
    expect(await currentVerseIsOnPage(page)).toBe(true);
  });
});
