import { test, expect, Page } from '@playwright/test';

const JOHN_316 = {
  id: '11111111-1111-1111-1111-111111111111',
  nextReviewAt: null,
  fromVerseRef: 'John 3:16',
  toVerseRef: 'John 3:16',
  masteryLevel: 0,
  verses: [{
    id: 26137,
    verseNum: 16,
    reference: 'John 3:16',
    text: 'For God so loved the world, that he gave his only begotten Son.',
  }],
};

const JOHN_316_17 = {
  ...JOHN_316,
  id: '33333333-3333-3333-3333-333333333333',
  toVerseRef: 'John 3:17',
  verses: [
    JOHN_316.verses[0],
    {
      id: 26138,
      verseNum: 17,
      reference: 'John 3:17',
      text: 'For God sent not his Son into the world to condemn the world.',
    },
  ],
};

async function beginTraining(page: Page, entry: object) {
  await page.addInitScript(e => {
    sessionStorage.setItem('kjv_training_session', JSON.stringify({ entries: [e], index: 0 }));
  }, entry);
  await page.goto('/train');
  await page.getByRole('button', { name: 'Begin training' }).click();
}

test.describe('training reference recall', () => {
  test('asks for the reference after begin and focuses the input', async ({ page }) => {
    await beginTraining(page, JOHN_316);

    await expect(page.locator('#train-ref-input')).toBeVisible();
    await expect(page.locator('#train-ref-input')).toBeFocused();
    await expect(page.locator('#train-ref')).toBeHidden();
    await expect(page).toHaveTitle(/Memory Training/);
    await expect(page).not.toHaveTitle(/John 3:16/);
  });

  test('does not skip an empty reference', async ({ page }) => {
    await beginTraining(page, JOHN_316);

    await page.getByRole('button', { name: 'Check' }).click();

    await expect(page.locator('#train-ref-feedback')).toBeVisible();
    await expect(page.locator('#train-ref-feedback')).toContainText(/book, chapter, and verse/i);
    await expect(page.locator('#train-ratings')).toBeHidden();
    await expect(page.locator('#train-ref-input')).toBeFocused();
  });

  test('tells the user when the reference is wrong', async ({ page }) => {
    await beginTraining(page, JOHN_316);

    await page.locator('#train-ref-input').fill('Psalm 23:1');
    await page.getByRole('button', { name: 'Check' }).click();

    await expect(page.locator('#train-ref-feedback')).toBeVisible();
    await expect(page.locator('#train-ref-feedback')).toContainText('John 3:16');
    await expect(page.locator('#train-ref-input')).toHaveClass(/ref-wrong/);
    await expect(page.locator('#train-ratings')).toBeVisible();
  });

  test('accepts spacing and punctuation nits', async ({ page }) => {
    await beginTraining(page, JOHN_316);

    await page.locator('#train-ref-input').fill('  john  3 : 16. ');
    await page.getByRole('button', { name: 'Check' }).click();

    await expect(page.locator('#train-ref-input')).toHaveClass(/ref-correct/);
    await expect(page.locator('#train-ref-feedback')).toBeHidden();
    await expect(page.locator('#train-ratings')).toBeVisible();
  });

  test('accepts a same-chapter range', async ({ page }) => {
    await beginTraining(page, JOHN_316_17);

    await page.locator('#train-ref-input').fill('John 3:16-17');
    await page.getByRole('button', { name: 'Check' }).click();

    await expect(page.locator('#train-ref-input')).toHaveClass(/ref-correct/);
    await expect(page.locator('#train-ratings')).toBeVisible();
  });

  test('accepts a common book abbreviation', async ({ page }) => {
    await beginTraining(page, JOHN_316);

    await page.locator('#train-ref-input').fill('Jn 3:16');
    await page.getByRole('button', { name: 'Check' }).click();

    await expect(page.locator('#train-ref-input')).toHaveClass(/ref-correct/);
    await expect(page.locator('#train-ratings')).toBeVisible();
  });
});
