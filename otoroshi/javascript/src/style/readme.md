# Howto

## Layout
The container is fluid.
The main layout is defined like this : *global.scss* and areas are divided in : *header.scss*, *aside.scss* and *section.scss*.

## Breakpoint

Defined in variables.scss. Default breakpoint for media queries are
```css
  $bp-xs-up:767px; //min-width
  $bp-xs:$bp-xs-up - 1; //max-width
```
add *-xs-up-* or *-xs-* to the name of the class to target the breakpoint. Example *m-xs-20*

## Margins & Padding
* Choice of values (in pixels) : 5, 10, 20

* Choice of margins or paddings target : m, mt, mr, mb, ml, p, pt, pr, pb, pl

**Example of classname**
```css
    m-5, ...  (margin : 5px)
    pt-10, ... (padding-top : 10px)

    m-xs-20, ... (margin for breakpoint max-width value : 20px)
```

## Colors for text, background, border

* Choice of colors : *white*, *black* and a list of colors values defined in the variable.scss :  *dark*, *warning*, *alert*, *success*, *info*, *primary*

* add *--important* after the name will !important the value

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
 * Choice of values : **display** concatenate among : --none, --block, --inline-block, --flex, --inline-flex, --grid

```css
  display--none, display--block, ...
```

## Positions
 * Choice of values : position--fixed, position--relative

## Flex
* flex-direction--column, flex-direction-xs--column
* flex-wrap--wrap, flex-wrap-xs--wrap
* justify-content concatenate among : --between, --around, --center, --start, --end
* grow--1

```css
  justify-content--between
```
## Grid
* Choice of grid-template-col concatenate among : __2fr-3fr, __1fr-auto, __1fr-1fr-auto
* Choice of grid-template-col-xs-up : __1fr-5fr
  
```css
  grid-template-col__1fr-auto,...
  grid-template-col-xs-up__1fr-5fr
```

## Items alignements
* align-items concatenate among : --start, --center, --baseline
* align-items-xs--start
```css
  align-items--start,...
```

## Cursor
* cursor--pointer


## Template examples

### Default structure for content with label ane input
* form__group mb-20 grid-template-bp1--fifth
  
  ```html
  <div class="form__group grid-template-col-xs-up__1fr-5fr mb-10 mt-10">
    <label></label>
    <div class="display--flex justify-content--between cursor--pointer mb-10">
      <h3>Location</h3>
      <button type="button" class="btn-info btn-xs"><i class="fas fa-eye-slash"></i></button>
    </div>
  </div>
  ```

### Display buttons
* btn__group--right -> display the button(s) to the right
* btn__group-fixed--right -> display the button(s) to the right and fixed them


### Display form with prepend or append informations
```html
<div class="display--grid grid-template-col__1fr-auto">
	<input type="number" id="input-Traffic split" value="0.2">
	<div class="input-group-addon">ratio</div>
</div>

<div class="display--grid grid-template-col__1fr-auto">
  <div class="input-group-addon">ratio</div>
  <input type="number" id="input-Traffic split" value="0.2">
</div>
```
*input-group-addon* must be a child of a form or .form parent
