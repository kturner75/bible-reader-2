import { test, expect } from '@playwright/test';

const DUE_ENTRY = {
  id: '11111111-1111-1111-1111-111111111111',
  nextReviewAt: null,
  fromVerseRef: 'John 3:16',
  toVerseRef: 'John 3:16',
  masteryLevel: 0,
  verses: [{ verseNum: 16, reference: 'John 3:16', text: 'For God so loved the world.' }],
};

const LATER_ENTRY = {
  ...DUE_ENTRY,
  id: '22222222-2222-2222-2222-222222222222',
  nextReviewAt: '2099-01-01',
  fromVerseRef: 'Psalm 23:1',
  toVerseRef: 'Psalm 23:1',
};

async function openCompletedSession(page: import('@playwright/test').Page) {
  // Only seed a finished session when none is present, so Next Up's
  // newly written session survives the reload.
  await page.addInitScript(() => {
    if (!sessionStorage.getItem('kjv_training_session')) {
      sessionStorage.setItem(
        'kjv_training_session',
        JSON.stringify({ entries: [{ id: 'done' }], index: 1 })
      );
    }
  });
  await page.goto('/train');
}

test.describe('training complete screen', () => {
  test('offers Next Up as the default when more verses are due', async ({ page }) => {
    await page.route('**/api/memorization/queue', async (route) => {
      if (route.request().method() !== 'GET') return route.continue();
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify([DUE_ENTRY, LATER_ENTRY]),
      });
    });

    await openCompletedSession(page);

    const nextUp = page.getByRole('button', { name: 'Next Up' });
    await expect(page.getByRole('heading', { name: 'Session complete' })).toBeVisible();
    await expect(page.getByText('1 still due today.')).toBeVisible();
    await expect(page.getByText('All done for today!')).toHaveCount(0);
    await expect(page.getByText('Come back tomorrow for the next session.')).toHaveCount(0);
    await expect(nextUp).toBeVisible();
    await expect(nextUp).toBeFocused();
    await expect(page.locator('#train-done-dashboard')).toBeVisible();
    await expect(page.locator('#train-done-reading')).toBeVisible();
    await expect(page.locator('#train-done-dashboard')).toHaveClass(/train-done-link-secondary/);
  });

  test('names a remaining count when several verses are still due', async ({ page }) => {
    const dueDozen = Array.from({ length: 12 }, (_, i) => ({
      ...DUE_ENTRY,
      id: `00000000-0000-0000-0000-${String(i + 1).padStart(12, '0')}`,
    }));
    await page.route('**/api/memorization/queue', async (route) => {
      if (route.request().method() !== 'GET') return route.continue();
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(dueDozen),
      });
    });

    await openCompletedSession(page);

    await expect(page.getByRole('heading', { name: 'Session complete' })).toBeVisible();
    await expect(page.getByText('12 still due today.')).toBeVisible();
    await expect(page.getByText('All done for today!')).toHaveCount(0);
    await expect(page.getByRole('button', { name: 'Next Up' })).toBeFocused();
  });

  test('keeps the two-button complete screen when the due queue is empty', async ({ page }) => {
    await page.route('**/api/memorization/queue', async (route) => {
      if (route.request().method() !== 'GET') return route.continue();
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify([LATER_ENTRY]),
      });
    });

    await openCompletedSession(page);

    await expect(page.getByRole('heading', { name: 'All done for today!' })).toBeVisible();
    await expect(page.getByText('Come back tomorrow for the next session.')).toBeVisible();
    await expect(page.getByRole('button', { name: 'Next Up' })).toBeHidden();
    await expect(page.locator('#train-done-dashboard')).toBeVisible();
    await expect(page.locator('#train-done-reading')).toBeVisible();
    await expect(page.locator('#train-done-dashboard')).not.toHaveClass(/train-done-link-secondary/);
  });

  test('Next Up starts training on the next due queue entry', async ({ page }) => {
    await page.route('**/api/memorization/queue', async (route) => {
      if (route.request().method() !== 'GET') return route.continue();
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify([DUE_ENTRY, LATER_ENTRY]),
      });
    });

    await openCompletedSession(page);
    await page.getByRole('button', { name: 'Next Up' }).click();

    await expect(page.locator('#train-ref')).toHaveText('John 3:16');
    await expect(page.locator('#train-done')).toBeHidden();
    const session = await page.evaluate(() => sessionStorage.getItem('kjv_training_session'));
    expect(session).toBeTruthy();
    const parsed = JSON.parse(session!);
    expect(parsed.index).toBe(0);
    expect(parsed.entries).toHaveLength(1);
    expect(parsed.entries[0].id).toBe(DUE_ENTRY.id);
  });
});
