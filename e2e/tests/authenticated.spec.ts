import { test, expect } from '@playwright/test';

test.describe('authenticated as Sam Adams', () => {
  test('session is active via /api/auth/me', async ({ page }) => {
    const me = await page.request.get('/api/auth/me');
    expect(me.ok()).toBeTruthy();
    const user = await me.json();
    const label = String(user.displayName || user.name || user.email || '');
    expect(label.length).toBeGreaterThan(0);
    expect(label.toLowerCase()).toMatch(/sam/);
  });

  test('landing shows signed-in chrome', async ({ page }) => {
    await page.goto('/landing.html');
    await expect(page.locator('.nav-user, a.nav-user')).toContainText(/sam/i);
    await expect(page.getByRole('link', { name: /Dashboard/i })).toBeVisible();
    await expect(page.getByRole('link', { name: /Open Reader/i })).toBeVisible();
    await expect(page.getByRole('button', { name: /Sign Out/i })).toBeVisible();
  });

  test('dashboard loads for the signed-in user', async ({ page }) => {
    await page.goto('/dashboard');
    await expect(page).toHaveTitle(/Dashboard/i);
    await expect(page.locator('.nav-user')).toContainText(/sam/i);
    await expect(page.locator('#dash-greeting')).toBeVisible();
    await expect(page.getByRole('link', { name: /Open Reader/i }).first()).toBeVisible();
    await expect(page.locator('#due-count')).toBeVisible();
    await expect(page.locator('#reading-ref')).toBeVisible();
  });

  test('reader is reachable while signed in', async ({ page }) => {
    const response = await page.goto('/read');
    expect(response).not.toBeNull();
    expect(response!.status()).toBeLessThan(500);
    await expect(page.locator('body')).toBeVisible();
    await expect(page).not.toHaveURL(/login\.html/);
  });
});
