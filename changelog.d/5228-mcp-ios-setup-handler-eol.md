<!-- category: Fixed -->
- **`sceneview-mcp` 4.3.2: `get_ios_setup` itself says SceneViewSwift is end of life.** 4.3.1 added the notice to the platform-setup and guide texts, but `get_ios_setup` answers from its own copy in the tool handler, which still read as a normal setup. Both its 3D and AR answers now open with the notice; a dispatch test guards it.
