const STORAGE = {
    S3: "S3",
    GITHUB: "GITHUB"
};

module.exports = {
    STORAGE,
    ENV: {
        PORT: process.env.MANAGER_PORT || 5001,
        S3_ACCESS_KEY_ID: process.env.S3_ACCESS_KEY_ID,
        S3_SECRET_ACCESS_KEY: process.env.S3_SECRET_ACCESS_KEY,
        DOCKER_USAGE: process.env.DOCKER_USAGE,
        S3_ENDPOINT: process.env.S3_ENDPOINT,
        S3_FORCE_PATH_STYLE: process.env.S3_FORCE_PATH_STYLE,
        S3_BUCKET: process.env.S3_BUCKET,
        GITHUB_PERSONAL_TOKEN: process.env.GITHUB_PERSONAL_TOKEN,
        GITHUB_MAX_REPO_SIZE: process.env.GITHUB_MAX_REPO_SIZE,
        WAPM_REGISTRY_TOKEN: process.env.WAPM_REGISTRY_TOKEN,
        MANAGER_ALLOWED_DOMAINS: process.env.MANAGER_ALLOWED_DOMAINS,
        AUTH_MODE: process.env.AUTH_MODE || 'NO_AUTH',
        MANAGER_TEMPLATES: process.env.MANAGER_TEMPLATES,
        OTOROSHI_TOKEN_SECRET: process.env.OTOROSHI_TOKEN_SECRET || 'veryverysecret',
        OTOROSHI_USER_HEADER: process.env.OTOROSHI_USER_HEADER,
        MANAGER_MAX_PARALLEL_JOBS: process.env.MANAGER_MAX_PARALLEL_JOBS || 2,
        EXTISM_RUNTIME_ENVIRONMENT: process.env.EXTISM_RUNTIME_ENVIRONMENT || false,
        STORAGE: process.env.STORAGE || STORAGE.S3
    }
}