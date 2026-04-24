---
slug: new-ui-themes
title: A fresh coat of paint - new dark and light themes for the Otoroshi UI
authors: [otoroshi-team]
tags: [otoroshi, ui, design]
---

The Otoroshi backoffice has been with us for a long time, and over the years it grew organically -- screen by screen, feature by feature. The result was a UI that worked, but felt dated, a little inconsistent from one page to another, and frankly a bit harsh on the eyes (especially in light mode, where staring at a wall of pure white is no one's idea of fun).

We took the time to refresh both themes from the ground up, and we are pretty happy with the result.

<!-- truncate -->

## A new light theme that doesn't burn your retinas

The light theme has been the most aggressive change. We moved away from the previous high-contrast white-on-white look toward a softer, more neutral palette: gentle off-whites for the background, light gray cards with subtle borders, and just enough contrast to make every block stand out without screaming for attention. Typography has been tightened, spacing rebalanced, and the iconic Otoroshi yellow now plays a clearer accent role instead of fighting with the rest of the page.

![New light theme - home page](/img/blog/new-ui-theme/capture-home-light.png)

The result is a UI that you can comfortably look at for hours without feeling like you are staring into a lightbulb -- which, given how much time some of you spend in the dashboard, felt like the least we could do.

## A dark theme worthy of the name

The dark theme also got a full makeover. The new palette is a proper deep dark instead of the old half-gray, the contrast on text and metrics is crisp, and the yellow accent really pops against the background. The whole UI feels more modern and more "product-grade".

![New dark theme - home page](/img/blog/new-ui-theme/capture-home-dark.png)

It looks great on big monitors, on laptops in the dark, and -- most importantly -- it looks consistent with the light theme. They are now genuinely two facets of the same design system, not two unrelated stylesheets.

## Consistent layouts across screens

Refreshing the colors was only half of the work. We have also started to rework individual screens so that the important controls live in predictable places. The general principles we have been moving toward are:

- a clear page header with the entity name and its primary status (state, version, etc.)
- the **save / publish / primary action** sits in a consistent area at the top
- a structured left sidebar dedicated to navigation inside the entity (dashboard, routes, backends, plans, ...)
- a **contextual right panel** when the screen has natural follow-up actions, instead of scattering buttons throughout the page

You can see this on the new API editor, where the main panel keeps the live metrics and health summary, while the right rail surfaces the obvious next steps ("Build your API": plugin chains, backends, endpoints):

![New API editor](/img/blog/new-ui-theme/capture-api.png)

The same logic applies to more complex screens like the workflow designer, where the canvas takes center stage and the controls (Save, run, debug, zoom) are anchored in stable positions you can build muscle memory around:

![New workflow editor](/img/blog/new-ui-theme/capture-workflows.png)

## What's next

This is an ongoing effort. Not every screen has been migrated yet, and we will keep rolling out the new look and the consistent layout pattern in upcoming releases. If you spot a screen that still feels off, or a button that lives in a surprising place, please open an issue or ping us on [Discord](https://discord.gg/dmbwZrfpcQ) -- this is exactly the kind of feedback we are looking for.

We hope you enjoy the new themes as much as we enjoyed building them. Now go ahead and try them out -- your eyes will thank you.
