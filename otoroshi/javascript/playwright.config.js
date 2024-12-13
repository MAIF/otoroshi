import { defineConfig, devices } from '@playwright/test';

export default defineConfig({
    testDir: './tests',
    fullyParallel: true,
    timeout: 12000,
    forbidOnly: !!process.env.CI,
    retries: process.env.CI ? 2 : 0,
    workers: process.env.CI ? 1 : undefined,
    reporter: 'html',
    use: {
        /* Base URL to use in actions like `await page.goto('/')`. */
        baseURL: 'http://otoroshi.oto.tools:9999',
        trace: 'on-first-retry',
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
                // Use prepared auth state.
                storageState: 'playwright/.auth/user.json',
            },
            dependencies: ['setup']
        },

        {
            name: 'firefox',
            use: {
                ...devices['Desktop Firefox'],
                // Use prepared auth state.
                storageState: 'playwright/.auth/user.json',
            },
            dependencies: ['setup']
        }
    ]
});

