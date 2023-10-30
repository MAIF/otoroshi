import GreenScoreExtension from './informations';

const GreenScoreExtensionId = 'otoroshi.extensions.GreenScore';

export function setupGreenScoreExtension(registerExtensionThunk) {
  registerExtensionThunk(GreenScoreExtensionId, true, (ctx) =>
    GreenScoreExtension(GreenScoreExtensionId, ctx)
  );
}
