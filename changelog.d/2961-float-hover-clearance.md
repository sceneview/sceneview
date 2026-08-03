<!-- category: Fixed -->
- Contact-shadow preview (android-demo): `DemoMath.CONTACT_FLOAT_CENTER_Y_METERS` no longer
  documents a face-to-face clearance the constants do not provide. The floating box's lowest
  bottom face sits at 0.38 m — exactly flush with the grounded box's top face at its landing
  pose (0.00 m of clearance), and 0.34 m *below* that box's top face at the peak of the hop.
  The KDoc now states the measured geometry, the 0.040 m top-face margin that actually carries
  the "aloft" reading, and why a clearance over the hop peak is impossible in this room (it
  would need a rest centre above 0.96 m, whose top face punches through the wall TV at 0.93 m).
  The accompanying test now asserts on box **faces** with the margins in metres, instead of
  comparing box centres — a comparison two interpenetrating boxes also satisfy (#2961, #2931).
