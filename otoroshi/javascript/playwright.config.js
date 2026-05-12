import { defineConfig, devices } from '@playwright/test';

export default defineConfig({
    testDir: './tests',
    // Tests across files AND inside a file may run in parallel. Each test
    // must (a) generate unique entity names (use `uniqueName()` from the
    // helpers) and (b) clean up everything it creates in a try/finally —
    // shared `wipeLeftovers` runs only in `beforeAll` (once per worker)
    // and uses file-scoped prefixes that never overlap between specs.
    fullyParallel: true,
    timeout: 5000,
    forbidOnly: !!process.env.CI,
    retries: process.env.CI ? 1 : 0,
    workers: process.env.CI ? 5 : undefined,
    reporter: 'html',
    use: {
        /* Base URL to use in actions like `await page.goto('/')`. */
        baseURL: 'http://otoroshi.oto.tools:9999',
        trace: 'on-first-retry'
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

