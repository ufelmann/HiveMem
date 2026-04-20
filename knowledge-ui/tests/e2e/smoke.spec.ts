import { test, expect } from '@playwright/test'

test('login → search → open cell → reader → close', async ({ page }) => {
  await page.addInitScript(() => localStorage.setItem('hivemem_mock', 'true'))
  await page.goto('/')
  // login dialog appears; mock toggle is pre-enabled via init script
  await expect(page.getByLabel('Use local mock data')).toBeChecked()
  await page.getByRole('button', { name: 'Connect' }).click()
  // wait for canvas (splash disappears)
  await expect(page.locator('canvas')).toBeVisible({ timeout: 10_000 })
  // open search panel via keybind
  await page.keyboard.press('Control+KeyK')
  await page.getByPlaceholder('Type to search…').fill('hivemem')
  // click first result
  await page.locator('.v-list-item').first().click()
  // scan panel appears
  await expect(page.locator('.scan')).toBeVisible({ timeout: 5_000 })
  // open reader
  await page.getByRole('button', { name: 'Open reader' }).click()
  await expect(page.locator('.reader-shell')).toBeVisible()
  // close via back button
  await page.locator('.reader-shell header button').first().click()
  await expect(page.locator('.reader-shell')).toBeHidden()
})
