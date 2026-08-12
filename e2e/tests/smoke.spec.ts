import { test, expect } from '@playwright/test';

test.describe('rkj local smoke', () => {
  test('landing page loads', async ({ page }) => {
    await page.goto('/');
    await expect(page).toHaveTitle(/KJV Bible Reader/i);
    await expect(
      page.getByRole('heading', { name: /Read the\s+King James Bible/i })
    ).toBeVisible();
    await expect(
      page.getByRole('link', { name: /Start Reading|Continue/i })
    ).toBeVisible();
  });

  test('read route loads without server error', async ({ page }) => {
    const response = await page.goto('/read');
    expect(response).not.toBeNull();
    expect(response!.status()).toBeLessThan(500);
    await expect(page.locator('body')).toBeVisible();
    await expect(page).not.toHaveTitle(/Whitelabel Error/i);
  });

  test('login page loads', async ({ page }) => {
    await page.goto('/login.html');
    await expect(page.locator('body')).toBeVisible();
    await expect(
      page.locator(
        'form, input[type="email"], input[type="password"], input[name="username"], button'
      )
    ).not.toHaveCount(0);
  });
});
