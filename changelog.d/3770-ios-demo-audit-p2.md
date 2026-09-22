<!-- category: Fixed -->
- **The iOS demo's Explore tab never spins forever.** Each online feed now gives up after 15 s, and when every feed of the selected catalog fails the tab says "Couldn't reach <catalog>" with a Try again button above the bundled models, instead of a heading spinner that outlived the app.
- **Seven full-screen demos got their back button.** Collision, Debug Overlay, Lighting, PBR Materials, Occlusion Material, Scene Gallery and Material Presets drew their own chrome with no way out; they now use the shared demo scaffold — back button, title pill, dock — like every other demo.
- **Legends and hints read in both themes.** Captions that floated bare over the 3D scene (shape, physics, reflection probes, placement reticle preview, double pendulum, collision, materials, scene gallery) are one glass hint component inside the scaffold's scrim, and the light-only panels that turned white in dark mode (lighting, materials, scene gallery, material presets) are gone.
- **AR banners clear the Dynamic Island.** The status banners of movable-light, wall-placement, multi-model and ar-cloud-anchor sat under the status bar; they live in the scaffold's bottom cluster now, and the asset-source pill sits in the identity row beside the title.
- **Occlusion Material actually occludes.** The invisible plane sat inside the sphere and its material punched black holes through the skybox; it now cuts the sphere's lower third with the skybox off.
- **Depth Collider no longer fakes AR on the simulator.** The static floor drawn inside the studio skybox is gone; without a camera the demo shows the same "AR requires a physical device" stage as the other AR demos.

<!-- category: Tests -->
- **`ExploreFeedLoadTests` pins the feed deadline and the "unreachable" rule** — a stalled feed is abandoned at the deadline, one answering feed is never reported as unreachable, and a rejected key keeps its own banner.
