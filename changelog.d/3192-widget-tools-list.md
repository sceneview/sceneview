<!-- category: Fixed -->

- **The hosted MCP gateway now declares its 3D-viewer widget in `tools/list`, not only on tool results ([#3192](https://github.com/sceneview/sceneview/issues/3192)).** `_meta.ui.resourceUri` rode on the result of `view_3d_model` alone, so a host deciding from the tool list whether a tool has a UI — the MCP Apps convention — never learned the widget existed. The declaration now carries the same `ui://widget/3d-viewer.html` pointer; non-widget tools gain no `_meta`.
