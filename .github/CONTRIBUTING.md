# Contributing to Otoroshi

These guidelines apply to all Otoroshi projects living in the the `MAIF/otoroshi` repository.

These guidelines are meant to be a living document that should be changed and adapted as needed.
We encourage changes that make it easier to achieve our goals in an efficient way.

## Codebase

* [docker](https://github.com/MAIF/otoroshi/tree/master/docker): contains various Dockerfiles
* [docs](https://github.com/MAIF/otoroshi/tree/master/docs): contains the Otoroshi website, avoid modification as its generated
* [manual](https://github.com/MAIF/otoroshi/tree/master/manual): the otoroshi user manual that will be generated with [Docusaurus](https://docusaurus.io/)
* [otoroshi](https://github.com/MAIF/otoroshi/tree/master/otoroshi): the otoroshi server app
* [resources](https://github.com/MAIF/otoroshi/tree/master/resources): various static resources
* [scripts](https://github.com/MAIF/otoroshi/tree/master/scripts): various scripts used by github actions and devs

## Local development setup

Otoroshi uses [mise](https://mise.jdx.dev/) to manage development tools (Java, sbt, Node.js) and to expose a unified set of tasks for building, running, testing, formatting and packaging the project. Using `mise` is the recommended way to contribute as it guarantees that you use the same tool versions as the CI and the rest of the team.

### Install mise

Follow the instructions on the [mise website](https://mise.jdx.dev/getting-started.html). On macOS / Linux a typical install is:

```sh
curl https://mise.run | sh
```

Then activate the shell hook (see the mise documentation for your shell, e.g. `eval "$(mise activate zsh)"` in `~/.zshrc`).

### Bootstrap the project

From the root of the repository:

```sh
# Install the tool versions declared in mise.toml
mise install

# Install JS dependencies (frontend admin UI + Docusaurus documentation)
mise run install-js-deps
```

### Common tasks

All tasks are declared in [`mise.toml`](https://github.com/MAIF/otoroshi/blob/master/mise.toml). The most useful ones are:

| Task | Description |
|------|-------------|
| `mise run dev` | Start the backend (sbt `~reStart`, hot reload) and the frontend webpack dev server |
| `mise run dev-w-doc` | Same as `dev` plus the Docusaurus dev server for the documentation |
| `mise run dev-backend` | Run only the backend in dev mode |
| `mise run dev-frontend` | Run only the admin UI dev server (port 3040) |
| `mise run dev-doc` | Run only the documentation dev server |
| `mise run compile` | Compile the Scala backend |
| `mise run build` | Build the production frontend bundle and assemble the backend jar |
| `mise run build-w-doc` | Same as `build` plus the static documentation site |
| `mise run jar` | Run the assembled `otoroshi.jar` locally with sane defaults |
| `mise run test` | Run the main backend test suite (`OtoroshiTests`) |
| `mise run test-plugins` | Run the plugins test suite |
| `mise run test-all` | Run both the main and plugins test suites |
| `mise run test-e2e` | Run the Playwright end-to-end tests (requires a running otoroshi) |
| `mise run fmt` | Format Scala (scalafmt) and JS (prettier) code |
| `mise run clean` | Remove build artifacts |

To list every available task at any time:

```sh
mise tasks
```

> Note: the legacy shell scripts under `scripts/` are still available and used by some Github Actions workflows, but for day-to-day development you should rely on `mise` tasks.

## Workflow

The steps below describe how to get a patch into a main development branch (e.g. `master`). 
The steps are exactly the same for everyone involved in the project (be it core team, or first time contributor).
We follow the standard GitHub [fork & pull](https://help.github.com/articles/using-pull-requests/#fork--pull) approach to pull requests. Just fork the official repo, develop in a branch, and submit a PR!

1. To avoid duplicated effort, it might be good to check the [issue tracker](https://github.com/MAIF/otoroshi/issues) and [existing pull requests](https://github.com/MAIF/otoroshi/pulls) for existing work.
   - If there is no ticket yet, feel free to [create one](https://github.com/MAIF/otoroshi/issues/new) to discuss the problem and the approach you want to take to solve it.
1. [Fork the project](https://github.com/MAIF/otoroshi#fork-destination-box) on GitHub. You'll need to create a feature-branch for your work on your fork, as this way you'll be able to submit a pull request against the mainline Otoroshi.
1. Create a branch on your fork and work on the feature. For example: `git checkout -b wip-awesome-new-feature`
   - Please make sure to follow the general quality guidelines (specified below) when developing your patch.
   - Please write additional tests covering your feature and adjust existing ones if needed before submitting your pull request. 
1. Once your feature is complete, prepare the commit with a good commit message, for example: `Adding canary mode support for services #42` (note the reference to the ticket it aimed to resolve).
1. If it's a new feature, or a change of behaviour, document it on the [Otoroshi docs](https://github.com/MAIF/otoroshi/tree/master/manual), remember, an undocumented feature is not a feature.
1. Now it's finally time to [submit the pull request](https://help.github.com/articles/using-pull-requests)!
    - Please make sure to include a reference to the issue you're solving *in the comment* for the Pull Request, this will cause the PR to be linked properly with the Issue. Examples of good phrases for this are: "Resolves #1234" or "Refs #1234".
1. Now both committers and interested people will review your code. This process is to ensure the code we merge is of the best possible quality, and that no silly mistakes slip through. You're expected to follow-up these comments by adding new commits to the same branch. The commit messages of those commits can be more loose, for example: `Removed debugging using printline`, as they all will be squashed into one commit before merging into the main branch.
    - The community and team are really nice people, so don't be afraid to ask follow up questions if you didn't understand some comment, or would like clarification on how to continue with a given feature. We're here to help, so feel free to ask and discuss any kind of questions you might have during review!
1. After the review you should fix the issues as needed (pushing a new commit for new review etc.), iterating until the reviewers give their thumbs up–which is signalled usually by a comment saying `LGTM`, which means "Looks Good To Me". 
1. If the code change needs to be applied to other branches as well (for example a bugfix needing to be backported to a previous version), one of the team will either ask you to submit a PR with the same commit to the old branch, or do this for you.
1. Once everything is said and done, your pull request gets merged. You've made it!

The TL;DR; of the above very precise workflow version is:

1. Fork Otoroshi
2. Hack and test on your feature (on a branch)
3. Document it 
4. Submit a PR
6. Keep polishing it until received thumbs up from the core team
7. Profit!

## External dependencies

All the external runtime dependencies for the project, including transitive dependencies, must have an open source license that is equal to, or compatible with, [Apache 2](http://www.apache.org/licenses/LICENSE-2.0).

This must be ensured by manually verifying the license for all the dependencies for the project:

1. Whenever a committer to the project changes a version of a dependency (including Scala) in the build file.
2. Whenever a committer to the project adds a new dependency.
3. Whenever a new release is cut (public or private for a customer).

Which licenses are compatible with Apache 2 are defined in [this doc](http://www.apache.org/legal/3party.html#category-a), where you can see that the licenses that are listed under ``Category A`` are automatically compatible with Apache 2, while the ones listed under ``Category B`` need additional action:

> Each license in this category requires some degree of [reciprocity](http://www.apache.org/legal/3party.html#define-reciprocal); therefore, additional action must be taken in order to minimize the chance that a user of an Apache product will create a derivative work of a reciprocally-licensed portion of an Apache product without being aware of the applicable requirements.

Each project must also create and maintain a list of all dependencies and their licenses, including all their transitive dependencies. This can be done either in the documentation or in the build file next to each dependency.

You must add the dependency and its licence in https://github.com/MAIF/otoroshi/blob/master/licences.md

## Documentation

if you add features to Otoroshi, don't forget to modify the user manual, the openapi file

* https://github.com/MAIF/otoroshi/tree/master/manual/next/
* https://github.com/ptitFicus/otoroshi/blob/master/otoroshi/conf/schemas/openapi.json

to build the documentation, run the following command at the root of the repository

```sh
mise run build-doc
```

To preview the documentation locally with hot reload:

```sh
mise run dev-doc
```

## Tests

Every new feature should provide corresponding tests to ensure everything is working and will still working in future releases.

The simplest way to run the tests locally is via mise:

```sh
# Main backend test suite
mise run test

# Plugins test suite
mise run test-plugins

# Both
mise run test-all

# End-to-end Playwright tests (requires a running otoroshi instance)
mise run test-e2e
```

For more advanced scenarios (specific test, specific store backend, etc.), see the [dedicated documentation](https://www.otoroshi.io/docs/dev#testing).

## Source style

The whole code of Otoroshi is automatically formatted using 

* prettier
* rust_fmt
* scalafmt

Before pushing your code, don't forget to format it. The easiest way is:

```sh
mise run fmt
```

You can also run the formatters individually with `mise run fmt-backend` (scalafmt) and `mise run fmt-frontend` (prettier). For more details, see the [dedicated documentation](https://www.otoroshi.io/docs/dev#formatting-code).

## Continuous integration

Every commit and PR to Otoroshi is built by [Github actions](https://github.com/MAIF/otoroshi/actions/workflows/server_build_and_test.yaml). Github will also check your pull request to prevent merging code that does not build.
