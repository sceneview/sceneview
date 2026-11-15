<!-- category: Fixed -->
- **Android AR: cover the camera startup gap on re-entry ([#4363](https://github.com/sceneview/sceneview/issues/4363)).** Opaque AR views keep a neutral placeholder until a render pass containing a camera image has been submitted. Timestamp-zero frames no longer reveal an uninitialised camera texture.
