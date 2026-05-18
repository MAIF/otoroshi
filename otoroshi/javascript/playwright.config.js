import { defineConfig, devices } from '@playwright/test';

export default defineConfig({
    testDir: './tests',
    // Tests across files AND inside a file may run in parallel. Each test
    // must (a) generate unique entity names (use `uniqueName()` from the
    // helpers) and (b) clean up everything it creates in a try/finally —
    // shared `wipeLeftovers` runs only in `beforeAll` (once per worker)
    // and uses file-scoped prefixes that never overlap between specs.
    fullyParallel: true,
    // CI runners are much slower than a dev laptop: a 5s per-test budget there
    // makes tests time out, which tears down the browser context and cascades
    // "Target page/context/browser has been closed" into every following test
    // on the same worker. Give CI a realistic budget.
    timeout: process.env.CI ? 60_000 : 15_000,
    expect: { timeout: process.env.CI ? 15_000 : 5_000 },
    forbidOnly: !!process.env.CI,
    retries: process.env.CI ? 1 : 0,
    workers: process.env.CI ? 3 : undefined,
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

