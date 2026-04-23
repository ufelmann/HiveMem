import { test, expect } from '@playwright/test'

test('login → search → open cell → reader → close', async ({ page }) => {
  await page.addInitScript(() => localStorage.setItem('hivemem_mock', 'true'))
  await page.goto('/')
  // mock auth initializes automatically; wait for the main canvas
  await expect(page.locator('canvas')).toBeVisible({ timeout: 10_000 })
  // open search panel via keybind
  await page.keyboard.press('Control+KeyK')
  await page.getByPlaceholder('Type to search…').fill('paxos')
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

test('graph view loads from the shell', async ({ page }) => {
  await page.addInitScript(() => localStorage.setItem('hivemem_mock', 'true'))
  await page.goto('/')
  await expect(page.locator('canvas')).toBeVisible({ timeout: 10_000 })
  await page.locator('.rail button').nth(6).click()
  await expect(page).toHaveURL(/\/graph$/)
  await expect(page.getByTestId('force-graph-bridge')).toBeVisible()
})
