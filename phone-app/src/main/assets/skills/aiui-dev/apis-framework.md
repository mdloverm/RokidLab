# AIUI Framework API Reference

Read [framework concepts](../framework.md) for file formats and declarations, and [events](../events.md) for lifecycle guidance.

## Global objects

Use [Navigator](./navigator.md) for `navigator` properties and methods, and
[Window](./window.md) for viewport properties and Widget routing.

For instance-specific members, use [Widget](./widget.md) or
[Agent Worker](./agent-worker.md).

## Page Data

### `this.data`

Contains rendered Page or Widget state.

### `this.setData(patch)`

Merges changed values into state and updates affected bindings. Dot paths can update nested values:

```javascript
this.setData({
  count: this.data.count + 1,
  'status.label': 'ready',
});
```

## Page Node Access

Use Page-supported query methods only after the interface is ready. A common pattern is:

```javascript
const list = page.querySelector('#results');
```

Do not assume a complete browser DOM. Component nodes expose only their documented AIUI methods and properties.
