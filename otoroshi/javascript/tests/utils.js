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
}