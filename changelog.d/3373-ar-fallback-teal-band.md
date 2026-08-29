<!-- category: Fixed -->
Fixed a coloured band painted across AR fallback screens when ARCore never delivers a camera
frame (#3373). The camera-stream quad was drawn from the moment it joined the scene, sampling an
external texture that had no image attached and reading an unset UV buffer. It now seeds identity
UVs at build time and stays hidden until the first ARCore frame binds a real texture. The demo
app's camera-init scrim also keeps its opaque backdrop after its defensive timeout, dropping only
the spinner, so the "AR couldn't start" fallback always has a deliberate background.
