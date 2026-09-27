<!-- category: Fixed -->
- **Demo — Open with SceneView:** an STL, OBJ or PLY shared as a nameless
  `application/octet-stream` now opens instead of being refused. The staged file is recognised
  from its own bytes with the SDK's `StlLoader.isStl` / `ObjLoader.isObj` / `PlyLoader.isPly`, as
  GLB, glTF and 3MF already were, and a name with no model extension gets the one the bytes
  proved, so the real-size question still appears for these unit-less formats (#3490).
