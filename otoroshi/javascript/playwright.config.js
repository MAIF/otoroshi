import { defineConfig, devices } from '@playwright/test';

export default defineConfig({
    testDir: './tests',
    fullyParallel: false,
    timeout: process.env.CI ? 60_000 : 15_000,
    expect: { timeout: process.env.CI ? 15_000 : 5_000 },
    forbidOnly: !!process.env.CI,
    retries: 0,
    workers: 1,
    reporter: 'html',
    use: {
        /* Base URL to use in actions like `await page.goto('/')`. */
        baseURL: 'http://otoroshi.oto.tools:9999',
        trace: 'on-first-retry',
        actionTimeout: process.env.CI ? 15_000 : 5_000,
        navigationTimeout: process.env.CI ? 30_000 : 10_000,
    },
    projects: [
        {
            name: 'setup',
            testMatch: /.*\.setup\.js/
        },
        {
            name: 'chromium',
            use: {
                ...devices['Desktop Chrome'],
            },
            dependencies: ['setup']
        }
    ]
});

