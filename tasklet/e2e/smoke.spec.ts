import { expect, test } from '@playwright/test'

test('adding a task posts an event batch the gateway accepts', async ({ page }) => {
  await page.goto('/')
  await page.getByLabel('New task').fill('Connect data source')
  const response = page.waitForResponse('**/v1/e')
  await page.getByRole('button', { name: 'Add task' }).click()
  expect((await response).status()).toBe(202)
  await expect(page.getByRole('listitem')).toHaveText('Connect data source')
})
