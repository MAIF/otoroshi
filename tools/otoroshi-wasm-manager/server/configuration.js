const STORAGE = {
    S3: "S3",
    GITHUB: "GITHUB"
};

module.exports = {
    STORAGE,
    ENV: {
        PORT: process.env.MANAGER_PORT || 5001,
        STORAGE: process.env.STORAGE || STORAGE.S3,

        // process.env.AWS_ACCESS_KEY_ID,
        // process.env.AWS_SECRET_ACCESS_KEY,
        S3_ENDPOINT: process.env.S3_ENDPOINT,
        S3_FORCE_PATH_STYLE: process.env.S3_FORCE_PATH_STYLE,
        S3_BUCKET: process.env.S3_BUCKET,
        S3_REGION: process.env.S3_REGION || 'US_WEST_1',
        
        DOCKER_USAGE: process.env.DOCKER_USAGE,
        
        GITHUB_PERSONAL_TOKEN: process.env.GITHUB_PERSONAL_TOKEN,
        GITHUB_MAX_REPO_SIZE: process.env.GITHUB_MAX_REPO_SIZE,
        

        // TODO - migrate to the new wasmer https://docs.wasmer.io/registry/get-started
        WAPM_REGISTRY_TOKEN: false, // process.env.WAPM_REGISTRY_TOKEN,
        
        MANAGER_TEMPLATES: process.env.MANAGER_TEMPLATES,
        MANAGER_MAX_PARALLEL_JOBS: process.env.MANAGER_MAX_PARALLEL_JOBS || 2,
        MANAGER_ALLOWED_DOMAINS: process.env.MANAGER_ALLOWED_DOMAINS,
        
        AUTH_MODE: process.env.AUTH_MODE || 'NO_AUTH',
        OTOROSHI_TOKEN_SECRET: process.env.OTOROSHI_TOKEN_SECRET || 'veryverysecret',
        OTOROSHI_USER_HEADER: process.env.OTOROSHI_USER_HEADER,
        
        EXTISM_RUNTIME_ENVIRONMENT: process.env.EXTISM_RUNTIME_ENVIRONMENT || false,
        
        LOCAL_WASM_JOB_CLEANING: process.envLOCAL_WASM_JOB_CLEANING || (60 * 60 * 1000) // 1 hour
    }
}