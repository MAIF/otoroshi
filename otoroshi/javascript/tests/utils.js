export const SECTIONS = {
    MANAGE_RESOURCES: 'Manage resources',
    CONFIGURATION: 'Configuration',
    ANALYTICS: 'Analytics',
    NETWORKING: 'Networking',
    SESSIONS: 'Sessions',
    TOOLING: 'Tooling',
    EXTENSIONS: 'Extensions'
}

export async function validAnonymousModal(page) {
    const closeButton = await page.$('text=Close');
    if (closeButton) {
        await closeButton.click();
    }

    const modal = await page.$('text=A new version of Otoroshi');
    if (modal) {
        await modal.click();
    }

    // The anonymous-reporting popup (rendered in #otoroshi-alerts-container)
    // overlays the page and intercepts pointer events. Dismiss it via the
    // "No, thanks" button so subsequent clicks (topbar search, etc.) land.
    const noThanks = await page.$('#otoroshi-alerts-container button:has-text("No, thanks")');
    if (noThanks) {
        await noThanks.click();
    }

    // Best-effort: if any modal is still blocking, close it.
    const dialog = await page.$('#otoroshi-alerts-container .modal[role="dialog"]');
    if (dialog) {
        const dismiss = await page.$(
            '#otoroshi-alerts-container .modal button:has-text("Close"), ' +
            '#otoroshi-alerts-container .modal button:has-text("Cancel"), ' +
            '#otoroshi-alerts-container .modal button:has-text("Ok")'
        );
        if (dismiss) await dismiss.click();
    }
}