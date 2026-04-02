---
title: Developing Otoroshi
sidebar_label: "Developing Otoroshi"
sidebar_position: 95
---

# Developing Otoroshi

This guide explains how to set up your development environment and work on Otoroshi using [mise-en-place](https://mise.jdx.dev/) as the toolchain manager.

## Prerequisites

You only need two things installed on your machine:

* **git** - to clone the repository
* **[mise](https://mise.jdx.dev/getting-started.html)** - to manage all other tools automatically

mise will handle installing the correct versions of Java, SBT, and Node.js for you.

## Getting started

### Clone the repository

```sh
git clone https://github.com/MAIF/otoroshi.git
cd otoroshi
```

Or fork the repository first and clone your own fork.

### Install the toolchain

```sh
mise install
```

This installs all the required tools at the exact versions defined in `mise.toml`:

| Tool | Version | Purpose |
|------|---------|---------|
| Java | OpenJDK 17 | Scala backend runtime |
| SBT | 1.7.2 | Scala build tool |
| Node.js | 22 | Frontend build & documentation |

### Install JS dependencies

```sh
mise run install-js-deps
```

This installs npm dependencies for both the frontend UI and the documentation site.

You're ready to go!

## Development mode

### Start everything (recommended)

```sh
mise run dev
```

This starts **both** the Scala backend (with hot reload) and the frontend webpack dev server in parallel. Once running, access Otoroshi at:

**http://otoroshi.oto.tools:9999**

If you also want the documentation dev server:

```sh
mise run dev-w-doc
```

### Start components individually

You can also start each component separately:

```sh
# Backend only (sbt ~reStart with hot reload)
mise run dev-backend

# Frontend only (webpack dev server on port 3040)
mise run dev-frontend

# Documentation only (Docusaurus dev server)
mise run dev-doc
```

### Customizing the backend environment

The `dev-backend` task supports environment variables to configure Otoroshi at startup. Uncomment and edit them in `mise.toml` or pass them inline:

```sh
OTOROSHI_INITIAL_ADMIN_LOGIN=admin@otoroshi.io \
OTOROSHI_INITIAL_ADMIN_PASSWORD=password \
mise run dev-backend
```

You can also pass JVM args directly to the sbt `reStart` command by editing the task or using sbt interactively.

## Building

### Build everything

```sh
mise run build
```

This builds the frontend (webpack production bundle) then compiles and assembles the Scala backend into a fat JAR. The output is:

```
otoroshi/target/scala-2.12/otoroshi.jar
```

To also build the documentation:

```sh
mise run build-w-doc
```

### Build components individually

```sh
# Compile Scala backend only (no jar assembly, faster feedback loop)
mise run compile

# Build backend jar (compile + assembly)
mise run build-backend

# Build frontend production bundle
mise run build-frontend

# Build documentation static site
mise run build-doc
```

### Run the built jar

After building, you can run Otoroshi locally as a standalone jar:

```sh
mise run jar
```

This starts Otoroshi on port 8080 with file-based storage and default admin credentials (`admin@otoroshi.io` / `password`). See the `[tasks.jar.env]` section in `mise.toml` to customize.

## Testing

### Run the main test suite

```sh
mise run test
```

Runs the `OtoroshiTests` suite using the in-memory datastore.

### Run plugin tests

```sh
mise run test-plugins
```

Runs the `functional.PluginsTestSpec` suite.

### Run all backend tests

```sh
mise run test-all
```

Runs both the main suite and the plugin suite sequentially.

### Run a specific test

For more granular test execution, use sbt directly:

```sh
cd otoroshi
sbt 'testOnly *PluginsTestSpec -- -t "plugins should React2SShellDetector"'
```

### Run end-to-end tests

The project includes Playwright-based E2E tests. You need a running Otoroshi instance first:

```sh
# In one terminal
mise run dev

# In another terminal
mise run test-e2e
```

## Formatting code

### Format everything

```sh
mise run fmt
```

Formats both frontend (prettier) and backend (scalafmt) code.

### Format individually

```sh
# Frontend only (prettier)
mise run fmt-frontend

# Backend only (scalafmt)
mise run fmt-backend
```

## Cleaning build artifacts

```sh
mise run clean
```

Removes backend build outputs, frontend bundles, and documentation build files.

## All available tasks

Run `mise tasks` to see the full list. Here is a summary:

| Task | Description |
|------|-------------|
| **Development** | |
| `dev` | Start backend + frontend in dev mode |
| `dev-w-doc` | Start backend + frontend + doc in dev mode |
| `dev-backend` | Run otoroshi backend in dev mode (sbt ~reStart, hot reload) |
| `dev-frontend` | Start frontend webpack dev server (port 3040) |
| `dev-doc` | Run documentation Docusaurus dev server |
| **Build** | |
| `compile` | Compile Scala backend (no assembly) |
| `build` | Build otoroshi (frontend + backend jar) |
| `build-w-doc` | Build otoroshi (frontend + backend + doc) |
| `build-backend` | Build otoroshi backend jar (compile + assembly) |
| `build-frontend` | Build otoroshi frontend (webpack production) |
| `build-doc` | Build the documentation static site |
| **Run** | |
| `jar` | Run the built otoroshi jar locally |
| **Test** | |
| `test` | Run the main backend test suite (OtoroshiTests) |
| `test-plugins` | Run the plugins test suite |
| `test-all` | Run all backend tests (main + plugins) |
| `test-e2e` | Run Playwright end-to-end tests |
| **Format** | |
| `fmt` | Format all code (frontend + backend) |
| `fmt-frontend` | Format frontend code with prettier |
| `fmt-backend` | Format Scala code with scalafmt |
| **Install** | |
| `install-js-deps` | Install all JS dependencies (frontend + doc) |
| `install-frontend-js-deps` | Install frontend JS dependencies |
| `install-doc-js-deps` | Install documentation JS dependencies |
| **Clean** | |
| `clean` | Clean all build artifacts |

## Quick reference

```sh
# First time setup
mise install && mise run install-js-deps

# Daily development
mise run dev                  # start coding

# Before committing
mise run fmt                  # format code
mise run test                 # run tests

# Build a release
mise run build                # produce otoroshi.jar
mise run jar                  # test the jar locally
```
