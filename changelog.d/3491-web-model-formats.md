<!-- category: Added -->
- **Web mesh formats ([#3491](https://github.com/sceneview/sceneview/issues/3491)).** Load 3MF, STL, OBJ and mesh PLY from a URL or ArrayBuffer through the shared KMP converters, including WebXR. The MCP viewer forwards filename and unit hints; `/open` adds a file picker, unit selection and a phone QR. Unitless meshes default to millimetres; 3MF keeps its declared unit.

<!-- category: Fixed -->
- Keep glTF loading when `asset` follows a large JSON property, ship the converter used by the website and MCP, and build the model-format browser fixtures before CI runs Playwright.
