import { expect, test } from '@playwright/test'

test('adding a task posts an event batch the gateway accepts', async ({ page }) => {
  await page.goto('/')
  await page.getByLabel('New task').fill('Connect data source')

  // Match the creation batch, not the view event the page tracks on load.
  const response = page.waitForResponse(
    (candidate) =>
      candidate.url().includes('/v1/e') &&
      (candidate.request().postData() ?? '').includes('"task_created"'),
  )

  await page.getByRole('button', { name: 'Add task' }).click()
  expect((await response).status()).toBe(202)
  await expect(page.getByRole('listitem')).toHaveText('Connect data source')
})
