import GreenScoreExtension from "./informations";

const GreenScoreExtensionId = "otoroshi.extensions.GreenScore";

export function setupGreenScoreExtension(registerExtensionThunk) {
    registerExtensionThunk(GreenScoreExtensionId, ctx => GreenScoreExtension(GreenScoreExtensionId, ctx));
}