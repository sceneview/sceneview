<!-- category: Fixed -->
**Demo app — Point & Ask now shows what it is doing, and stops lying about your phone.**
On a Pixel 9 the demo answered nothing, "saw nothing" on the AR frame, and after a few taps
declared it could not run on that device at all. Three defects behind that: the frame was
read back from the whole *window*, which can lose the Filament `SurfaceView` layer the AR
scene lives in; the only validation was a transparency probe, so the same lost layer coming
back as opaque black passed straight through to Gemini Nano; and a retry counter promoted
three ordinary failures into a permanent "not supported on this device". The frame is now
read back from the AR view itself, validated for size, transparency *and* flatness before it
leaves the app, and the screen is an explicit state machine — checking, downloading, ready,
capturing, thinking, answer, or a failure that names its cause and offers the one action that
fixes it. Only a report from the platform can say a device is unsupported. Debug builds show
a thumbnail of the exact frame that was sent.
