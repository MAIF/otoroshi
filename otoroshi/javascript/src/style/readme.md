# Howto

## Layout
The container is fluid.
The main layout is defined as a *global* and areas are divided in : *header*, *aside* and *section*.

## Breakpoint

Defined in variables.scss. Default breakpoint for mobile is
```css
  $bp-xs-up:767px; //min-width
  $bp-xs:$bp-xs-up - 1; //max-width
```
add *-xs-* in the name (see examples below)

## Margins & Padding
* Choice of values (in pixels) : 5, 10, 20

* Choice of margins or paddings target : m, mt, mr, mb, ml, p, pt, pr, pb, pl

**Example of classname**
```css
    m-5, ...  (margin : 5px)
    pt-10, ... (padding-top : 10px)

    m-xs-20, ... (margin < breakpoint value : 5px)
```

## Colors for text, background, border

* Choice of colors : *white*, *black* and a list of colors where values defined in the variable.scss :  *dark*, *warning*, *alert*, *success*, *info*, *primary*

* !important is optionnal with --important

**Example of classname**
```css
  text__warning, text__warning--important

  bg__warning, bg__warning--important

  border__warning, border__warning--important
```
## Widths
* Choice of widths (in %) :  25, 33, 50, 100

**Example of classname**
```css
    w-25, w-33, w-50, w-100

    w-xs-25, ...
```

## Display
 * Choice of values : display--none, .display--block, display--inline-block, display--flex, display--inline-flex, display--grid


## Positions
 * Choice of values : position--fixed, position--relative

## Flex
* flex-direction--column, flex-direction-xs--column
* flex-wrap--wrap, flex-wrap-xs--wrap
* justify-content : --between, --around, --center, --start, --end
* grow--1

```css
  justify-content--between
```
## Grid
* Choice of grid-template-col : __2fr-3fr, __1fr-auto, __1fr-1fr-auto
* Choice of grid-template-col-xs-up : __1fr-5fr
  
```css
  grid-template-col__1fr-auto,...
  grid-template-col-xs-up__1fr-5fr
```

## Items alignements
* align-items : --start, --center, --baseline
* align-items-xs--start

## Cursor
* cursor--pointer